# ASR Intent Pipeline — Implementation Plan

> **Spec:** [asr-intent-pipeline spec](../specs/asr-intent-pipeline.md) — architecture, backend layer, intent/entity design, language coverage, error handling.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A multi-backend, on-device ASR layer (`AsrBackend` + `AsrBackendSelector`) fed by a `SignalFilter` (playback cross-correlation) and a `WakeWordDetector` (openWakeWord "Auris" classifier trained via livekit-wakeword, 3-stage ONNX pipeline, bundled) gate, then a language-agnostic intent pipeline: an embedding-based intent matcher (`multilingual-e5-small` ONNX) with edit-distance fallback, plus a pure-Kotlin rule-based entity extractor and normalizer. Everything runs on CPU (or the NPU, in the optional backend).

**Tech Stack:** sherpa-onnx (SenseVoice + bundled ONNX Runtime), whisper.cpp (built from source via CMake), `onnxruntime-android` (standalone, for embeddings), `multilingual-e5-small` ONNX (INT8, ~118 MB), pure-Kotlin BPE tokenizer + entity engine. Optional: Qualcomm QNN / WhisperKit-Android for the NPU backend.

The work is phased so the pipeline is functional after Phase 1 and improves with each later phase.

---

## Phase 1 — Backend-agnostic core + whisper.cpp

Goal: the full pipeline works for all languages via the universal whisper.cpp backend.

### Task 1: Define the ASR backend interface

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../asr/AsrBackend.kt`
- `modules/services/voice/src/main/kotlin/.../asr/AsrModels.kt` (`AsrResult`, `AsrCapabilities`, `ModelSpec`, `ModelFile`)

- [ ] **Step 1: Declare the interface and data types** exactly as in the spec's "AsrBackend interface" section: `ensureReady()`, `transcribe(samples, sampleRateHz)`, `requiredModel`, `capabilities`, `release()`.

- [ ] **Step 2: Compile** `./gradlew :modules:services:voice:assembleDebug`.

### Task 2: Implement WhisperCppBackend

**Files to create:**
- `modules/services/voice/src/main/cpp/WhisperJni.cpp` + `.h`
- `modules/services/voice/src/main/kotlin/.../asr/WhisperCppBackend.kt`

**Files to modify:**
- `modules/services/voice/src/main/cpp/CMakeLists.txt` — add whisper.cpp via FetchContent and a JNI target.

- [ ] **Step 1: Build whisper.cpp** through CMake. Keep 16 KB page-size alignment flags and `armv8.2-a+dotprod` for arm64.

- [ ] **Step 2: JNI surface** — load a quantized `ggml-*.bin`, transcribe a float PCM buffer, return text + detected language. Configure for short-command latency:
  - quantized weights,
  - `audio_ctx ≈ 384`,
  - single `whisper_full` pass (no separate language-detect pre-pass),
  - threads scaled to cores,
  - greedy sampling, no cross-utterance context.

- [ ] **Step 3: `WhisperCppBackend`** implements `AsrBackend`. `capabilities`: broad languages, `canTranslateToEnglish = true`, `requiresSnapdragon = false`. `requiredModel`: one `ggml-*.bin`.

- [ ] **Step 4: Translate-by-default** — Whisper backends run the **translate** task so every command arrives as English text (see spec "Translate vs. transcribe"). This collapses entity grammars and intent matching to monolingual English. Gated on validating Whisper translation quality on short commands/bare numbers before locking; the documented fallback is transcribe + per-language grammars.

### Task 3: VoiceAsrEngine

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../engine/VoiceAsrEngine.kt`

**Files to modify:**
- `modules/services/voice/src/main/kotlin/.../service/VoiceControlService.kt`

- [ ] **Step 1: Capture/VAD/dispatch loop** — own the Oboe capture + `NativeVadSegmenter` segmentation. On `SpeechEnded`, run the `SignalFilter` (playback cross-correlation) against the segment *first*; drop bleed before transcribing. The filter applies **only on the built-in loudspeaker route** (where the mic signal aligns to the playback buffer within a known delay); it is off on A2DP-to-external-speaker (codec latency unbounded) and on `Isolated`/headset routes (no bleed). On wake-word-mode segments, run the `WakeWordDetector` (Task 8) before ASR; only segments that survive the filter and clear the wake-word gate are transcribed. For surviving segments, convert shorts → float (`/ 32768f`) and call `backend.transcribe(...)`, then forward the transcript to the recognizer/`onIntent` path. Keep Bluetooth SCO handling and the playback buffer.

- [ ] **Step 2: Construction** — `VoiceAsrEngine` injects `VoiceAudioProcessor`, `UtteranceFilter`, `VoiceRecognizer`, `WakeWordDetector`. `start(backend, audioRoute, listeningMode, playbackBufferProvider, micExposureProvider, onIntent)` accepts the mode from the service; `updateListeningMode(mode)` propagates runtime mode changes without restart. The engine builds `VoiceRecognitionContext` internally from its current mode + the mic exposure provider, so the context always reflects live state.

- [ ] **Step 3: Service wiring** — `VoiceControlService` observes `ListeningModePolicy.mode`: on `Off` it stops the engine; on `Continuous`/`WakeWord` it calls `startEngine(mode)` initially, or `voiceAsrEngine.get().updateListeningMode(mode)` when the mode changes while already running (headset unplug, foreground loss, etc.). Models are ensured via `ensureModelsReady()` on `Dispatchers.IO`.

### Task 4: AsrBackendSelector

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../asr/AsrBackendSelector.kt`
- `modules/services/voice/src/main/kotlin/.../asr/DeviceProbe.kt` (Snapdragon / NPU detection)

- [ ] **Step 1: Selection matrix** from device probe + OS locale (spec table). With the NPU backend not yet shipped, the effective matrix is the SenseVoice / whisper.cpp rows. Until the SenseVoice task (Phase 2, Task 10) lands, SenseVoice is also absent, so Phase 1 selects whisper.cpp unconditionally — but write the full matrix and gate unavailable backends behind feature checks.

- [ ] **Step 2: Manual override** — read a user setting that forces a specific backend/language, taking priority over the matrix.

- [ ] **Step 3: `AsrBackendSelectorTest`** — cover every (Snapdragon?, NPU shipped?, locale, override) combination.

### Task 5: Model management for ASR

**Files to modify:**
- `modules/services/voice/src/main/kotlin/.../model/ModelManager.kt`

- [ ] **Step 1: Download by `ModelSpec`** — given the selected backend's `ModelSpec`, download its files (resume, retry, SHA-256, atomic rename) into the backend's `targetDir`. whisper.cpp: one quantized `ggml-*.bin` into `filesDir/whisper-model/`.

### Task 6: Embedding intent matcher with standalone ONNX Runtime

**Files to create/modify:**
- `modules/services/voice/src/main/kotlin/.../intent/EmbeddingIntentMatcher.kt`
- `modules/services/voice/src/main/cpp/EmbeddingJni.cpp` + `.h` (or pure-Kotlin ORT Java API)
- `modules/services/voice/src/main/kotlin/.../di/VoiceControlModule.kt`
- `modules/services/voice/build.gradle.kts` — add `onnxruntime-android`.

- [ ] **Step 1: Standalone ORT** — the embedding model links/loads its own `onnxruntime-android`, independent of any ASR backend. No reliance on another component loading ORT first.

- [ ] **Step 2: Tokenizer** — pure-Kotlin BPE over HuggingFace `tokenizer.json` (preferred), or a JNI SentencePiece wrapper.

- [ ] **Step 3: `EmbeddingIntentMatcher`** implementing `VoiceRecognizer`:

```kotlin
@Singleton
class EmbeddingIntentMatcher @Inject constructor(
    @Named("embeddingModel") private val modelFile: File,
) : VoiceRecognizer {

    private val intentEmbeddings: MutableMap<String, FloatArray> = linkedMapOf()

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
        for ((intent, keywords) in intentKeywords) {
            intentEmbeddings[intent] = averageAndNormalize(keywords.map { embed(it) })
        }
    }

    fun match(transcript: String): IntentMatch? {
        val q = embed(transcript)
        var bestScore = 0.0; var bestIntent: String? = null
        for ((intent, vec) in intentEmbeddings) {
            val score = cosineSimilarity(q, vec)
            if (score > bestScore) { bestScore = score; bestIntent = intent }
        }
        if (bestScore >= EMBEDDING_THRESHOLD && bestIntent != null) {
            return IntentMatch(bestIntent, bestScore, "embedding")
        }
        val lower = transcript.lowercase().trim()
        var bestDist = Int.MAX_VALUE; var fallback: String? = null
        for ((cmd, intent) in editDistanceFallback) {
            val dist = levenshteinDistance(lower, cmd)
            val norm = dist.toDouble() / maxOf(lower.length, cmd.length)
            if (norm < EDIT_DISTANCE_THRESHOLD && dist < bestDist) { bestDist = dist; fallback = intent }
        }
        if (fallback != null) return IntentMatch(fallback, 1.0 - bestDist.toDouble() / lower.length, "edit_distance")
        return null
    }

    companion object {
        private const val EMBEDDING_THRESHOLD = 0.6
        private const val EDIT_DISTANCE_THRESHOLD = 0.3
    }
}

data class IntentMatch(val intent: String, val confidence: Double, val method: String)
```

- [ ] **Step 4: `VoiceRecognizer`** — `ensureReady()` waits for model load + `initialize()`; `recognize(transcript, context)` calls `match()` → `EntityExtractor.extract()` → assembles a `VoiceIntent`. Bind in DI: `@Binds fun bindVoiceRecognizer(impl: EmbeddingIntentMatcher): VoiceRecognizer`.

- [ ] **Step 5: Download** — `model_opt2_QInt8.onnx` + `tokenizer.json` into `filesDir/embedding-model/`, provided via `@Named("embeddingModel")` / `@Named("embeddingTokenizer")`.

### Task 7: Entity extractor + normalizer (pure Kotlin)

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../intent/EntityExtractor.kt`
- `modules/services/voice/src/main/kotlin/.../intent/EntityNormalizer.kt`
- `modules/services/voice/src/main/kotlin/.../intent/lang/{NumberGrammar,DurationGrammar,OrdinalGrammar,EnGrammar,ZhGrammar}.kt`

- [ ] **Step 1: Grammar interface**

```kotlin
interface LanguageGrammar {
    val languageCode: String
    fun canParse(text: String): Boolean
    fun extractDuration(text: String): List<ExtractedEntity<Int>>   // → seconds
    fun extractNumber(text: String): List<ExtractedEntity<Double>>
    fun extractOrdinal(text: String): List<ExtractedEntity<Int>>    // → 0-based
    fun extractTrimMode(text: String): List<ExtractedEntity<String>>
    fun extractBoolean(text: String): List<ExtractedEntity<Boolean>>
}

data class ExtractedEntity<T>(val value: T, val span: String, val startIndex: Int, val endIndex: Int)
```

- [ ] **Step 2: English grammar** — durations ("30 seconds", "half a minute" → 30; "2 and a half minutes" → 150), numbers ("1.5", "two", "2x", "double" → 2.0), ordinals ("first" → 0, "last" → -1), trim modes, booleans.

- [ ] **Step 3: Chinese grammar** — "半分钟" → 30, "一分半" → 90, "5分钟" → 300, "两倍" → 2.0, "第三" → 2, booleans 开/关.

- [ ] **Step 4: `EntityExtractor`** — pick grammar by `canParse` (script/language hint), dispatch by intent type to the relevant slot, with defaults: seek ±30s, adjust speed ±0.5, adjust volume ±10, sleep 30 min.

```kotlin
data class EntityResult(
    val deltaSeconds: Int? = null, val positionSeconds: Int? = null,
    val speed: Double? = null, val speedDelta: Double? = null,
    val volume: Int? = null, val volumeDelta: Int? = null,
    val sleepMinutes: Int? = null, val chapterIndex: Int? = null,
    val trimMode: String? = null, val boostEnabled: Boolean? = null,
    val bookmarkTitle: String? = null, val chapterTitle: String? = null,
)
```

- [ ] **Step 5: Wire into `recognize()`** — `match()` → `IntentMatch` → `extract()` → `VoiceIntent`. Chapter/bookmark titles: remaining text after stripping matched keywords and extracted spans.

### Task 8: Wake word detection

**Files to create:**
- `modules/services/voice/src/main/cpp/WakeWordJni.cpp` — C++ JNI bridge running the openWakeWord 3-stage ONNX pipeline (mel spectrogram → embedding → classifier) via onnxruntime. Follows the `dlsym`-based ORT loading pattern from `VadJni.cpp`.
- `modules/services/voice/src/main/kotlin/.../wakeword/WakeWordJni.kt` — Kotlin `external` declarations for the native wake word functions.
- `modules/services/voice/src/main/kotlin/.../wakeword/OpenWakeWordDetector.kt` — implements `WakeWordDetector`, loads the three ONNX model files from assets, delegates to `WakeWordJni`.

**Files to modify:**
- `modules/services/voice/src/main/cpp/CMakeLists.txt` — add `WakeWordJni.cpp` to the existing `pocketcasts_voice_capture` target.
- `modules/services/voice/src/main/kotlin/.../di/VoiceControlModule.kt` — change binding: `fun bindWakeWordDetector(impl: OpenWakeWordDetector): WakeWordDetector`.

**Files to keep (unused):**
- `SherpaOnnxKwsDetector.kt` — retained in codebase, not bound in DI, for easy fallback switching.
- `com.k2fsa.sherpa.onnx.KeywordSpotter` and related JNI wrappers — retained.

**Files to delete:**
- `modules/services/voice/src/main/assets/kws/` — old sherpa-onnx KWS model files (encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt, bpe.model, keywords.txt) replaced by the openWakeWord ONNX models.

The detector uses an **openWakeWord Conv-Attention classifier** trained via livekit-wakeword. Inference is a 3-stage ONNX pipeline (mel spectrogram → speech embedding → classifier), all running on the already-loaded `libonnxruntime.so`. All three ONNX models are bundled in `assets/oww/`. Detection runs per VAD segment that survives the `SignalFilter`, **before** ASR.

- [ ] **Step 1: Train the "Auris" model** — install `livekit-wakeword[train,eval,export]`, create a config YAML for "auris", run `livekit-wakeword run` then `livekit-wakeword export`. Output: `auris.onnx` (~160 KB).

- [ ] **Step 2: Obtain supporting ONNX models** — extract `melspectrogram.onnx` and `embedding_model.onnx` from the livekit-wakeword Python package (bundled in its `onnx/` directory). These are fixed for all wake words.

- [ ] **Step 3: Bundle models in assets** — copy `melspectrogram.onnx`, `embedding_model.onnx`, and `auris.onnx` into `modules/services/voice/src/main/assets/oww/`. Delete old `assets/kws/`.

- [ ] **Step 4: `WakeWordJni.cpp`** — C++ JNI implementing the openWakeWord streaming pipeline:
  - Accumulate 1280 samples (80ms), convert normalized float [-1,1] → int16-range (×32768), run `melspectrogram.onnx`, apply `mel = mel/10 + 2` transform, push to rolling mel buffer (max 76 frames).
  - Every 8 new mel frames (80ms stride), run `embedding_model.onnx` on latest 76 mel frames → 96-dim vector, push to rolling embedding buffer (max 16 vectors).
  - Every new embedding, run `auris.onnx` on latest 16 embeddings → sigmoid score.
  - Score > threshold (default 0.5) → detection. State is reset between `detect()` calls.
  - JNI native methods: `nativeInit(melModel, embedModel, classifierModel, threshold)`, `nativeDetect(samples, sampleRateHz)` returning confidence score, `nativeRelease()`.
  - Uses `dlsym` to resolve `OrtGetApiBase` from the already-loaded `libonnxruntime.so` (same pattern as `VadJni.cpp` and `EmbeddingJni.cpp`).

- [ ] **Step 5: `WakeWordJni.kt`** — Kotlin `object` with `external` methods that load `pocketcasts_voice_capture` and declare the three JNI functions.

- [ ] **Step 6: `OpenWakeWordDetector.kt`** — `@Singleton` implementing `WakeWordDetector`:
  - In `init`: load the three ONNX model files from `assets/oww/` as `ByteArray`, pass to `WakeWordJni.nativeInit()`.
  - `detect(segment, sampleRateHz)`: call `WakeWordJni.nativeDetect()` on the full FloatArray (no chunking — the C++ code handles internal buffering). Extract remainder audio after keyword for combined "Auris, skip forward" support.
  - `isReady`: true once native init succeeds.
  - `release()`: calls `WakeWordJni.nativeRelease()`.

- [ ] **Step 7: DI wiring** — change `VoiceControlModule.kt` line 68 to `@Binds abstract fun bindWakeWordDetector(impl: OpenWakeWordDetector): WakeWordDetector`. Keep `SherpaOnnxKwsDetector` in the codebase but remove its `@Singleton` and `@Inject` annotations so it doesn't participate in DI (or keep them and just change the binding — either way, it's not constructed).

- [ ] **Step 8: Register in CMakeLists.txt** — add `WakeWordJni.cpp` to the existing `pocketcasts_voice_capture` library target.

### Task 9: Phase 1 build + verify

- [ ] **Compile** `./gradlew :modules:services:voice:assembleDebug`
- [ ] **Unit tests** `./gradlew :modules:services:voice:testDebugUnitTest` — `AsrBackendSelectorTest`, `EmbeddingIntentMatcherTest`, `EntityExtractorTest`, `EntityNormalizerTest`, `WakeWordDetectorTest`.
- [ ] **Spotless** `./gradlew spotlessApply`
- [ ] **Integration smoke (device)**: "fast forward 30 seconds" (en) → `SeekRelative(30000)`; "快进半分钟" (zh) → `SeekRelative(30000)`; "pray" → `Resume`; podcast bleed → no false intent; in wake-word mode a bare command is ignored until preceded by "Auris".

---

## Phase 2 — SenseVoiceBackend

Goal: CJK/English users get the fast CTC path.

### Task 10: SenseVoiceBackend

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../asr/SenseVoiceBackend.kt`

**Files to modify:**
- `modules/services/voice/build.gradle.kts` — add `sherpa-onnx`.
- `modules/services/voice/src/main/kotlin/.../model/ModelManager.kt` — SenseVoice `ModelSpec`.

- [ ] **Step 1: sherpa-onnx `OfflineRecognizer`** with SenseVoice-Small (int8). `capabilities`: languages {zh, en, ja, ko, yue}, `canTranslateToEnglish = false`, `requiresSnapdragon = false`. Auto LID on.

- [ ] **Step 2: Download** model + tokens into `filesDir/sensevoice-model/`.

- [ ] **Step 3: Enable the selector's CJK/English branch** and confirm the manual override can still force whisper.cpp.

- [ ] **Step 4: `SenseVoiceBackendTest`** (instrumentation) — fixed zh + en WAV → non-empty transcript, correct detected language.

- [ ] **Step 5: Verify ORT coexistence** — sherpa-onnx's bundled ORT and the standalone embedding ORT load together; pin a single `onnxruntime-android` version if they conflict. Confirm 16 KB alignment on all `.so`.

---

## Phase 3 — WhisperNpuBackend (optional)

Goal: fastest path on Snapdragon. May be deferred indefinitely; nothing else depends on it.

### Task 11: WhisperNpuBackend

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../asr/WhisperNpuBackend.kt`

- [ ] **Step 1: QNN / WhisperKit-Android integration**, Snapdragon-gated via `DeviceProbe`. `capabilities`: broad languages, `requiresSnapdragon = true`.
- [ ] **Step 2: Download** QNN context binaries (per-SoC) into `filesDir/whisper-npu-model/`.
- [ ] **Step 3: Enable the selector's NPU branch** (priority over SenseVoice and whisper.cpp). Fall through to other backends if the NPU is unavailable at runtime.

---

## Model Size Summary

| Component | Format | Size |
|---|---|---|
| multilingual-e5-small | ONNX INT8 | ~118 MB |
| BPE tokenizer (`tokenizer.json`) | JSON | ~16 MB |
| Entity engine | Pure Kotlin | ~50 KB |
| whisper.cpp model (`ggml-small-q5_1`, baseline) | GGML quantized | ~190 MB |
| whisper.cpp model (`ggml-base-q5_1`, fallback only) | GGML quantized | ~57 MB |
| SenseVoice-Small (optional) | ONNX int8 | ~228 MB |
| openWakeWord mel spectrogram (bundled) | ONNX | ~100 KB |
| openWakeWord speech embedding (bundled) | ONNX | ~1.3 MB |
| openWakeWord "Auris" classifier (bundled, trained) | ONNX | ~160 KB |

`ggml-small-q5_1` is the baseline because the translate-by-default path needs reliable translation, which `base` is markedly weaker at on short commands and bare numbers; `base-q5_1` is a fallback only if validation shows it is adequate and `small` misses the latency target. Only the selected ASR backend's model is downloaded at runtime, alongside the embedding model. The wake word models are always bundled.
