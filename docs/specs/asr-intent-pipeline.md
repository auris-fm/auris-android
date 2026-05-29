# ASR Intent Pipeline

## Problem

The current pipeline uses a general-purpose language model (SmolLM2 360M) for structured intent parsing from transcribed English text. This approach has three fundamental limitations:

1. **Single-language bottleneck** — The LLM is English-pretrained. Non-English transcripts may not parse correctly, limiting voice commands to English-only despite the ASR supporting multiple languages.
2. **Semantic fragility** — The LLM matches intents by exact textual instruction ("if the user says X, output Y"). It has no notion of cross-lingual semantic similarity. "快进" (Chinese for "fast forward") and "fast forward" are semantically identical but lexically unrelated — the LLM can only handle this via explicit per-language aliases in the system prompt.
3. **Heavy inference cost** — A 360M-parameter LLM with a GPU-backed KV cache is overkill for a classification task over a closed set of ~16 intents with typed slot extraction. The model spends most of its capacity understanding the system prompt and generating boilerplate JSON rather than the actual intent decision.

The solution is to decompose intent parsing into three focused stages — each purpose-built for its task, each language-agnostic, and each lightweight enough to run on CPU.

## Architecture

Replace the SmolLM2 LLM with a three-stage pipeline: semantic intent matching, entity extraction, and entity normalization.

```
Moonshine MicTranscriber  →  Signal Filter  →  Intent Matcher    →  Intent Executor
 (capture + VAD + ASR       (speaker diariz.    (embedding-based      (unchanged)
  + speaker diarization)     + cross-corr.)     + edit distance)
                                                     │
                                                Entity Extractor
                                                (zero-shot NER)
                                                     │
                                                Entity Normalizer
                                                (rule-based multilingual)
```

- **Intent Matcher** — A multilingual embedding model encodes the user's utterance and a fixed set of English intent keyword phrases into the same vector space. Cosine similarity selects the best-matching intent. A secondary edit-distance check on the raw transcript against known command strings catches ASR phonetic errors (e.g., "pray" misrecognized for "play") that the embedding model would miss.
- **Entity Extractor** — A zero-shot named entity recognition model extracts typed spans from the transcript. Entity types are declared at runtime in English (e.g., `duration`, `amount`, `speed`, `ordinal`), and the model returns spans regardless of the source language. This replaces the LLM's slot-filling role.
- **Entity Normalizer** — A rule-based multilingual parsing engine converts extracted entity spans into machine-readable values. "半分钟" → 30 (seconds), "tomorrow at 3" → timestamp, "chapter five" → 5. Domain-scoped to time, duration, speed, volume, and ordinal expressions.

The rest of the pipeline — Moonshine for capture/VAD/ASR, the signal filter for speaker consistency and playback bleed rejection, and the intent executor — remains unchanged.

## Pipeline Detail

1. **Audio Capture + VAD + ASR**: Moonshine `MicTranscriber` handles all three stages. Microphone capture via Oboe, voice activity detection, and Moonshine streaming ASR producing transcripts in the source language.
2. **Speaker Diarization**: Moonshine's built-in speaker identification. First accepted utterance in a session establishes the target speaker; subsequent utterances from other speaker indices are discarded.
3. **Playback Bleed Rejection**: Cross-correlation of mic audio against the playback buffer. High correlation → podcast bleed → dropped.
4. **Intent Matching**: The filtered transcript is embedded and compared against pre-computed embeddings of English intent keyword phrases. The highest cosine similarity above a confidence threshold selects the intent. Below-threshold matches fall back to edit distance against a known command vocabulary to recover from ASR phonetic errors.
5. **Entity Extraction**: The transcript is run through a zero-shot NER model with English entity type labels (`duration`, `amount`, `speed`, `ordinal`, `chapter_query`, `volume`, `bookmark_title`). The model returns `(span, label)` pairs. For parameterless intents (pause, resume, next chapter), this step is skipped.
6. **Entity Normalization**: Each extracted entity span is parsed by a rule-based normalization engine that handles multilingual time, duration, number, ordinal, and speed expressions. The output is a typed value ready for intent execution.
7. **Intent Construction**: The matched intent type and normalized entity values are assembled into a `VoicePlaybackIntent` and dispatched to the executor.
8. **Intent Execution** (unchanged): `VoicePlaybackIntentExecutor` maps the intent to `PlaybackManager` actions.

## Component Design

### Intent Matcher

A multilingual embedding model encodes both the user's utterance and a predefined set of English intent keyword phrases into dense vectors. Intent selection is a nearest-neighbor lookup over a small fixed vocabulary (~20 phrases).

**Pipeline:**
1. At initialization, embed all English intent keyword phrases once and store the vectors.
2. For each utterance, embed the transcript and compute cosine similarity against all stored intent vectors.
3. If the top score exceeds a confidence threshold, select that intent.
4. If below threshold, compute normalized edit distance (Levenshtein) between the raw transcript and each known command string. If the best edit-distance match is strong, select that intent instead. This recovers from ASR substitutions that change meaning but preserve phonetics (e.g., "pray" → "play").
5. If neither method confidently matches, return `null` (no intent).

**Why embedding + edit distance:**
- Embeddings handle cross-lingual semantics: the Chinese utterance "快进三十分钟" and the English keyword "fast forward" are semantically close in a multilingual embedding space.
- Edit distance catches a complementary failure mode: when the ASR makes a phonetic error on an English utterance ("fast foward" → embedding may still match, but "pray" for "play" likely won't). Edit distance provides a character-level safety net over the small closed vocabulary of known commands.
- No per-language aliases, no prompt engineering, no GPU required.

### Entity Extractor

A zero-shot named entity recognition model extracts typed spans from the transcript. Unlike traditional NER models with fixed entity types, a zero-shot model accepts entity type descriptions at inference time. This means entity types are defined in English and applied to transcripts in any language.

**Entity types** (defined once in English):
| Entity Type | Description | Example Spans | Used By |
|---|---|---|---|
| `duration` | A time duration expression | "30 seconds", "半分钟", "5 min" | seek_relative, sleep_timer |
| `position` | An absolute time position | "1 hour 30 min", "第15分钟" | seek_absolute |
| `speed` | A playback speed value | "1.5", "2x", "两倍" | set_speed, adjust_speed |
| `volume` | A volume level or delta | "50", "ten", "up by 10" | set_volume, adjust_volume |
| `ordinal` | An ordinal number | "third", "第3个", "troisième" | chapter_by_index |
| `chapter_title` | A chapter name or description | "introduction", "总结" | chapter_by_title |
| `bookmark_title` | A bookmark label | "favorite part", "好地方" | add_bookmark |
| `trim_mode` | Silence trimming level | "off", "medium", "低" | set_trim |

For parameterless intents (pause, resume, next_chapter, previous_chapter, next_episode), entity extraction is skipped — the intent matcher alone determines the action.

For boolean intents (set_volume_boost), the entity extractor detects the presence/absence of affirmative vs. negative language.

### Entity Normalizer

A rule-based multilingual parsing engine converts entity spans into typed, machine-readable values. It handles the combinatorial explosion of time, duration, number, and ordinal expressions across languages.

**Why rule-based instead of learned:** Normalization is compositional, not statistical. "Half a minute" decomposes into `0.5 × 60 = 30` regardless of language. A rules engine with grammars for each target language is deterministic, debuggable, and requires no training data. The domain is tightly scoped — time, duration, speed, volume, ordinals — so the grammar surface is tractable.

**Supported normalizations:**

| Input (any language) | Normalized Output | Intent Slot |
|---|---|---|
| "半分钟" / "half a minute" / "30 seconds" | `30` (seconds) | delta_seconds |
| "1小时20分" / "1 hour 20 min" | `4800` (seconds) | position_seconds |
| "两倍" / "double" / "2x" | `2.0` | speed |
| "第三" / "third" / "3rd" | `3` (0-indexed: 2) | chapter index |
| "明天下午3点" | ISO timestamp | (future use) |

**Grammar scope:** Time durations (seconds, minutes, hours), absolute times, numbers (cardinal, ordinal), playback speeds, volume levels. Each target language adds a grammar module; the engine selects the grammar based on the ASR's detected language or the script of the transcript.

### Signal Filter

Unchanged from the prior design. Two checks run against each utterance before it reaches intent processing:

1. **Speaker consistency** — Session-based speaker gating via Moonshine diarization. First accepted command establishes the target speaker; other speakers are dropped.
2. **Playback cross-correlation** — Cross-correlate mic utterance against the playback buffer. High correlation → podcast bleed → dropped. Only active on speaker/A2DP routes (not headsets).

### Intent Schema

Unchanged. The pipeline outputs the same `VoicePlaybackIntent` sealed interface. Available intents:

| Intent | Parameters |
|---|---|
| Pause | — |
| Resume | — |
| Seek relative | `delta_seconds: Int` (>0 forward, <0 backward) |
| Seek absolute | `position_seconds: Int` |
| Next chapter | — |
| Previous chapter | — |
| Chapter by index | `index: Int` (0-based) |
| Chapter by title | `query: String` |
| Next episode | — |
| Set speed | `speed: Double` (0.5–5.0) |
| Adjust speed | `delta: Double` |
| Set volume | `volume: Int` (0–100) |
| Adjust volume | `delta: Int` |
| Sleep timer | `minutes: Int` (0 = cancel) |
| Set trim | `mode: "off" \| "low" \| "medium" \| "high"` |
| Set volume boost | `enabled: Boolean` |
| Add bookmark | `title: String` |

### Orchestrator

`VoiceControlService` connects the stages. The orchestrator receives a transcript from Moonshine, runs it through the signal filter, then dispatches to the intent matcher and entity extractor in parallel. Results are combined, normalized, and executed.

```
VoiceControlService:
  on TranscriptEvent.LineCompleted:
    line = event.line
    text = line.text
    audio = line.audioData

    if not utteranceFilter.shouldProcess(audio, hasSpeakerId, speakerIndex, playbackBuffer):
      return

    intentType = intentMatcher.match(text)
    if intentType == null:
      return  // no intent matched

    if intentType requires entities:
      entities = entityExtractor.extract(text, intentType.entityTypes)
      values = entityNormalizer.normalize(entities)
      intent = VoicePlaybackIntent(intentType, values)
    else:
      intent = VoicePlaybackIntent(intentType)

    intentExecutor.execute(intent)
```

### Model Management

Replaces the SmolLM2 GGUF download with downloaded embedding and NER model files:
- Embedding model: ONNX format, INT8 quantized, ~50 MB
- NER model: ONNX format, INT8 quantized, ~150 MB
- Normalization engine: compiled grammar rules, no model weights
- All models downloaded at first launch or bundled in assets
- SHA-256 verification, resume support, atomic rename

### Error Handling

| Condition | Behavior |
|---|---|
| Moonshine returns empty transcript | No processing |
| Utterance fails speaker diarization check | Dropped silently |
| Utterance fails cross-correlation check | Dropped silently |
| Embedding confidence below threshold AND edit distance low | No intent (null) |
| Entity extraction returns no spans for a parameterized intent | Fall back to defaults (e.g., seek_relative +30s, adjust_speed ±0.5) |
| Entity normalization fails on a span | Skip the span, use default value |
| Model not yet downloaded | Queue utterance, process once model ready |

## Language Coverage

The new pipeline is language-agnostic by design:

- **ASR**: Moonshine provides monolingual models for English, Arabic, Japanese, Korean, Mandarin, Spanish, Ukrainian, and Vietnamese. The transcript language follows the loaded ASR model.
- **Intent matching**: The multilingual embedding model maps semantically equivalent phrases across 100+ languages into the same vector space. English intent keywords match against transcripts in any supported language.
- **Entity extraction**: The zero-shot NER model supports 70+ languages. Entity type labels are in English; extracted spans are in the source language.
- **Entity normalization**: The rule-based normalizer includes grammars for each supported language. Adding a language means adding its number/duration/ordinal grammar module — no model retraining.

**ASR error recovery**: For English utterances, edit distance on the raw transcript catches phonetic ASR errors that change word identity. For non-English utterances, this fallback is less effective since English command strings have high edit distance from non-English transcripts by definition. The embedding model is the primary match signal for non-English commands.

## Component Map

| Component | Responsibility |
|---|---|
| Moonshine `MicTranscriber` | Audio capture (Oboe), VAD, streaming ASR |
| `UtteranceFilter` | Speaker consistency + playback cross-correlation |
| `IntentMatcher` | Embedding-based intent classification + edit distance fallback |
| `EntityExtractor` | Zero-shot NER with English entity type labels |
| `EntityNormalizer` | Rule-based multilingual parsing of time/duration/number expressions |
| `VoicePlaybackIntentExecutor` | Maps intents to `PlaybackManager` actions (unchanged) |

### Dependencies

```kotlin
// Gradle
implementation("ai.moonshine:moonshine-voice:0.0.59")
// Embedding model: ONNX, INT8 quantized
// Tokenizer: pure Kotlin BPE (parses HuggingFace tokenizer.json)
// Normalization engine: pure Kotlin/JVM library (grammar-based, no native deps)
```

**ONNX Runtime reuse:** The embedding model runs on the same `libonnxruntime.so` that Moonshine Voice bundles. No separate `onnxruntime-android` dependency. Following the pattern established by `VadJni.cpp` (Silero VAD), the embedding JNI resolves `OrtGetApiBase` via `dlsym(RTLD_NOLOAD)` — no build-time linkage against ORT, no duplicate .so files, guaranteed ABI compatibility with whatever ORT version Moonshine ships.

No llama.cpp. No GGUF models. No Vulkan GPU backend. Everything runs on CPU via the ORT runtime Moonshine already provides.

## Testing

- **Unit**: `IntentMatcherTest` for embedding similarity ranking and edit distance fallback. `EntityExtractorTest` for span extraction across languages. `EntityNormalizerTest` for duration/number/ordinal parsing in each supported language.
- **Integration**: End-to-end with pre-recorded WAV clips in multiple languages. Test that "快进半分钟" (Chinese) and "fast forward 30 seconds" (English) both produce `SeekRelative(30000)`. Test ASR error recovery: "pray" → `Resume`.
- **Regression**: All existing `VoicePlaybackIntentExecutor` and gate rule tests continue to pass.
