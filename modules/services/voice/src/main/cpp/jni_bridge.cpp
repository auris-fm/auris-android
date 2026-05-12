#include <jni.h>
#include "OboeAudioCapture.h"

// All JNI functions below are called from the same Kotlin coroutine context
// (Dispatchers.IO), so a single global pointer is safe.
static OboeAudioCapture* gCapture = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voice_audio_OboeNative_nativeStartCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint channels)
{
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
Java_au_com_shiftyjelly_pocketcasts_voice_audio_OboeNative_nativeReadAudioData(
    JNIEnv* env,
    jclass /*clazz*/,
    jshortArray jBuffer)
{
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

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voice_audio_OboeNative_nativeStopCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    if (gCapture != nullptr) {
        gCapture->stop();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voice_audio_OboeNative_nativeCloseCapture(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    delete gCapture;
    gCapture = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voice_audio_OboeNative_nativeIsCapturing(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    return (gCapture != nullptr && gCapture->isActive()) ? JNI_TRUE : JNI_FALSE;
}
