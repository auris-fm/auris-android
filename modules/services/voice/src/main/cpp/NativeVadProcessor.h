#ifndef POCKETCASTS_NATIVE_VAD_PROCESSOR_H
#define POCKETCASTS_NATIVE_VAD_PROCESSOR_H

#include <atomic>
#include <condition_variable>
#include <cstdint>
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

    // Block until event. Returns: 1=speech started, 2=speech ended, 0=timeout, -1=stopped
    int waitForEvent(int32_t timeoutMs);

    int32_t getSpeechPcmSize();
    int32_t getSpeechPcm(int16_t* outBuffer, int32_t maxSamples);
    int32_t getSpeechOnsetSample();
    int32_t getSpeechEndSample();

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
    static bool energyGate(const int16_t* samples, int32_t count);

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

    // Snapshot of mSpeechBuffer for safe cross-thread access from Kotlin.
    std::vector<int16_t> mSnapshotBuffer;
    int32_t mSnapshotSpeechOnsetSample = 0;
    int32_t mSnapshotSpeechEndSample = 0;
    std::mutex mSpeechMutex;

    int32_t mConsecutiveSilentFrames = 0;
    int32_t mDrainRemaining = 0;

    std::mutex mEventMutex;
    std::condition_variable mEventCv;
    int mPendingEvent = 0;
    bool mEventReady = false;
};

#endif // POCKETCASTS_NATIVE_VAD_PROCESSOR_H
