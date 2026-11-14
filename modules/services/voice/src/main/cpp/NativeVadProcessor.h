#ifndef POCKETCASTS_NATIVE_VAD_PROCESSOR_H
#define POCKETCASTS_NATIVE_VAD_PROCESSOR_H

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

class OboeAudioCapture;

/**
 * C++ VAD state machine that consumes audio from the Oboe ring buffer,
 * runs an energy gate and Silero VAD ONNX inference, and signals
 * speech-start / speech-end events to Kotlin via JNI.
 *
 * The processing thread is the sole consumer of the ring buffer.
 * Kotlin blocks on waitForEvent() rather than polling per-frame.
 */
class NativeVadProcessor {
public:
    explicit NativeVadProcessor(OboeAudioCapture* capture);
    ~NativeVadProcessor();

    bool start();   // launch processing thread
    void stop();    // join thread, reset state

    // Block until event. Returns: 1=speech started, 2=speech ended, 3=trailing silence began,
    // 4=early-wake cutoff reached (needs setEarlyWakeCutoff), 0=timeout, -1=stopped.
    // Every event carries the utterance generation it belongs to: waitForEvent installs the
    // generation in [outGeneration] (never null). A consumer compares it against the generation
    // it expects before acting, so a delayed event for a finished utterance cannot be mistaken
    // for one about the utterance now in progress.
    int waitForEvent(int32_t timeoutMs, int32_t* outGeneration);

    // The generation of the COMPLETED segment waitForEvent installed last (its own, not the
    // utterance now in progress). -1 before any end event was popped.
    int32_t getCompletedGeneration();

    int32_t getSpeechPcmSize();
    int32_t getSpeechPcm(int16_t* outBuffer, int32_t maxSamples);

    /** Copies min(outMax, size-offset) samples from snapshot [offset, ...) into outBuffer. */
    int32_t getSpeechPcmRange(int16_t* outBuffer, int32_t offset, int32_t outMax);

    /**
     * How many samples of the IN-PROGRESS utterance are available right now.
     *
     * Separate from getSpeechPcmSize(), which only describes a finalized segment. Wake detection
     * only ever scores the first two seconds after onset, so a caller that gets this many samples
     * can score the wake word before the utterance ends without changing the answer.
     *
     * Returns 0 when no utterance is in progress.
     */
    int32_t getInProgressSampleCount();

    /**
     * ONE coherent snapshot of the utterance still in progress: PCM copied into [outBuffer] (up to
     * [maxSamples]), and header[0] = utterance generation, header[1] = speech onset (samples from
     * the utterance's start), header[2] = utterance size in samples — all read under mSpeechMutex
     * in a single call, so they can never describe different utterances. Returns the number of
     * samples copied, or 0 when no utterance is in progress. The generation changes at every
     * finalisation or fault reset, so a caller that recorded one generation can drop a snapshot
     * taken across that boundary instead of scoring a mixture.
     */
    int32_t getInProgressSnapshot(int16_t* outBuffer, int32_t maxSamples, int64_t header[3]);

    /**
     * The speech onset inside the utterance STILL IN PROGRESS, in samples from its start.
     * Taken under the same lock as the in-progress reads, so a reader can place the early-score
     * window in the buffer it copies without racing finalisation.
     */
    int32_t getInProgressSpeechOnsetSample();

    /**
     * The utterance length past which the VAD publishes an early-wake cutoff event (code 4), in
     * samples at the capture rate. 0 disables it. Set by Kotlin so the constant lives on one side
     * of the boundary.
     */
    void setEarlyWakeCutoff(int32_t samples);

    /** Host-test observation of the configured cutoff (JniBridgeOwnershipTest scenario D). */
    int32_t earlyWakeCutoff() const { return mEarlyWakeCutoff.load(std::memory_order_relaxed); }

    int32_t getSpeechOnsetSample();
    int32_t getSpeechEndSample();

    /**
     * The absolute consumed-stream sample position of the last completed segment's end, recorded by
     * the VAD thread at the moment it finalized the segment. Recorded, not read afterwards: the
     * callback and ring can advance between finalization and Kotlin's drain, so any later clock or
     * cursor read would fold that lag into the segment's position. Counts samples consumed from
     * capture since start (mono), the same space the ring cursor and stream frame position use.
     */
    int64_t getSpeechEndStreamPosition();

    /**
     * Frames this processor has consumed from the capture ring buffer since start(), whether or not
     * they produced an event. Distinguishes "capture is running" from "captured audio is actually
     * reaching the VAD", which a liveness check alone cannot tell apart in silence.
     */
    int64_t getFramesConsumed();

    /**
     * Aggregate diagnosis of the detector, for capture debugging only.
     *
     * Aggregates rather than raw audio: the fields are the last frame's energy-gate RMS and Silero
     * score, the ones the detector itself compared, plus how many consecutive speech frames it has
     * accepted and its endpoint state. No sample is exported, and the values carry no user content
     * beyond a level. All fields are atomics read across threads.
     */
    struct VadDiagnostics {
        double lastRms = 0.0;          // energy gate input for the most recent frame
        float lastScore = 0.0f;        // Silero probability for the most recent frame
        bool lastGatePassed = false;   // whether the energy gate let the frame through
        bool lastIsSpeech = false;     // the detector's decision for the most recent frame
        int32_t speechFrames = 0;      // frames accepted into the current utterance
        bool speechActive = false;     // whether an utterance is open
        int32_t silentFrames = 0;      // consecutive silent frames, for the endpoint
        int32_t drainRemaining = 0;    // endpoint drain countdown
    };
    VadDiagnostics getDiagnostics();

    /**
     * Whether diagnostics are collected at all. Off unless a debug/test build turns them on, so a
     * release build does not pay for them and cannot expose them.
     */
    void setDiagnosticsEnabled(bool enabled);

private:
    void runLoop();
    // Not static: the gate records the RMS it compared when diagnostics are on, so the diagnosis
    // reports the gate's own value rather than recomputing the same formula elsewhere.
    bool energyGate(const int16_t* samples, int32_t count);

    // Parameters (matching spec thresholds)
    static constexpr int32_t kVadFrameSize = 1024;        // 64ms @ 16kHz
    static constexpr int32_t kSilenceTimeoutFrames = 7;    // ~448ms
    static constexpr int32_t kMinPostSpeechFrames = 10;    // ~640ms drain floor
    static constexpr int32_t kTargetTotalFrames = 55;      // ~3.5s target
    static constexpr int32_t kMaxContextFrames = 20;       // ~1.28s pre-speech
    // No forced speech-duration endpoint: framing retains and joins until the natural end. This
    // ceiling only exists to surface a detector STUCK active as an explicit fault; it must be far
    // above any plausible utterance so ordinary long speech is never affected.
    static constexpr int32_t kStuckDetectorFrameLimit = 3750;  // ~240s @ 64ms frames
  public:
    static constexpr int32_t stuckFrameLimitForTest() { return kStuckDetectorFrameLimit; }
    // TEST BOUNDARY (U1 barrier): blocks until the runLoop thread has EXITED on its own (the exit
    // block ran), WITHOUT calling stop() and without touching the pending event. Returns false if
    // the worker did not exit within the bound -- the caller must assert success before reading the
    // terminal cause. Synchronization is by worker completion, not by script bookkeeping.
    bool awaitRunLoopExitForTest(int maxMs);
  private:

    /**
     * The event value that reports a FAULT, distinct from the stop sentinel.
     *
     * The consumer reads -1 as an ordinary end (it is what stop() emits), so a fault reported with that
     * value looked like a clean shutdown: the session ended with no error, no result and no recovery.
     * A separate value lets the consumer surface it instead of completing.
     */
  public:
    static constexpr int32_t kFaultEvent = -2;
  private:
    static constexpr double kRmsThreshold = 200.0;
    static constexpr float kSpeechThreshold = 0.2f;

    OboeAudioCapture* mCapture;

    std::unique_ptr<std::thread> mThread;
    std::atomic<bool> mActive{false};

    /** Frames read from the capture ring buffer since start(); read across threads for diagnostics. */
    std::atomic<int64_t> mFramesConsumed{0};

    /** Diagnostics: written by the VAD thread, read by Kotlin. Disabled in release builds. */
    std::atomic<bool> mDiagnosticsEnabled{false};
    std::atomic<double> mLastRms{0.0};
    std::atomic<float> mLastScore{0.0f};
    std::atomic<bool> mLastGatePassed{false};
    std::atomic<bool> mLastIsSpeech{false};
    std::atomic<int32_t> mDiagSpeechFrames{0};
    std::atomic<bool> mDiagSpeechActive{false};
    std::atomic<int32_t> mDiagSilentFrames{0};
    std::atomic<int32_t> mDiagDrainRemaining{0};

    // Circular pre-speech context buffer: stores up to kMaxContextFrames of
    // silent audio frames before speech onset.
    std::vector<int16_t> mContextBuffer;
    int32_t mContextHead = 0;
    int32_t mContextCount = 0;

    // Assembled utterance PCM (written by VAD thread).
    std::vector<int16_t> mSpeechBuffer;
    int32_t mSpeechFrames = 0;
    int32_t mSpeechOnsetSample = 0;
    int32_t mSpeechEndSample = 0;
    bool mSpeechActive = false;
    bool mFaultLatched = false; // set when the stuck guard published the fault
    std::atomic<bool> mLoopExited{false}; // runLoop's final act, observed by awaitRunLoopExitForTest

    // Snapshot of mSpeechBuffer for safe cross-thread access from Kotlin.
    std::vector<int16_t> mSnapshotBuffer;
    int32_t mSnapshotSpeechOnsetSample = 0;
    int32_t mSnapshotSpeechEndSample = 0;
    int64_t mSnapshotSpeechEndStreamPosition = 0;

    /** Bumped at every finalisation or fault reset, under mSpeechMutex. Identifies one utterance. */
    int32_t mUtteranceGeneration = 0;

    /** Utterance length past which the cutoff event (4) fires once. 0 = disabled. Atomic: set from JNI. */
    std::atomic<int32_t> mEarlyWakeCutoff {0};

    /** Whether event 4 already fired for the current utterance. VAD thread only. */
    bool mEarlyWakeSignalled = false;

    /** Ring read-cursor position after the most recent chunk this thread consumed (VAD thread only). */
    int64_t mLastChunkEndPosition = 0;
    std::mutex mSpeechMutex;

    int32_t mConsecutiveSilentFrames = 0;
    int32_t mDrainRemaining = 0;

    std::mutex mEventMutex;
    std::condition_variable mEventCv;

    /**
     * Pending events, FIFO. Was a single slot: the early-wake events made that a lossy publisher —
     * a cutoff or trailing-silence event published while the consumer slept overwrote an unread
     * one, and the engine would miss the very moments the early-wake optimisation reacts to.
     */
    struct PendingEvent {
        int32_t code;
        int32_t generation; // the utterance generation the event belongs to
    };
    std::deque<PendingEvent> mPendingEvents;

    // A COMPLETED segment captured at finalize time. Queued per end event so a second end event
    // cannot overwrite the first consumer's snapshot: the snapshot the getters serve belongs to
    // the end event just popped, not to whatever utterance finished most recently.
    struct CompletedSegment {
        int32_t generation;
        std::vector<int16_t> pcm;
        int32_t onset;
        int32_t endSample;
        int64_t segmentEndStreamPosition;
    };
    std::deque<CompletedSegment> mCompletedSegments;

    // The generation of the segment currently served by the completed-snapshot getters
    // (getSpeechPcm/getSpeechOnsetSample/getSpeechEndSample/getSpeechEndStreamPosition).
    int32_t mCompletedGeneration = -1;
};

#endif // POCKETCASTS_NATIVE_VAD_PROCESSOR_H
