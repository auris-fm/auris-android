#include <jni.h>
#include <mutex>
#include "OboeAudioCapture.h"
#include "NativeVadProcessor.h"

// gCapture/gVadProcessor are protected by gCaptureMutex.
// Combined start/stop (nativeStartCaptureAndVad / nativeStopCaptureAndVad)
// hold the mutex for the entire sequence, preventing races from concurrent
// route-change restarts and foreground transitions.
static OboeAudioCapture* gCapture = nullptr;
static NativeVadProcessor* gVadProcessor = nullptr;
static std::mutex gCaptureMutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeIsCapturing(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    return (gCapture != nullptr && gCapture->isActive()) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Combined capture + VAD lifecycle — atomic start/stop to prevent races
// between route-change restarts and foreground transitions.
// ---------------------------------------------------------------------------

extern "C" bool vadEnsureInitialized(JNIEnv* env, jobject assetManager);

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartCaptureAndVad(
    JNIEnv* env,
    jclass /*clazz*/,
    jint sampleRate,
    jint channels,
    jobject assetManager)
{
    // Initialize the Silero VAD ONNX session before starting the VAD processor.
    // Uses std::call_once internally — repeated calls are cheap.
    if (!vadEnsureInitialized(env, assetManager)) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(gCaptureMutex);

    // Reuse a stream opened by acquisition (nativeOpenInputCapture) so the device it
    // selected — already confirmed against the route — is the one that captures.
    if (gCapture == nullptr) {
        auto* capture = new OboeAudioCapture();
        if (!capture->open()) {
            delete capture;
            return JNI_FALSE;
        }
        gCapture = capture;
    }

    if (!gCapture->isActive() && !gCapture->start()) {
        delete gCapture;
        gCapture = nullptr;
        return JNI_FALSE;
    }

    delete gVadProcessor;
    gVadProcessor = new NativeVadProcessor(gCapture);

    if (!gVadProcessor->start()) {
        delete gVadProcessor;
        gVadProcessor = nullptr;
        // Capture is still valid — caller will stop/close it
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

// Opens the input stream without starting it; returns the AAudio device id it selected,
// or -1 when the stream could not be opened. No audio flows until the stream is started.
extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeOpenInputCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint /*sampleRate*/,
    jint /*channels*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);

    delete gVadProcessor;
    gVadProcessor = nullptr;
    delete gCapture;
    gCapture = nullptr;

    auto* capture = new OboeAudioCapture();
    if (!capture->open()) {
        delete capture;
        return -1;
    }
    gCapture = capture;
    return capture->getDeviceId();
}

// Starts a stream opened by nativeOpenInputCapture. No-op (true) when already active.
extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartInputStream(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    if (gCapture == nullptr) {
        return JNI_FALSE;
    }
    if (gCapture->isActive()) {
        return JNI_TRUE;
    }
    return gCapture->start() ? JNI_TRUE : JNI_FALSE;
}

// Closes any stream opened for acquisition. Idempotent.
extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeCloseInputCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    delete gVadProcessor;
    gVadProcessor = nullptr;
    delete gCapture;
    gCapture = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStopCaptureAndVad(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);

    // Stop VAD before capture — VAD thread reads from capture's ring buffer
    delete gVadProcessor;
    gVadProcessor = nullptr;

    delete gCapture;
    gCapture = nullptr;
}

// ---------------------------------------------------------------------------
// VAD processor JNI — lifecycle and event access
// ---------------------------------------------------------------------------


extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeWaitForVadEvent(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint timeoutMs)
{
    NativeVadProcessor* processor = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCaptureMutex);
        processor = gVadProcessor;
    }
    if (processor == nullptr) {
        return -1;
    }
    return processor->waitForEvent(static_cast<int32_t>(timeoutMs));
}

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeGetSpeechPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jshortArray jBuffer)
{
    NativeVadProcessor* processor = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCaptureMutex);
        processor = gVadProcessor;
    }
    if (processor == nullptr) {
        return 0;
    }

    jsize capacity = env->GetArrayLength(jBuffer);
    jshort* elements = env->GetShortArrayElements(jBuffer, nullptr);
    if (elements == nullptr) {
        return 0;
    }

    int32_t copied = processor->getSpeechPcm(
        reinterpret_cast<int16_t*>(elements),
        static_cast<int32_t>(capacity));

    env->ReleaseShortArrayElements(jBuffer, elements, 0); // copy back
    return static_cast<jint>(copied);
}

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeGetSpeechPcmSize(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    NativeVadProcessor* processor = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCaptureMutex);
        processor = gVadProcessor;
    }
    if (processor == nullptr) {
        return 0;
    }
    return processor->getSpeechPcmSize();
}

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeGetSpeechOnsetSample(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    NativeVadProcessor* processor = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCaptureMutex);
        processor = gVadProcessor;
    }
    if (processor == nullptr) {
        return 0;
    }
    return processor->getSpeechOnsetSample();
}

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeGetSpeechEndSample(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    NativeVadProcessor* processor = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCaptureMutex);
        processor = gVadProcessor;
    }
    if (processor == nullptr) {
        return 0;
    }
    return processor->getSpeechEndSample();
}
