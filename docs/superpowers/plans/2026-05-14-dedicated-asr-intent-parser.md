# Dedicated ASR + Tiny LLM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Gemma 4 E2B monolithic model (~2.58 GB, high WER) with a cascaded pipeline: Whisper base multilingual (ASR with translate) + SmolLM2 360M (intent parsing).

**Architecture:** Audio capture (Oboe), VAD (Silero), and speaker verification remain unchanged. The recognized audio clip is fed to whisper.cpp which transcribes/translates to English. The English text is passed to SmolLM2 360M (via llama.cpp) which outputs the same `VoicePlaybackIntent` JSON schema. Both models run on CPU via native JNI.

**Tech Stack:** whisper.cpp (ASR), llama.cpp (inference runtime for SmolLM2), Android NDK CMake, JNI, Hilt DI

---

### Task 1: Set up native build for whisper.cpp + llama.cpp

**Files:**
- Modify: `modules/services/voice/src/main/cpp/CMakeLists.txt`
- Create: `modules/services/voice/src/main/cpp/WhisperJni.h`
- Create: `modules/services/voice/src/main/cpp/WhisperJni.cpp`
- Create: `modules/services/voice/src/main/cpp/LmJni.h`
- Create: `modules/services/voice/src/main/cpp/LmJni.cpp`
- Create: `modules/services/voice/src/main/cpp/jni_bridge_common.h`

- [ ] **Step 1: Create `jni_bridge_common.h`** — shared declarations used by both JNI bridges

```cpp
// jni_bridge_common.h
#pragma once

#include <jni.h>
#include <string>
#include <vector>

// JNI helper: jstring to std::string
inline std::string jstringToString(JNIEnv* env, jstring str) {
    if (!str) return {};
    const char* chars = env->GetStringUTFChars(str, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(str, chars);
    return result;
}

// JNI helper: std::string to jstring
inline jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}
```

- [ ] **Step 2: Update `CMakeLists.txt`** to fetch and build whisper.cpp and llama.cpp, and add the JNI bridge targets

```cmake
cmake_minimum_required(VERSION 3.22)
project("pocketcasts-voice-capture" CXX)

# Oboe is provided via Prefab from the Gradle dependency
find_package(oboe REQUIRED CONFIG)

# --- whisper.cpp ---
include(FetchContent)
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
set(WHISPER_BUILD_STATIC_LIB ON CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whispercpp)

# --- llama.cpp ---
FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b5079
    GIT_SHALLOW TRUE
)
set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llamacpp)

# --- Target: pocketcasts_voice_capture ---
add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
    WhisperJni.cpp
    LmJni.cpp
)

target_include_directories(pocketcasts_voice_capture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${whispercpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_BINARY_DIR}/include  # generated common.h
)

target_link_libraries(pocketcasts_voice_capture
    oboe::oboe
    log
    android
    whisper_static
    llama_static
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)
target_compile_options(pocketcasts_voice_capture PRIVATE -Os -fvisibility=hidden)
```

- [ ] **Step 3: Create `WhisperJni.h`** — header declaring the JNI function

```cpp
// WhisperJni.h
#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperRecognizer_transcribeNative(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path,
    jshortArray pcm_data,
    jint sample_rate
);

#ifdef __cplusplus
}
#endif
```

- [ ] **Step 4: Create `WhisperJni.cpp`** — implementation wrapping whisper.cpp

```cpp
// WhisperJni.cpp
#include "WhisperJni.h"
#include "jni_bridge_common.h"
#include "whisper.h"
#include <mutex>
#include <vector>

static std::mutex g_whisper_mutex;
static whisper_context* g_context = nullptr;
static std::string g_model_path;

static bool ensure_model(const std::string& model_path) {
    if (g_context && g_model_path == model_path) return true;
    if (g_context) {
        whisper_free(g_context);
        g_context = nullptr;
    }
    struct whisper_context_params params = whisper_context_default_params();
    g_context = whisper_init_from_file_with_params(model_path.c_str(), params);
    g_model_path = model_path;
    return g_context != nullptr;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperRecognizer_transcribeNative(
    JNIEnv* env,
    jobject /* thiz */,
    jstring j_model_path,
    jshortArray j_pcm_data,
    jint j_sample_rate
) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);

    std::string model_path = jstringToString(env, j_model_path);
    if (!ensure_model(model_path)) {
        return stringToJstring(env, "");
    }

    jsize len = env->GetArrayLength(j_pcm_data);
    jshort* elements = env->GetShortArrayElements(j_pcm_data, nullptr);

    // Convert to float samples
    std::vector<float> pcm_f32(len);
    for (jsize i = 0; i < len; i++) {
        pcm_f32[i] = elements[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(j_pcm_data, elements, JNI_ABORT);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = true;
    wparams.language = "auto";
    wparams.n_threads = 4;
    wparams.audio_ctx = 0;
    wparams.speed_up = false;
    wparams.no_context = true;

    if (whisper_full(g_context, wparams, pcm_f32.data(), (int)pcm_f32.size()) != 0) {
        return stringToJstring(env, "");
    }

    std::string result;
    int n_segments = whisper_full_n_segments(g_context);
    for (int i = 0; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(g_context, i);
        if (text) {
            if (!result.empty()) result += " ";
            result += text;
        }
    }

    return stringToJstring(env, result);
}

} // extern "C"
```

- [ ] **Step 5: Create `LmJni.h`** — header declaring the JNI function

```cpp
// LmJni.h
#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_SmolLmIntentParser_parseIntentNative(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path,
    jstring prompt
);

#ifdef __cplusplus
}
#endif
```

- [ ] **Step 6: Create `LmJni.cpp`** — implementation wrapping llama.cpp

```cpp
// LmJni.cpp
#include "LmJni.h"
#include "jni_bridge_common.h"
#include "llama.h"
#include "common.h"  // llama.cpp's common.h for common_params, etc.
#include <mutex>
#include <string>
#include <vector>

static std::mutex g_lm_mutex;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::string g_model_path;

static bool ensure_model(const std::string& model_path) {
    if (g_model && g_model_path == model_path) return true;
    if (g_model) {
        llama_free(g_ctx);
        llama_free_model(g_model);
        g_ctx = nullptr;
        g_model = nullptr;
    }

    llama_model_params model_params = llama_model_default_params();
    g_model = llama_load_model_from_file(model_path.c_str(), model_params);
    if (!g_model) return false;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;  // small context window for intent parsing
    ctx_params.n_batch = 128;
    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        llama_free_model(g_model);
        g_model = nullptr;
        return false;
    }

    g_model_path = model_path;
    return true;
}

std::string run_prompt(const std::string& prompt_text) {
    if (!g_ctx || !g_model) return "";
    
    // Tokenize
    auto tokens = common_tokenize(g_model, prompt_text, true);
    if (tokens.empty()) return "";

    // Evaluate
    int n_ctx = llama_n_ctx(g_ctx);
    if ((int)tokens.size() > n_ctx - 16) {
        tokens.resize(n_ctx - 16);
    }

    if (llama_eval(g_ctx, tokens.data(), (int)tokens.size(), 0) != 0) {
        return "";
    }

    // Generate up to 256 tokens
    std::string result;
    const int max_tokens = 256;
    auto batch = llama_batch_get_one(tokens.data(), tokens.size());
    
    for (int i = 0; i < max_tokens; i++) {
        llama_token id = llama_sample_token(g_ctx, batch);
        if (id == llama_token_eos(g_model)) break;
        
        batch.token = id;
        batch.n_tokens = 1;
        if (llama_eval(g_ctx, &id, 1, tokens.size() + i) != 0) break;
        
        char buf[8];
        int n = llama_token_to_piece(g_model, id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }
    }
    return result;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_SmolLmIntentParser_parseIntentNative(
    JNIEnv* env,
    jobject /* thiz */,
    jstring j_model_path,
    jstring j_prompt
) {
    std::lock_guard<std::mutex> lock(g_lm_mutex);

    std::string model_path = jstringToString(env, j_model_path);
    if (!ensure_model(model_path)) {
        return stringToJstring(env, "");
    }

    std::string prompt = jstringToString(env, j_prompt);
    std::string result = run_prompt(prompt);
    return stringToJstring(env, result);
}

} // extern "C"
```

- [ ] **Step 7: Commit**

```bash
git add modules/services/voice/src/main/cpp/
git commit -m "feat: add whisper.cpp + llama.cpp native libraries and JNI bridges"
```

---

### Task 2: Create WhisperRecognizer Kotlin class

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizer.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizer.kt` (test)

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperRecognizerTest {
    @Test
    fun `returns empty string when model not ready`() = runTest {
        val recognizer = WhisperRecognizer(fakeNative = object : WhisperNativeBridge {
            override fun transcribe(modelPath: String, pcmData: ShortArray, sampleRate: Int): String {
                return ""
            }
        })
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(16000), 16000)))
        val result = recognizer.transcribe(clip)
        assertEquals("", result)
    }

    @Test
    fun `returns transcript from native bridge`() = runTest {
        val recognizer = WhisperRecognizer(fakeNative = object : WhisperNativeBridge {
            override fun transcribe(modelPath: String, pcmData: ShortArray, sampleRate: Int): String {
                return "play the next episode"
            }
        })
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(16000), 16000)))
        val result = recognizer.transcribe(clip)
        assertEquals("play the next episode", result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*WhisperRecognizerTest*"`

- [ ] **Step 3: Create `WhisperRecognizer.kt`**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

interface WhisperNativeBridge {
    fun transcribe(modelPath: String, pcmData: ShortArray, sampleRate: Int): String
}

object WhisperNative : WhisperNativeBridge {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }

    override external fun transcribe(
        modelPath: String,
        pcmData: ShortArray,
        sampleRate: Int,
    ): String
}

@Singleton
class WhisperRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modelDir = File(context.filesDir, "whisper-model")
    private val modelFile = File(modelDir, "ggml-base-multilingual.bin")

    private var nativeBridge: WhisperNativeBridge = WhisperNative

    // Visible for testing
    internal constructor(nativeBridge: WhisperNativeBridge) : this(
        context = TODO("not used in test constructor"),
    ) {
        this.nativeBridge = nativeBridge
    }

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    fun getModelPath(): String? = if (isModelReady()) modelFile.absolutePath else null

    suspend fun transcribe(clip: VoiceUtteranceClip): String = withContext(Dispatchers.IO) {
        val path = modelFile.absolutePath
        if (!modelFile.exists()) {
            Timber.w("Whisper model not ready")
            return@withContext ""
        }

        // Flatten all PCM frames into a single ShortArray
        val totalSamples = clip.frames.sumOf { it.samples.size }
        val allSamples = ShortArray(totalSamples)
        var offset = 0
        for (frame in clip.frames) {
            frame.samples.copyInto(allSamples, offset)
            offset += frame.samples.size
        }

        try {
            val text = nativeBridge.transcribe(path, allSamples, clip.sampleRateHz)
            Timber.i("Whisper: '%s'", text)
            text.trim()
        } catch (e: Exception) {
            Timber.e(e, "Whisper transcription failed")
            ""
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*WhisperRecognizerTest*"`

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizer.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizerTest.kt
git commit -m "feat: add WhisperRecognizer for ASR via whisper.cpp"
```

---

### Task 3: Create SmolLmIntentParser Kotlin class

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParser.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParserTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmolLmIntentParserTest {

    private val fakeBridge = object : LmNativeBridge {
        override fun parseIntent(modelPath: String, prompt: String): String {
            return if (prompt.contains("next")) {
                """{"intent": "next_episode"}"""
            } else if (prompt.contains("pause")) {
                """{"intent": "pause"}"""
            } else if (prompt.contains("30 seconds")) {
                """{"intent": "seek_relative", "delta_seconds": 30}"""
            } else {
                """{"intent": "none"}"""
            }
        }
    }

    private val context = VoiceRecognitionContext(
        playbackContext = PlaybackContext.Inactive,
        audioRoute = AudioRoute.Headset,
    )

    @Test
    fun `parses next_episode intent`() = runTest {
        val parser = SmolLmIntentParser(fakeBridge)
        val result = parser.parseIntent("go to the next episode", context)
        assertEquals(VoicePlaybackIntent.NextEpisode, result)
    }

    @Test
    fun `parses pause intent`() = runTest {
        val parser = SmolLmIntentParser(fakeBridge)
        val result = parser.parseIntent("pause the podcast", context)
        assertEquals(VoicePlaybackIntent.Pause, result)
    }

    @Test
    fun `parses seek_relative intent`() = runTest {
        val parser = SmolLmIntentParser(fakeBridge)
        val result = parser.parseIntent("skip forward 30 seconds", context)
        assertEquals(VoicePlaybackIntent.SeekRelative(30000), result)
    }

    @Test
    fun `returns null for none intent`() = runTest {
        val parser = SmolLmIntentParser(fakeBridge)
        val result = parser.parseIntent("what is the weather like", context)
        assertNull(result)
    }

    @Test
    fun `returns null for invalid JSON`() = runTest {
        val badBridge = object : LmNativeBridge {
            override fun parseIntent(modelPath: String, prompt: String): String {
                return "not json at all"
            }
        }
        val parser = SmolLmIntentParser(badBridge)
        val result = parser.parseIntent("something", context)
        assertNull(result)
    }

    @Test
    fun `returns null for empty transcript`() = runTest {
        val parser = SmolLmIntentParser(fakeBridge)
        val result = parser.parseIntent("", context)
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SmolLmIntentParserTest*"`

- [ ] **Step 3: Create `SmolLmIntentParser.kt`**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

interface LmNativeBridge {
    fun parseIntent(modelPath: String, prompt: String): String
}

object LmNative : LmNativeBridge {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }

    override external fun parseIntent(
        modelPath: String,
        prompt: String,
    ): String
}

@Singleton
class SmolLmIntentParser @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modelDir = File(context.filesDir, "smol-lm-model")
    private val modelFile = File(modelDir, "smolLM2-360M-instruct-Q4_K_M.gguf")

    private var nativeBridge: LmNativeBridge = LmNative

    // Visible for testing
    internal constructor(nativeBridge: LmNativeBridge) : this(
        context = TODO("not used in test constructor"),
    ) {
        this.nativeBridge = nativeBridge
    }

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    fun getModelPath(): String? = if (isModelReady()) modelFile.absolutePath else null

    suspend fun parseIntent(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) {
            return@withContext null
        }

        val path = modelFile.absolutePath
        if (!modelFile.exists()) {
            Timber.w("SmolLM model not ready")
            return@withContext null
        }

        val prompt = buildPrompt(transcript, context)
        try {
            val output = nativeBridge.parseIntent(path, prompt)
            Timber.i("SmolLM: '%s'", output)
            parseIntentJson(output)
        } catch (e: Exception) {
            Timber.e(e, "SmolLM intent parsing failed")
            null
        }
    }

    private fun buildPrompt(transcript: String, ctx: VoiceRecognitionContext): String {
        val playbackInfo = when (val pc = ctx.playbackContext) {
            is PlaybackContext.Active -> {
                "Current playback state: ${if (pc.isPlaying) "playing" else "paused"}"
            }
            PlaybackContext.Inactive -> "Current playback state: inactive"
        }

        return """<|system|>
You are a voice command processor for a podcast player. Given a transcript of the user's speech, respond with ONLY a JSON object representing the closest matching intent. Available intents:

{"intent": "pause"}
{"intent": "resume"}
{"intent": "seek_relative", "delta_seconds": <positive integer>}
{"intent": "seek_absolute", "position_seconds": <positive integer>}
{"intent": "next_chapter"}
{"intent": "previous_chapter"}
{"intent": "chapter_by_index", "index": <non-negative integer>}
{"intent": "chapter_by_title", "query": "<chapter name>"}
{"intent": "next_episode"}
{"intent": "set_speed", "speed": <0.5 to 5.0>}
{"intent": "adjust_speed", "delta": <signed increment>}
{"intent": "set_volume", "volume": <0 to 100>}
{"intent": "adjust_volume", "delta": <signed increment>}
{"intent": "sleep_timer", "minutes": <positive integer; 0 to cancel>}
{"intent": "set_trim", "mode": "off"|"low"|"medium"|"high"}
{"intent": "set_volume_boost", "enabled": true|false}
{"intent": "add_bookmark", "title": "<bookmark label>"}

Common aliases:
"play" → {"intent": "resume"} | "stop" → {"intent": "pause"}
"next" → {"intent": "next_chapter"} | "previous" → {"intent": "previous_chapter"}
"faster" / "speed up" → {"intent": "adjust_speed", "delta": 0.5}
"slower" / "slow down" → {"intent": "adjust_speed", "delta": -0.5}
"forward X" / "skip X" → {"intent": "seek_relative", "delta_seconds": X}
"go back X" → {"intent": "seek_relative", "delta_seconds": -X}
"turn off" → {"intent": "sleep_timer", "minutes": 0}
"volume up" → {"intent": "adjust_volume", "delta": 10}
"volume down" → {"intent": "adjust_volume", "delta": -10}
"set volume X" → {"intent": "set_volume", "volume": X}
"louder" → {"intent": "adjust_volume", "delta": 10}
"quieter" → {"intent": "adjust_volume", "delta": -10}
"trim silence" / "silence trimming" → {"intent": "set_trim", "mode": "medium"}
"no trim" → {"intent": "set_trim", "mode": "off"}
"boost" / "turn on boost" → {"intent": "set_volume_boost", "enabled": true}
"no boost" → {"intent": "set_volume_boost", "enabled": false}
"bookmark this" / "save this" → {"intent": "add_bookmark", "title": "Voice bookmark"}
"set speed X" → {"intent": "set_speed", "speed": X}

$playbackInfo
Audio route: ${ctx.audioRoute}

If the speech is not a playback command, respond with {"intent": "none"}.
<|user|>
$transcript
<|assistant|>
""".trimIndent()
    }

    internal fun parseIntentJson(output: String): VoicePlaybackIntent? {
        if (output.isBlank()) return null

        return try {
            val trimmed = output.trim()
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) return null

            val json = JSONObject(trimmed.substring(jsonStart, jsonEnd + 1))
            val intent = json.optString("intent", "")

            if (intent == "none") return null

            @Suppress("NONEXHAUSTIVE_WHEN")
            when (intent) {
                "pause" -> VoicePlaybackIntent.Pause

                "resume" -> VoicePlaybackIntent.Resume

                "seek_relative" -> {
                    val seconds = json.optDouble("delta_seconds", 30.0)
                    VoicePlaybackIntent.SeekRelative((seconds * 1000).toInt())
                }

                "seek_absolute" -> {
                    val seconds = json.optDouble("position_seconds", 0.0)
                    VoicePlaybackIntent.SeekAbsolute((seconds * 1000).toInt())
                }

                "next_chapter" -> VoicePlaybackIntent.NextChapter

                "previous_chapter" -> VoicePlaybackIntent.PreviousChapter

                "next_episode" -> VoicePlaybackIntent.NextEpisode

                "chapter_by_index" -> {
                    val index = json.optInt("index", -1)
                    if (index < 0) null else VoicePlaybackIntent.ChapterByIndex(index)
                }

                "chapter_by_title" -> {
                    val query = json.optString("query", "")
                    if (query.isBlank()) null else VoicePlaybackIntent.ChapterByTitle(query)
                }

                "set_speed" -> {
                    val speed = json.optDouble("speed", -1.0)
                    if (speed in 0.5..5.0) VoicePlaybackIntent.SetSpeed(speed) else null
                }

                "adjust_speed" -> {
                    val delta = json.optDouble("delta", 0.0)
                    if (delta != 0.0) VoicePlaybackIntent.AdjustSpeed(delta) else null
                }

                "set_volume" -> {
                    val volume = json.optInt("volume", -1)
                    if (volume in 0..100) VoicePlaybackIntent.SetVolume(volume) else null
                }

                "adjust_volume" -> {
                    val delta = json.optInt("delta", 0)
                    if (delta != 0) VoicePlaybackIntent.AdjustVolume(delta) else null
                }

                "sleep_timer" -> {
                    val minutes = json.optInt("minutes", -1)
                    if (minutes < 0) null else VoicePlaybackIntent.SleepTimer(minutes)
                }

                "set_trim" -> {
                    val mode = json.optString("mode", "")
                    if (mode in listOf("off", "low", "medium", "high")) VoicePlaybackIntent.SetTrimMode(mode) else null
                }

                "set_volume_boost" -> {
                    VoicePlaybackIntent.SetVolumeBoost(json.optBoolean("enabled", false))
                }

                "add_bookmark" -> {
                    val title = json.optString("title", "")
                    if (title.isNotBlank()) VoicePlaybackIntent.AddBookmark(title) else null
                }

                else -> {
                    Timber.w("SmolLM: unknown intent '%s'", intent)
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "SmolLM: failed to parse intent JSON")
            null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SmolLmIntentParserTest*"`

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParser.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParserTest.kt
git commit -m "feat: add SmolLmIntentParser for intent extraction via llama.cpp"
```

---

### Task 4: Create Orchestrating VoiceRecognizer

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/CascadedVoiceRecognizer.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/CascadedVoiceRecognizerTest.kt`

This class implements the existing `VoiceRecognizer` interface and wires `WhisperRecognizer` + `SmolLmIntentParser` together.

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CascadedVoiceRecognizerTest {

    @Test
    fun `recognize returns parsed intent on successful ASR and parsing`() = runTest {
        val whisper = FakeWhisperRecognizer("pause the podcast")
        val parser = FakeIntentParser("pause")
        val recognizer = CascadedVoiceRecognizer(whisper, parser)

        val result = recognizer.recognize(mockClip(), mockContext())
        assertEquals(VoicePlaybackIntent.Pause, result)
    }

    @Test
    fun `recognize returns null when whisper returns empty`() = runTest {
        val whisper = FakeWhisperRecognizer("")
        val parser = FakeIntentParser("none")
        val recognizer = CascadedVoiceRecognizer(whisper, parser)

        val result = recognizer.recognize(mockClip(), mockContext())
        assertNull(result)
    }

    @Test
    fun `recognize returns null when intent parser returns null`() = runTest {
        val whisper = FakeWhisperRecognizer("what is the weather")
        val parser = FakeIntentParser("none")
        val recognizer = CascadedVoiceRecognizer(whisper, parser)

        val result = recognizer.recognize(mockClip(), mockContext())
        assertNull(result)
    }

    @Test
    fun `ensureReady succeeds when models are ready`() = runTest {
        val whisper = FakeWhisperRecognizer("test")
        val parser = FakeIntentParser("none")
        val recognizer = CascadedVoiceRecognizer(whisper, parser)
        // just check it doesn't throw
        recognizer.ensureReady()
    }

    private fun mockClip() = VoiceUtteranceClip.fromFrames(
        listOf(au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame(ShortArray(1600), 16000))
    )

    private fun mockContext() = VoiceRecognitionContext(
        playbackContext = PlaybackContext.Inactive,
        audioRoute = AudioRoute.Headset,
    )
}

class FakeWhisperRecognizer(private val transcript: String) : WhisperRecognizer {
    constructor() : this("")
    override fun isModelReady(): Boolean = true
    override fun getModelPath(): String? = "/fake/path"
    override suspend fun transcribe(clip: VoiceUtteranceClip): String = transcript
}

class FakeIntentParser(private val intentName: String) : SmolLmIntentParser {
    constructor() : this("none")
    override fun isModelReady(): Boolean = true
    override fun getModelPath(): String? = "/fake/path"
    override suspend fun parseIntent(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? {
        return when (intentName) {
            "pause" -> VoicePlaybackIntent.Pause
            "resume" -> VoicePlaybackIntent.Resume
            "none" -> null
            else -> null
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*CascadedVoiceRecognizerTest*"`

- [ ] **Step 3: Create the implementation**

Inherit the tests mean `WhisperRecognizer` and `SmolLmIntentParser` need to be open or have an interface. Since the current plan has `WhisperRecognizer` as a concrete class, we should extract interfaces or make the key methods `open`. Actually, looking at the test code above, the test fakes are designed to extend those classes. Let's make the relevant classes open, or extract an interface.

Better approach: Extract a common interface for both recognizers. But the existing `VoiceRecognizer` interface is what the DI provides. `CascadedVoiceRecognizer` implements `VoiceRecognizer` and takes `WhisperRecognizer` and `SmolLmIntentParser` as constructor params.

Let me simplify: Make `WhisperRecognizer` and `SmolLmIntentParser` have open methods. Actually, let me look at how the test fakes are designed...

The tests above have `FakeWhisperRecognizer` extending `WhisperRecognizer` and `FakeIntentParser` extending `SmolLmIntentParser`. They call superclass constructors. This won't compile as-is because the constructors have `@ApplicationContext context: Context` param. 

Let me use a different approach: use an interface for `WhisperRecognizer` and `SmolLmIntentParser`, or make test fakes inline.

Actually, the cleanest approach for testing is to make `CascadedVoiceRecognizer` accept interfaces. Let me define:

```kotlin
class CascadedVoiceRecognizer @Inject constructor(
    private val whisperRecognizer: WhisperRecognizer,
    private val intentParser: SmolLmIntentParser,
) : VoiceRecognizer {
    ...
}
```

And in tests, use real instances with mocked native bridges that return known values. The `WhisperRecognizer` and `SmolLmIntentParser` already inject their native bridges via constructors for testing.

Actually, the simplest approach: `CascadedVoiceRecognizer` takes the two concrete types as constructor parameters. The tests create real `WhisperRecognizer` and `SmolLmIntentParser` instances with fake native bridges, then pass them to `CascadedVoiceRecognizer`.

Let me rewrite the test to use that approach.

```kotlin
@Test
fun `recognize returns parsed intent on successful ASR and parsing`() = runTest {
    val whisper = WhisperRecognizer(object : WhisperNativeBridge {
        override fun transcribe(modelPath: String, pcmData: ShortArray, sampleRate: Int) = "pause the podcast"
    })
    val parser = SmolLmIntentParser(object : LmNativeBridge {
        override fun parseIntent(modelPath: String, prompt: String) = """{"intent": "pause"}"""
    })
    val recognizer = CascadedVoiceRecognizer(whisper, parser)
    val result = recognizer.recognize(mockClip(), mockContext())
    assertEquals(VoicePlaybackIntent.Pause, result)
}
```

This is cleaner. Now the `CascadedVoiceRecognizer`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class CascadedVoiceRecognizer @Inject constructor(
    private val whisperRecognizer: WhisperRecognizer,
    private val intentParser: SmolLmIntentParser,
) : VoiceRecognizer {

    override suspend fun ensureReady(): Result<Unit> {
        return if (whisperRecognizer.isModelReady() && intentParser.isModelReady()) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Models not ready"))
        }
    }

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? {
        val transcript = whisperRecognizer.transcribe(clip)
        if (transcript.isBlank()) {
            Timber.w("Whisper: empty transcript")
            return null
        }
        return intentParser.parseIntent(transcript, context)
    }
}
```

OK let me also think about the `WhisperRecognizer` test constructor issue. The class has `@Inject constructor(@ApplicationContext private val context: Context)`. For testing, we add an `internal constructor(nativeBridge: WhisperNativeBridge)` that doesn't need context. The `TODO()` for context is fine since the test constructor won't use it.

Actually wait, looking more carefully, the `WhisperRecognizer` won't use `context` until `transcribe()` is called and it needs to check `modelFile.exists()`. In tests with fake native bridges, we never call the real `transcribe`... wait no, we do. The `transcribe()` method checks `modelFile.exists()` first. But in the test, we bypass that by using the test constructor.

Wait, actually, in the test I wrote for `CascadedVoiceRecognizer`, I create `WhisperRecognizer(fakeNative)` which uses the test constructor. But then `transcribe()` is called, which accesses `modelFile`. The test constructor sets `context` to... nothing (TODO). This is a problem.

Let me rethink. Since the `WhisperRecognizer` is concrete and we need it to be testable, the best approach is to have the test pass in a fake native bridge AND have the model ready check be overridable. 

Actually, the simplest: just have the test check `modelFile.exists()` return true. Since we can't override the check easily, let me restructure `WhisperRecognizer` to accept model path as a parameter or make `isModelReady()` virtual.

Alternatively, I can make `CascadedVoiceRecognizer` direct enough that I don't need to test it separately. The `VoiceRecognizer` interface is just wiring, and component testing for `WhisperRecognizer` + `SmolLmIntentParser` individually covers the logic. Let me keep `CascadedVoiceRecognizer` simple and just test the two subcomponents individually.

Let me simplify this task: `CascadedVoiceRecognizer` is thin wiring. The `ensureReady()` check and `recognize()` orchestration are already covered by the individual component tests. I'll skip the `CascadedVoiceRecognizerTest` file and just create the orchestrator.

Actually, let me keep the test but make it work:

```kotlin
// Test constructor doesn't need context - for testing only
internal constructor(nativeBridge: WhisperNativeBridge) : this(
    context = TODO("not used in test constructor"),
)
```

But `transcribe()` uses `modelFile.exists()`. In the test, the fake bridge returns a value unconditionally, but `transcribe()` checks `modelFile.exists()` first and returns "" if the file doesn't exist. So the test would fail because the file doesn't exist.

Hmm, I need to either:
1. Make `isModelReady()` open/protected so tests can override it
2. Make the model path configurable in tests
3. Have the transcribe function check model readiness differently

Actually, let me rethink this. For the `CascadedVoiceRecognizer`, I can have it check `isModelReady()` separately before calling `transcribe()`. But `WhisperRecognizer.transcribe()` also checks internally.

The simplest fix: Make `WhisperRecognizer` and `SmolLmIntentParser` accept the model directory path via constructor, defaulting to the real `context.filesDir`, and allow injection of the native bridge. Then in tests, we use the test constructor which sets the native bridge, and the model file check happens against a non-existent file.

Wait, but then the model readiness check and the transcoding check are the same. Let me just not check model file existence inside `transcribe` and `parseIntent` since the outer `CascadedVoiceRecognizer.ensureReady()` and the service layer are responsible for that. The internal methods can trust that if they're called, the model is ready.

But the current design has `WhisperRecognizer.transcribe()` check `modelFile.exists()`. For a clean design:

Actually, let me just make it so the test constructor doesn't do the model file check. The simplest way:

```kotlin
suspend fun transcribe(clip: VoiceUtteranceClip): String = withContext(Dispatchers.IO) {
    if (!isModelReady()) {
        Timber.w("Whisper model not ready")
        return@withContext ""
    }
    ...
}
```

For tests, the `isModelReady()` returns false since the file doesn't exist. So the test won't work this way.

OK, let me just design it better. I'll make the model path injectable:

```kotlin
class WhisperRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @VisibleForTesting
    internal var modelFile: File = File(context.filesDir, "whisper-model/ggml-base-multilingual.bin")
    internal var nativeBridge: WhisperNativeBridge = WhisperNative
    ...
}
```

Then in tests:
```kotlin
val recognizer = WhisperRecognizer(fakeContext).apply {
    modelFile = File("/fake/path/whisper")
    nativeBridge = fakeBridge
}
```

But creating a fake Context for unit tests is heavy.

You know what, I'm overcomplicating this. Let me just make `CascadedVoiceRecognizer` simple and test the components individually. The task can be just:

1. Create `CascadedVoiceRecognizer`
2. Create a minimal smoke test

The real testing detail is in Tasks 2 and 3. Let me write the plan accordingly.

OK let me just write this cleanly now.<｜end▁of▁thinking｜>

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="Write">
<｜｜DSML｜｜parameter name="content" string="true"># Dedicated ASR + Tiny LLM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Gemma 4 E2B monolithic model (~2.58 GB, high WER) with a cascaded pipeline: Whisper base multilingual (ASR with translate) + SmolLM2 360M (intent parsing).

**Architecture:** Audio capture (Oboe), VAD (Silero), and speaker verification remain unchanged. The recognized audio clip is fed to whisper.cpp which transcribes/translates to English. The English text is passed to SmolLM2 360M (via llama.cpp) which outputs the same `VoicePlaybackIntent` JSON schema. Both models run on CPU via native JNI.

**Tech Stack:** whisper.cpp (ASR), llama.cpp (inference runtime for SmolLM2), Android NDK CMake, JNI, Hilt DI

---

### Task 1: Set up native build for whisper.cpp + llama.cpp

**Files:**
- Modify: `modules/services/voice/src/main/cpp/CMakeLists.txt`
- Create: `modules/services/voice/src/main/cpp/WhisperJni.h`
- Create: `modules/services/voice/src/main/cpp/WhisperJni.cpp`
- Create: `modules/services/voice/src/main/cpp/LmJni.h`
- Create: `modules/services/voice/src/main/cpp/LmJni.cpp`
- Create: `modules/services/voice/src/main/cpp/jni_bridge_common.h`

- [ ] **Step 1: Create `jni_bridge_common.h`** — shared JNI helper utilities

```cpp
// jni_bridge_common.h
#pragma once

#include <jni.h>
#include <string>

inline std::string jstringToString(JNIEnv* env, jstring str) {
    if (!str) return {};
    const char* chars = env->GetStringUTFChars(str, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(str, chars);
    return result;
}

inline jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}
```

- [ ] **Step 2: Create `WhisperJni.h`** — JNI function declaration (static native, matching `WhisperNative` object)

```cpp
// WhisperJni.h
#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_transcribe(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jshortArray pcm_data,
    jint sample_rate
);

} // extern "C"
```

- [ ] **Step 3: Create `WhisperJni.cpp`** — whisper.cpp transcription, singleton context cached for process lifetime, thread-safe via mutex

```cpp
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
```

- [ ] **Step 4: Create `LmJni.h`** — JNI function declaration (static native, matching `LmNative` object)

```cpp
// LmJni.h
#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_parseIntent(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jstring prompt
);

} // extern "C"
```

- [ ] **Step 5: Create `LmJni.cpp`** — llama.cpp inference for intent parsing

```cpp
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
```

- [ ] **Step 6: Update `CMakeLists.txt`** — add whisper.cpp and llama.cpp as fetched dependencies, link them, add the new JNI sources

```cmake
cmake_minimum_required(VERSION 3.22)
project("pocketcasts-voice-capture" CXX)

find_package(oboe REQUIRED CONFIG)

include(FetchContent)

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
set(WHISPER_BUILD_STATIC_LIB ON CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whispercpp)

FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b5079
    GIT_SHALLOW TRUE
)
set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llamacpp)

add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
    WhisperJni.cpp
    LmJni.cpp
)

target_include_directories(pocketcasts_voice_capture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${whispercpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_BINARY_DIR}/ggml/include
)

target_link_libraries(pocketcasts_voice_capture
    oboe::oboe
    log
    android
    whisper_static
    llama_static
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)
target_compile_options(pocketcasts_voice_capture PRIVATE -Os -fvisibility=hidden)
```

- [ ] **Step 7: Commit**

```bash
git add modules/services/voice/src/main/cpp/
git commit -m "feat: add whisper.cpp + llama.cpp native libraries and JNI bridges"
```

---

### Task 2: Create WhisperRecognizer Kotlin class

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizer.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizerTest.kt`

`WhisperRecognizer` wraps the native whisper.cpp JNI and accepts a `VoiceUtteranceClip` returning a transcribed English string.

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperRecognizerTest {

    @Test
    fun `returns transcript when native transcribes`() = runTest {
        val recognizer = WhisperRecognizer(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _, _ -> "play the next episode" }
        }
        val clip = VoiceUtteranceClip.fromFrames(
            listOf(PcmAudioFrame(ShortArray(16000), 16000))
        )
        assertEquals("play the next episode", recognizer.transcribe(clip))
    }

    @Test
    fun `returns empty string on native returning empty`() = runTest {
        val recognizer = WhisperRecognizer(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _, _ -> "" }
        }
        val clip = VoiceUtteranceClip.fromFrames(
            listOf(PcmAudioFrame(ShortArray(16000), 16000))
        )
        assertEquals("", recognizer.transcribe(clip))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*WhisperRecognizerTest*"`

- [ ] **Step 3: Create `WhisperRecognizer.kt`**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object WhisperNative {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }
    external fun transcribe(
        modelPath: String,
        pcmData: ShortArray,
        sampleRate: Int,
    ): String
}

@Singleton
open class WhisperRecognizer @Inject constructor(
    private val modelFile: File,
) {
    // Override in tests to avoid calling real native code
    @VisibleForTesting
    internal var nativeImpl: ((modelPath: String, pcmData: ShortArray, sampleRate: Int) -> String)? = null

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    suspend fun transcribe(clip: VoiceUtteranceClip): String = withContext(Dispatchers.IO) {
        val totalSamples = clip.frames.sumOf { it.samples.size }
        val allSamples = ShortArray(totalSamples)
        var offset = 0
        for (frame in clip.frames) {
            frame.samples.copyInto(allSamples, offset)
            offset += frame.samples.size
        }
        try {
            val text = nativeImpl?.invoke(modelFile.absolutePath, allSamples, clip.sampleRateHz)
                ?: WhisperNative.transcribe(modelFile.absolutePath, allSamples, clip.sampleRateHz)
            Timber.i("Whisper: '%s'", text)
            text.trim()
        } catch (e: Exception) {
            Timber.e(e, "Whisper transcription failed")
            ""
        }
    }
}
```

(Add `import androidx.annotation.VisibleForTesting` to the imports.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*WhisperRecognizerTest*"`

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizer.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/asr/WhisperRecognizerTest.kt
git commit -m "feat: add WhisperRecognizer for ASR via whisper.cpp"
```

---

### Task 3: Create SmolLmIntentParser Kotlin class

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParser.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParserTest.kt`

`SmolLmIntentParser` takes an English transcript and `VoiceRecognitionContext`, builds a prompt for SmolLM2, calls the native bridge, and parses the JSON output into `VoicePlaybackIntent`.

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmolLmIntentParserTest {

    private val ctx = VoiceRecognitionContext(
        playbackContext = PlaybackContext.Inactive,
        audioRoute = AudioRoute.Headset,
    )

    @Test
    fun `parses next_episode intent`() = runTest {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, prompt ->
                if (prompt.contains("next episode")) """{"intent": "next_episode"}""" else """{"intent": "none"}"""
            }
        }
        assertEquals(VoicePlaybackIntent.NextEpisode, parser.parseIntent("go to the next episode", ctx))
    }

    @Test
    fun `parses pause intent`() = runTest {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, prompt ->
                if (prompt.contains("pause")) """{"intent": "pause"}""" else """{"intent": "none"}"""
            }
        }
        assertEquals(VoicePlaybackIntent.Pause, parser.parseIntent("pause the podcast", ctx))
    }

    @Test
    fun `parses seek_relative intent`() = runTest {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, prompt ->
                if (prompt.contains("30 seconds")) """{"intent": "seek_relative", "delta_seconds": 30}""" else """{"intent": "none"}"""
            }
        }
        assertEquals(VoicePlaybackIntent.SeekRelative(30000), parser.parseIntent("skip forward 30 seconds", ctx))
    }

    @Test
    fun `returns null for none intent`() = runTest {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _ -> """{"intent": "none"}""" }
        }
        assertNull(parser.parseIntent("what is the weather", ctx))
    }

    @Test
    fun `returns null for invalid JSON`() = runTest {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _ -> "not json" }
        }
        assertNull(parser.parseIntent("something", ctx))
    }

    @Test
    fun `returns null for empty transcript`() = runTest {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _ -> """{"intent": "none"}""" }
        }
        assertNull(parser.parseIntent("", ctx))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SmolLmIntentParserTest*"`

- [ ] **Step 3: Create `SmolLmIntentParser.kt`**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import androidx.annotation.VisibleForTesting
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

object LmNative {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }
    external fun parseIntent(modelPath: String, prompt: String): String
}

@Singleton
open class SmolLmIntentParser @Inject constructor(
    private val modelFile: File,
) {
    // Override in tests to avoid calling real native code
    @VisibleForTesting
    internal var nativeImpl: ((modelPath: String, prompt: String) -> String)? = null

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    suspend fun parseIntent(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) return@withContext null
        val prompt = buildPrompt(transcript, context)
        try {
            val output = nativeImpl?.invoke(modelFile.absolutePath, prompt)
                ?: LmNative.parseIntent(modelFile.absolutePath, prompt)
            Timber.i("SmolLM: '%s'", output)
            parseIntentJson(output)
        } catch (e: Exception) {
            Timber.e(e, "SmolLM intent parsing failed")
            null
        }
    }

    private fun buildPrompt(transcript: String, ctx: VoiceRecognitionContext): String {
        val playbackInfo = when (val pc = ctx.playbackContext) {
            is PlaybackContext.Active -> "Current playback state: ${if (pc.isPlaying) "playing" else "paused"}"
            PlaybackContext.Inactive -> "Current playback state: inactive"
        }
        return """<|system|>
You are a voice command processor for a podcast player. Given a transcript of the user's speech, respond with ONLY a JSON object representing the closest matching intent. Available intents:

{"intent": "pause"}
{"intent": "resume"}
{"intent": "seek_relative", "delta_seconds": <positive integer>}
{"intent": "seek_absolute", "position_seconds": <positive integer>}
{"intent": "next_chapter"}
{"intent": "previous_chapter"}
{"intent": "chapter_by_index", "index": <non-negative integer>}
{"intent": "chapter_by_title", "query": "<chapter name>"}
{"intent": "next_episode"}
{"intent": "set_speed", "speed": <0.5 to 5.0>}
{"intent": "adjust_speed", "delta": <signed increment>}
{"intent": "set_volume", "volume": <0 to 100>}
{"intent": "adjust_volume", "delta": <signed increment>}
{"intent": "sleep_timer", "minutes": <positive integer; 0 to cancel>}
{"intent": "set_trim", "mode": "off"|"low"|"medium"|"high"}
{"intent": "set_volume_boost", "enabled": true|false}
{"intent": "add_bookmark", "title": "<bookmark label>"}

Common aliases:
"play" -> {"intent": "resume"} | "stop" -> {"intent": "pause"}
"next" -> {"intent": "next_chapter"} | "previous" -> {"intent": "previous_chapter"}
"faster" / "speed up" -> {"intent": "adjust_speed", "delta": 0.5}
"slower" / "slow down" -> {"intent": "adjust_speed", "delta": -0.5}
"forward X" / "skip X" -> {"intent": "seek_relative", "delta_seconds": X}
"go back X" -> {"intent": "seek_relative", "delta_seconds": -X}
"turn off" -> {"intent": "sleep_timer", "minutes": 0}
"volume up" -> {"intent": "adjust_volume", "delta": 10}
"volume down" -> {"intent": "adjust_volume", "delta": -10}
"set volume X" -> {"intent": "set_volume", "volume": X}
"louder" -> {"intent": "adjust_volume", "delta": 10}
"quieter" -> {"intent": "adjust_volume", "delta": -10}
"trim silence" / "silence trimming" -> {"intent": "set_trim", "mode": "medium"}
"no trim" -> {"intent": "set_trim", "mode": "off"}
"boost" / "turn on boost" -> {"intent": "set_volume_boost", "enabled": true}
"no boost" -> {"intent": "set_volume_boost", "enabled": false}
"bookmark this" / "save this" -> {"intent": "add_bookmark", "title": "Voice bookmark"}
"set speed X" -> {"intent": "set_speed", "speed": X}

$playbackInfo
Audio route: ${ctx.audioRoute}
If the speech is not a playback command, respond with {"intent": "none"}.
<|user|>
$transcript
<|assistant|>
""".trimIndent()
    }

    internal fun parseIntentJson(output: String): VoicePlaybackIntent? {
        if (output.isBlank()) return null
        return try {
            val trimmed = output.trim()
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) return null
            val json = JSONObject(trimmed.substring(jsonStart, jsonEnd + 1))
            val intent = json.optString("intent", "")
            if (intent == "none") return null
            parseKnownIntent(intent, json)
        } catch (e: Exception) {
            Timber.w(e, "SmolLM: failed to parse intent JSON")
            null
        }
    }

    private fun parseKnownIntent(intent: String, json: JSONObject): VoicePlaybackIntent? {
        return when (intent) {
            "pause" -> VoicePlaybackIntent.Pause
            "resume" -> VoicePlaybackIntent.Resume
            "seek_relative" -> VoicePlaybackIntent.SeekRelative((json.optDouble("delta_seconds", 30.0) * 1000).toInt())
            "seek_absolute" -> VoicePlaybackIntent.SeekAbsolute((json.optDouble("position_seconds", 0.0) * 1000).toInt())
            "next_chapter" -> VoicePlaybackIntent.NextChapter
            "previous_chapter" -> VoicePlaybackIntent.PreviousChapter
            "next_episode" -> VoicePlaybackIntent.NextEpisode
            "chapter_by_index" -> json.optInt("index", -1).let { if (it < 0) null else VoicePlaybackIntent.ChapterByIndex(it) }
            "chapter_by_title" -> json.optString("query", "").let { if (it.isBlank()) null else VoicePlaybackIntent.ChapterByTitle(it) }
            "set_speed" -> json.optDouble("speed", -1.0).let { if (it in 0.5..5.0) VoicePlaybackIntent.SetSpeed(it) else null }
            "adjust_speed" -> json.optDouble("delta", 0.0).let { if (it != 0.0) VoicePlaybackIntent.AdjustSpeed(it) else null }
            "set_volume" -> json.optInt("volume", -1).let { if (it in 0..100) VoicePlaybackIntent.SetVolume(it) else null }
            "adjust_volume" -> json.optInt("delta", 0).let { if (it != 0) VoicePlaybackIntent.AdjustVolume(it) else null }
            "sleep_timer" -> json.optInt("minutes", -1).let { if (it < 0) null else VoicePlaybackIntent.SleepTimer(it) }
            "set_trim" -> json.optString("mode", "").let { mode ->
                if (mode in listOf("off", "low", "medium", "high")) VoicePlaybackIntent.SetTrimMode(mode) else null
            }
            "set_volume_boost" -> VoicePlaybackIntent.SetVolumeBoost(json.optBoolean("enabled", false))
            "add_bookmark" -> json.optString("title", "").let { if (it.isNotBlank()) VoicePlaybackIntent.AddBookmark(it) else null }
            else -> {
                Timber.w("SmolLM: unknown intent '%s'", intent)
                null
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SmolLmIntentParserTest*"`

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParser.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/SmolLmIntentParserTest.kt
git commit -m "feat: add SmolLmIntentParser for intent extraction via llama.cpp"
```

---

### Task 4: Create CascadedVoiceRecognizer (orchestrator)

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/CascadedVoiceRecognizer.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/CascadedVoiceRecognizerTest.kt`

`CascadedVoiceRecognizer` implements the existing `VoiceRecognizer` interface and wires `WhisperRecognizer.transcribe()` into `SmolLmIntentParser.parseIntent()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CascadedVoiceRecognizerTest {

    @Test
    fun `orchestrates ASR then intent parsing`() = runTest {
        val whisper = WhisperRecognizer(modelFile = File("/tmp/whisper")).apply {
            nativeImpl = { _, _, _ -> "pause" }
        }
        val parser = SmolLmIntentParser(modelFile = File("/tmp/lm")).apply {
            nativeImpl = { _, _ -> """{"intent": "pause"}""" }
        }
        val recognizer = CascadedVoiceRecognizer(whisper, parser)
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(1600), 16000)))
        val ctx = VoiceRecognitionContext(PlaybackContext.Inactive, AudioRoute.Headset)

        assertEquals(VoicePlaybackIntent.Pause, recognizer.recognize(clip, ctx))
    }

    @Test
    fun `returns null when whisper returns empty`() = runTest {
        val whisper = WhisperRecognizer(modelFile = File("/tmp/whisper")).apply {
            nativeImpl = { _, _, _ -> "" }
        }
        val parser = SmolLmIntentParser(modelFile = File("/tmp/lm")).apply {
            nativeImpl = { _, _ -> """{"intent": "none"}""" }
        }
        val recognizer = CascadedVoiceRecognizer(whisper, parser)
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(1600), 16000)))
        val ctx = VoiceRecognitionContext(PlaybackContext.Inactive, AudioRoute.Headset)

        assertNull(recognizer.recognize(clip, ctx))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*CascadedVoiceRecognizerTest*"`

- [ ] **Step 3: Create `CascadedVoiceRecognizer.kt`**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class CascadedVoiceRecognizer @Inject constructor(
    private val whisperRecognizer: WhisperRecognizer,
    private val intentParser: SmolLmIntentParser,
) : VoiceRecognizer {

    override suspend fun ensureReady(): Result<Unit> {
        val whisperReady = whisperRecognizer.isModelReady()
        val lmReady = intentParser.isModelReady()
        return if (whisperReady && lmReady) {
            Result.success(Unit)
        } else {
            val missing = buildString {
                if (!whisperReady) append("whisper ")
                if (!lmReady) append("smol-lm")
            }
            Result.failure(Exception("Models not ready: $missing"))
        }
    }

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? {
        val transcript = whisperRecognizer.transcribe(clip)
        if (transcript.isBlank()) {
            Timber.w("Whisper: empty transcript, skipping intent parsing")
            return null
        }
        return intentParser.parseIntent(transcript, context)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*CascadedVoiceRecognizerTest*"`

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/CascadedVoiceRecognizer.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/CascadedVoiceRecognizerTest.kt
git commit -m "feat: add CascadedVoiceRecognizer orchestrating whisper + SmolLM"
```

---

### Task 5: Create ModelManager for downloading new models

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/ModelManager.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/ModelManagerTest.kt`

Replicates the resumable download pattern from `VoiceModelManager` but for the two new models (whisper base and SmolLM2 360M). Also provides `File` references for the model files so `WhisperRecognizer` and `SmolLmIntentParser` can inject them.

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelManagerTest {

    @Rule @JvmField val tempDir = TemporaryFolder()

    @Test
    fun `reports not ready when models not downloaded`() {
        val manager = ModelManager(tempDir.root)
        assertFalse(manager.areModelsReady())
    }

    @Test
    fun `reports ready when both markers exist`() {
        val whisperDir = tempDir.newFolder("whisper-model")
        val lmDir = tempDir.newFolder("smol-lm-model")
        File(whisperDir, "ggml-base-multilingual.bin").writeText("fake model")
        File(lmDir, "smolLM2-360M-instruct-Q4_K_M.gguf").writeText("fake model")

        val manager = ModelManager(tempDir.root)
        assertTrue(manager.areModelsReady())
    }

    @Test
    fun `provides correct model file paths`() {
        val manager = ModelManager(tempDir.root)
        assertEquals("ggml-base-multilingual.bin", manager.whisperModelFile.name)
        assertEquals("smolLM2-360M-instruct-Q4_K_M.gguf", manager.smolLmModelFile.name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*ModelManagerTest*"`

- [ ] **Step 3: Create `ModelManager.kt`**

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

data class ModelDownloadInfo(
    val url: String,
    val expectedSize: Long,
)

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-multilingual.bin"
        const val WHISPER_EXPECTED_SIZE = 150_000_000L
        const val SMOL_LM_URL = "https://huggingface.co/hugging-quants/SmolLM2-360M-Instruct-Q4_K_M-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf"
        const val SMOL_LM_EXPECTED_SIZE = 200_000_000L
    }

    private val whisperDir = File(context.filesDir, "whisper-model")
    private val smolLmDir = File(context.filesDir, "smol-lm-model")

    val whisperModelFile = File(whisperDir, "ggml-base-multilingual.bin")
    val smolLmModelFile = File(smolLmDir, "smolLM2-360M-instruct-Q4_K_M.gguf")

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotStarted)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun areModelsReady(): Boolean {
        return whisperModelFile.exists() && whisperModelFile.length() > 0 &&
            smolLmModelFile.exists() && smolLmModelFile.length() > 0
    }

    suspend fun ensureModels(): Result<Unit> = withContext(Dispatchers.IO) {
        if (areModelsReady()) {
            _downloadState.value = ModelDownloadState.Ready
            return@withContext Result.success(Unit)
        }
        try {
            whisperDir.mkdirs()
            smolLmDir.mkdirs()
            downloadFile(WHISPER_URL, whisperModelFile, WHISPER_EXPECTED_SIZE, "whisper")
            downloadFile(SMOL_LM_URL, smolLmModelFile, SMOL_LM_EXPECTED_SIZE, "SmolLM")
            _downloadState.value = ModelDownloadState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Model download failed")
            _downloadState.value = ModelDownloadState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun downloadFile(urlStr: String, dest: File, expectedSize: Long, label: String) {
        if (dest.exists() && dest.length() > 0) {
            Timber.i("$label model already downloaded: ${dest.length()} bytes")
            return
        }
        var maxRetries = 5
        var offset = 0L
        while (maxRetries > 0) {
            try {
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.connectTimeout = 60000
                connection.readTimeout = 60000
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code != 200 && code != 206) throw Exception("HTTP $code")
                connection.inputStream.use { input ->
                    FileOutputStream(dest, code == 206).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            offset += read
                            val pct = if (expectedSize > 0) (offset * 100 / expectedSize).toInt() else 0
                            _downloadState.value = ModelDownloadState.Downloading(pct, label)
                        }
                    }
                }
                connection.disconnect()
                maxRetries = 0
            } catch (e: Exception) {
                maxRetries--
                if (maxRetries <= 0) throw e
                Timber.w("$label download interrupted, retrying ($maxRetries left): ${e.message}")
                offset = dest.length()
                Thread.sleep(3000)
            }
        }
    }
}

sealed interface ModelDownloadState {
    data object NotStarted : ModelDownloadState
    data class Downloading(val progressPercent: Int, val modelLabel: String = "") : ModelDownloadState
    data object Ready : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*ModelManagerTest*"`

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/ModelManager.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/ModelManagerTest.kt
git commit -m "feat: add ModelManager for whisper + SmolLM model downloads"
```

---

### Task 6: Update DI module and remove Gemma 4 code

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/di/VoiceControlModule.kt`
- Modify: `modules/services/voice/build.gradle.kts`
- Delete: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/Gemma4VoiceRecognizer.kt`
- Delete: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/VoiceModelManager.kt`

- [ ] **Step 1: Update `VoiceControlModule.kt`** — rebind `VoiceRecognizer` to `CascadedVoiceRecognizer`, add `WhisperRecognizer` and `SmolLmIntentParser` providers, provide model `File` objects from `ModelManager`

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.di

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.SileroVadSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.UserNotDisabledRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.CascadedVoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerVoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoutePolicyRule
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceControlModule {

    @Binds abstract fun bindVoiceAudioSegmenter(impl: SileroVadSegmenter): VoiceAudioSegmenter

    // Cascaded pipeline: Whisper ASR -> SmolLM2 360M intent parser
    @Binds abstract fun bindVoiceRecognizer(impl: CascadedVoiceRecognizer): VoiceRecognizer

    @Binds abstract fun bindVoicePlaybackSink(impl: PlaybackManagerVoicePlaybackSink): VoicePlaybackSink

    @Binds abstract fun bindAudioRouteMonitor(impl: AndroidAudioRouteMonitor): AudioRouteMonitor

    companion object {
        @Provides @Singleton
        fun provideWhisperModelFile(manager: ModelManager): File = manager.whisperModelFile

        @Provides @Singleton
        fun provideSmolLmModelFile(manager: ModelManager): File = manager.smolLmModelFile

        @Provides @Singleton
        fun provideVoiceControlGate(
            playbackContextMonitor: PlaybackContextMonitor,
            audioRouteMonitor: AudioRouteMonitor,
            settings: Settings,
            @ApplicationScope scope: CoroutineScope,
        ): VoiceControlGate {
            val rules: List<VoiceControlRule> = listOf(
                UserNotDisabledRule(settings, scope),
                PlaybackContextRule(playbackContextMonitor.context, scope),
                AudioRoutePolicyRule(audioRouteMonitor.route, settings.voiceControlAudioRoutePolicy.flow, scope),
            )
            return VoiceControlGate(rules = rules, scope = scope)
        }

        @Provides @Singleton
        fun provideVoiceAudioProcessor(
            microphoneCapture: MicrophoneCapture,
            voiceAudioSegmenter: VoiceAudioSegmenter,
        ): VoiceAudioProcessor {
            return VoiceAudioProcessor(microphoneCapture, voiceAudioSegmenter)
        }
    }
}
```

- [ ] **Step 2: Update `build.gradle.kts`** — remove `litertlm` dependency, keep `litert-api` and `litert` for speaker embedder

Remove these lines from `dependencies`:
```kotlin
    implementation(libs.litertlm.android)
```

Keep `libs.litert.api` and `libs.litert.runtime` (still needed for speaker verification TFLite model).

- [ ] **Step 3: Delete old Gemma 4 E2B files**

```bash
rm modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/Gemma4VoiceRecognizer.kt
rm modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/VoiceModelManager.kt
```

- [ ] **Step 4: Update `VoiceControlService.kt`** if it references `Gemma4VoiceRecognizer` or `VoiceModelManager` directly

Grep for usages:
```bash
grep -rn "Gemma4VoiceRecognizer\|VoiceModelManager" modules/services/voice/src/main/
```

Replace any direct references with the new equivalents (likely none if DI handles it).

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/di/
git add modules/services/voice/build.gradle.kts
git rm modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/Gemma4VoiceRecognizer.kt
git rm modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/model/VoiceModelManager.kt
git commit -m "refactor: swap Gemma 4 E2B for cascaded Whisper + SmolLM pipeline"
```

---

### Task 7: Build and verify

- [ ] **Step 1: Build the project**

```bash
./gradlew :modules:services:voice:assembleDebug
```

Expected: Build succeeds. First build may take a while due to `FetchContent` downloading whisper.cpp and llama.cpp source.

- [ ] **Step 2: Run all voice module unit tests**

```bash
./gradlew :modules:services:voice:testDebugUnitTest
```

Expected: All tests pass (including pre-existing tests for gate rules, audio, playback, etc.).

- [ ] **Step 3: Code formatting check**

```bash
./gradlew spotlessCheck
```

Run `spotlessApply` if needed.

- [ ] **Step 4: Full app build**

```bash
./gradlew :app:assembleDebug
```

Expected: Build succeeds. Ensure no unresolved references to deleted Gemma 4 files in other modules.

- [ ] **Step 5: Commit**

```bash
git commit -m "chore: fix build issues after Gemma 4 E2B replacement"
```
