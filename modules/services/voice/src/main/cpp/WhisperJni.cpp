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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void whisperGgmlLog(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    __android_log_write(prio, "whisper.cpp", text);
}

// Vulkan backend performance tuning for Mali-G715 (Tensor G3 / Pixel 8).
// Whisper currently runs on CPU (no Vulkan support in whisper.cpp), but the
// ggml Vulkan backend is still initialized at library load time through the
// shared ggml build with llama.cpp. These env vars keep it configured
// consistently between the two JNI modules.
static void initVulkanEnv() {
    static bool done = false;
    if (done) return;
    setenv("GGML_VK_ALLOW_GRAPHICS_QUEUE", "1", 1);
    setenv("GGML_VK_FORCE_MAX_ALLOCATION_SIZE", "536870912", 1);
    setenv("GGML_VK_SUBALLOCATION_BLOCK_SIZE", "67108864", 1);
    done = true;
}

static std::mutex g_mutex;
static whisper_context* g_ctx = nullptr;
static std::string g_model_path;

static void whisperProgress(struct whisper_context*, struct whisper_state*, int progress, void*) {
    LOGI("whisper progress: %d%%", progress);
}

static bool ensureModel(const std::string& path) {
    if (g_ctx && g_model_path == path) return true;
    if (g_ctx) { whisper_free(g_ctx); g_ctx = nullptr; }
    auto t0 = std::chrono::steady_clock::now();
    auto params = whisper_context_default_params();
    g_ctx = whisper_init_from_file_with_params(path.c_str(), params);
    auto t1 = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("model loaded in %lldms, use_gpu=%d", (long long)ms, params.use_gpu);
    g_model_path = path;
    return g_ctx != nullptr;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_transcribe(
    JNIEnv* env, jclass, jstring j_model_path, jshortArray j_pcm_data, jint j_sample_rate
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ggml_log_set(whisperGgmlLog, nullptr);
    whisper_log_set(whisperGgmlLog, nullptr);
    initVulkanEnv();

    std::string modelPath = jstringToString(env, j_model_path);
    if (!ensureModel(modelPath)) return stringToJstring(env, "");

    jsize len = env->GetArrayLength(j_pcm_data);
    jshort* elements = env->GetShortArrayElements(j_pcm_data, nullptr);

    std::vector<float> pcmF32(len);
    for (jsize i = 0; i < len; i++) pcmF32[i] = elements[i] / 32768.0f;
    env->ReleaseShortArrayElements(j_pcm_data, elements, JNI_ABORT);

    // Use hardware_concurrency() capped to [2, 8] — encoder matmul saturates
    // at ~6-8 threads on big.LITTLE Arm cores; fewer threads waste throughput.
    int nThreads = static_cast<int>(std::thread::hardware_concurrency());
    if (nThreads < 2) nThreads = 2;
    if (nThreads > 8) nThreads = 8;

    auto wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    // Auto-detect spoken language and translate to English so downstream intent
    // parsing always sees English regardless of input language. Letting
    // whisper_full do the detection internally reuses its single encoder pass;
    // detecting separately (whisper_lang_auto_detect) would force a second full
    // encode of the same audio.
    wparams.language = "en"; // DIAGNOSTIC: was "auto"; bisecting the decode runaway
    wparams.translate = true;
    wparams.n_threads = nThreads;
    // DIAGNOSTIC: single_segment/no_timestamps removed to isolate the decode runaway.
    // Scale the audio context to the utterance instead of always encoding a
    // full 30s window. Encoder cost scales with audio_ctx, so a ~1s command no
    // longer pays for 30s of padding. mel hop is 160 samples and the encoder
    // conv halves the frame count, so audio_ctx ~= n_samples/320; add margin
    // and clamp to the model's full 1500.
    int audioCtx = static_cast<int>(pcmF32.size() / 320) + 16;
    if (audioCtx < 32) audioCtx = 32;
    if (audioCtx > 1500) audioCtx = 1500;
    wparams.audio_ctx = audioCtx;
    wparams.no_context = true;
    // DIAGNOSTIC: disable temperature fallback so the decode is a single greedy
    // pass (no 6-step retry ladder). Measures the whisper-small CPU floor.
    wparams.temperature_inc = 0.0f;
    wparams.progress_callback = whisperProgress;

    LOGI("whisper_full START: %d samples (%dms), audio_ctx=%d, lang=%s, translate=%d, temp_inc=%.2f",
         (int)pcmF32.size(), (int)(pcmF32.size() * 1000 / 16000), audioCtx,
         wparams.language ? wparams.language : "(null)", wparams.translate, wparams.temperature_inc);

    whisper_reset_timings(g_ctx);
    auto t0 = std::chrono::steady_clock::now();
    int decodeResult = whisper_full(g_ctx, wparams, pcmF32.data(), (int)pcmF32.size());
    auto t1 = std::chrono::steady_clock::now();
    auto inferMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("whisper_full: %lldms, %d threads, audio_ctx=%d, %d samples (%dms audio)",
         (long long)inferMs, nThreads, audioCtx, (int)pcmF32.size(), (int)(pcmF32.size() * 1000 / 16000));
    // DIAGNOSTIC: prints encode/decode/sample ms + token count to tag "whisper.cpp"
    whisper_print_timings(g_ctx);

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
    LOGI("whisper result (%d segments): \"%s\"", n, result.c_str());
    return stringToJstring(env, result);
}

} // extern "C"
