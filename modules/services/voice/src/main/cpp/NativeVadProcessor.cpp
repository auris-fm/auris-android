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
        mPendingEvents.clear();
        mCompletedSegments.clear();
        mPendingEvents.push_back({-1, mUtteranceGeneration});
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

int NativeVadProcessor::waitForEvent(int32_t timeoutMs, int32_t* outGeneration) {
    std::unique_lock<std::mutex> lock(mEventMutex);
    if (mPendingEvents.empty()) {
        mEventCv.wait_for(lock, std::chrono::milliseconds(timeoutMs));
    }
    if (mPendingEvents.empty()) {
        if (outGeneration != nullptr) *outGeneration = -1;
        return 0; // timeout
    }
    // FIFO: the early-wake events made the single slot a lossy publisher — a cutoff or
    // trailing-silence event published while the consumer slept OVERWROTE an unread one. A queue
    // delivers every event in the order it was published.
    PendingEvent event = mPendingEvents.front();
    mPendingEvents.pop_front();
    if (outGeneration != nullptr) *outGeneration = event.generation;
    // An end event delivers ITS OWN completed snapshot, not the most recent one: two queued end
    // events (a consumer that lagged a pause-resume-pause sequence) must each serve the segment
    // they belong to, so the getters cannot hand back B's audio when the consumer asked for A's.
    if (event.code == 2 && !mCompletedSegments.empty()) {
        CompletedSegment segment = std::move(mCompletedSegments.front());
        mCompletedSegments.pop_front();
        std::lock_guard<std::mutex> speechLock(mSpeechMutex);
        mSnapshotBuffer = std::move(segment.pcm);
        mSnapshotSpeechOnsetSample = segment.onset;
        mSnapshotSpeechEndSample = segment.endSample;
        mSnapshotSpeechEndStreamPosition = segment.segmentEndStreamPosition;
        mCompletedGeneration = segment.generation;
    }
    return event.code;
}

int32_t NativeVadProcessor::getCompletedGeneration() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    return mCompletedGeneration;
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

int32_t NativeVadProcessor::getSpeechPcmRange(int16_t* outBuffer, int32_t offset, int32_t outMax) {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    const int32_t size = static_cast<int32_t>(mSnapshotBuffer.size());
    if (outBuffer == nullptr || outMax <= 0 || offset < 0 || offset >= size) {
        return 0;
    }
    const int32_t count = std::min(outMax, size - offset);
    std::memcpy(outBuffer, mSnapshotBuffer.data() + offset, static_cast<size_t>(count) * sizeof(int16_t));
    return count;
}

int32_t NativeVadProcessor::getInProgressSampleCount() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    // The active flag is a redundant guard: finalisation clears mSpeechBuffer under this same lock,
    // so a finished utterance reports 0 either way. It is kept as defence in depth, NOT as the
    // mechanism -- a mutation removing it leaves the test green, because the clear already does the
    // work. The clear is what the control actually covers.
    if (!mSpeechActive) {
        return 0;
    }
    return static_cast<int32_t>(mSpeechBuffer.size());
}

int32_t NativeVadProcessor::getInProgressSnapshot(int16_t* outBuffer, int32_t maxSamples, int64_t header[3]) {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    // ONE lock acquisition covers generation, onset, size and the copy: a snapshot taken here can
    // never half-describe one utterance and half another, which three separate locked calls could.
    if (!mSpeechActive || outBuffer == nullptr || maxSamples <= 0) {
        header[0] = mUtteranceGeneration;
        header[1] = 0;
        header[2] = 0;
        return 0;
    }
    header[0] = mUtteranceGeneration;
    header[1] = mSpeechOnsetSample;
    header[2] = static_cast<int32_t>(mSpeechBuffer.size());
    const int32_t count = (maxSamples < header[2]) ? maxSamples : static_cast<int32_t>(header[2]);
    std::memcpy(outBuffer, mSpeechBuffer.data(), static_cast<size_t>(count) * sizeof(int16_t));
    return count;
}

void NativeVadProcessor::setEarlyWakeCutoff(int32_t samples) {
    mEarlyWakeCutoff.store(samples, std::memory_order_relaxed);
}

int32_t NativeVadProcessor::getSpeechEndSample() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    return mSnapshotSpeechEndSample;
}

int32_t NativeVadProcessor::getInProgressSpeechOnsetSample() {
    std::lock_guard<std::mutex> lock(mSpeechMutex);
    return mSpeechOnsetSample;
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
        // The chunk's absolute end in RING accounting -- the read cursor after this read -- is the
        // sample position the segment anchor is derived from. This is the ring's own read position,
        // not a count of successful reads: the two coincide only while nothing is dropped, which is
        // exactly the condition the finalize path checks.
        mLastChunkEndPosition = mCapture->lastReadEndPosition();


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
                mUtteranceGeneration++;
                mEarlyWakeSignalled = false;
                mSpeechFrames = 0;
                mSpeechOnsetSample = 0;
                mSpeechEndSample = 0;
                mSpeechActive = false;
            }
            mConsecutiveSilentFrames = 0;
            mDrainRemaining = 0;
            mContextCount = 0;
            mContextHead = 0;

            std::lock_guard<std::mutex> lock(mEventMutex);
            // NOT the stop sentinel. `-1` means "the stream was stopped" and the consumer treats it
            // as an ordinary end, so emitting it here made a fault indistinguishable from a normal
            // shutdown: no error, no result, no state change, no recovery when the noise stops. A
            // distinct value is what makes the fault visible at the boundary.
            mPendingEvents.clear();
            mCompletedSegments.clear();
            mPendingEvents.push_back({kFaultEvent, mUtteranceGeneration}); // fault: surfaced, not an ordinary end
            mFaultLatched = true; // preserve the terminal cause through loop exit
            // The processor is STOPPED by the fault: clear mActive BEFORE unlocking so a later
            // start() cannot report already-running against an exiting worker.
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
                // Onset, context flush, the active flag and this frame's append all happen under
                // one lock. A reader can take a partial copy while speech is running, and it must
                // never observe a half-built utterance -- an empty buffer that claims to be active,
                // or a vector reallocating underneath it. Every field the snapshot reads is written
                // under this same lock, so a snapshot always describes ONE utterance coherently.
                {
                    std::lock_guard<std::mutex> lock(mSpeechMutex);
                    mSpeechOnsetSample = mContextCount * kVadFrameSize;
                    // Flush pre-speech context into speech buffer.
                    flushContextIntoSpeech(mSpeechBuffer, mSpeechFrames,
                        mContextBuffer, mContextHead, mContextCount,
                        kMaxContextFrames, kVadFrameSize);
                    mContextCount = 0;
                    mContextHead = 0;
                    mSpeechActive = true;
                    mEarlyWakeSignalled = false;
                }

                // Signal speech started. The generation is read under the speech mutex: this
                // event belongs to the utterance that just started, and only that lock owns
                // consistent access to the generation counter.
                int32_t startedGeneration;
                {
                    std::lock_guard<std::mutex> speechLock(mSpeechMutex);
                    startedGeneration = mUtteranceGeneration;
                }
                {
                    std::lock_guard<std::mutex> lock(mEventMutex);
                    mPendingEvents.push_back({1, startedGeneration}); // speech started
                    mEventCv.notify_one();
                }
            }

            // Accumulate current frame. Under the lock, together with the end-sample marker and the
            // frame count, so a concurrent snapshot reader sees one coherent utterance.
            bool cutoffReached = false;
            {
                std::lock_guard<std::mutex> lock(mSpeechMutex);
                mSpeechBuffer.insert(mSpeechBuffer.end(), chunk, chunk + kVadFrameSize);
                mSpeechFrames++;

                // The end of the last frame classified as speech, in the segment's own coordinates:
                // the buffer after this append is retained pre-roll plus every speech frame, which is the
                // same timeline speechOnsetSample and the detector's completionSample use. Taken AFTER
                // the append, or it lands one frame early. Only speech frames write it, so the drain
                // cannot move it.
                mSpeechEndSample = static_cast<int32_t>(mSpeechBuffer.size());

                // The early-wake cutoff: once per utterance, when the in-progress utterance passes
                // the length Kotlin configured, publish code 4 so the engine can score the wake
                // word while the user is still speaking. Under the same lock as the buffer so the
                // signalling cannot race the length it measures.
                const int32_t cutoff = mEarlyWakeCutoff.load(std::memory_order_relaxed);
                if (cutoff > 0 && !mEarlyWakeSignalled &&
                    mSpeechBuffer.size() - static_cast<size_t>(mSpeechOnsetSample) >= static_cast<size_t>(cutoff)) {
                    mEarlyWakeSignalled = true;
                    cutoffReached = true;
                }
            }
            if (cutoffReached) {
                // The cutoff belongs to the utterance whose length crossed the window: the
                // generation captured when the deciding state was read, under the speech mutex.
                int32_t cutoffGeneration;
                {
                    std::lock_guard<std::mutex> speechLock(mSpeechMutex);
                    cutoffGeneration = mUtteranceGeneration;
                }
                {
                    std::lock_guard<std::mutex> lock(mEventMutex);
                    mPendingEvents.push_back({4, cutoffGeneration}); // early-wake cutoff reached
                    mEventCv.notify_one();
                }
            }
            continue;
        }

        // 6. Silence handling.
        if (!mSpeechActive) {
            // No speech yet: buffer silent audio as potential pre-speech context.
            addToContext(mContextBuffer, mContextHead, mContextCount,
                chunk, kMaxContextFrames, kVadFrameSize);
        } else {
            mConsecutiveSilentFrames++;

            // The FIRST silent frame after speech is where trailing silence begins — the event the
            // engine opens an early connection on, outside the grace window. Published once, at
            // the transition, so it cannot flood the Kotlin side.
            if (mConsecutiveSilentFrames == 1) {
                int32_t silenceGeneration;
                {
                    std::lock_guard<std::mutex> speechLock(mSpeechMutex);
                    silenceGeneration = mUtteranceGeneration;
                }
                {
                    std::lock_guard<std::mutex> lock(mEventMutex);
                    mPendingEvents.push_back({3, silenceGeneration}); // trailing silence began
                    mEventCv.notify_one();
                }
            }

            if (mConsecutiveSilentFrames < kSilenceTimeoutFrames) {
                // Micro-pause bridging: accumulate silent frames as part of speech.
                {
                    std::lock_guard<std::mutex> lock(mSpeechMutex);
                    mSpeechBuffer.insert(mSpeechBuffer.end(), chunk, chunk + kVadFrameSize);
                    mSpeechFrames++;
                }
            } else {
                // Silence timeout reached — enter drain phase.
                if (mDrainRemaining <= 0) {
                    // Compute how many more frames to drain to reach the target
                    // utterance length (~3.5s) for reliable language detection.
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
                {
                    std::lock_guard<std::mutex> lock(mSpeechMutex);
                    mSpeechBuffer.insert(mSpeechBuffer.end(), chunk, chunk + kVadFrameSize);
                    mSpeechFrames++;
                }

                if (mDrainRemaining <= 0) {
                    // Drain complete — finalize the utterance.
                    int32_t endedGeneration = -1;

                    // The finalize point IS the sample-accounting anchor: this thread just
                    // consumed the frame containing the segment's last sample, and the ring's read
                    // cursor after that read is the segment's absolute end position. Recorded HERE,
                    // under the same lock as the snapshot, because the callback keeps advancing the
                    // ring afterwards -- a position read later would include that lag. If any write
                    // has dropped samples, cursor continuity with real stream positions is broken
                    // and 0 is recorded instead: the Kotlin side treats that as "timing
                    // unavailable" and falls back to offset matching, which is honest rather than
                    // confidently wrong.
                    // Revalidate the xrun evidence AFTER the segment was collected, not before it:
                    // a single poll taken on the way in would miss an overrun that happened during
                    // the extraction, which is precisely the window that displaces a position. The
                    // poll runs on this thread (never the audio callback) and is idempotent -- it
                    // compares against the epoch baseline set at stream start.
                    mCapture->sampleXrunContinuity();
                    const int64_t accountedEnd = mCapture->sampleAccountingContinuous()
                        ? mLastChunkEndPosition
                        : 0;
                    {
                        std::lock_guard<std::mutex> lock(mSpeechMutex);
                        mSnapshotBuffer = std::move(mSpeechBuffer);
                        mSnapshotSpeechOnsetSample = mSpeechOnsetSample;
                        mSnapshotSpeechEndSample = mSpeechEndSample;
                        mSnapshotSpeechEndStreamPosition = accountedEnd;
                        mSpeechBuffer.clear();
                        // The generation changes HERE, under the same lock as the reset: a snapshot
                        // taken across this boundary carries a generation the engine can detect and
                        // drop, instead of half one utterance and half the next.
                        endedGeneration = mUtteranceGeneration; // the utterance that just ENDED
                        mUtteranceGeneration++;
                        mEarlyWakeSignalled = false;
                        // Every field the snapshot reads is reset under this same lock, so no
                        // concurrent reader can see a torn utterance.
                        mSpeechFrames = 0;
                        mSpeechOnsetSample = 0;
                        mSpeechEndSample = 0;
                        mSpeechActive = false;
                    }

                    mConsecutiveSilentFrames = 0;
                    mDrainRemaining = 0;
                    mContextCount = 0;
                    mContextHead = 0;
                    // THIS utterance's completed segment, captured under the same lock as the
                    // snapshot members: its OWN generation (pre-increment — the generation the
                    // engine learned from its early snapshots for this utterance), PCM copy,
                    // onset, speech end and the accounting anchor. Queued with the end event so
                    // the consumer pops one coherent segment per event even when two ends are
                    // queued.
                    CompletedSegment completed;
                    completed.generation = endedGeneration;
                    completed.pcm = mSnapshotBuffer;
                    completed.onset = mSnapshotSpeechOnsetSample;
                    completed.endSample = mSnapshotSpeechEndSample;
                    completed.segmentEndStreamPosition = mSnapshotSpeechEndStreamPosition;

                    std::lock_guard<std::mutex> lock(mEventMutex);
                    mCompletedSegments.push_back(std::move(completed));
                    mPendingEvents.push_back({2, endedGeneration}); // speech ended
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
            mPendingEvents.clear();
            mCompletedSegments.clear();
            mPendingEvents.push_back({-1, mUtteranceGeneration});
        }
        // FINAL ACT, after the terminal cause is settled: publish worker completion for the test
        // boundary (and any future observer). Set UNDER the event lock so an observer that sees
        // the flag also sees the published event state.
        mLoopExited.store(true, std::memory_order_release);
        mEventCv.notify_all();
    }
}
