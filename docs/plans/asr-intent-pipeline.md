# ASR Intent Pipeline — Implementation Plan

> **Spec:** [asr-intent-pipeline spec](../specs/asr-intent-pipeline.md) — architecture, backend layer, intent/entity design, language coverage, error handling.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A multi-backend, on-device ASR layer (`AsrBackend` + `AsrBackendSelector`) fed by a `SignalFilter` (playback cross-correlation) and a `WakeWordDetector` (openWakeWord "Auris" classifier trained via livekit-wakeword, 3-stage ONNX pipeline, bundled) gate, then a finetuned **FunctionGemma-270M** intent router on **LiteRT-LM**: intent classification, entity extraction, and rejection (`no_match`) in a single inference pass. The previous embedding-based matcher, entity grammars, and ONNX Runtime embedding engine are retained in the codebase (unused) as a fallback for future native-text ASR backends.

**Tech Stack:** sherpa-onnx (SenseVoice + bundled ONNX Runtime), whisper.cpp (built from source via CMake), LiteRT-LM (Google on-device inference, for FunctionGemma-270M). Optional: Qualcomm QNN / WhisperKit-Android for the NPU backend.

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

### Task 6: FunctionGemma intent router with LiteRT-LM

**Files to create:**
- `modules/services/voice/src/main/kotlin/.../intent/FunctionGemmaIntentRouter.kt` — implements `VoiceRecognizer`, builds prompt from transcript + tool schema JSON, calls LiteRT-LM, parses JSON response, maps to `VoiceIntent`.
- `modules/services/voice/src/main/kotlin/.../intent/ToolSchema.kt` — the 12-tool JSON schema as a constant plus a `ToolCall` data class and a JSON parser.

**Files to modify:**
- `modules/services/voice/build.gradle.kts` — add `com.google.ai.edge.litert:litert-lm`.
- `modules/services/voice/src/main/kotlin/.../di/VoiceControlModule.kt` — bind `FunctionGemmaIntentRouter` as the `VoiceRecognizer` implementation. `EmbeddingIntentMatcher` binding is removed (class kept, not bound).

**Existing files kept but unused (same pattern as `SherpaOnnxKwsDetector`):**
- `EmbeddingIntentMatcher.kt`, `EmbeddingEngine.kt`, `EmbeddingJni.kt`, `JniEmbeddingEngine.kt`, `BpeTokenizer.kt`, `TextTokenizer.kt`, `EditDistance.kt` — embedding pipeline retained for future native-text ASR backends.
- `GrammarEntityExtractor.kt`, `EnGrammar.kt`, `ZhGrammar.kt`, `LanguageGrammar.kt` — entity grammars retained for future native-text ASR backends.
- `EntityExtractor.kt`, `EntityResult` — interface and data class retained.

- [ ] **Step 1: Add LiteRT-LM dependency** — `implementation("com.google.ai.edge.litert:litert-lm:<ver>")` in `build.gradle.kts`.

- [ ] **Step 2: `ToolSchema.kt`** — define the 12-tool JSON schema as a compile-time constant. Define `ToolCall(name: String, arguments: Map<String, JsonElement>)` data class and a parser that extracts the first tool call from the model's JSON response.

- [ ] **Step 3: `FunctionGemmaIntentRouter`** implementing `VoiceRecognizer`:

```kotlin
@Singleton
class FunctionGemmaIntentRouter @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecognizer {

    private var model: LiteRtModel? = null
    private var initialized = false

    override suspend fun ensureReady(): Result<Unit> {
        if (initialized) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            try {
                val modelFile = File(context.filesDir, "functiongemma-model/model.litertlm")
                model = LiteRtModel.load(modelFile.absolutePath)
                initialized = true
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun recognize(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoiceIntent? = withContext(Dispatchers.IO) {
        if (!initialized || transcript.isBlank()) return@withContext null

        val prompt = buildPrompt(transcript, TOOL_SCHEMA_JSON)
        val response = model!!.generate(prompt, maxTokens = 128)
        val toolCall = parseToolCall(response) ?: return@withContext null
        mapToIntent(toolCall)
    }

    private fun buildPrompt(transcript: String, tools: String): String {
        // FunctionGemma prompt format: query + tools
        return "<start_of_turn>user
$transcript

Tools:
$tools<end_of_turn>
<start_of_turn>model
"
    }

    private fun parseToolCall(response: String): ToolCall? { /* JSON extraction */ }

    private fun mapToIntent(call: ToolCall): VoiceIntent? = when (call.name) {
        "pause" -> VoiceIntent.Pause
        "resume" -> VoiceIntent.Resume
        "seek_relative" -> { /* extract delta_seconds → SeekRelative */ }
        "set_speed" -> { /* extract speed or delta → SetSpeed or AdjustSpeed */ }
        "set_volume" -> { /* extract volume or delta → SetVolume or AdjustVolume */ }
        "sleep_timer" -> { /* extract minutes → SleepTimer */ }
        "set_trim" -> { /* extract mode → SetTrimMode */ }
        "seek_absolute" -> { /* extract position_seconds → SeekAbsolute */ }
        "next_chapter" -> VoiceIntent.NextChapter
        "previous_chapter" -> VoiceIntent.PreviousChapter
        "next_episode" -> VoiceIntent.NextEpisode
        "no_match" -> null
        else -> null
    }
}
```

- [ ] **Step 4: DI wiring** — in `VoiceControlModule.kt`, change the `VoiceRecognizer` binding: `@Binds abstract fun bindVoiceRecognizer(impl: FunctionGemmaIntentRouter): VoiceRecognizer`. Remove the `EmbeddingIntentMatcher` binding. Keep the `EntityExtractor` binding but it is no longer used by the active `VoiceRecognizer`.

- [ ] **Step 5: Model download** — add FunctionGemma model to `ModelManager`: resumable HTTP download of the finetuned `.litertlm` model + tokenizer into `filesDir/functiongemma-model/`. SHA-256 verification, atomic rename. The `ModelsReady` gate condition blocks listening until the model is downloaded and loaded.

- [ ] **Step 6: Fine-tuning** — produce the finetuned model:
  1. Write 50-100 seed examples covering all 12 tools + `no_match` (podcast bleed, ambient speech, questions, non-command utterances).
  2. Expand to 2,000-5,000 synthetic examples via Google's FunctionGemma data pipeline.
  3. Fine-tune on M2 Mac via MLX-LM with LoRA rank 8 (15 min) or free Colab TPU (5 min).
  4. Evaluate on held-out 20%: target >95% tool accuracy, >90% argument accuracy, >98% rejection.
  5. Convert SafeTensors to LiteRT `.litertlm` format via Google's conversion tool.
  6. Host the model file and tokenizer alongside existing model downloads.

### Task 7: Entity extraction — retained, not wired

The entity extraction layer (`GrammarEntityExtractor`, `EnGrammar`, `ZhGrammar`, `LanguageGrammar`, `EntityExtractor`, `EntityResult`) is **already implemented** from the original embedding-based pipeline. In the FunctionGemma architecture, entity extraction is handled by the model itself — the mapper in `FunctionGemmaIntentRouter` reads typed arguments directly from the JSON tool call output.

**No changes needed.** The entity extraction code is retained in the codebase, not bound in DI for the default Whisper-English path. It serves as a fallback if a future native-text ASR backend (SenseVoice) is activated.

- [ ] **Step 1: Verify compilation** — confirm existing entity extraction files compile without changes.

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
- [ ] **Unit tests** `./gradlew :modules:services:voice:testDebugUnitTest` — `AsrBackendSelectorTest`, `FunctionGemmaIntentRouterTest` (mapper coverage, `no_match` → null, parameter clamping), `ToolSchemaTest` (valid JSON, all 12 tools map to `VoiceIntent` variants), existing `EntityExtractorTest` / `EntityNormalizerTest` (retained, still pass), `WakeWordDetectorTest`.
- [ ] **Spotless** `./gradlew spotlessApply`
- [ ] **Intent router instrumentation**: evaluate the finetuned FunctionGemma model on a held-out test set — tool accuracy >95%, argument accuracy >90%, rejection >98%.
- [ ] **Integration smoke (device)**: "fast forward 30 seconds" (en) → `SeekRelative(30000)`; "快进半分钟" (zh) → `SeekRelative(30000)`; "3x speed" → `SetSpeed(3.0)`; "go back a minute" → `SeekRelative(-60000)`; podcast bleed → null (`no_match`); "what time is it" → null; in wake-word mode a bare command is ignored until preceded by "Auris".

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
| FunctionGemma-270M (finetuned) | LiteRT INT8 | ~270 MB |
| whisper.cpp model (`ggml-small-q5_1`, baseline) | GGML quantized | ~190 MB |
| whisper.cpp model (`ggml-base-q5_1`, fallback only) | GGML quantized | ~57 MB |
| SenseVoice-Small (optional) | ONNX int8 | ~228 MB |
| openWakeWord mel spectrogram (bundled) | ONNX | ~100 KB |
| openWakeWord speech embedding (bundled) | ONNX | ~1.3 MB |
| openWakeWord "Auris" classifier (bundled, trained) | ONNX | ~160 KB |
| Embedding matcher + tokenizer + entity engine | ONNX + JSON + Kotlin (retained, unused) | ~134 MB |

Total downloaded (baseline): whisper.cpp (~190 MB) + FunctionGemma (~270 MB) = **~460 MB**. The embedding model (~118 MB) and tokenizer (~16 MB) remain in the codebase but are not downloaded unless a native-text ASR backend is activated.

`ggml-small-q5_1` is the baseline because the translate-by-default path needs reliable translation, which `base` is markedly weaker at on short commands and bare numbers; `base-q5_1` is a fallback only if validation shows it is adequate and `small` misses the latency target. Only the selected ASR backend's model and the FunctionGemma model are downloaded at runtime. The wake word models are always bundled.
