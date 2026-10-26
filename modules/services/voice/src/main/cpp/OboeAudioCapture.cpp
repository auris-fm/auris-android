#include "OboeAudioCapture.h"
#include <android/log.h>
#include <ctime>

#define LOG_TAG "OboeCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// RingBuffer
// ---------------------------------------------------------------------------

RingBuffer::RingBuffer() {
    reset();
}

RingBuffer::~RingBuffer() = default;

int32_t RingBuffer::write(const int16_t* data, int32_t numSamples) {
    int64_t writeCursor = mWriteCursor.load(std::memory_order_relaxed);
    int64_t readCursor = mReadCursor.load(std::memory_order_acquire);

    int64_t used = writeCursor - readCursor;
    int64_t capacity = static_cast<int64_t>(kRingBufferCapacity);
    int64_t available = capacity - used;

    if (available <= 0) {
        // Buffer full — drop samples. Acceptable for ASR, but NOT for timing: a drop breaks the
        // cursors' continuity with real stream positions, so the accounting is flagged and every
        // later sample position is reported as unavailable rather than wrong.
        mSamplesDropped.store(true, std::memory_order_release);
        return 0;
    }

    int32_t toWrite = static_cast<int32_t>(
        (numSamples < available) ? numSamples : available);
    if (toWrite < numSamples) {
        // A partial write drops the remainder -- same continuity break as a full drop.
        mSamplesDropped.store(true, std::memory_order_release);
    }

    for (int32_t i = 0; i < toWrite; ++i) {
        int32_t idx = static_cast<int32_t>((writeCursor + i) % kRingBufferCapacity);
        mBuffer[idx] = data[i];
    }

    mWriteCursor.store(writeCursor + toWrite, std::memory_order_release);
    return toWrite;
}

int32_t RingBuffer::read(int16_t* outData, int32_t maxSamples) {
    int64_t readCursor = mReadCursor.load(std::memory_order_relaxed);
    int64_t writeCursor = mWriteCursor.load(std::memory_order_acquire);

    int64_t available = writeCursor - readCursor;
    if (available <= 0) {
        return 0;
    }

    int32_t toRead = static_cast<int32_t>(
        (maxSamples < available) ? maxSamples : available);

    for (int32_t i = 0; i < toRead; ++i) {
        int32_t idx = static_cast<int32_t>((readCursor + i) % kRingBufferCapacity);
        outData[i] = mBuffer[idx];
    }

    mReadCursor.store(readCursor + toRead, std::memory_order_release);
    return toRead;
}

int32_t RingBuffer::available() const {
    int64_t writeCursor = mWriteCursor.load(std::memory_order_acquire);
    int64_t readCursor = mReadCursor.load(std::memory_order_acquire);
    return static_cast<int32_t>(writeCursor - readCursor);
}

void RingBuffer::reset() {
    mWriteCursor.store(0, std::memory_order_relaxed);
    mReadCursor.store(0, std::memory_order_relaxed);
    // A new session is a new position epoch: cursors and continuity restart together, matching the
    // new stream's timestamp epoch.
    mSamplesDropped.store(false, std::memory_order_release);
}

// ---------------------------------------------------------------------------
// OboeAudioCapture
// ---------------------------------------------------------------------------

OboeAudioCapture::OboeAudioCapture() = default;

OboeAudioCapture::~OboeAudioCapture() {
    close();
}

oboe::AudioStreamBuilder OboeAudioCapture::buildStreamBuilder() {
    oboe::AudioStreamBuilder builder;

    // AAudio is required: on OpenSL ES getDeviceId() is kUnspecified and confirms nothing
    // (arch/android.md > Audio Route Detection, stream confirmation).
    builder.setAudioApi(oboe::AudioApi::AAudio)
           ->setDirection(oboe::Direction::Input)
           ->setSampleRate(mSampleRate)
           ->setChannelCount(mChannelCount)
           ->setFormat(oboe::AudioFormat::I16)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setPerformanceMode(oboe::PerformanceMode::None)
           ->setInputPreset(oboe::InputPreset::VoiceRecognition)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    return builder;
}

bool OboeAudioCapture::open() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream != nullptr) {
        return true; // already open
    }

    auto builder = buildStreamBuilder();

    oboe::Result result = builder.openStream(&mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        return false;
    }

    // Record actual parameters (may differ from requested)
    mSampleRate = mStream->getSampleRate();
    mChannelCount = mStream->getChannelCount();
    // The ACTUAL routed device, read only after open. It can differ from a requested device, and the
    // OpenSL ES backend reports kUnspecified, so callers must treat kUnspecified as unknown rather
    // than substituting an enumerated one.
    mRoutedDeviceId.store(mStream->getDeviceId(), std::memory_order_release);

    LOGI("Opened input stream: %d Hz, %d channels",
         mSampleRate, mChannelCount);
    return true;
}

bool OboeAudioCapture::start() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream == nullptr) {
        LOGE("Cannot start: stream is null");
        return false;
    }

    // A test-installed placeholder cannot be started (requestStart is pure virtual; calling
    // through it is undefined) -- the same accommodation close() makes. Marking active directly
    // is exactly what a successful requestStart would have done.
    // A new stream is a new continuity epoch: the xrun counter restarts with the stream, so the
    // previous epoch's evidence must not carry over into this one.
    mXrunBaselineValid.store(false, std::memory_order_release);
    mXrunBreaksContinuity.store(false, std::memory_order_release);

    if (mTestStreamInstalled) {
        mActive.store(true, std::memory_order_release);
        return true;
    }

    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        return false;
    }

    // Baseline the xrun counter HERE, while the stream has just started and before the VAD thread
    // consumes anything: a baseline taken later could absorb an xrun that happened since start and
    // report a compromised epoch as continuous. Taken after requestStart() succeeds, so the stream
    // exists and the query is meaningful.
    sampleXrunContinuity();

    mActive.store(true, std::memory_order_release);
    LOGI("Capture started");
    return true;
}

int32_t OboeAudioCapture::readData(int16_t* outBuffer, int32_t maxFrames) {
    int32_t samplesToRead = maxFrames * mChannelCount;
    return mRingBuffer.read(outBuffer, samplesToRead);
}

void OboeAudioCapture::stop() {
    mActive.store(false, std::memory_order_release);
    mDataCv.notify_all();
    // The routed device is a fact about a live stream: once capture stops there is no observed
    // capture route, and reporting the last one would be a stale observation.
    mRoutedDeviceId.store(oboe::kUnspecified, std::memory_order_release);

    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream != nullptr) {
        oboe::Result result = mStream->requestStop();
        if (result != oboe::Result::OK) {
            LOGE("Failed to stop stream: %s", oboe::convertToText(result));
        }
    }

    LOGI("Capture stopped");
}

void OboeAudioCapture::close() {
    if (mClosed.exchange(true, std::memory_order_acq_rel)) {
        return; // already closed
    }

    mActive.store(false, std::memory_order_release);

    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream != nullptr) {
        // A test-installed stream is not a real one: `requestStop` is pure virtual, so calling through
        // it on a placeholder is undefined behaviour. The test's stream is dropped rather than closed.
        if (!mTestStreamInstalled) {
            mStream->requestStop();
            mStream->close();
        }
        mStream = nullptr;
    }

    mRingBuffer.reset();
    mSampleRate = kSampleRate;
    mChannelCount = kChannelCount;
    // No stream, no observed capture device. Clearing here covers the explicit close path the same
    // way stop() and the error path do, so every way a stream ends leaves no observation behind.
    mRoutedDeviceId.store(oboe::kUnspecified, std::memory_order_release);

    LOGI("Capture closed");
}

int32_t OboeAudioCapture::getDeviceId() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream == nullptr) {
        return -1;
    }
    return mStream->getDeviceId();
}

void OboeAudioCapture::sampleXrunContinuity() {
    std::lock_guard<std::mutex> lock(mStreamMutex);

    int32_t count = 0;
    bool supported = false;

    if (mStream == nullptr) {
        // No live stream: there is no evidence to establish continuity from.
        supported = false;
    } else if (mTestStreamInstalled) {
        supported = mTestXrunSupported;
        count = mTestXrunCount;
    } else if (mStream->isXRunCountSupported()) {
        auto xruns = mStream->getXRunCount();
        if (xruns) {
            supported = true;
            count = xruns.value();
        }
        // An error is NOT zero: it is the absence of evidence, handled by the unsupported path.
    }

    if (!supported) {
        // Could not establish the baseline. Continuity is unavailable for this epoch, and stays
        // unavailable: "unknown" must never be reported as "continuous".
        mXrunBaselineValid.store(false, std::memory_order_release);
        mXrunBreaksContinuity.store(true, std::memory_order_release);
        return;
    }

    if (!mXrunBaselineValid.load(std::memory_order_acquire)) {
        // First observation of this epoch -- the baseline. Only sound if it is taken before audio
        // flows; start() takes it there, so a first VAD-thread call means start() could not, and the
        // epoch is already suspect.
        mXrunBaseline = count;
        mXrunBaselineValid.store(true, std::memory_order_release);
        return;
    }

    if (count != mXrunBaseline) {
        // The count moved: the HAL discarded input somewhere the ring never saw. Every position
        // derived from these cursors afterwards is untrustworthy for the rest of the epoch.
        mXrunBreaksContinuity.store(true, std::memory_order_release);
    }
}

OboeAudioCapture::CaptureClock OboeAudioCapture::captureClock() const {
    CaptureClock clock;
    clock.sampleRate = mSampleRate;

    // The fallback: the producer's own monotonic sample counter, which is always available and is a
    // real position in the capture stream. The stream's timestamp is preferred only because the clock
    // is what the OTHER side of the alignment is measured against.
    clock.framePosition = mRingBuffer.writeCursor();

    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream == nullptr) {
        // No live stream: the counter is the honest answer and the timestamp is unavailable rather
        // than invented.
        return clock;
    }
    if (mTestStreamInstalled) {
        // A test's placeholder cannot be interrogated, so its observation is supplied instead. The
        // consumption branch below is the same one a real stream takes.
        if (mTestTimestampAvailable) {
            clock.framePosition = mTestTimestampFrame;
            clock.timestampNs = mTestTimestampNs;
            clock.observable = true;
        }
        return clock;
    }
    auto timestamp = mStream->getTimestamp(CLOCK_MONOTONIC);
    if (timestamp) {
        clock.framePosition = timestamp.value().position;
        clock.timestampNs = timestamp.value().timestamp;
        clock.observable = true;
    }
    return clock;
}
int32_t OboeAudioCapture::getRoutedDeviceId() const {
    // Ask the LIVE stream when there is one. A cached value goes stale the moment the platform
    // reroutes an open stream -- a scenario distinct from a disconnect, where the stream stays open
    // and its routed device changes -- and a stale device is worse than a missing one because it
    // reads as a confirmed observation. The cache is only the fallback for the stop/close window,
    // where it is deliberately kUnspecified.
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream != nullptr) {
        // A test's placeholder cannot be interrogated (AudioStream has pure virtuals), so it reports
        // the id its seam supplied. A REAL stream is asked LIVE -- returning the test id here is what
        // made routedCaptureDeviceId() always null on device, classifying every wired/BLE headset as
        // microphone-less and refusing to start voice control (reviewer 4356625267).
        if (mTestStreamInstalled) {
            return mTestReportedDeviceId;
        }
        return mStream->getDeviceId();
    }
    return mRoutedDeviceId.load(std::memory_order_acquire);
}
bool OboeAudioCapture::isActive() const {
    return mActive.load(std::memory_order_acquire)
        && !mClosed.load(std::memory_order_acquire);
}

bool OboeAudioCapture::waitForData(int32_t timeoutMs) {
    std::unique_lock<std::mutex> lock(mWaitMutex);
    return mDataCv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
        [this]{ return mRingBuffer.available() > 0 || !isActive(); });
}

int32_t OboeAudioCapture::readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) {
    std::unique_lock<std::mutex> lock(mWaitMutex);
    bool ready = mDataCv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
        [this, maxSamples]{ return mRingBuffer.available() >= maxSamples || !isActive(); });

    if (!isActive()) {
        return -1;
    }

    if (!ready || mRingBuffer.available() < maxSamples) {
        return 0;
    }

    const int32_t read = mRingBuffer.read(outData, maxSamples);
    if (read > 0) {
        // The read cursor IS the absolute stream position of this chunk's last sample (while the
        // accounting is continuous). Recorded at the read, so the VAD can bind a finalized segment
        // to the position its final chunk actually ended at -- not to any later clock read.
        mLastReadEndPosition.store(mRingBuffer.readCursor(), std::memory_order_release);
    }
    return read;
}

oboe::DataCallbackResult OboeAudioCapture::onAudioReady(
    oboe::AudioStream* /*audioStream*/,
    void* audioData,
    int32_t numFrames)
{
    int32_t numSamples = numFrames * mChannelCount;
    auto* inputData = static_cast<const int16_t*>(audioData);

    mRingBuffer.write(inputData, numSamples);
    mDataCv.notify_one();

    return oboe::DataCallbackResult::Continue;
}

void OboeAudioCapture::onErrorAfterClose(
    oboe::AudioStream* failedStream,
    oboe::Result error)
{
    if (mClosed.load(std::memory_order_acquire)) {
        mActive.store(false, std::memory_order_release);
        return; // close() already cleaned up
    }

    std::lock_guard<std::mutex> lock(mStreamMutex);
    // Only the CURRENT stream's failure may act on this object's state. A replaced stream's delayed
    // callback would otherwise erase the observation its successor published -- a late event acting on
    // state that is no longer its own.
    //
    // The ACTIVE flag is part of that state, not an exception to it: clearing it here would stop a
    // capture whose own stream is still running, and refuse every later read with "stream is not
    // active" while audio is arriving. The guard has to cover both fields or it covers neither -- a
    // late callback that leaves the device intact but stops the stream is the same defect half-fixed.
    if (failedStream != mStream) {
        LOGE("Ignoring error-after-close from a replaced stream: %s", oboe::convertToText(error));
        return;
    }
    mActive.store(false, std::memory_order_release);
    mStream = nullptr;
    // The stream is gone, so the device it reported is no longer an observation. Leaving it set
    // would present a dead stream's routing as the live capture route -- the stale-observation
    // failure, on a disconnect or a route change that closed the stream.
    mRoutedDeviceId.store(oboe::kUnspecified, std::memory_order_release);
    LOGE("Stream error after close: %s", oboe::convertToText(error));
}
