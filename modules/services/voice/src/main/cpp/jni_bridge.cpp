#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <mutex>
#include "OboeAudioCapture.h"
#include "NativeVadProcessor.h"

#define LOG_TAG "VoicePipeline"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[VoicePipeline] " __VA_ARGS__)

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

// Whether libonnxruntime.so is already loaded in this process. Uses the same RTLD_NOLOAD idiom the
// capture path uses, so a test can assert the ordering property directly: capture must succeed even
// when this returns false (i.e. when wake/embedding/transcriber setup has not run first).
extern "C" JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeIsOrtLoaded(
    JNIEnv* /*env*/,
    jclass /*clazz*/)
{
    void* ortLib = dlopen("libonnxruntime.so", RTLD_NOLOAD);
    if (ortLib != nullptr) {
        dlclose(ortLib);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Combined capture + VAD lifecycle — atomic start/stop to prevent races
// between route-change restarts and foreground transitions.
// ---------------------------------------------------------------------------

extern "C" bool vadEnsureInitialized(JNIEnv* env, jobject assetManager);
extern "C" const char* vadLastError();

// Logs the reason a start step failed. The combined entry is what production calls, so an opaque
// false here is a failure with no way to tell the asset, the ORT library and the model session
// apart — the sibling entry already reports its reason, and this one should not be less useful.
static void logStartFailure(const char* step, const char* reason) {
    if (reason != nullptr && reason[0] != '\0') {
        LOGE("Capture/VAD start failed at %s: %s", step, reason);
    } else {
        LOGE("Capture/VAD start failed at %s", step);
    }
}

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
        logStartFailure("vad init", vadLastError());
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(gCaptureMutex);

    // Clean up any previous instances
    delete gVadProcessor;
    gVadProcessor = nullptr;
    delete gCapture;
    gCapture = nullptr;

    auto* capture = new OboeAudioCapture();

    if (!capture->open()) {
        logStartFailure("oboe open", nullptr);
        delete capture;
        return JNI_FALSE;
    }

    if (!capture->start()) {
        logStartFailure("oboe start", nullptr);
        capture->close();
        delete capture;
        return JNI_FALSE;
    }

    gCapture = capture;
    gVadProcessor = new NativeVadProcessor(gCapture);

    if (!gVadProcessor->start()) {
        logStartFailure("vad processor thread", nullptr);
        delete gVadProcessor;
        gVadProcessor = nullptr;
        // Capture is still valid — caller will stop/close it
        return JNI_FALSE;
    }

    return JNI_TRUE;
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

// Frames the VAD has consumed from capture since start. Exposed so a test can assert captured audio
// reaches the VAD, which a liveness check alone cannot show while no speech event fires.
extern "C" JNIEXPORT jlong JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeGetFramesConsumed(
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
    return static_cast<jlong>(processor->getFramesConsumed());
}

// Aggregate detector diagnosis for capture debugging, as a long array so one call returns the frame
// state atomically. Order: rms, score, speechFrames, silentFrames, drainRemaining, then the three
// booleans as 0/1 (gatePassed, isSpeech, speechActive). Aggregates only — no audio is exported.
// Collection is off unless nativeSetVadDiagnosticsEnabled(true) was called, which release builds do
// not do.
extern "C" JNIEXPORT jlongArray JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeGetVadDiagnostics(
    JNIEnv* env,
    jclass /*clazz*/)
{
    NativeVadProcessor* processor = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCaptureMutex);
        processor = gVadProcessor;
    }
    jlong values[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    if (processor != nullptr) {
        NativeVadProcessor::VadDiagnostics d = processor->getDiagnostics();
        // The RMS and score are scaled to integers: a level and a probability do not need float
        // precision to answer "did the loopback reach the detector".
        values[0] = static_cast<jlong>(d.lastRms * 1000.0);
        values[1] = static_cast<jlong>(d.lastScore * 1000.0f);
        values[2] = d.speechFrames;
        values[3] = d.silentFrames;
        values[4] = d.drainRemaining;
        values[5] = d.lastGatePassed ? 1 : 0;
        values[6] = d.lastIsSpeech ? 1 : 0;
        values[7] = d.speechActive ? 1 : 0;
    }
    jlongArray out = env->NewLongArray(8);
    if (out != nullptr) {
        env->SetLongArrayRegion(out, 0, 8, values);
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeSetVadDiagnosticsEnabled(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jboolean enabled)
{
    NativeVadProcessor* processor = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCaptureMutex);
        processor = gVadProcessor;
    }
    if (processor != nullptr) {
        processor->setDiagnosticsEnabled(enabled == JNI_TRUE);
    }
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
