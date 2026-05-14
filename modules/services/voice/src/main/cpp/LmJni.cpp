// LmJni.cpp
#include "LmJni.h"
#include "jni_bridge_common.h"
#include <llama.h>
#include <vector>
#include <mutex>
#include <cstdint>

static std::mutex g_mutex;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_smpl = nullptr;
static std::string g_model_path;

static bool ensureModel(const std::string& path) {
    if (g_model && g_model_path == path) return true;
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    auto modelParams = llama_model_default_params();
    g_model = llama_model_load_from_file(path.c_str(), modelParams);
    if (!g_model) return false;

    auto ctxParams = llama_context_default_params();
    ctxParams.n_ctx = 512;
    g_ctx = llama_init_from_model(g_model, ctxParams);
    if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; return false; }

    auto sparams = llama_sampler_chain_default_params();
    g_smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());

    g_model_path = path;
    return true;
}

static std::string run(const std::string& prompt) {
    if (!g_ctx || !g_model || !g_smpl) return {};

    const auto* vocab = llama_model_get_vocab(g_model);
    if (!vocab) return {};

    // Tokenize
    int nCtx = llama_n_ctx(g_ctx);
    std::vector<llama_token> tokens(nCtx);
    int nTokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, false);
    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, false);
    }
    if (nTokens <= 0) return {};
    tokens.resize(nTokens);

    if (nTokens > nCtx - 16) nTokens = nCtx - 16;
    tokens.resize(nTokens);

    // Initial decode
    auto batch = llama_batch_get_one(tokens.data(), nTokens);
    if (llama_decode(g_ctx, batch) != 0) return {};

    std::string result;
    for (int i = 0; i < 256; i++) {
        auto id = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (id == llama_vocab_eos(vocab)) break;

        tokens.push_back(id);
        auto nextBatch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, nextBatch) != 0) break;

        char buf[8];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
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
