# Recognition Pipeline

## Problem

On-device voice control for podcast playback turns short spoken commands ("skip forward 30 seconds", "play at 1.5x", "快进半分钟") into playback actions. Two requirements pull against each other:

1. **Broad language coverage with good English-accent accuracy** — the product targets a global audience, so the pipeline must handle major world languages, not a single locale.
2. **Fast and reliable on normal-to-high-end Android** — commands are short and must feel instant on mid-range hardware, without a network round-trip.

No single on-device ASR model is best on both axes at once. CTC models like SenseVoice-Small are fast and accurate but cover only CJK + English. Whisper covers ~99 languages and can translate to English but is autoregressive and slower. NPU-accelerated Whisper is dramatically faster but only on specific Snapdragon hardware. The design therefore uses a **multi-backend ASR layer** behind one interface, selecting the best backend for the device and language at startup.

Downstream of ASR, a finetuned FunctionGemma-270M model running on LiteRT-LM handles intent classification, argument extraction, and rejection in a single inference pass. This keeps the command vocabulary closed and typed while leveraging a model purpose-built for tool calling.

## Architecture

```
OboeAudioCapture        mic capture (native)
       │
       ▼
NativeVadSegmenter      utterance segmentation (Silero VAD)
       │
       ▼
SignalFilter            drop podcast bleed (playback cross-correlation)
       │
       ▼
WakeWordDetector        mode-dependent gate; opens the command window
       │
       ▼
VoiceAsrEngine ── selects ──► AsrBackendSelector ──► one of:
       │                         WhisperCppBackend  (broad, baseline)
       │ transcript              SenseVoiceBackend  (CJK + en, optional accel.)
       │                         WhisperNpuBackend  (Snapdragon, optional)
       ▼
FunctionGemmaIntentRouter  finetuned FunctionGemma-270M via LiteRT-LM:
                           intent classification + entity extraction
                           + rejection (no_match) in one inference pass
       │
       ▼
VoiceIntentExecutor ──► PlaybackManager
```

1. **Audio capture + VAD** — Oboe captures microphone audio; a native Silero VAD segments it into discrete utterances. On end-of-speech, a complete utterance (mono PCM) is produced.
2. **Signal filter** — Cross-correlation of the utterance against the playback buffer rejects podcast audio bleed *before* any transcription, so ASR compute is never spent on bleed.
3. **Wake-word gate** — In wake-word mode, a lightweight keyword spotter must detect the wake word before utterances flow downstream; detection opens a command window that stays open for follow-up commands until the conversation lapses into silence. In continuous mode the gate is open. The mode is set by the core spec's `ListeningModePolicy`.
4. **ASR** — `VoiceAsrEngine` passes the surviving utterance to the selected `AsrBackend`, which returns a transcript and (where available) a detected language. Whisper backends translate to English by default; SenseVoice returns native text.
5. **Intent routing** — A finetuned FunctionGemma-270M model running on LiteRT-LM classifies the transcript and extracts typed arguments in one inference pass. A `no_match` tool in the schema provides explicit rejection for non-commands. The model is finetuned on podcast-control examples; the embedding matcher and entity grammars remain in the codebase (unused) as a fallback for future native-text ASR backends.
7. **Execution** — The assembled `VoiceIntent` is dispatched to `PlaybackManager`.

## Signal Filter

One check runs against each segmented utterance immediately after VAD, before ASR:

- **Playback cross-correlation** — cross-correlate the mic utterance against the playback buffer. High correlation means podcast bleed, so the utterance is dropped before it reaches the ASR backend.

**Scope: the built-in loudspeaker route only.** Cross-correlation needs the mic signal aligned to the playback buffer within a known delay window, which only holds for the device's own speaker (bounded local latency, and the playback buffer matches what is emitted). On A2DP to an *external* speaker the codec latency is large and variable and the remote acoustic timing is neither known nor controlled, so alignment is unreliable; there the filter is not applied. The filter is also off on headset (`Isolated`) routes, where there is no bleed.

This filter is a backstop, not the primary defense. In every `Exposed` context the wake word gates commands and the core spec's Android AEC + noise suppression already attenuate echo; cross-correlation only catches residual bleed that AEC misses on the built-in speaker.

## Wake Word Detection

The pipeline runs in one of two listening modes, set by the core spec's `ListeningModePolicy` (see
[Voice Control Core](voice-control-core.md)): **continuous** (the wake-word gate is open, every utterance is a candidate command)
or **wake-word required** (utterances are gated until the wake word is detected). This section owns *how* the wake word is detected;
the core spec owns *when* each mode applies.

### Detector placement and command window

A `WakeWordDetector` runs on each VAD segment that survives the signal filter, **before** ASR. It is deliberately lightweight so that
in wake-word mode — used in echo-prone contexts like speaker playback — the heavy ASR + intent stages stay idle until the wake word
fires:

- On a **negative** segment, the segment is dropped; ASR never runs.
- On a **positive** segment, a **command window** opens. While the window is open the mode behaves like continuous: each subsequent
  utterance flows to ASR → intent, with no wake word required for follow-up commands. The window stays open as long as the
  conversation continues — it is refreshed by any speech activity (a VAD segment), not only by accepted commands — and closes only
  after a continuous silence gap exceeding the conversation timeout (default **10 seconds**). After it closes, the wake word is
  required again.
- A combined "Auris, skip forward" utterance is supported: when the wake word is detected at the start of a segment, the remainder of
  that same segment is forwarded to ASR rather than discarded.

### Model

Wake word detection uses an **openWakeWord Conv-Attention classifier** trained via [livekit-wakeword](https://github.com/livekit/livekit-wakeword) and exported to ONNX. Inference runs directly on `onnxruntime-android` — the same runtime already used for Silero VAD and the embedding model — through a 3-stage ONNX pipeline bundled in the app:

1. **Mel spectrogram** (`oww/melspectrogram.onnx`, ~100 KB) — converts raw 16kHz PCM to 32 mel-filterbank frames.
2. **Speech embedding** (`oww/embedding_model.onnx`, ~1.3 MB) — a frozen Google speech-embedding backbone that maps 76 mel frames to a 96-dim vector every 80ms.
3. **Wake word classifier** (`oww/auris.onnx`, ~160 KB) — a Conv-Attention head trained to detect "Auris" over a 16-embedding (1,280ms) temporal window, outputting a single sigmoid score in [0,1].

All three models are **bundled in the app** (not downloaded). Inference is stateful: mel frames and embeddings accumulate in rolling buffers, and the classifier runs on each new embedding. A score above the threshold (default 0.5) triggers detection. The mel spectrogram and embedding models are fixed for all wake words; only the classifier model is wake-word-specific.

The 3-stage pipeline runs in a single C++ JNI module (`WakeWordJni.cpp`) linked into the existing `pocketcasts_voice_capture` shared library, following the same `dlsym`-based ONNX Runtime loading pattern used by `VadJni.cpp` and `EmbeddingJni.cpp`.

### Built-in keyword ("Auris")

The "Auris" classifier is trained with livekit-wakeword on synthetic TTS data, then exported to ONNX and bundled at `assets/oww/auris.onnx`. Training is reproducible from the livekit-wakeword config; retraining for a different wake word follows the same pipeline.

The bundled "Auris" model is the default. The wake word detector can be swapped by changing the DI binding — the existing `SherpaOnnxKwsDetector` is kept in the codebase (unused) as a fallback if needed.

## ASR Backend Layer

### AsrBackend interface

All backends implement one interface, so everything downstream of the transcript is backend-agnostic:

```kotlin
interface AsrBackend {
    /** Download (if needed) and initialize the backend's model. */
    suspend fun ensureReady(): Result<Unit>

    /** Transcribe one complete utterance. samples are mono float PCM in [-1, 1]. */
    suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult

    /** Which model files this backend needs, for the downloader. */
    val requiredModel: ModelSpec

    /** Languages, translation ability, and hardware needs. */
    val capabilities: AsrCapabilities

    fun release()
}

data class AsrResult(
    val text: String,
    val detectedLanguage: String?,   // ISO code if the backend reports one
)

data class AsrCapabilities(
    val languages: Set<String>,       // ISO codes; empty = broad/all
    val canTranslateToEnglish: Boolean,
    val requiresSnapdragon: Boolean,
)

data class ModelSpec(
    val id: String,                   // "sensevoice" | "whisper-cpp" | "whisper-npu"
    val files: List<ModelFile>,       // url + filename + optional sha256
    val targetDir: String,            // subdir under filesDir
)
```

`transcribe` takes `FloatArray` because both candidate runtimes (sherpa-onnx, whisper.cpp) consume float PCM. `VoiceAsrEngine` converts captured 16-bit shorts to float (`/ 32768f`) before the call.

### WhisperCppBackend *(baseline)*

whisper.cpp (GGML) running the **multilingual `ggml-small-q5_1`** model (~190 MB). Broad coverage (~99 languages) and able to translate to English. This is the **universal baseline**: it works on any device and any language, runs on GGML (no ONNX Runtime), and is the only backend required day one. `small` is chosen over the smaller `base` because the translate-by-default path needs reliable translation, and `base`'s translation is markedly weaker on short commands and bare numbers; `base-q5_1` (~57 MB) is a fallback only if the translate-quality validation (see Translate vs. transcribe) shows it is adequate and `small` misses the latency target. `large-v3-turbo` is excluded — too large/slow for mid-range mobile. Configured for low short-command latency:

- Quantized weights.
- Reduced `audio_ctx` (~384) so the encoder does not process a full 30s mel window for a 2–4s command.
- Single encode pass — language detection happens inside the one `whisper_full` call, not a separate pre-pass.
- Thread count scaled to available cores.
- Greedy sampling; no cross-utterance context.

### SenseVoiceBackend *(optional acceleration)*

sherpa-onnx `OfflineRecognizer` running SenseVoice-Small (int8, ~228 MB). CTC, non-autoregressive, very fast, with built-in language identification across **zh, en, ja, ko, yue**. No translation. ONNX Runtime ships inside the sherpa-onnx AAR.

**Optional, gated on measured need.** SenseVoice exists only to accelerate the CJK/English majority if the whisper.cpp baseline misses the latency target on mid-range hardware. It is not a day-one requirement: it adds a second native stack, a 228 MB model, and the dual-ORT packaging constraint (see Dependencies). Ship it only after measurement shows whisper.cpp is too slow for those languages.

### WhisperNpuBackend *(optional, last phase)*

Qualcomm QNN / WhisperKit-Android, NPU-accelerated (~45s audio/sec on Snapdragon), broad languages. Snapdragon-only: requires the QNN SDK and per-SoC binaries, gated behind a device probe and shipped only where it runs. **Optional** — the pipeline is fully functional on whisper.cpp alone. It is defined here so the interface and selector account for it, but it is the last backend to build and may be deferred.

### AsrBackendSelector

Backend selection runs once at startup, from a hardware probe (Snapdragon NPU available?) and the OS default locale. whisper.cpp is the default; the optional backends are chosen only when they are shipped *and* applicable:

| Condition | Selected backend |
|---|---|
| Snapdragon + NPU available **and** NPU backend shipped | `WhisperNpuBackend` |
| Otherwise, SenseVoice shipped **and** OS language ∈ {zh, en, ja, ko, yue} | `SenseVoiceBackend` |
| Otherwise (default) | `WhisperCppBackend` |

Priority when several apply: **NPU > SenseVoice > whisper.cpp**. With neither optional backend shipped, the selector always resolves to whisper.cpp.

**Locale is a hint, not a filter.** The OS locale is the user's default language, not a guarantee of what they speak. So:

- Backends always run in **auto language-detect** mode (SenseVoice LID; Whisper detection). The locale only chooses *which backend loads*.
- A user with a CJK/English locale who speaks French would load SenseVoice, which cannot transcribe French. A **manual backend/language override** setting lets such users force whisper.cpp.

### VoiceAsrEngine

`VoiceAsrEngine` owns the capture → VAD → segment loop and dispatches each completed utterance to the selected backend. It holds the Bluetooth SCO handling and the playback-bleed buffer. On end-of-speech it runs the signal filter against the segment; surviving segments are converted shorts → float and passed to `backend.transcribe(...)`, and the resulting transcript is forwarded to the recognizer. Running the filter first means bleed never reaches the backend. The engine has no model-architecture knobs — those live inside each backend.

### Translate vs. transcribe

Whisper backends can emit native-language text (**transcribe**) or English (**translate**). Since whisper.cpp is the default baseline, the default is **translate** on Whisper backends (whisper.cpp and the NPU backend): every command arrives as English text. This is the central simplification of the pipeline:

- **Entity grammars collapse to one.** Numbers, durations, and ordinals arrive already in English ("half a minute", "one hour twenty"), so a single English grammar serves every language instead of one grammar per language — removing the laborious, hard-to-curate per-language entity work.
- **Intent routing becomes monolingual** (English → English), which is more reliable than cross-lingual matching. FunctionGemma is finetuned on English podcast-control examples.

SenseVoice cannot translate. When the optional SenseVoice backend is in use, its CJK/English output stays native. For this path the embedding matcher and entity grammars retained in the codebase serve as a fallback; the preferred long-term approach is finetuning a multilingual FunctionGemma variant when that need arises.

**The make-or-break risk is Whisper's translation quality on short commands and bare numbers.** Whisper's translate task is trained on longer-form audio; on 2–4s commands it can mistranslate the exact number or unit the entity layer needs, especially for lower-resource languages. Translate-by-default is **gated on validating this** before implementation. If validation fails, the documented fallback is transcribe + per-language entity grammars.

## Intent Router (FunctionGemma)

A finetuned **FunctionGemma-270M** model runs on **LiteRT-LM** (Google's on-device inference runtime) to classify the transcript and extract typed arguments in a single forward pass. Unlike the previous embedding-based matcher (which always picked the closest intent), FunctionGemma is trained on a tool schema that includes an explicit `no_match` tool — so non-commands are rejected rather than forced into the nearest intent.

**Why FunctionGemma:**
- **Accuracy**: Fine-tuned on domain-specific data, FunctionGemma-270M achieves 96.7% tool-selection accuracy on smart-home tasks (Distil-Labs benchmark) — a near-identical problem to podcast playback control. This exceeds the 120B teacher model used for distillation.
- **Unified intent + entities**: A single inference pass outputs both the selected tool and its typed arguments. No separate regex-based entity extraction stage. "3x speed" → `{"name":"set_speed","arguments":{"speed":3.0}}`. "go back a minute" → `{"name":"seek_relative","arguments":{"delta_seconds":-60}}`.
- **Rejection**: The `no_match` tool is a first-class member of the tool schema. The model is explicitly trained to select it for ambient speech, questions, podcast bleed, and other non-commands. The mapper returns `null` for `no_match`.
- **Speed**: LiteRT-LM with GPU/NPU acceleration achieves ~125-153 tok/s decode on phone CPUs, ~200ms full response — well within the sub-second latency target.
- **Size**: ~270MB INT8 quantized via LiteRT, downloaded on first launch (not bundled in the APK).

**Tool schema (12 tools):**

Directional intent pairs are unified into parameterized tools where direction is an argument sign (`+` = forward/louder/faster, `−` = backward/quieter/slower). This reduces the schema from 17 to 12 tools:

```json
[
  {"name": "pause", "description": "Pause playback."},
  {"name": "resume", "description": "Resume playback."},
  {"name": "seek_relative", "description": "Skip forward or backward by a duration.",
   "parameters": {"delta_seconds": {"type": "integer", "description": "Positive = forward, negative = backward."}}},
  {"name": "seek_absolute", "description": "Jump to a specific time position.",
   "parameters": {"position_seconds": {"type": "integer"}}},
  {"name": "next_chapter", "description": "Skip to the next chapter."},
  {"name": "previous_chapter", "description": "Skip to the previous chapter."},
  {"name": "next_episode", "description": "Play the next episode."},
  {"name": "set_speed", "description": "Set playback speed exactly or by a delta.",
   "parameters": {"speed": {"type": "number"}, "delta": {"type": "number"}}},
  {"name": "set_volume", "description": "Set volume level or adjust by a delta.",
   "parameters": {"volume": {"type": "integer"}, "delta": {"type": "integer"}}},
  {"name": "sleep_timer", "description": "Set a sleep timer.",
   "parameters": {"minutes": {"type": "integer"}}},
  {"name": "set_trim", "description": "Set silence trimming mode.",
   "parameters": {"mode": {"type": "string", "enum": ["off", "low", "medium", "high"]}}},
  {"name": "no_match", "description": "No command was recognized. Select this when the user is not issuing a playback command."}
]
```

**Mapper** — a thin Kotlin layer maps FunctionGemma output to the existing `VoiceIntent` sealed types. `VoiceIntentExecutor` is unchanged.

**Fine-tuning:** The base FunctionGemma-270M is finetuned on 50-100 seed examples expanded to 2,000-5,000 synthetic examples via Google's data pipeline. Training runs on an M2 Mac via MLX-LM (~15 min with LoRA rank 8) or free Colab TPU (~5 min). The finetuned checkpoint is converted to LiteRT `.litertlm` format and hosted for download. Evaluation on a held-out set targets >95% tool accuracy, >90% argument accuracy, >98% rejection on non-commands.

**Inference runtime:** LiteRT-LM provides the official Android SDK with GPU (OpenCL/Vulkan) and NPU (QNN for Snapdragon, MediaTek APU) acceleration. The runtime is 3-7× faster than llama.cpp GGUF on the same hardware, with lower battery drain. Used in production by Chrome, Gboard, and Pixel Watch.

**Existing code retained but unused:** The `EmbeddingIntentMatcher`, `GrammarEntityExtractor`, `EnGrammar`, `ZhGrammar`, and ONNX Runtime embedding engine remain in the codebase. They are not bound in DI for the default Whisper-English path but are available as a fallback if a future native-text ASR backend (SenseVoice) is activated. Same pattern as `SherpaOnnxKwsDetector` — kept, not wired.

## Intent Schema

The pipeline's output is a validated `VoiceIntent`. The intent set, parameters, and value ranges are owned by the
[Voice Intents spec](voice-intents.md) and are not restated here. The matcher selects one of those intent types; the
extractor and normalizer fill its typed slots.

## Model Management

The downloader handles resumable HTTP download with retry, SHA-256 verification, and atomic `.tmp` → rename. It fetches **only the selected ASR backend's model** plus the intent router model:

- whisper.cpp (baseline): the `ggml-small-q5_1.bin` model, into `filesDir/whisper-model/`.
- SenseVoice (optional acceleration): model + tokens (sherpa-onnx assets), into `filesDir/sensevoice-model/`.
- whisper-npu (optional phase): QNN context binaries.
- Intent router: finetuned FunctionGemma-270M INT8 `.litertlm` model + tokenizer, into `filesDir/functiongemma-model/`. Downloaded on first launch; the embedding model, ONNX Runtime, and entity grammars remain in the codebase (unused) as a fallback for future native-text ASR backends.
- Wake word: openWakeWord 3-stage ONNX pipeline (~1.6 MB total: mel spectrogram ~100 KB, embedding ~1.3 MB, "Auris" classifier ~160 KB) **bundled in the app** (not downloaded), so the wake word always ships with the binary. The sherpa-onnx KWS detector is retained in the codebase (unused) for easy fallback.

## Language Coverage

- **ASR**: whisper.cpp is the baseline and covers ~99 languages on any device. The optional SenseVoice backend covers zh/en/ja/ko/yue (an acceleration for those languages); the optional NPU backend matches Whisper's coverage. The selector ensures a device gets a backend that can handle its locale, with a manual override for mismatches.
- **Intent routing**: by default the transcript is English (Whisper translation), so FunctionGemma operates monolingually. The model is finetuned on English podcast-control examples; its underlying Gemma 3 base is English-primary. For a future native-text SenseVoice path, the embedding matcher and entity grammars are retained in the codebase as a fallback.
- **ASR error recovery**: FunctionGemma-270M with 32K context naturally handles ASR errors and paraphrases — it was distilled from Gemini 3.1 and trained on varied synthetic data. The finetuning dataset includes ASR-typical errors (homophones, dropped words) to ensure robustness.

## Component Map

| Component | Responsibility |
|---|---|
| `OboeAudioCapture` (native) | Microphone capture |
| `NativeVadSegmenter` (native Silero VAD) | Utterance segmentation |
| `VoiceAsrEngine` | Capture/VAD loop, backend dispatch, SCO + filter |
| `AsrBackend` (+ 3 impls) | Transcribe an utterance |
| `AsrBackendSelector` | Pick backend by hardware + locale |
| `SignalFilter` | Playback cross-correlation |
| `WakeWordDetector` | Per-segment wake word spotting via openWakeWord 3-stage ONNX pipeline; gates ASR in wake-word mode. Bundled "Auris" classifier trained via livekit-wakeword. sherpa-onnx KWS detector retained in codebase (unused) for easy switching |
| `FunctionGemmaIntentRouter` | Finetuned FunctionGemma-270M on LiteRT-LM: intent classification + entity extraction + rejection in one pass |
| `EmbeddingIntentMatcher` | Retained in codebase (unused): embedding classification + edit-distance fallback for future native-text ASR backends |
| `GrammarEntityExtractor` | Retained in codebase (unused): rule-based slot extraction for future native-text ASR backends |
| `ModelManager` | Download selected ASR backend model + FunctionGemma model |
| `VoiceIntentExecutor` | Map intents to `PlaybackManager` actions |

### Dependencies

```kotlin
// Intent router — LiteRT-LM for FunctionGemma-270M inference
implementation("com.google.ai.edge.litert:litert-lm:<ver>")

// Embedding intent matcher (retained, unused) — standalone ONNX Runtime
implementation("com.microsoft.onnxruntime:onnxruntime-android:<ver>")

// SenseVoice backend — bundles its own ONNX Runtime
implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:<ver>")

// whisper.cpp backend — built from source via CMake in the voice module
// NPU backend (optional) — Qualcomm QNN / WhisperKit-Android, Snapdragon-gated
// Wake word — openWakeWord 3-stage ONNX pipeline (bundled), runs on the same onnxruntime as VAD
//             sherpa-onnx KWS detector retained in codebase (unused) for easy switching
```

The embedding tokenizer is a pure-Kotlin BPE parser over HuggingFace `tokenizer.json` (retained, unused). The entity engine is pure Kotlin/JVM with no native deps (retained, unused). FunctionGemma uses LiteRT's built-in tokenizer. Every bundled `.so` must keep 16 KB page-size alignment.

**Shared ONNX Runtime constraint.** sherpa-onnx and the standalone `onnxruntime-android` each ship `lib/<abi>/libonnxruntime.so`. Two AARs cannot place the same native-lib path in one APK, so when the SenseVoice backend is present both must pin the **same** ORT version and packaging must keep a single copy (`pickFirst 'lib/**/libonnxruntime.so'`), re-verified on every dependency bump. This cost exists only because SenseVoice runs on ORT; whisper.cpp (GGML) and the standalone-ORT components (embedding matcher, wake word, VAD) do not collide. If SenseVoice is dropped, the constraint disappears.

## Error Handling

| Condition | Behavior |
|---|---|
| Selected backend's model download fails | Fall back to `WhisperCppBackend`; if that also fails, stop service with a notification |
| Backend `ensureReady` fails | Same fallback chain; non-fatal notification |
| ASR returns empty transcript | No processing |
| Backend gets a language it cannot handle (e.g. SenseVoice + French) | Best-effort text; user can switch via override |
| NPU unavailable at runtime despite Snapdragon probe | Selector falls through to SenseVoice / whisper.cpp |
| Utterance fails cross-correlation | Dropped silently |
| In wake-word mode, segment without the wake word | Dropped before ASR; no further processing |
| Wake-word detection cannot run in a WakeWord-mode context | Listening is treated as `Off` (mic stays closed); never fall back to continuous, so an exposed mic can never bypass the wake word |
| Wake word model fails to load | Detector reports `isReady = false`; `ListeningModePolicy` treats wake-word mode as unavailable and falls back to `Off` |
| FunctionGemma model not yet downloaded | Listening is blocked by `ModelsReady` gate; no audio is captured until download completes |
| Inference returns malformed JSON | No intent (null) |
| `no_match` tool selected | No intent (null) — explicit rejection |
| Tool name not in mapper | No intent (null), log warning |
| Missing required parameter in tool call | No intent (null) |
| Parameter out of range (speed > 5.0, etc.) | Clamp to valid range |
| Model not yet downloaded | Queue utterance, process once ready |

## Testing

- **Unit**: `AsrBackendSelectorTest` — selection matrix across (Snapdragon?, NPU?, locale), including NPU-not-shipped. `FunctionGemmaIntentRouterTest` — mapper coverage for all 12 tools, correct `null` for `no_match`, correct clamping for out-of-range parameters, correct handling of malformed JSON. `ToolSchemaTest` — schema validates as JSON, every tool maps to a `VoiceIntent` variant.
- **Backend (instrumentation, real model)**: `WhisperCppBackendTest`, `SenseVoiceBackendTest` — fixed WAV in, assert non-empty transcript and detected language.
- **Wake word**: `WakeWordDetectorTest` — bundled "Auris" classifier fires above threshold on TTS-generated "Auris" clips, non-wake speech does not fire; combined "Auris, skip forward" forwards the command remainder to ASR. Command-window behavior: opens on detection, stays open across follow-up utterances (no re-trigger) while speech continues, and closes only after a silence gap exceeding the conversation timeout (default 10s).
- **Intent router (instrumentation)**: `FunctionGemmaIntentRouterTest` — evaluate the finetuned model on a held-out test set: tool-selection accuracy, argument-extraction accuracy, rejection accuracy on non-commands. Target >95% tool accuracy, >90% argument accuracy, >98% rejection.
- **Integration**: pre-recorded WAV clips per language. "fast forward 30 seconds" (en) and "快进半分钟" (zh) both reach `SeekRelative(30000)` regardless of which backend produced the transcript. "3x speed" → `SetSpeed(3.0)`. Podcast bleed → `no_match` (null). In wake-word mode, a bare command is ignored until preceded by "Auris".
- **Performance**: short-command latency (2–4s utterance) on a mid-range device. Intent-router latency ≤200ms inclusive of tokenization + inference + JSON parse. Confirming whisper.cpp `audio_ctx`/quantization/single-encode tuning.
- **Regression**: existing `VoiceIntentExecutor` and gate-rule tests pass. `ModelsReady` gate blocks until FunctionGemma model is downloaded and loaded.

## Open Risks

- ASR inference latency on mobile hardware is device-dependent and needs ongoing measurement against the sub-second target. Whether the whisper.cpp baseline meets that target for CJK/English decides whether the optional SenseVoice backend is built at all.
- Playback cross-correlation depends on aligning the mic signal to the playback buffer; this is tractable only for the built-in speaker. A2DP-to-external-speaker bleed is left to the wake word and Android AEC, and the residual yield of the filter over AEC needs measurement.
- Keeping the backend and intent-matcher runtimes warm may be too memory intensive on lower-end devices, requiring on-demand loading.
- Multilingual natural commands may need language-specific evaluation data, especially to validate Whisper translation quality per language and the native SenseVoice-path grammars.
- FunctionGemma is English-primary. If a future native-text ASR backend (SenseVoice) is activated, the embedding matcher and entity grammars retained in the codebase serve as a fallback — but they have the known false-positive and parameter-capture weaknesses documented in earlier revisions of this spec. Finetuning a multilingual FunctionGemma variant is the preferred path when that need arises.
- Fine-tuning quality depends on seed example coverage. Gaps in the seed data (missing phrasings, edge cases) propagate to the synthetic training set. The evaluation hold-out set must include deliberately unusual phrasings to catch this.
- LiteRT-LM is a newer runtime than ONNX. While it ships in Chrome and Gboard, the FunctionGemma-270M model on LiteRT-LM is a less-proven combination than the whisper.cpp + ONNX stack. Instrumentation testing on target hardware is required before release.
