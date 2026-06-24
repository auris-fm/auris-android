#include <jni.h>
#include <mutex>
#include "OboeAudioCapture.h"
#include "NativeVadProcessor.h"

// gCapture is protected by gCaptureMutex for safe access from the
// Kotlin Dispatchers.IO thread pool. The OboeAudioCapture instance
// also has internal synchronization for its stream pointer.
static OboeAudioCapture* gCapture = nullptr;
static NativeVadProcessor* gVadProcessor = nullptr;
static std::mutex gCaptureMutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint channels)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);

    // Clean up any previous instance
    delete gCapture;
    gCapture = nullptr;

    auto* capture = new OboeAudioCapture();

    if (!capture->open()) {
        delete capture;
        return JNI_FALSE;
    }

    if (!capture->start()) {
        capture->close();
        delete capture;
        return JNI_FALSE;
    }

    gCapture = capture;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeReadAudioData(
    JNIEnv* env,
    jclass /*clazz*/,
    jshortArray jBuffer)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);

    if (gCapture == nullptr || !gCapture->isActive()) {
        return 0;
    }

    jsize capacity = env->GetArrayLength(jBuffer);
    jshort* elements = env->GetShortArrayElements(jBuffer, nullptr);
    if (elements == nullptr) {
        return 0; // JNI pin failed
    }

    int32_t framesRead = gCapture->readData(
        reinterpret_cast<int16_t*>(elements),
        static_cast<int32_t>(capacity));

    env->ReleaseShortArrayElements(jBuffer, elements, 0); // 0 = copy back and free

    return static_cast<jint>(framesRead);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeAudioWaitForData(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint timeoutMs)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    if (gCapture == nullptr) {
        return JNI_FALSE;
    }
    return gCapture->waitForData(static_cast<int32_t>(timeoutMs)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStopCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    if (gCapture != nullptr) {
        gCapture->stop();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeCloseCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    delete gCapture;
    gCapture = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeIsCapturing(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    return (gCapture != nullptr && gCapture->isActive()) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// VAD processor JNI — lifecycle and event access
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartVadProcessor(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);

    if (gCapture == nullptr) {
        return JNI_FALSE;
    }

    delete gVadProcessor;
    gVadProcessor = new NativeVadProcessor(gCapture);

    if (!gVadProcessor->start()) {
        delete gVadProcessor;
        gVadProcessor = nullptr;
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStopVadProcessor(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gCaptureMutex);
    delete gVadProcessor;
    gVadProcessor = nullptr;
}

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
