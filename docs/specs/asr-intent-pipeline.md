# ASR Intent Pipeline

## Problem

On-device voice control for podcast playback turns short spoken commands ("skip forward 30 seconds", "play at 1.5x", "快进半分钟") into playback actions. Two requirements pull against each other:

1. **Broad language coverage with good English-accent accuracy** — the product targets a global audience, so the pipeline must handle major world languages, not a single locale.
2. **Fast and reliable on normal-to-high-end Android** — commands are short and must feel instant on mid-range hardware, without a network round-trip.

No single on-device ASR model is best on both axes at once. CTC models like SenseVoice-Small are fast and accurate but cover only CJK + English. Whisper covers ~99 languages and can translate to English but is autoregressive and slower. NPU-accelerated Whisper is dramatically faster but only on specific Snapdragon hardware. The design therefore uses a **multi-backend ASR layer** behind one interface, selecting the best backend for the device and language at startup.

Downstream of ASR, intent parsing is decomposed into three focused, language-agnostic stages that run on CPU: semantic intent matching, entity extraction, and entity normalization. This keeps the command vocabulary closed and typed without a heavyweight language model.

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
IntentMatcher           embedding classification + edit-distance fallback
       │
       ▼
EntityExtractor         rule grammars: extract typed slots
       │
       ▼
EntityNormalizer        rule grammars: normalize to typed values
       │
       ▼
VoiceIntentExecutor ──► PlaybackManager
```

1. **Audio capture + VAD** — Oboe captures microphone audio; a native Silero VAD segments it into discrete utterances. On end-of-speech, a complete utterance (mono PCM) is produced.
2. **Signal filter** — Cross-correlation of the utterance against the playback buffer rejects podcast audio bleed *before* any transcription, so ASR compute is never spent on bleed.
3. **Wake-word gate** — In wake-word mode, a lightweight keyword spotter must detect the wake word before utterances flow downstream; detection opens a command window that stays open for follow-up commands until the conversation lapses into silence. In continuous mode the gate is open. The mode is set by the core spec's `ListeningModePolicy`.
4. **ASR** — `VoiceAsrEngine` passes the surviving utterance to the selected `AsrBackend`, which returns a transcript and (where available) a detected language. Whisper backends translate to English by default; SenseVoice returns native text.
5. **Intent matching** — A multilingual embedding model classifies the transcript against a closed set of intents; an edit-distance fallback recovers from phonetic ASR errors.
6. **Entity extraction + normalization** — Rule-based grammars extract and normalize typed slots (durations, speeds, ordinals, etc.).
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

Wake word detection uses **sherpa-onnx Keyword Spotting**, a tiny ASR-based approach. It shares the ONNX Runtime stack already used for Silero VAD and (optionally) for the SenseVoice ASR backend. The model is `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile` (~17 MB extracted, English), an ONNX Zipformer with ~3.3M parameters running on CPU. It is bundled in the app (not downloaded), so detection always works regardless of network state.

### Built-in keyword ("Auris")

sherpa-onnx KWS accepts **custom keywords by text file — no training needed**. The wake word phrase is tokenized into the model's BPE units (`▁AUR IS`), given a boosting score and trigger threshold, and written to a `keywords.txt` that is bundled in assets alongside the model:

```
▁AUR IS :1.5 #0.35
```

At detection time the tiny ASR model runs beam search biased toward these token sequences; when the acoustic probability of a path containing the keyword exceeds the trigger threshold, the detector fires. This is the same mechanism as ASR hotword biasing — just with a much smaller model.

### Custom keyword (voice-sample enrollment)

Custom keywords use the **same sherpa-onnx model and the same text-file mechanism**. The user picks a phrase, it is tokenized and written to `keywords.txt` in app-private storage, and the detector reloads its keywords. No retraining, no TTS generation, no audio samples needed — the model is already trained to recognize English speech, and the text-based keyword file is sufficient.

Because the model is English-only, custom phrases must be English or English-like. Custom keyword enrollment is therefore a UX flow (choose phrase → tokenize → write keywords file → reload detector) rather than a machine-learning flow (record samples → train → deploy). Raw audio is never stored.

If no custom keyword is configured, the bundled "Auris" keywords file is used. If the custom keyword file fails to parse or the phrase cannot be tokenized, detection falls back to "Auris".

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
- **Intent matching becomes monolingual** (English → English), which is more reliable than cross-lingual matching, and the phonetic edit-distance fallback then helps every language, not just English.

SenseVoice cannot translate. When the optional SenseVoice backend is in use, its CJK/English output stays native, so that path keeps native-text entity grammars for its five languages. The multilingual embedding matcher is kept regardless of mode, so any text that arrives untranslated (a native-text backend, or a Whisper translation that falls back to native) is still classified.

**The make-or-break risk is Whisper's translation quality on short commands and bare numbers.** Whisper's translate task is trained on longer-form audio; on 2–4s commands it can mistranslate the exact number or unit the entity layer needs, especially for lower-resource languages. Translate-by-default is **gated on validating this** before implementation. If validation fails, the documented fallback is transcribe + per-language entity grammars.

## Intent Matcher

A multilingual embedding model (`multilingual-e5-small`, ONNX INT8, ~118 MB) encodes both the utterance and a fixed set of English intent keyword phrases into one vector space. Intent selection is a nearest-neighbor lookup over a small fixed vocabulary (~20 phrases). e5 requires input prefixes (`query:` / `passage:` per the model card); both the stored intent phrases and the per-utterance transcript must be embedded with the correct prefix, or similarity quality degrades.

**Pipeline:**
1. At init, embed all English intent keyword phrases once and store the vectors.
2. Per utterance, embed the transcript and compute cosine similarity against every stored intent vector.
3. If the top score clears the confidence threshold, select that intent.
4. If below threshold, compute normalized Levenshtein distance between the transcript and each English intent keyword phrase. A strong match selects that intent. It recovers ASR substitutions that change meaning but preserve phonetics ("pray" → "play"). Because the Whisper path is English, this net applies to every language on that path; on the native-text SenseVoice path it is effective only for English.
5. If neither matches confidently, return `null`.

**Why embedding + edit distance:**
- With Whisper translating to English by default, matching is monolingual (English → English), the most reliable case, and the edit-distance net then catches phonetic errors for *every* language, not just English.
- The embedding stays multilingual so any untranslated text — the SenseVoice path, or a Whisper translation that falls back to native — is still classified: "快进三十分钟" and "fast forward" sit close in a multilingual space.
- No per-language aliases, no prompt engineering, CPU-only.

The embedding model runs on its **own `onnxruntime-android` dependency**, independent of whichever ASR backend is active. No component relies on another loading ONNX Runtime first, so there is no start-order coupling between ASR and intent matching.

## Entity Extractor

A rule-based extractor pulls typed slots from the transcript. Because Whisper translates to English by default, the transcript is English and a **single English grammar** handles every language. A grammar module is added only for a backend that emits native text — the optional SenseVoice path (zh, en, ja, ko, yue). Each grammar implements a shared interface; the grammar is selected from the **script of the transcript text itself** (translated English → English grammar; native CJK → CJK grammar), *not* the ASR-detected source language, which under translation differs from the output language.

**Entity types:**
| Entity Type | Description | Example Spans (English; native only on SenseVoice path) | Used By |
|---|---|---|---|
| `duration` | A time duration | "30 seconds", "5 min", "半分钟" | seek_relative, sleep_timer |
| `position` | An absolute time position | "1 hour 30 min", "第15分钟" | seek_absolute |
| `speed` | A playback speed | "1.5", "2x", "两倍" | set_speed, adjust_speed |
| `volume` | A volume level or delta | "50", "ten", "up by 10" | set_volume, adjust_volume |
| `ordinal` | An ordinal number | "third", "3rd", "第3个" | chapter_by_index |
| `chapter_title` | A chapter name | "introduction", "总结" | chapter_by_title |
| `bookmark_title` | A bookmark label | "favorite part", "好地方" | add_bookmark |
| `trim_mode` | Silence trimming level | "off", "medium", "低" | set_trim |

Parameterless intents (pause, resume, next_chapter, previous_chapter, next_episode) skip extraction. Boolean intents (set_volume_boost) detect affirmative vs. negative language.

## Entity Normalizer

A rule-based engine converts extracted spans into typed, machine-readable values. Normalization is compositional, not statistical — "half a minute" decomposes to `0.5 × 60 = 30` — so deterministic grammars are debuggable and need no training data. Scope: durations, absolute times, cardinals, ordinals, speeds, volumes. The default path normalizes English (Whisper translation); the SenseVoice path additionally normalizes native CJK number/duration expressions.

| Input | Normalized Output | Slot |
|---|---|---|
| "half a minute" / "30 seconds" (or "半分钟" on SenseVoice path) | `30` (seconds) | delta_seconds |
| "1 hour 20 min" (or "1小时20分") | `4800` (seconds) | position_seconds |
| "double" / "2x" (or "两倍") | `2.0` | speed |
| "third" / "3rd" (or "第三") | `3` (0-indexed: 2) | chapter index |

The English grammar covers the default translated path; a native number/duration/ordinal grammar is added per language only for native-text backends (SenseVoice). The engine picks the grammar from the script of the transcript text, defaulting to English.

## Intent Schema

The pipeline's output is a validated `VoiceIntent`. The intent set, parameters, and value ranges are owned by the
[Playback Controls spec](playback-controls.md) and are not restated here. The matcher selects one of those intent types; the
extractor and normalizer fill its typed slots.

## Model Management

The downloader handles resumable HTTP download with retry, SHA-256 verification, and atomic `.tmp` → rename. It fetches **only the selected ASR backend's model** plus the embedding model:

- whisper.cpp (baseline): the `ggml-small-q5_1.bin` model, into `filesDir/whisper-model/`.
- SenseVoice (optional acceleration): model + tokens (sherpa-onnx assets), into `filesDir/sensevoice-model/`.
- whisper-npu (optional phase): QNN context binaries.
- Embedding: `model_opt2_QInt8.onnx` + `tokenizer.json`, into `filesDir/embedding-model/`.
- Wake word: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile` (~17 MB extracted, ONNX) + tokens are **bundled in the app** (not downloaded), so the wake word always ships with the binary. A bundled `keywords.txt` defines "Auris" with boosting score and threshold. A user's custom keyword is a separate `keywords.txt` written to app-private storage (a text file, not audio) and is deletable from settings.

## Language Coverage

- **ASR**: whisper.cpp is the baseline and covers ~99 languages on any device. The optional SenseVoice backend covers zh/en/ja/ko/yue (an acceleration for those languages); the optional NPU backend matches Whisper's coverage. The selector ensures a device gets a backend that can handle its locale, with a manual override for mismatches.
- **Intent matching**: by default the transcript is English (Whisper translation), so matching is monolingual English. The embedding stays multilingual so untranslated text (SenseVoice path, or a partial translation) still maps into the same space and matches the English intent keywords.
- **Entity extraction/normalization**: a single English grammar covers the default translated path; a native number/duration/ordinal grammar is added per language only for native-text backends (SenseVoice).

**ASR error recovery**: on the Whisper path everything is English, so edit distance catches phonetic errors for every language and the embedding handles semantic paraphrase. On the native SenseVoice path, the embedding is the primary cross-lingual signal and edit distance helps only English.

## Component Map

| Component | Responsibility |
|---|---|
| `OboeAudioCapture` (native) | Microphone capture |
| `NativeVadSegmenter` (native Silero VAD) | Utterance segmentation |
| `VoiceAsrEngine` | Capture/VAD loop, backend dispatch, SCO + filter |
| `AsrBackend` (+ 3 impls) | Transcribe an utterance |
| `AsrBackendSelector` | Pick backend by hardware + locale |
| `SignalFilter` | Playback cross-correlation |
| `WakeWordDetector` | Per-segment keyword spotting via sherpa-onnx KWS; gates ASR in wake-word mode. Bundled "Auris" keywords file, custom keywords by text file — no training needed |
| `IntentMatcher` | Embedding classification + edit-distance fallback |
| `EntityExtractor` | Rule-based slot extraction (English by default; native grammar on the SenseVoice path) |
| `EntityNormalizer` | Rule-based normalization of time/number expressions |
| `ModelManager` | Download selected backend + embedding models |
| `VoiceIntentExecutor` | Map intents to `PlaybackManager` actions |

### Dependencies

```kotlin
// Embedding intent matcher — standalone ONNX Runtime
implementation("com.microsoft.onnxruntime:onnxruntime-android:<ver>")

// SenseVoice backend — bundles its own ONNX Runtime
implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:<ver>")

// whisper.cpp backend — built from source via CMake in the voice module
// NPU backend (optional) — Qualcomm QNN / WhisperKit-Android, Snapdragon-gated
// Wake word — sherpa-onnx KWS model (ONNX, bundled), runs on the same onnxruntime as VAD/embeddings
```

The embedding tokenizer is a pure-Kotlin BPE parser over HuggingFace `tokenizer.json`. The entity engine is pure Kotlin/JVM with no native deps. Every bundled `.so` must keep 16 KB page-size alignment.

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
| Custom keyword file missing or fails to parse | Fall back to the bundled "Auris" keywords file |
| Wake-word detection cannot run in a WakeWord-mode context | Listening is treated as `Off` (mic stays closed); never fall back to continuous, so an exposed mic can never bypass the wake word |
| Embedding confidence below threshold AND edit distance low | No intent (null) |
| Relative intent (seek, adjust speed/volume) with no extracted slot | Use the default delta (seek ±30s, adjust speed ±0.5, adjust volume ±10) |
| Absolute intent (set speed/volume, seek absolute, sleep timer) with no extracted slot | Reject — no action, since there is no safe default value |
| Entity normalization fails on a span | Skip the span, use default |
| Model not yet downloaded | Queue utterance, process once ready |

## Testing

- **Unit**: `AsrBackendSelectorTest` — selection matrix across (Snapdragon?, NPU?, locale), including NPU-not-shipped. `IntentMatcherTest` — similarity ranking, edit-distance fallback, thresholds. `EntityExtractorTest` / `EntityNormalizerTest` — English duration/number/ordinal parsing ("half a minute" → 30, "2 and a half minutes" → 150) plus the native grammar on the SenseVoice path ("一分半" → 90).
- **Backend (instrumentation, real model)**: `WhisperCppBackendTest`, `SenseVoiceBackendTest` — fixed WAV in, assert non-empty transcript and detected language.
- **Wake word**: `WakeWordDetectorTest` — bundled "Auris" keywords fire above threshold on TTS-generated "Auris" clips, non-wake speech does not fire; custom keyword file with a user-chosen phrase fires on that phrase and not others; missing/failed custom file falls back to "Auris"; combined "Auris, skip forward" forwards the command remainder to ASR. Command-window behavior: opens on detection, stays open across follow-up utterances (no re-trigger) while speech continues, and closes only after a silence gap exceeding the conversation timeout (default 10s).
- **Integration**: pre-recorded WAV clips per language. "fast forward 30 seconds" (en) and "快进半分钟" (zh) both reach `SeekRelative(30000)` regardless of which backend produced the transcript. "pray" → `Resume` via edit distance. Podcast bleed → no false intent. In wake-word mode, a bare command is ignored until preceded by the wake word.
- **Performance**: short-command latency (2–4s utterance) on a mid-range device, confirming the whisper.cpp `audio_ctx`/quantization/single-encode tuning.
- **Regression**: existing `VoiceIntentExecutor` and gate-rule tests pass. Embedding matcher initializes with no ASR backend started (verifies ORT independence).

## Open Risks

- ASR inference latency on mobile hardware is device-dependent and needs ongoing measurement against the sub-second target. Whether the whisper.cpp baseline meets that target for CJK/English decides whether the optional SenseVoice backend is built at all.
- Playback cross-correlation depends on aligning the mic signal to the playback buffer; this is tractable only for the built-in speaker. A2DP-to-external-speaker bleed is left to the wake word and Android AEC, and the residual yield of the filter over AEC needs measurement.
- Keeping the backend and intent-matcher runtimes warm may be too memory intensive on lower-end devices, requiring on-demand loading.
- Multilingual natural commands may need language-specific evaluation data, especially to validate Whisper translation quality per language and the native SenseVoice-path grammars.
- **Antonym polarity is the embedding's known weak spot.** Sentence embeddings place opposites (louder/quieter, faster/slower, seek forward/back, boost on/off) close together, and those pairs are exactly the commands that must be distinguished. For now polarity is decided by the embedding like any other intent and tuned via the cosine threshold; this needs per-language measurement. If the error rate proves material, the planned mitigation is to have the embedding classify only the intent *family* and decide direction from a small closed per-language direction lexicon (up/down, more/less, forward/back) — the same closed-set, reference-curatable class as the number grammars. Not adopted yet.
- Other misclassification and low-confidence misses are tuned via the cosine threshold and the English-only edit-distance fallback; these thresholds need empirical tuning per language.
