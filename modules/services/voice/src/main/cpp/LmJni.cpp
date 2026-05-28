// LmJni.cpp
#include "LmJni.h"
#include <jni.h>
#include <string>
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
    if (level <= GGML_LOG_LEVEL_WARN) {
        __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "llama.cpp", text);
    }
}

// Vulkan backend performance tuning for Mali-G715 (Tensor G3 / Pixel 8).
// These env vars are read by ggml-vulkan during device init and control
// memory allocation strategy and queue selection.
static void initVulkanEnv() {
    static bool done = false;
    if (done) return;
    // Mali-G715 may not expose a dedicated compute queue. Force fallback to
    // the graphics queue to avoid VK_ERROR_INITIALIZATION_FAILED.
    setenv("GGML_VK_ALLOW_GRAPHICS_QUEUE", "1", 1);
    // Cap GPU memory allocations at 512 MB and suballocation blocks at 64 MB
    // to match Mali's heap limits and reduce fragmentation overhead.
    // NB: 256 MB was too tight — SmolLM2 Q4_K_M's model buffer is 256.35 MiB,
    // and inference adds ~250 MiB more (KV cache + compute buffers).
    setenv("GGML_VK_FORCE_MAX_ALLOCATION_SIZE", "536870912", 1);
    setenv("GGML_VK_SUBALLOCATION_BLOCK_SIZE", "67108864", 1);
    done = true;
}

static std::mutex g_mutex;

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_smpl = nullptr;
static std::string g_model_path;

// Number of prefix tokens pre-decoded into the KV cache. Zero means no
// prewarmed prefix — parseIntent will fail in that case.
static int g_prefix_token_count = 0;

static bool ensureModel(const std::string& path) {
    if (g_model && g_model_path == path) return true;
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    g_prefix_token_count = 0;

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
        if (!g_model) {
            LOGE("model load failed");
            return false;
        }
        int nLayers = llama_model_n_layer(g_model);
        LOGI("model loaded in %lldms, %d layers, n_gpu_layers=%d", (long long)ms, nLayers, modelParams.n_gpu_layers);
    }

    {
        auto t0 = std::chrono::steady_clock::now();
        auto ctxParams = llama_context_default_params();
        // 2048 matches SmolLM2's max_position_embeddings. The system prompt with
        // all intent aliases is ~500-600 tokens; 512 is too small and causes
        // severe truncation which can produce garbled activations → NaN.
        ctxParams.n_ctx = 2048;
        // Offload KV cache to GPU along with model layers.
        ctxParams.offload_kqv = true;
        // Flash attention disabled on Mali-G715: it fragments the GPU graph into
        // 66+ splits vs. 2 without, causing driver hangs and 4-10x decode overhead.
        ctxParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
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

static bool prewarmImpl(const std::string& prefix) {
    if (!g_ctx || !g_model) return false;

    // Clear any stale KV cache entries before decoding the prefix.
    llama_memory_seq_rm(llama_get_memory(g_ctx), 0, -1, -1);

    const auto* vocab = llama_model_get_vocab(g_model);
    if (!vocab) return false;

    int nCtx = llama_n_ctx(g_ctx);
    std::vector<llama_token> tokens(nCtx);
    int nTokens = llama_tokenize(vocab, prefix.c_str(), (int32_t)prefix.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, true);
    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(vocab, prefix.c_str(), (int32_t)prefix.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (nTokens <= 0) return false;
    tokens.resize(nTokens);

    auto decodeT0 = std::chrono::steady_clock::now();
    auto batch = llama_batch_get_one(tokens.data(), nTokens);
    if (llama_decode(g_ctx, batch) != 0) return false;
    auto decodeT1 = std::chrono::steady_clock::now();
    auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(decodeT1 - decodeT0).count();

    g_prefix_token_count = nTokens;
    LOGI("prewarm: decoded %d prefix tokens in %lldms", nTokens, (long long)decodeMs);
    return true;
}

// Decodes suffix tokens after the prewarmed prefix using auto-positioning
// (llama_batch_get_one), generates output, then removes only the suffix and
// generated tokens from the KV cache — keeping the prefix cached for reuse.
static std::string run(const std::string& suffix, int prefixLen) {
    if (!g_ctx || !g_model || !g_smpl) return {};

    const auto* vocab = llama_model_get_vocab(g_model);
    if (!vocab) return {};

    // Tokenize suffix
    auto tTokenize = std::chrono::steady_clock::now();
    int nCtx = llama_n_ctx(g_ctx);
    int maxSuffixTokens = nCtx - prefixLen - 16;
    std::vector<llama_token> tokens(nCtx);
    int nTokens = llama_tokenize(vocab, suffix.c_str(), (int32_t)suffix.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, true);
    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(vocab, suffix.c_str(), (int32_t)suffix.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (nTokens <= 0) return {};
    if (nTokens > maxSuffixTokens) nTokens = maxSuffixTokens;
    tokens.resize(nTokens);
    auto tTokenizeEnd = std::chrono::steady_clock::now();
    auto tokenizeUs = std::chrono::duration_cast<std::chrono::microseconds>(tTokenizeEnd - tTokenize).count();

    // llama_batch_get_one uses auto-positioning: after the prefix at positions
    // [0, prefixLen), it continues sequentially from position prefixLen.
    auto tDecode = std::chrono::steady_clock::now();
    auto batch = llama_batch_get_one(tokens.data(), nTokens);
    if (llama_decode(g_ctx, batch) != 0) return {};
    auto tDecodeEnd = std::chrono::steady_clock::now();
    auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(tDecodeEnd - tDecode).count();

    LOGI("suffix: %d tokens, tokenize=%lldus, decode=%lldms",
         nTokens, (long long)tokenizeUs, (long long)decodeMs);

    // Generation loop — same as before, using auto-positioned single-token batches.
    auto tGen = std::chrono::steady_clock::now();
    std::string result;
    int genTokens = 0;
    for (int i = 0; i < 256; i++) {
        auto id = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (id == llama_vocab_eos(vocab)) {
            LOGI("generated %d tokens (EOS at step %d)", genTokens, i);
            break;
        }

        auto genBatch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, genBatch) != 0) break;

        char buf[8];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);
        genTokens++;
    }
    auto tGenEnd = std::chrono::steady_clock::now();
    auto genMs = std::chrono::duration_cast<std::chrono::milliseconds>(tGenEnd - tGen).count();
    LOGI("generation: %d tokens in %lldms (%.1fms/tok)", genTokens, (long long)genMs,
         genTokens > 0 ? (double)genMs / genTokens : 0.0);

    // Remove suffix + generated tokens from KV cache, leaving the prefix intact
    // for the next utterance.
    llama_memory_seq_rm(llama_get_memory(g_ctx), 0, prefixLen, -1);

    return result;
}

static std::string jstringToString(JNIEnv* env, jstring str) {
    if (!str) return {};
    const char* chars = env->GetStringUTFChars(str, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(str, chars);
    return result;
}

static jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// Logger for ggml_abort assertion failures. The default handler writes to
// stderr which is discarded on Android. This forwards the message to logcat.
static void abortCallback(const char * message) {
    __android_log_write(ANDROID_LOG_FATAL, "ggml_abort", message);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_prewarmModel(
    JNIEnv* env, jclass, jstring j_model_path, jstring j_fixed_prefix
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ggml_log_set(llamaLogCallback, nullptr);
    llama_log_set(llamaLogCallback, nullptr);
    ggml_set_abort_callback(abortCallback);
    initVulkanEnv();

    auto modelPath = jstringToString(env, j_model_path);
    if (!ensureModel(modelPath)) {
        LOGE("prewarm: model load failed");
        return JNI_FALSE;
    }

    auto prefix = jstringToString(env, j_fixed_prefix);
    if (prefix.empty()) {
        LOGE("prewarm: empty prefix");
        return JNI_FALSE;
    }

    return prewarmImpl(prefix) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_parseIntent(
    JNIEnv* env, jclass, jstring j_model_path, jstring j_suffix
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ggml_log_set(llamaLogCallback, nullptr);
    llama_log_set(llamaLogCallback, nullptr);
    ggml_set_abort_callback(abortCallback);
    initVulkanEnv();

    auto modelPath = jstringToString(env, j_model_path);
    if (!ensureModel(modelPath)) return stringToJstring(env, "");

    if (g_prefix_token_count <= 0) {
        LOGE("parseIntent called without prewarmed prefix — ignoring");
        return stringToJstring(env, "");
    }

    auto suffix = jstringToString(env, j_suffix);
    auto result = run(suffix, g_prefix_token_count);
    return stringToJstring(env, result);
}

} // extern "C"
