// LmJni.cpp
#include "LmJni.h"
#include "jni_bridge_common.h"
#include <llama.h>
#include <android/log.h>
#include <chrono>
#include <cstdlib>
#include <thread>
#include <vector>
#include <mutex>
#include <cstdint>

#define LOG_TAG "LmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Redirect llama.cpp's internal logging (weight errors, dimension mismatches,
// backend selection messages) to Android logcat for diagnostics.
static void llamaLogCallback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (level <= GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, "llama.cpp", text);
    } else if (level <= GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, "llama.cpp", text);
    } else {
        __android_log_write(ANDROID_LOG_INFO, "llama.cpp", text);
    }
}

static std::mutex g_mutex;

// Disable FP16 compute to avoid VK_ERROR_DEVICE_LOST on Mali-G715 (Pixel 8 / Tensor G3).
// Cooperative matrix is kept enabled — tested stable with llama.cpp b9174 on this device.
static void initVulkanEnv() {
    static bool done = false;
    if (done) return;
    setenv("GGML_VK_DISABLE_F16", "1", 1);
    done = true;
}

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_smpl = nullptr;
static std::string g_model_path;

static bool ensureModel(const std::string& path) {
    if (g_model && g_model_path == path) return true;
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    {
        auto t0 = std::chrono::steady_clock::now();
        auto modelParams = llama_model_default_params();
        // NB: must be >= n_layer+1 (=33) to offload all layers. b5079 has a bug:
        // n_gpu_layers=-1 sets i_gpu_start = n_layer - (-1) = 33, so all 32 layers
        // (indices 0..31) are < i_gpu_start and stay on CPU.
        modelParams.n_gpu_layers = 99;
        g_model = llama_model_load_from_file(path.c_str(), modelParams);
        auto t1 = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        int nLayers = llama_model_n_layer(g_model);
        LOGI("model loaded in %lldms, %d layers, n_gpu_layers=%d", (long long)ms, nLayers, modelParams.n_gpu_layers);
        if (!g_model) return false;
    }

    {
        auto t0 = std::chrono::steady_clock::now();
        auto ctxParams = llama_context_default_params();
        // 2048 matches SmolLM2's max_position_embeddings. The system prompt with
        // all intent aliases is ~800-1250 tokens; 512 is too small and causes
        // severe truncation which can produce garbled activations → NaN.
        ctxParams.n_ctx = 2048;
        // Offload KV cache to GPU along with model layers. When the KV cache is
        // CPU-backed but model layers are on Vulkan, the ggml graph scheduler
        // creates GPU views for CPU buffers (e.g. Vulkan0#cache_k_l0), the view
        // validation fails, and execution falls back to CPU entirely — causing
        // NaN crashes in SiLU from slow CPU inference.
        ctxParams.offload_kqv = true;
        // NB: flash_attn is disabled — on Mali-G715 with Vulkan it creates 66
        // graph splits vs. 2 without, causing GPU driver hangs on this device.
        // Cap threads to avoid big.LITTLE contention. 4 threads is optimal on mobile ARM.
        int nThreads = static_cast<int>(std::thread::hardware_concurrency());
        if (nThreads < 2) nThreads = 2;
        if (nThreads > 4) nThreads = 4;
        ctxParams.n_threads = nThreads;
        ctxParams.n_threads_batch = nThreads;
        g_ctx = llama_init_from_model(g_model, ctxParams);
        auto t1 = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        LOGI("context init in %lldms", (long long)ms);
        if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; return false; }
    }

    auto sparams = llama_sampler_chain_default_params();
    g_smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());

    g_model_path = path;
    return true;
}

static std::string run(const std::string& prompt) {
    if (!g_ctx || !g_model || !g_smpl) return {};

    // Clear the KV cache before each inference to prevent cache pollution from
    // previous runs. Without this, stale KV entries from the prior prompt cause
    // numerical instability (NaN) in the attention softmax computation.
    // b9174 replaces llama_kv_self_clear with the memory API.
    llama_memory_seq_rm(llama_get_memory(g_ctx), -1, -1, -1);

    const auto* vocab = llama_model_get_vocab(g_model);
    if (!vocab) return {};

    // Tokenize
    auto tTokenize = std::chrono::steady_clock::now();
    int nCtx = llama_n_ctx(g_ctx);
    std::vector<llama_token> tokens(nCtx);
    // parse_special=true: SmolLM2's chat template uses <|system|>, <|user|>,
    // <|assistant|> as single special tokens. Without parsing them, the tokenizer
    // splits them into individual characters, producing ~10x more tokens and
    // losing the role boundary structure the model was trained on.
    int nTokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, true);
    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (nTokens <= 0) return {};
    tokens.resize(nTokens);

    if (nTokens > nCtx - 16) nTokens = nCtx - 16;
    tokens.resize(nTokens);
    auto tTokenizeEnd = std::chrono::steady_clock::now();
    auto tokenizeMs = std::chrono::duration_cast<std::chrono::microseconds>(tTokenizeEnd - tTokenize).count();

    // Initial decode (prompt processing)
    auto tDecode = std::chrono::steady_clock::now();
    auto batch = llama_batch_get_one(tokens.data(), nTokens);
    if (llama_decode(g_ctx, batch) != 0) return {};
    auto tDecodeEnd = std::chrono::steady_clock::now();
    auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(tDecodeEnd - tDecode).count();

    LOGI("prompt: %d tokens, tokenize=%lldus, decode=%lldms",
         nTokens, (long long)tokenizeMs, (long long)decodeMs);

    // Generation loop
    auto tGen = std::chrono::steady_clock::now();
    std::string result;
    int genTokens = 0;
    for (int i = 0; i < 256; i++) {
        auto tSample = std::chrono::steady_clock::now();
        auto id = llama_sampler_sample(g_smpl, g_ctx, -1);
        auto tSampleEnd = std::chrono::steady_clock::now();
        auto sampleUs = std::chrono::duration_cast<std::chrono::microseconds>(tSampleEnd - tSample).count();
        if (id == llama_vocab_eos(vocab)) {
            LOGI("generated %d tokens (EOS at step %d), last sample=%lldus", genTokens, i, (long long)sampleUs);
            break;
        }

        tokens.push_back(id);
        auto nextBatch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, nextBatch) != 0) break;

        char buf[8];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);
        genTokens++;
    }
    auto tGenEnd = std::chrono::steady_clock::now();
    auto genMs = std::chrono::duration_cast<std::chrono::milliseconds>(tGenEnd - tGen).count();
    LOGI("generation: %d tokens in %lldms (%.1fms/tok)", genTokens, (long long)genMs,
         genTokens > 0 ? (double)genMs / genTokens : 0.0);
    return result;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_parseIntent(
    JNIEnv* env, jclass, jstring j_model_path, jstring j_prompt
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    initVulkanEnv();
    llama_log_set(llamaLogCallback, nullptr);
    auto modelPath = jstringToString(env, j_model_path);
    if (!ensureModel(modelPath)) return stringToJstring(env, "");
    auto prompt = jstringToString(env, j_prompt);
    auto result = run(prompt);
    return stringToJstring(env, result);
}

} // extern "C"
