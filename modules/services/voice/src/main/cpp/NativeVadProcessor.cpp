#include "NativeVadProcessor.h"
#include "OboeAudioCapture.h"

#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>

#define LOG_TAG "VoicePipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "[VoicePipeline] " __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[VoicePipeline] " __VA_ARGS__)

// Reusable Silero VAD inference, implemented in VadJni.cpp
extern float sileroVadPredict(const int16_t* samples, int32_t count);
extern void sileroVadResetState();

// ---------------------------------------------------------------------------
// NativeVadProcessor
// ---------------------------------------------------------------------------

NativeVadProcessor::NativeVadProcessor(OboeAudioCapture* capture)
    : mCapture(capture)
    , mContextBuffer(kMaxContextFrames * kVadFrameSize, 0)
{
}

NativeVadProcessor::~NativeVadProcessor() {
    stop();
}

bool NativeVadProcessor::start() {
    if (mActive.load(std::memory_order_acquire)) {
        return true; // already running
    }

    // Reset Silero VAD LSTM state so a new session starts fresh
    // rather than carrying over hidden/cell state from the prior run.
    sileroVadResetState();

    mActive.store(true, std::memory_order_release);
    mThread = std::make_unique<std::thread>(&NativeVadProcessor::runLoop, this);
    LOGI("VAD processor thread started");
    return true;
}

void NativeVadProcessor::stop() {
    mActive.store(false, std::memory_order_release);

    // Wake up any blocked callers
    {
        std::lock_guard<std::mutex> lock(mEventMutex);
        mPendingEvent = -1;
        mEventReady = true;
        mEventCv.notify_all();
    }

    if (mThread && mThread->joinable()) {
        mThread->join();
    }
    mThread.reset();

    // Reset state
    mContextHead = 0;
    mContextCount = 0;
    mSpeechBuffer.clear();
    mSpeechFrames = 0;
    mSpeechOnsetSample = 0;
                    mSpeechEndSample = 0;
    mSpeechActive = false;
    mFaultLatched = false; // a new session starts with no terminal cause latched
    mLoopExited.store(false, std::memory_order_release); // and no stale completion flag
    mConsecutiveSilentFrames = 0;
    mDrainRemaining = 0;

    {
        std::lock_guard<std::mutex> lock(mSpeechMutex);
        mSnapshotBuffer.clear();
        mSnapshotSpeechOnsetSample = 0;
        mSnapshotSpeechEndStreamPosition = 0;
    }

    LOGI("VAD processor thread stopped");
}

int NativeVadProcessor::waitForEvent(int32_t timeoutMs) {
    std::unique_lock<std::mutex> lock(mEventMutex);
    if (!mEventReady) {
        mEventCv.wait_for(lock, std::chrono::milliseconds(timeoutMs));
    }
    if (!mEventReady) {
        return 0; // timeout
    }
    int event = mPendingEvent;
    mPendingEvent = 0;
    mEventReady = false;
    return event;
}

bool NativeVadProcessor::awaitRunLoopExitForTest(int maxMs) {
    // Waits for the runLoop's final act (mLoopExited, set under mEventMutex AFTER the terminal
    // cause is settled). No stop(), no touching mPendingEvent: reading the terminal cause only
    // after this returns true is synchronized by worker completion, not by racing notifications.
    for (int waited = 0; waited < maxMs; waited += 5) {
        if (mLoopExited.load(std::memory_order_acquire)) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return false;
}

NativeVadProcessor::VadDiagnostics NativeVadProcessor::getDiagnostics() {
    VadDiagnostics d;
    d.lastRms = mLastRms.load(std::memory_order_relaxed);
    d.lastScore = mLastScore.load(std::memory_order_relaxed);
    d.lastGatePassed = mLastGatePassed.load(std::memory_order_relaxed);
    d.lastIsSpeech = mLastIsSpeech.load(std::memory_order_relaxed);
    d.speechFrames = mDiagSpeechFrames.load(std::memory_order_relaxed);
    d.speechActive = mDiagSpeechActive.load(std::memory_order_relaxed);
    d.silentFrames = mDiagSilentFrames.load(std::memory_order_relaxed);
    d.drainRemaining = mDiagDrainRemaining.load(std::memory_order_relaxed);
    return d;
}

void NativeVadProcessor::setDiagnosticsEnabled(bool enabled) {
    mDiagnosticsEnabled.store(enabled, std::memory_order_relaxed);
}

int64_t NativeVadProcessor::getFramesConsumed() {
    return mFramesConsumed.load(std::memory_order_relaxed);
}

int32_t NativeVadProcessor::getSpeechPcmSize() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    return static_cast<int32_t>(mSnapshotBuffer.size());
}

int32_t NativeVadProcessor::getSpeechPcm(int16_t* outBuffer, int32_t maxSamples) {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    if (mSnapshotBuffer.empty()) {
        return 0;
    }
    int32_t count = (maxSamples < static_cast<int32_t>(mSnapshotBuffer.size()))
        ? maxSamples
        : static_cast<int32_t>(mSnapshotBuffer.size());
    std::memcpy(outBuffer, mSnapshotBuffer.data(), static_cast<size_t>(count) * sizeof(int16_t));
    return count;
}

int32_t NativeVadProcessor::getSpeechEndSample() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    return mSnapshotSpeechEndSample;
}

int32_t NativeVadProcessor::getSpeechOnsetSample() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    return mSnapshotSpeechOnsetSample;
}

int64_t NativeVadProcessor::getSpeechEndStreamPosition() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    return mSnapshotSpeechEndStreamPosition;
}

// ---------------------------------------------------------------------------
// energyGate — fast RMS check to skip Silero VAD for silent frames
// ---------------------------------------------------------------------------
bool NativeVadProcessor::energyGate(const int16_t* samples, int32_t count) {
    double sum = 0.0;
    for (int32_t i = 0; i < count; ++i) {
        double v = static_cast<double>(samples[i]);
        sum += v * v;
    }
    double rms = std::sqrt(sum / static_cast<double>(count));
    // Record the value this gate compared when diagnostics are on, so the diagnosis reports the gate's
    // own number rather than recomputing it: two copies of one formula drift apart the moment either
    // frame size or threshold changes.
    if (mDiagnosticsEnabled.load(std::memory_order_relaxed)) {
        mLastRms.store(rms, std::memory_order_relaxed);
    }
    return rms >= kRmsThreshold;
}

// ---------------------------------------------------------------------------
// Helper: flush context buffer into speech buffer
// ---------------------------------------------------------------------------
static void flushContextIntoSpeech(
    std::vector<int16_t>& speechBuffer,
    int32_t& speechFrames,
    const std::vector<int16_t>& contextBuffer,
    int32_t contextHead,
    int32_t contextCount,
    int32_t maxContextFrames,
    int32_t vadFrameSize)
{
    if (contextCount <= 0) return;

    // Context frames are stored circularly. The oldest frame is at
    // (contextHead - contextCount) wrapped around.
    int32_t startIdx = (contextHead - contextCount + maxContextFrames) % maxContextFrames;

    for (int32_t i = 0; i < contextCount; ++i) {
        int32_t idx = (startIdx + i) % maxContextFrames;
        int32_t offset = idx * vadFrameSize;
        speechBuffer.insert(speechBuffer.end(),
            contextBuffer.begin() + offset,
            contextBuffer.begin() + offset + vadFrameSize);
        speechFrames++;
    }
}

// ---------------------------------------------------------------------------
// Helper: add a frame to the circular context buffer
// ---------------------------------------------------------------------------
static void addToContext(
    std::vector<int16_t>& contextBuffer,
    int32_t& contextHead,
    int32_t& contextCount,
    const int16_t* samples,
    int32_t maxContextFrames,
    int32_t vadFrameSize)
{
    int32_t offset = contextHead * vadFrameSize;
    std::memcpy(&contextBuffer[static_cast<size_t>(offset)], samples,
        static_cast<size_t>(vadFrameSize) * sizeof(int16_t));
    contextHead = (contextHead + 1) % maxContextFrames;
    if (contextCount < maxContextFrames) {
        contextCount++;
    }
}

// ---------------------------------------------------------------------------
// runLoop — main VAD processing loop
// ---------------------------------------------------------------------------
void NativeVadProcessor::runLoop() {
    LOGI("VAD runLoop started");

    int16_t chunk[kVadFrameSize];

    while (mActive.load(std::memory_order_acquire)) {
        // 1. Block on ring buffer for one full VAD frame.
        // readRingBuffer waits for >=1024 samples to accumulate.
        int32_t read = mCapture->readRingBuffer(chunk, kVadFrameSize, 500);
        if (read < 0) {
            break; // stream inactive
        }
        if (read < kVadFrameSize) {
            continue; // timeout — retry
        }
        // A full frame was read: count it, so callers can tell "capture is running" from "captured
        // audio is actually reaching the VAD" even while no speech event fires.
        mFramesConsumed.fetch_add(1, std::memory_order_relaxed);


        // 2. No cooldown gate and no forced duration endpoint. Internal framing retains and joins
        // audio until the natural endpoint, so no captured frames are discarded and no truncated
        // request is ever published. A duration limit, if one is introduced, must surface as an
        // explicit fault rather than a silent endpoint (see the guard below).

        // 3. Fault guard — a detector stuck active past a hard ceiling is a surfaced fault, not
        // ordinary long speech. It must NOT emit a completed utterance: the frames are neither
        // finalised nor handed to ASR. Returning the stop sentinel ends the stream so the caller
        // sees a fault instead of a plausible-looking truncated segment.
        if (mSpeechActive && mSpeechFrames >= kStuckDetectorFrameLimit) {
            LOGE("VAD: detector stuck active (%d frames); surfacing fault, no segment emitted",
                mSpeechFrames);
            {
                std::lock_guard<std::mutex> lock(mSpeechMutex);
                mSnapshotBuffer.clear();
                mSnapshotSpeechOnsetSample = 0;
                mSnapshotSpeechEndSample = 0;
                mSnapshotSpeechEndStreamPosition = 0;
                mSpeechBuffer.clear();
            }
            mSpeechFrames = 0;
            mSpeechOnsetSample = 0;
            mSpeechEndSample = 0;
            mSpeechActive = false;
            mConsecutiveSilentFrames = 0;
            mDrainRemaining = 0;
            mContextCount = 0;
            mContextHead = 0;

            std::lock_guard<std::mutex> lock(mEventMutex);
            // NOT the stop sentinel. `-1` means "the stream was stopped" and the consumer treats it
            // as an ordinary end, so emitting it here made a fault indistinguishable from a normal
            // shutdown: no error, no result, no state change, no recovery when the noise stops. A
            // distinct value is what makes the fault visible at the boundary.
            mPendingEvent = kFaultEvent; // fault: surfaced, not an ordinary end
            mEventReady = true;
            mFaultLatched = true; // preserve the terminal cause through loop exit
            // The processor is STOPPED by the fault: clear mActive BEFORE unlocking so a later
            // start() cannot report already-running against an exiting worker (b124a183).
            mActive.store(false, std::memory_order_release);
            mEventCv.notify_one();
            break;
        }

        // 4. Energy gate — skip Silero VAD for silent frames.
        bool hasEnergy = energyGate(chunk, kVadFrameSize);
        float prob = 0.0f;
        bool isSpeech = false;
        if (hasEnergy) {
            prob = sileroVadPredict(chunk, kVadFrameSize);
            isSpeech = (prob >= kSpeechThreshold);
        }
        // Diagnosis only, and only when enabled: the values the detector compared for this frame. The
        // RMS comes from the gate itself (recorded above), so there is one formula, not two.
        if (mDiagnosticsEnabled.load(std::memory_order_relaxed)) {
            mLastScore.store(prob, std::memory_order_relaxed);
            mLastGatePassed.store(hasEnergy, std::memory_order_relaxed);
            mLastIsSpeech.store(isSpeech, std::memory_order_relaxed);
            mDiagSpeechFrames.store(mSpeechFrames, std::memory_order_relaxed);
            mDiagSpeechActive.store(mSpeechActive, std::memory_order_relaxed);
            mDiagSilentFrames.store(mConsecutiveSilentFrames, std::memory_order_relaxed);
            mDiagDrainRemaining.store(mDrainRemaining, std::memory_order_relaxed);
        }

        // 5. Speech detected.
        if (isSpeech) {
            mConsecutiveSilentFrames = 0;
            mDrainRemaining = 0;

            if (!mSpeechActive) {
                mSpeechOnsetSample = mContextCount * kVadFrameSize;

                // Flush pre-speech context into speech buffer.
                flushContextIntoSpeech(mSpeechBuffer, mSpeechFrames,
                    mContextBuffer, mContextHead, mContextCount,
                    kMaxContextFrames, kVadFrameSize);
                mContextCount = 0;
                mContextHead = 0;

                mSpeechActive = true;

                // Signal speech started.
                std::lock_guard<std::mutex> lock(mEventMutex);
                mPendingEvent = 1; // speech started
                mEventReady = true;
                mEventCv.notify_one();
            }

            // Accumulate current frame.
            mSpeechBuffer.insert(mSpeechBuffer.end(), chunk, chunk + kVadFrameSize);
            mSpeechFrames++;

            // The end of the last frame classified as speech, in the segment's own coordinates:
            // the buffer after this append is retained pre-roll plus every speech frame, which is the
            // same timeline speechOnsetSample and the detector's completionSample use. Taken AFTER
            // the append, or it lands one frame early. Only speech frames write it, so the drain
            // cannot move it.
            mSpeechEndSample = static_cast<int32_t>(mSpeechBuffer.size());
            continue;
        }

        // 6. Silence handling.
        if (!mSpeechActive) {
            // No speech yet: buffer silent audio as potential pre-speech context.
            addToContext(mContextBuffer, mContextHead, mContextCount,
                chunk, kMaxContextFrames, kVadFrameSize);
        } else {
            mConsecutiveSilentFrames++;

            if (mConsecutiveSilentFrames < kSilenceTimeoutFrames) {
                // Micro-pause bridging: accumulate silent frames as part of speech.
                mSpeechBuffer.insert(mSpeechBuffer.end(), chunk, chunk + kVadFrameSize);
                mSpeechFrames++;
            } else {
                // Silence timeout reached — enter drain phase.
                if (mDrainRemaining <= 0) {
                    // Compute how many more frames to drain to reach the target
                    // utterance length (~3.5s) for reliable Whisper language detection.
                    int32_t totalSoFar = mSpeechFrames;
                    int32_t needed = kTargetTotalFrames - totalSoFar;
                    if (needed < kMinPostSpeechFrames) {
                        needed = kMinPostSpeechFrames;
                    }
                    if (needed > 40) {
                        needed = 40;
                    }
                    mDrainRemaining = needed;
                }

                mDrainRemaining--;
                mSpeechBuffer.insert(mSpeechBuffer.end(), chunk, chunk + kVadFrameSize);
                mSpeechFrames++;

                if (mDrainRemaining <= 0) {
                    // Drain complete — finalize the utterance.

                    // The finalize point IS the sample-accounting anchor: this thread has just
                    // consumed the frame containing the segment's last sample, so the absolute
                    // position of that end is the running consumed-sample count. Recorded HERE,
                    // under the same lock as the snapshot, because the callback keeps advancing the
                    // ring afterwards -- a position read later would include that lag.
                    const int64_t consumedSamples =
                        mFramesConsumed.load(std::memory_order_relaxed) * kVadFrameSize;
                    {
                        std::lock_guard<std::mutex> lock(mSpeechMutex);
                        mSnapshotBuffer = std::move(mSpeechBuffer);
                        mSnapshotSpeechOnsetSample = mSpeechOnsetSample;
                        mSnapshotSpeechEndSample = mSpeechEndSample;
                        mSnapshotSpeechEndStreamPosition = consumedSamples;
                        mSpeechBuffer.clear();
                    }

                    mSpeechFrames = 0;
                    mSpeechOnsetSample = 0;
                    mSpeechEndSample = 0;
                    mSpeechActive = false;
                    mConsecutiveSilentFrames = 0;
                    mDrainRemaining = 0;
                    mContextCount = 0;
                    mContextHead = 0;

                    std::lock_guard<std::mutex> lock(mEventMutex);
                    mPendingEvent = 2; // speech ended
                    mEventReady = true;
                    mEventCv.notify_one();
                }
            }
        }
    }

    LOGI("VAD runLoop exited");

    // The capture can end on its own — an AAudio disconnect makes readRingBuffer report the
    // stream inactive and ends this loop. The Kotlin flow is still blocked in waitForVadEvent,
    // which would then only ever time out, so publish the terminal -1 (the same event stop()
    // sends) and wake the waiter: otherwise the flow never completes and the service keeps
    // believing capture is running, with no restart.
    {
        std::lock_guard<std::mutex> lock(mEventMutex);
        // PRESERVE THE TERMINAL CAUSE. Overwriting unconditionally replaced a published
        // kFaultEvent with -1 whenever the consumer had not read it yet -- the fault became
        // scheduling-dependent. A latched fault stays what it is; only an unconsumed ordinary
        // exit gets the terminal stop sentinel.
        if (!mFaultLatched) {
            mPendingEvent = -1;
            mEventReady = true;
        }
        // FINAL ACT, after the terminal cause is settled: publish worker completion for the test
        // boundary (and any future observer). Set UNDER the event lock so an observer that sees
        // the flag also sees the published event state.
        mLoopExited.store(true, std::memory_order_release);
        mEventCv.notify_all();
    }
}
