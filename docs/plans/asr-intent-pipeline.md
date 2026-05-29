# ASR Intent Pipeline — Implementation Plan

> **Spec:** [asr-intent-pipeline spec](../specs/asr-intent-pipeline.md) — architecture, component design, language coverage, error handling.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace SmolLM2 / llama.cpp with an embedding-based intent matcher (`multilingual-e5-small` ONNX), a Kotlin rule-based entity extractor + normalizer (Duckling-style grammars), and edit-distance ASR error correction. Remove the Vulkan GPU backend — everything runs on CPU via ONNX Runtime.

**Tech Stack:** `ai.moonshine:moonshine-voice` (ASR/VAD), ONNX Runtime (already bundled by Moonshine), `multilingual-e5-small` ONNX (INT8, ~118 MB), SentencePiece C++ tokenizer (JNI), pure Kotlin entity extraction + normalization engine.

---

### Task 1: Remove SmolLM2 and llama.cpp infrastructure

**Files to modify:**
- `modules/services/voice/src/main/cpp/LmJni.cpp` — delete
- `modules/services/voice/src/main/cpp/LmJni.h` — delete
- `modules/services/voice/src/main/cpp/CMakeLists.txt` — remove llama.cpp FetchContent, remove LmJni target
- `modules/services/voice/build.gradle.kts` — remove any llama.cpp-related build config (Vulkan flags, etc.)
- `modules/services/voice/src/main/kotlin/.../intent/SmolLmIntentParser.kt` — delete
- `modules/services/voice/src/main/kotlin/.../model/ModelManager.kt` — remove SmolLM2 download logic (keep Moonshine model management)
- `modules/services/voice/src/main/kotlin/.../di/VoiceControlModule.kt` — remove SmolLmIntentParser binding and `@Named("smolLmModel")` provider

- [ ] **Step 1: Delete native JNI files for SmolLM2**

Remove `LmJni.cpp` and `LmJni.h` from the C++ source directory.

- [ ] **Step 2: Simplify CMakeLists.txt**

Remove the llama.cpp `FetchContent` block and the `pocketcasts_voice_capture` shared library target. After this change, the voice module may have no native code at all (Moonshine bundles its own .so files via Maven).

If no other native targets remain in the module, remove `externalNativeBuild { cmake { ... } }` from `build.gradle.kts` and delete `CMakeLists.txt` entirely. Remove any Vulkan-related CMake flags (`-DGGML_VULKAN=ON`, etc.).

- [ ] **Step 3: Remove SmolLmIntentParser and related Kotlin code**

Delete `SmolLmIntentParser.kt`. In `ModelManager.kt`, remove the `smolLmModelFile` property and any SmolLM2 download methods.

- [ ] **Step 4: Update DI module**

Remove the `@Named("smolLmModel")` provider and the `bindVoiceRecognizer(impl: SmolLmIntentParser)` binding. The new `EmbeddingIntentMatcher` will be bound once created (Task 2).

- [ ] **Step 5: Verify compilation**

```bash
./gradlew :modules:services:voice:assembleDebug
```

---

### Task 2: Create EmbeddingIntentMatcher

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../intent/EmbeddingIntentMatcher.kt`
- `modules/services/voice/src/main/cpp/EmbeddingJni.cpp` (if native tokenizer ORT session needed)
- `modules/services/voice/src/main/cpp/EmbeddingJni.h`

**Files to modify:**
- `modules/services/voice/src/main/kotlin/.../di/VoiceControlModule.kt` — bind `EmbeddingIntentMatcher` as `VoiceRecognizer`

- [ ] **Step 1: Set up ONNX Runtime and SentencePiece for embeddings**

Moonshine already bundles ONNX Runtime. We need two additions:
1. **SentencePiece C++ tokenizer** — compile `sentencepiece` as a static library and write a thin JNI wrapper that loads `sentencepiece.bpe.model`, tokenizes a string, and returns token IDs.
2. **Embedding ONNX inference** — write a JNI function that loads `multilingual-e5-small` ONNX model, runs token IDs through it, applies mean pooling over the last hidden state, L2-normalizes, and returns a `float[]` (384-dim).

Native surface:
```cpp
// Tokenizer
bool tokenizerLoad(const char* modelPath);
int* tokenizerEncode(const char* text, int* tokenCount); // caller frees

// Embedding
bool embeddingLoad(const char* onnxPath);
float* embeddingRun(const int* tokenIds, int tokenCount); // returns 384 floats, caller frees
```

Alternative to JNI: use the Java ONNX Runtime API (`onnxruntime-android`) and implement SentencePiece in pure Kotlin (the BPE algorithm is straightforward, ~200 lines). This avoids native compilation entirely. Prefer the pure-Kotlin tokenizer approach if the SentencePiece model format is stable enough.

- [ ] **Step 2: Create `EmbeddingIntentMatcher`**

```kotlin
@Singleton
class EmbeddingIntentMatcher @Inject constructor(
    @Named("embeddingModel") private val modelFile: File,
) : VoiceRecognizer {

    // Pre-computed: intent keyword → embedding vector
    private val intentEmbeddings: MutableMap<String, FloatArray> = linkedMapOf()

    // Intent keywords in English (the stable reference vocabulary)
    private val intentKeywords = linkedMapOf(
        "pause" to listOf("pause", "stop", "hold"),
        "resume" to listOf("resume", "play", "continue", "start"),
        "seek_relative_forward" to listOf("fast forward", "skip forward", "jump ahead"),
        "seek_relative_backward" to listOf("rewind", "go back", "skip back"),
        "seek_absolute" to listOf("go to", "jump to"),
        "next_chapter" to listOf("next chapter", "skip chapter"),
        "previous_chapter" to listOf("previous chapter", "last chapter", "prior chapter"),
        "next_episode" to listOf("next episode"),
        "set_speed" to listOf("set speed", "change speed"),
        "adjust_speed_up" to listOf("faster", "speed up", "increase speed"),
        "adjust_speed_down" to listOf("slower", "speed down", "decrease speed"),
        "set_volume" to listOf("set volume"),
        "adjust_volume_up" to listOf("volume up", "louder", "increase volume"),
        "adjust_volume_down" to listOf("volume down", "quieter", "decrease volume"),
        "sleep_timer" to listOf("sleep timer", "set timer", "stop in"),
        "set_trim" to listOf("trim silence", "silence trimming"),
        "set_volume_boost" to listOf("boost", "volume boost", "loudness"),
        "add_bookmark" to listOf("bookmark", "save this", "mark this"),
    )

    // Edit-distance fallback vocabulary: raw command strings → intent
    private val editDistanceFallback = linkedMapOf(
        "pause" to "pause", "stop" to "pause", "hold" to "pause",
        "resume" to "resume", "play" to "resume", "start" to "resume",
        "fast forward" to "seek_relative_forward", "skip forward" to "seek_relative_forward",
        "rewind" to "seek_relative_backward", "go back" to "seek_relative_backward",
        "next chapter" to "next_chapter", "previous chapter" to "previous_chapter",
        "next episode" to "next_episode",
        "faster" to "adjust_speed_up", "speed up" to "adjust_speed_up",
        "slower" to "adjust_speed_down", "speed down" to "adjust_speed_down",
        "volume up" to "adjust_volume_up", "louder" to "adjust_volume_up",
        "volume down" to "adjust_volume_down", "quieter" to "adjust_volume_down",
        "sleep timer" to "sleep_timer", "set timer" to "sleep_timer",
        "trim silence" to "set_trim",
        "boost" to "set_volume_boost", "volume boost" to "set_volume_boost",
        "bookmark" to "add_bookmark", "save this" to "add_bookmark",
    )

    fun initialize() {
        // Embed all intent keywords once at startup
        for ((intent, keywords) in intentKeywords) {
            // Average the embeddings of all keywords for this intent
            val vectors = keywords.map { embed(it) }
            intentEmbeddings[intent] = averageAndNormalize(vectors)
        }
    }

    fun match(transcript: String): IntentMatch? {
        // 1. Primary: embedding cosine similarity
        val transcriptEmbedding = embed(transcript)
        var bestScore = 0.0
        var bestIntent: String? = null

        for ((intent, intentVec) in intentEmbeddings) {
            val score = cosineSimilarity(transcriptEmbedding, intentVec)
            if (score > bestScore) {
                bestScore = score
                bestIntent = intent
            }
        }

        if (bestScore >= EMBEDDING_THRESHOLD && bestIntent != null) {
            return IntentMatch(bestIntent, bestScore, "embedding")
        }

        // 2. Fallback: edit distance on raw transcript
        val lowerTranscript = transcript.lowercase().trim()
        var bestDistance = Int.MAX_VALUE
        var bestFallbackIntent: String? = null

        for ((cmd, intent) in editDistanceFallback) {
            val dist = levenshteinDistance(lowerTranscript, cmd)
            val maxLen = maxOf(lowerTranscript.length, cmd.length)
            val normalizedDist = dist.toDouble() / maxLen

            if (normalizedDist < EDIT_DISTANCE_THRESHOLD && dist < bestDistance) {
                bestDistance = dist
                bestFallbackIntent = intent
            }
        }

        if (bestFallbackIntent != null) {
            return IntentMatch(bestFallbackIntent, 1.0 - bestDistance.toDouble() / lowerTranscript.length, "edit_distance")
        }

        return null
    }

    companion object {
        private const val EMBEDDING_THRESHOLD = 0.6  // cosine similarity
        private const val EDIT_DISTANCE_THRESHOLD = 0.3  // normalized Levenshtein
    }
}

data class IntentMatch(
    val intent: String,
    val confidence: Double,
    val method: String,  // "embedding" or "edit_distance"
)
```

- [ ] **Step 3: Implement `VoiceRecognizer` interface**

`EmbeddingIntentMatcher` implements `VoiceRecognizer`:
- `ensureReady()` — waits for model load + initialization
- `recognize(transcript, context)` — calls `match()`, then `EntityExtractor.extract()`, then assembles `VoicePlaybackIntent`

- [ ] **Step 4: Bind in DI module**

```kotlin
@Binds abstract fun bindVoiceRecognizer(impl: EmbeddingIntentMatcher): VoiceRecognizer
```

---

### Task 3: Create entity extraction + normalization engine (Duckling-style, pure Kotlin)

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../intent/EntityExtractor.kt`
- `modules/services/voice/src/main/kotlin/.../intent/EntityNormalizer.kt`
- `modules/services/voice/src/main/kotlin/.../intent/lang/NumberGrammar.kt`
- `modules/services/voice/src/main/kotlin/.../intent/lang/DurationGrammar.kt`
- `modules/services/voice/src/main/kotlin/.../intent/lang/OrdinalGrammar.kt`
- `modules/services/voice/src/main/kotlin/.../intent/lang/EnGrammar.kt`
- `modules/services/voice/src/main/kotlin/.../intent/lang/ZhGrammar.kt`
- (additional language modules as needed)

- [ ] **Step 1: Design the grammar interface**

Each language module implements a common interface:

```kotlin
interface LanguageGrammar {
    /** ISO 639-1 code */
    val languageCode: String

    /** Detect whether this grammar can parse the given text */
    fun canParse(text: String): Boolean

    /** Extract duration expressions → seconds (Int) */
    fun extractDuration(text: String): List<ExtractedEntity<Int>>

    /** Extract cardinal numbers → Double */
    fun extractNumber(text: String): List<ExtractedEntity<Double>>

    /** Extract ordinal numbers → 0-based index (Int) */
    fun extractOrdinal(text: String): List<ExtractedEntity<Int>>

    /** Extract trim mode keywords → "off"|"low"|"medium"|"high" */
    fun extractTrimMode(text: String): List<ExtractedEntity<String>>

    /** Extract boolean (affirmative/negative) → Boolean */
    fun extractBoolean(text: String): List<ExtractedEntity<Boolean>>
}

data class ExtractedEntity<T>(
    val value: T,
    val span: String,
    val startIndex: Int,
    val endIndex: Int,
)
```

- [ ] **Step 2: Implement English grammar**

English is the reference. Key patterns (regex-based, ordered by specificity):

```kotlin
// Duration patterns
"30 seconds" / "thirty seconds" → 30
"5 minutes" / "five minutes" → 300
"1 hour" / "one hour" → 3600
"half a minute" → 30
"2 and a half minutes" → 150
"90 seconds" → 90

// Number patterns
"1.5" → 1.5
"two" → 2.0
"2x" → 2.0
"double" → 2.0

// Ordinal patterns
"first" → 0, "third" → 2, "last" → -1

// Trim mode
"off" / "low" / "medium" / "high"

// Boolean
"on" / "enable" / "turn on" → true
"off" / "disable" / "turn off" → false
```

- [ ] **Step 3: Implement Chinese grammar**

```kotlin
// Duration patterns
"30秒" / "三十秒" → 30
"5分钟" / "五分钟" → 300
"半小时" / "半个钟头" → 1800
"半分钟" / "半分钟" → 30
"一分半" / "一分半钟" → 90

// Number patterns
"一点五" → 1.5
"两倍" → 2.0
"二" → 2

// Ordinal
"第一" → 0, "第三" → 2, "最后" → -1

// Trim mode — reuse English keywords (or Chinese equivalents)
"关闭" / "低" / "中" / "高"

// Boolean
"开" / "启用" → true
"关" / "禁用" → false
```

- [ ] **Step 4: Implement `EntityExtractor`**

Dispatches to language grammars based on script detection or ASR language hint:

```kotlin
@Singleton
class EntityExtractor @Inject constructor() {
    private val grammars: List<LanguageGrammar> = listOf(
        EnGrammar(),
        ZhGrammar(),
        // JaGrammar(), KoGrammar(), ArGrammar(), EsGrammar(), ...
    )

    fun detectLanguage(text: String): LanguageGrammar {
        // Simple script-based detection
        for (grammar in grammars) {
            if (grammar.canParse(text)) return grammar
        }
        return grammars.first() // fallback to English
    }

    fun extract(text: String, intentType: String): EntityResult {
        val grammar = detectLanguage(text)
        return when (intentType) {
            "seek_relative_forward" -> {
                val durations = grammar.extractDuration(text)
                EntityResult(deltaSeconds = durations.firstOrNull()?.value ?: 30)
            }
            "seek_relative_backward" -> {
                val durations = grammar.extractDuration(text)
                EntityResult(deltaSeconds = -(durations.firstOrNull()?.value ?: 30))
            }
            "seek_absolute" -> {
                val durations = grammar.extractDuration(text)
                EntityResult(positionSeconds = durations.firstOrNull()?.value)
            }
            "set_speed" -> {
                val numbers = grammar.extractNumber(text)
                EntityResult(speed = numbers.firstOrNull()?.value)
            }
            "adjust_speed_up" -> {
                val numbers = grammar.extractNumber(text)
                EntityResult(speedDelta = numbers.firstOrNull()?.value ?: 0.5)
            }
            "adjust_speed_down" -> {
                val numbers = grammar.extractNumber(text)
                EntityResult(speedDelta = -(numbers.firstOrNull()?.value ?: 0.5))
            }
            "set_volume" -> {
                val numbers = grammar.extractNumber(text)
                EntityResult(volume = numbers.firstOrNull()?.value?.toInt())
            }
            "adjust_volume_up" -> {
                val numbers = grammar.extractNumber(text)
                EntityResult(volumeDelta = numbers.firstOrNull()?.value?.toInt() ?: 10)
            }
            "adjust_volume_down" -> {
                val numbers = grammar.extractNumber(text)
                EntityResult(volumeDelta = -(numbers.firstOrNull()?.value?.toInt() ?: 10))
            }
            "sleep_timer" -> {
                val durations = grammar.extractDuration(text)
                EntityResult(sleepMinutes = durations.firstOrNull()?.value?.div(60))
            }
            "chapter_by_index" -> {
                val ordinals = grammar.extractOrdinal(text)
                EntityResult(chapterIndex = ordinals.firstOrNull()?.value)
            }
            "set_trim" -> {
                val modes = grammar.extractTrimMode(text)
                EntityResult(trimMode = modes.firstOrNull()?.value)
            }
            "set_volume_boost" -> {
                val bools = grammar.extractBoolean(text)
                EntityResult(boostEnabled = bools.firstOrNull()?.value)
            }
            else -> EntityResult() // parameterless intents
        }
    }
}

data class EntityResult(
    val deltaSeconds: Int? = null,
    val positionSeconds: Int? = null,
    val speed: Double? = null,
    val speedDelta: Double? = null,
    val volume: Int? = null,
    val volumeDelta: Int? = null,
    val sleepMinutes: Int? = null,
    val chapterIndex: Int? = null,
    val trimMode: String? = null,
    val boostEnabled: Boolean? = null,
    val bookmarkTitle: String? = null,
    val chapterTitle: String? = null,
)
```

- [ ] **Step 5: Handle defaults for parameterized intents**

When entity extraction finds nothing, apply sensible defaults:

| Intent | Default |
|---|---|
| seek_relative_forward | +30s |
| seek_relative_backward | -30s |
| adjust_speed_up | +0.5 |
| adjust_speed_down | -0.5 |
| adjust_volume_up | +10 |
| adjust_volume_down | -10 |
| sleep_timer | 30 min |
| chapter_by_index | null (skip intent) |

- [ ] **Step 6: Wire `EntityExtractor` into `EmbeddingIntentMatcher.recognize()`**

The `recognize()` method: calls `match()` → gets `IntentMatch` → calls `entityExtractor.extract()` → assembles `VoicePlaybackIntent`. Chapter/bookmark titles use a simple heuristic: any remaining text after removing the matched intent keywords and extracted entity spans is the title/query.

---

### Task 4: Clean up MoonshineVoiceEngine

**Files to modify:**
- `modules/services/voice/src/main/kotlin/.../engine/MoonshineVoiceEngine.kt`
- `modules/services/voice/src/main/kotlin/.../engine/UtteranceFilter.kt` (unchanged)
- `modules/services/voice/src/main/kotlin/.../service/VoiceControlService.kt`

- [ ] **Step 1: Update `MoonshineVoiceEngine`**

Remove the `SmolLmIntentParser` dependency. Replace with `EmbeddingIntentMatcher`:

```kotlin
@Singleton
class MoonshineVoiceEngine @Inject constructor(
    private val utteranceFilter: UtteranceFilter,
    private val intentMatcher: EmbeddingIntentMatcher,  // was SmolLmIntentParser
) {
    // ... MicTranscriber setup unchanged ...

    private fun processUtterance(
        text: String,
        audio: FloatArray,
        hasSpeakerId: Boolean,
        speakerIndex: Int,
        onIntent: (VoicePlaybackIntent) -> Unit,
    ) {
        if (text.isBlank()) return

        val playbackBuffer = playbackBufferProvider?.invoke() ?: FloatArray(0)
        if (!utteranceFilter.shouldProcess(audio, hasSpeakerId, speakerIndex, playbackBuffer)) return

        // suspend call to VoiceRecognizer.recognize()
        // EmbeddingIntentMatcher handles matching + extraction + assembly
        val intent = runBlocking { intentMatcher.recognize(text, buildContext()) }
        if (intent != null) onIntent(intent)
    }
}
```

The utterance filter, playback buffer, and service integration remain unchanged — only the intent parser dependency changes.

---

### Task 5: Add embedding model management

**Files to modify:**
- `modules/services/voice/src/main/kotlin/.../model/ModelManager.kt`
- `modules/services/voice/src/main/kotlin/.../di/VoiceControlModule.kt`

- [ ] **Step 1: Update `ModelManager`**

Remove SmolLM2 download. Add embedding model download:
- Model source: `nixiesearch/multilingual-e5-small-onnx` (HuggingFace) — `model_opt2_QInt8.onnx` (~118 MB)
- Tokenizer source: `intfloat/multilingual-e5-small` — `sentencepiece.bpe.model` (~5 MB)
- Same SHA-256 verification, resume, retry, atomic rename pattern
- Model stored under `filesDir/embedding-model/`

- [ ] **Step 2: Update DI providers**

```kotlin
companion object {
    @Provides @Singleton
    @Named("embeddingModel")
    fun provideEmbeddingModelFile(manager: ModelManager): File = manager.embeddingModelFile

    @Provides @Singleton
    @Named("embeddingTokenizer")
    fun provideEmbeddingTokenizerFile(manager: ModelManager): File = manager.embeddingTokenizerFile
}
```

---

### Task 6: Build and verify

- [ ] **Step 1: Compile**

```bash
./gradlew :modules:services:voice:assembleDebug
```

- [ ] **Step 2: Run unit tests**

```bash
./gradlew :modules:services:voice:testDebugUnitTest
```

Key new tests:
- `EmbeddingIntentMatcherTest` — cosine similarity ranking, edit distance fallback, threshold behavior
- `EntityExtractorTest` — English and Chinese duration/number/ordinal extraction
- `EntityNormalizerTest` — edge cases: "half a minute" → 30, "一分半" → 90, "2 and a half minutes" → 150

- [ ] **Step 3: Run existing tests to verify no regressions**

`VoicePlaybackIntentExecutorTest` and all gate rule tests must continue to pass.

- [ ] **Step 4: Spotless**

```bash
./gradlew spotlessApply
```

- [ ] **Step 5: Integration smoke test**

On a device:
1. English: "fast forward 30 seconds" → `SeekRelative(30000)`
2. Chinese: "快进半分钟" → `SeekRelative(30000)`
3. ASR error recovery: mock "pray" transcript → `Resume` (edit distance)
4. Podcast bleed: play podcast, speak command → no false intent (cross-correlation)
5. Speaker gating: second person speaks → dropped (diarization)

---

### Model Size Summary

| Component | Format | Size |
|---|---|---|
| multilingual-e5-small | ONNX INT8 | ~118 MB |
| SentencePiece tokenizer | BPE model file | ~5 MB |
| Entity extraction engine | Pure Kotlin (compiled) | ~50 KB |
| **Total new footprint** | | **~123 MB** |
| **Removed** (SmolLM2 Q4_K_M GGUF) | | **-200 MB** |
| **Net change** | | **~77 MB smaller** |
| llama.cpp native library | | **Removed** |
| Vulkan GPU backend | | **Removed** |
