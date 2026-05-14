// LmJni.cpp
#include "LmJni.h"
#include "jni_bridge_common.h"
#include <llama.h>
#include <common.h>
#include <vector>
#include <mutex>

static std::mutex g_mutex;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::string g_model_path;

static bool ensureModel(const std::string& path) {
    if (g_model && g_model_path == path) return true;
    if (g_model) { llama_free(g_ctx); llama_free_model(g_model); g_ctx = nullptr; g_model = nullptr; }

    auto modelParams = llama_model_default_params();
    g_model = llama_load_model_from_file(path.c_str(), modelParams);
    if (!g_model) return false;

    auto ctxParams = llama_context_default_params();
    ctxParams.n_ctx = 512;
    g_ctx = llama_new_context_with_model(g_model, ctxParams);
    if (!g_ctx) { llama_free_model(g_model); g_model = nullptr; return false; }

    g_model_path = path;
    return true;
}

static std::string run(const std::string& prompt) {
    if (!g_ctx || !g_model) return {};
    auto tokens = common_tokenize(g_model, prompt, true);
    if (tokens.empty()) return {};

    int nCtx = llama_n_ctx(g_ctx);
    if ((int)tokens.size() > nCtx - 16) tokens.resize(nCtx - 16);
    if (llama_eval(g_ctx, tokens.data(), (int)tokens.size(), 0) != 0) return {};

    std::string result;
    for (int i = 0; i < 256; i++) {
        auto id = llama_sample_token_simple(g_ctx, tokens.data(), (int)tokens.size() + i);
        if (id == llama_token_eos(g_model)) break;
        tokens.push_back(id);
        if (llama_eval(g_ctx, &id, 1, (int)tokens.size() - 1) != 0) break;

        char buf[8];
        int n = llama_token_to_piece(g_model, id, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);
    }
    return result;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_parseIntent(
    JNIEnv* env, jclass, jstring j_model_path, jstring j_prompt
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto modelPath = jstringToString(env, j_model_path);
    if (!ensureModel(modelPath)) return stringToJstring(env, "");
    auto prompt = jstringToString(env, j_prompt);
    auto result = run(prompt);
    return stringToJstring(env, result);
}

} // extern "C"
