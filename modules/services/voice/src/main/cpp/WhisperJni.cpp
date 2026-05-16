// WhisperJni.cpp
#include "WhisperJni.h"
#include "jni_bridge_common.h"
#include "whisper.h"
#include <android/log.h>
#include <chrono>
#include <cstdlib>
#include <mutex>
#include <thread>
#include <vector>

#define LOG_TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static whisper_context* g_ctx = nullptr;
static std::string g_model_path;

// Disable FP16 compute to avoid VK_ERROR_DEVICE_LOST on Mali-G715 (Pixel 8 / Tensor G3).
// Cooperative matrix is kept enabled — tested stable with llama.cpp b9174 on this device.
static void initVulkanEnv() {
    static bool done = false;
    if (done) return;
    setenv("GGML_VK_DISABLE_F16", "1", 1);
    done = true;
}

static bool ensureModel(const std::string& path) {
    if (g_ctx && g_model_path == path) return true;
    if (g_ctx) { whisper_free(g_ctx); g_ctx = nullptr; }
    auto t0 = std::chrono::steady_clock::now();
    auto params = whisper_context_default_params();
    g_ctx = whisper_init_from_file_with_params(path.c_str(), params);
    auto t1 = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("model loaded in %lldms", (long long)ms);
    g_model_path = path;
    return g_ctx != nullptr;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_transcribe(
    JNIEnv* env, jclass, jstring j_model_path, jshortArray j_pcm_data, jint j_sample_rate
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    initVulkanEnv();

    std::string modelPath = jstringToString(env, j_model_path);
    if (!ensureModel(modelPath)) return stringToJstring(env, "");

    jsize len = env->GetArrayLength(j_pcm_data);
    jshort* elements = env->GetShortArrayElements(j_pcm_data, nullptr);

    std::vector<float> pcmF32(len);
    for (jsize i = 0; i < len; i++) pcmF32[i] = elements[i] / 32768.0f;
    env->ReleaseShortArrayElements(j_pcm_data, elements, JNI_ABORT);

    // Cap threads at 4: on big.LITTLE ARM CPUs, OpenMP barriers synchronize
    // across all threads including slow efficiency cores, making more threads
    // actively worse for whisper's small model.
    int nThreads = static_cast<int>(std::thread::hardware_concurrency());
    if (nThreads < 2) nThreads = 2;
    if (nThreads > 4) nThreads = 4;

    // Pre-detect language so whisper knows the input language for acoustic
    // model selection. Translation will convert all output to English below.
    whisper_pcm_to_mel(g_ctx, pcmF32.data(), (int)pcmF32.size(), nThreads);
    int nLangs = whisper_lang_max_id();
    std::vector<float> langProbs(static_cast<size_t>(nLangs) + 1);
    int detectedLangId = whisper_lang_auto_detect(g_ctx, 0, nThreads, langProbs.data());

    auto wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    // Translate any language to English so downstream intent parsing always
    // sees English text regardless of the user's spoken language.
    wparams.translate = true;
    wparams.language = detectedLangId >= 0 ? whisper_lang_str(detectedLangId) : "en";
    wparams.n_threads = nThreads;
    // Use full audio context (no truncation) and no cross-utterance state.
    wparams.audio_ctx = 0;
    wparams.no_context = true;

    auto t0 = std::chrono::steady_clock::now();
    int decodeResult = whisper_full(g_ctx, wparams, pcmF32.data(), (int)pcmF32.size());
    auto t1 = std::chrono::steady_clock::now();
    auto inferMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("whisper_full: %lldms, %d threads, %d samples (%dms audio)",
         (long long)inferMs, nThreads, (int)pcmF32.size(), (int)(pcmF32.size() * 1000 / 16000));

    if (decodeResult != 0)
        return stringToJstring(env, "");

    std::string result;
    int n = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < n; i++) {
        const char* text = whisper_full_get_segment_text(g_ctx, i);
        if (text) {
            if (!result.empty()) result += " ";
            result += text;
        }
    }
    return stringToJstring(env, result);
}

} // extern "C"
