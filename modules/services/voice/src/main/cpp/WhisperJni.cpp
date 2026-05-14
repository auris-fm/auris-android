// WhisperJni.cpp
#include "WhisperJni.h"
#include "jni_bridge_common.h"
#include "whisper.h"
#include <mutex>
#include <vector>

static std::mutex g_mutex;
static whisper_context* g_ctx = nullptr;
static std::string g_model_path;

static bool ensureModel(const std::string& path) {
    if (g_ctx && g_model_path == path) return true;
    if (g_ctx) { whisper_free(g_ctx); g_ctx = nullptr; }
    auto params = whisper_context_default_params();
    g_ctx = whisper_init_from_file_with_params(path.c_str(), params);
    g_model_path = path;
    return g_ctx != nullptr;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_transcribe(
    JNIEnv* env, jclass, jstring j_model_path, jshortArray j_pcm_data, jint j_sample_rate
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    std::string modelPath = jstringToString(env, j_model_path);
    if (!ensureModel(modelPath)) return stringToJstring(env, "");

    jsize len = env->GetArrayLength(j_pcm_data);
    jshort* elements = env->GetShortArrayElements(j_pcm_data, nullptr);

    std::vector<float> pcmF32(len);
    for (jsize i = 0; i < len; i++) pcmF32[i] = elements[i] / 32768.0f;
    env->ReleaseShortArrayElements(j_pcm_data, elements, JNI_ABORT);

    auto wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = true;
    wparams.language = "auto";
    wparams.n_threads = 4;
    wparams.audio_ctx = 0;
    wparams.no_context = true;

    if (whisper_full(g_ctx, wparams, pcmF32.data(), (int)pcmF32.size()) != 0)
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
