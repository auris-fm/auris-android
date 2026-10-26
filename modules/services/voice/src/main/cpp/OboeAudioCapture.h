#ifndef POCKETCASTS_OBOE_AUDIO_CAPTURE_H
#define POCKETCASTS_OBOE_AUDIO_CAPTURE_H

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <oboe/Oboe.h>

constexpr int32_t kSampleRate = 16000;
constexpr int32_t kChannelCount = 1;          // MONO
constexpr int32_t kRingBufferCapacity = 32768; // ~2 seconds at 16kHz mono

/**
 * Lock-free single-producer single-consumer ring buffer for int16_t audio samples.
 *
 * The producer (Oboe onAudioReady callback) increments writeCursor.
 * The consumer (JNI poll thread) increments readCursor.
 * Both cursors are monotonic counters (never wrap modulo). The actual buffer
 * index is cursor % kRingBufferCapacity.
 *
 * Memory ordering:
 * - Producer: relaxed write cursor load, acquire read cursor load, release write cursor store
 * - Consumer: relaxed read cursor load, acquire write cursor load, release read cursor store
 */
class RingBuffer {
public:
    RingBuffer();
    ~RingBuffer();

    // Called from the audio thread (Oboe callback). Returns samples written.
    int32_t write(const int16_t* data, int32_t numSamples);

    // Called from the JNI polling thread. Returns samples read.
    int32_t read(int16_t* outData, int32_t maxSamples);

    // Returns number of samples available to read.
    int32_t available() const;

    /**
     * The producer's monotonic sample counter: the total samples written since construction.
     *
     * Never wraps (only the buffer INDEX is `cursor % capacity`), so this is a position in the capture
     * stream rather than an index into the buffer. That is what lets a sample position be related to a
     * stream timestamp: the two are the same quantity in different units.
     */
    int64_t writeCursor() const {
        return mWriteCursor.load(std::memory_order_acquire);
    }

    /**
     * The consumer's monotonic sample counter: the total samples READ since construction. After a
     * successful read of N samples, this is the absolute stream position of the LAST sample that
     * read returned -- the accounting the segment anchor is derived from. Valid as a stream
     * position only while [samplesDropped] is false.
     */
    int64_t readCursor() const {
        return mReadCursor.load(std::memory_order_acquire);
    }

    /**
     * True once a write had to drop samples because the consumer fell behind. Dropped samples make
     * the cursors diverge from real stream positions (the stream advanced by more than was
     * consumed), so any position derived from them afterwards is untrustworthy and consumers must
     * report timing unavailable rather than wrong.
     */
    bool samplesDropped() const {
        return mSamplesDropped.load(std::memory_order_acquire);
    }

    void reset();

private:
    int16_t mBuffer[kRingBufferCapacity];
    std::atomic<int64_t> mWriteCursor{0};
    std::atomic<int64_t> mReadCursor{0};
    std::atomic<bool> mSamplesDropped{false};
};

/**
 * Wraps an Oboe input stream using callback mode.
 *
 * Callback mode avoids the AAudio releaseBuffer assertion that fires when
 * using blocking read/write with co-existing capture and playback streams
 * (Oboe issue #535, google-ai-edge/LiteRT-LM issues #684, #1033).
 */
class OboeAudioCapture : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    OboeAudioCapture();
    // Virtual so a test can substitute a capture and drive the VAD loop deterministically.
    virtual ~OboeAudioCapture();

    // Open an input stream. Returns true on success.
    bool open();

    // Start the stream. Must call open() first.
    bool start();

    // Copy available samples from the ring buffer into outBuffer.
    // maxFrames is the desired number of audio frames to read.
    // Returns the number of frames actually written to outBuffer.
    int32_t readData(int16_t* outBuffer, int32_t maxFrames);

    // Block until data is available in the ring buffer or the stream stops.
    // Returns true if data is available to read, false on timeout or inactive.
    bool waitForData(int32_t timeoutMs);

    // Block until data is available, then read from the ring buffer.
    // This is the sole consumer path for the VAD processing thread.
    // Returns number of samples read, 0 on timeout, -1 if stream is not active.

    /**
     * The absolute stream position (ring read cursor) of the last sample a successful
     * readRingBuffer returned. Meaningful only while [sampleAccountingContinuous] holds.
     */
    int64_t lastReadEndPosition() const {
        return mLastReadEndPosition.load(std::memory_order_acquire);
    }

    /**
     * Whether the ring's cursors still correspond 1:1 to real stream positions. False once any
     * write dropped samples (consumer fell behind): positions after a drop are untrustworthy, and
     * the anchor must be reported unavailable instead of wrong.
     *
     * Origin: the ring is constructed with the capture and reset only when the capture closes, so
     * within one capture session position 0 is the session's first captured sample -- the same
     * epoch the stream's timestamp frame positions count from (the existing fallback anchor makes
     * the same identification). A capture restart builds a new capture, ring, stream and VAD
     * together, so both epochs restart together.
     */
    bool sampleAccountingContinuous() const {
        // TWO independent ways continuity can break, and BOTH must be clear. The ring flag catches a
        // drop the ring itself performed; the xrun count catches a drop the HAL performed before the
        // samples ever reached us (an overrun discards input when nobody read in time). The second
        // advances the hardware stream without ever entering RingBuffer::write, so the ring stays
        // self-consistent while the cursors fall behind real stream positions -- exactly the case a
        // ring-only check reports as continuous. Positions derived from either are unavailable.
        return !mRingBuffer.samplesDropped() && !mXrunBreaksContinuity.load(std::memory_order_acquire);
    }
    virtual int32_t readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs);

    // Stop the stream.
    void stop();

    // Close the stream and release resources.
    void close();

    // Returns true if the stream is active.
    bool isActive() const;

    // Accessors for actual stream parameters.
    int32_t getSampleRate() const { return mSampleRate; }
    int32_t getChannelCount() const { return mChannelCount; }

    // The device the opened stream actually selected. Valid after open(), before start().
    // Returns kUnspecified (0) when unknown (for example on the OpenSL ES backend) and
    // -1 when no stream is open.
    // Virtual for the host JNI-ownership test, whose installed placeholder stream cannot be
    // interrogated (same substitution pattern as readRingBuffer below).
    virtual int32_t getDeviceId();

    /**
     * The device the opened capture stream is actually routed to, or kUnspecified when the platform
     * does not report one.
     *
     * Read from the stream itself after open, which is the only way to know the ACTUAL device: it can
     * differ from what was requested, and OpenSL ES backends always report kUnspecified. A caller
     * must treat kUnspecified as unknown rather than substituting an enumerated device -- availability
     * is not routing. Matches AudioDeviceInfo.getId() on the Java side.
     */
    int32_t getRoutedDeviceId() const;

    /**
     * A capture-frame position on the shared monotonic clock: where a sample sits in time.
     *
     * `framePosition` is a position in the CAPTURE STREAM (the producer's monotonic sample counter, or
     * a stream frame position), `timestampNs` is `CLOCK_MONOTONIC`, and `sampleRate` converts between
     * them. Any sample at stream position `p` is then at
     * `timestampNs + (p - framePosition) * 1e9 / sampleRate`.
     *
     * `observable` is false when the platform reports no timestamp -- `getTimestamp` fails on some
     * backends, and OpenSL ES in particular. An unavailable observation must stay unavailable: a
     * substituted value would be a position the platform never reported, which is worse than none
     * because it aligns confidently against the wrong instant.
     */
    struct CaptureClock {
        int64_t framePosition = 0;
        int64_t timestampNs = 0;
        int32_t sampleRate = kSampleRate;
        bool observable = false;
    };

    /**
     * Samples the stream's own frame/time observation.
     *
     * MUST be called from the DRAIN thread, never from `onAudioReady`: the Oboe header says
     * *"due to issues in Android before R, we recommend NOT calling this method from a data
     * callback"*, and calling it there would also make the observation describe the callback's instant
     * rather than the consumer's.
     *
     * The frame position and the ring's write cursor are read TOGETHER, in one call, so the pair
     * describes ONE instant. Reading them separately gives two instants -- the same "two timelines
     * paired as one" failure the alignment exists to avoid, reached by sequencing instead of by a
     * missing value.
     */
    CaptureClock captureClock() const;

    /**
     * Installs [stream] as the live stream, with [deviceId] as the device it reports.
     *
     * A seam for the host test, which cannot open a real Oboe stream and cannot fabricate one either
     * (`AudioStream` has ten pure virtuals). `onErrorAfterClose` compares its argument against the live
     * stream and clears the device only on a match, and neither half of that could be driven while the
     * state was unreachable. `getRoutedDeviceId` reads through a REAL stream when one is installed, so
     * this sets the reported device directly as well.
     */
    void installStreamForTest(oboe::AudioStream* stream, int32_t deviceId) {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        mStream = stream;
        mTestReportedDeviceId = deviceId;
        mTestStreamInstalled = true;
        mActive.store(true, std::memory_order_release);
    }

    /**
     * Clears the active flag an installed stream sets, WITHOUT calling stop() -- which would
     * requestStop() the placeholder and crash (stop() has no test-installed guard; close() does).
     * The JNI-ownership host test uses this so the entries under test perform the actual start
     * themselves rather than inheriting one from the seam.
     */
    void deactivateForTest() { mActive.store(false, std::memory_order_release); }

    /**
     * Supplies the stream's frame/time observation, so the branch that USES it is reachable.
     *
     * A test cannot call `getTimestamp` -- it needs a real Oboe stream -- so without this the only
     * reachable path through `captureClock` is the early return, and the code that consumes a
     * timestamp would be covered by nothing. Setting it to "unavailable" is how the
     * unavailable-stays-unavailable property is exercised rather than assumed.
     */
    /**
     * Samples the stream's xrun counter, the evidence the ring cannot see.
     *
     * MUST be called from the VAD thread, never from `onAudioReady`: the AAudio implementation takes
     * the stream's shared lock to read the count, and calling a locking stream method from the data
     * callback risks priority inversion against the stream being measured.
     *
     * The FIRST call in a stream epoch records the baseline; every later call compares against it.
     * That ordering is the point: a baseline taken after audio has already started could absorb an
     * xrun that occurred since start, reporting the epoch as continuous when it never was. If the
     * baseline cannot be taken -- unsupported on this backend, or the query errors -- continuity is
     * never established for the epoch, so timing stays unavailable rather than assumed good.
     */
    void sampleXrunContinuity();

    /** Host-test control of the xrun evidence: unavailable emulates a backend without support. */
    void setXrunCountForTest(bool supported, int32_t count) {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        mTestXrunSupported = supported;
        mTestXrunCount = count;
    }

    void setStreamTimestampForTest(bool available, int64_t framePosition, int64_t timestampNs) {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        mTestTimestampAvailable = available;
        mTestTimestampFrame = framePosition;
        mTestTimestampNs = timestampNs;
    }

    /**
     * Drops the test's stream WITHOUT touching it.
     *
     * `close()` calls `requestStop()` and `close()` on the stream, and a test's stream is not a real
     * one -- `requestStop` is pure virtual, so calling through it on a placeholder is undefined
     * behaviour. The test clears the pointer here before destruction, which is the same state a real
     * close leaves behind.
     */
    /** Host-test read of the consumer cursor, for epoch-reset assertions. */
    int64_t readCursorForTest() const { return mRingBuffer.readCursor(); }

    /** Host-test read of the producer cursor, for continuity/epoch assertions. */
    int64_t writeCursorForTest() const { return mRingBuffer.writeCursor(); }

    void clearStreamForTest() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        mStream = nullptr;
        mTestStreamInstalled = false;
    }
    // --- oboe::AudioStreamDataCallback ---
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

    // --- oboe::AudioStreamErrorCallback ---
    void onErrorAfterClose(
        oboe::AudioStream* audioStream,
        oboe::Result error) override;

private:
    oboe::AudioStream* mStream = nullptr;

    /** The device the live capture stream is routed to; kUnspecified when none is observed. */
    std::atomic<int32_t> mRoutedDeviceId{oboe::kUnspecified};

    // The device the host test's installed stream reports. `getRoutedDeviceId` asks the live stream
    // for the device, which a test cannot construct, so this stands in for that answer.
    int32_t mTestReportedDeviceId = oboe::kUnspecified;
    bool mTestStreamInstalled = false;
    bool mTestTimestampAvailable = false;
    int64_t mTestTimestampFrame = 0;
    int64_t mTestTimestampNs = 0;
    bool mTestXrunSupported = true;
    int32_t mTestXrunCount = 0;

    // The xrun evidence, published for sampleAccountingContinuous(). `mXrunBaselineValid` is false
    // until a baseline is taken, and false means "continuity not established", never "continuous".
    std::atomic<bool> mXrunBreaksContinuity{false};
    std::atomic<bool> mXrunBaselineValid{false};
    int32_t mXrunBaseline = 0;
    std::atomic<int64_t> mLastReadEndPosition{0};


    RingBuffer mRingBuffer;
    int32_t mSampleRate = kSampleRate;
    int32_t mChannelCount = kChannelCount;
    std::atomic<bool> mActive{false};
    std::atomic<bool> mClosed{false};
    // Mutable so the const accessor can read the live stream's device under the same lock that
    // guards its assignment: the answer belongs to whichever stream is current.
    mutable std::mutex mStreamMutex;
    std::mutex mWaitMutex;
    std::condition_variable mDataCv;

    oboe::AudioStreamBuilder buildStreamBuilder();
};

#endif // POCKETCASTS_OBOE_AUDIO_CAPTURE_H
