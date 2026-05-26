# ASR Intent Pipeline

## Problem

The original monolithic model (~2.58 GB) used for both ASR and intent parsing had high Word Error Rate (WER) across the 20 supported languages. A single model also forces a single upgrade path — you cannot improve ASR independently of intent parsing.

The initial replacement built a custom native pipeline (Oboe JNI, whisper.cpp, llama.cpp, CMake FetchContent, Vulkan patches) that was fragile, hard to maintain, and required managing multiple native dependencies. [Moonshine Voice](https://github.com/moonshine-ai/moonshine) provides most of these stages as a unified, maintained library.

## Architecture

Replace the monolithic model — and the custom native stack — with Moonshine Voice for audio capture + VAD + ASR + speaker diarization, keeping SmolLM2 solely for structured intent parsing:

```
Moonshine MicTranscriber  →  Signal Filter  →  SmolLM2 360M  →  Intent Executor
 (capture + VAD + ASR       (speaker diariz.    (CPU, ~200 MB    (unchanged)
  + speaker diarization)     + cross-corr.)      Q4_K_M)
```

- **Moonshine Voice** (`ai.moonshine:moonshine-voice`, MIT license) — Integrated library providing Oboe-based audio capture, built-in VAD, Moonshine streaming ASR models (ONNX), and optional speaker diarization. Ships from Maven Central; no CMake FetchContent needed for capture, VAD, or ASR.
- **Signal Filter** — Two lightweight checks run against each utterance before it reaches SmolLM2:
  1. **Speaker diarization** (Moonshine `identify_speakers`): first accepted command in a listening session establishes the target speaker; utterances from other speaker indices during the same session are dropped.
  2. **Playback cross-correlation**: cross-correlate the mic utterance against the playback buffer (the audio the device itself is playing). If they correlate strongly (accounting for acoustic delay), the utterance is podcast bleed and is dropped.
- **SmolLM2 360M** (~200 MB Q4_K_M) — Structured intent parsing from the transcribed English text, outputting `VoicePlaybackIntent` JSON. Runs on CPU via llama.cpp. Kept because embedding-based intent matching (Moonshine IntentRecognizer) cannot extract typed parameters from natural language utterances.
- **llama.cpp** — The only remaining native dependency. Fetched via CMake `FetchContent`. Vulkan backend enabled for GPU acceleration.

## Pipeline Detail

1. **Audio Capture + VAD + ASR**: Moonshine `MicTranscriber` handles all three stages. Microphone capture via Oboe, voice activity detection (built-in, configurable thresholds), and Moonshine streaming ASR producing English transcripts.
2. **Speaker Diarization**: Moonshine's built-in `identify_speakers` sets `hasSpeakerId` and `speakerIndex` on each `TranscriptLine`. First accepted utterance in a session establishes the target speaker. Subsequent utterances from other speaker indices are discarded.
3. **Playback Bleed Rejection**: The mic audio (`TranscriptLine.audioData`) is cross-correlated against the playback buffer. High correlation → podcast bleed → dropped.
4. **Intent Parsing**: Filtered English transcript + playback context fed to SmolLM2 360M → structured JSON (`VoicePlaybackIntent`).
5. **Intent Execution** (unchanged): `VoicePlaybackIntentExecutor` maps intent to `PlaybackManager` actions.

## Component Design

### Moonshine Integration

- **Dependency**: `ai.moonshine:moonshine-voice` from Maven Central (no CMake FetchContent for ASR/VAD/capture).
- **Model**: Moonshine Small Streaming (123M params, 7.84% WER, ONNX). English-optimized. 73ms latency on MacBook Pro-class hardware.
- **API**: `MicTranscriber` (Java class) for microphone capture + VAD + transcription. `TranscriptLine` carries `speakerId` (long), `speakerIndex` (int), `hasSpeakerId` (boolean), and `audioData` (float[]) when `identify_speakers` is enabled. Model architecture is an `int` constant (e.g. `MOONSHINE_MODEL_ARCH_SMALL_STREAMING`).
- **Model location**: Bundled in app assets or downloaded at first launch. Moonshine provides a built-in model downloader.
- **Speaker diarization**: Enabled via `identify_speakers = true`. Uses pyannote embeddings under the hood. Marked experimental by Moonshine authors — adequate for session-based speaker differentiation in quiet environments.

### Signal Filter

A `UtteranceFilter` class runs two checks in sequence before an utterance is passed to SmolLM2:

**Speaker consistency:**
- On first accepted command after playback starts, record `speakerIndex` as the session target.
- Subsequent utterances from a different `speakerIndex` are dropped silently.
- Session target resets when playback stops (new listening session).
- No enrollment, no stored embeddings, no persistent identity.
- **Fallback (WeSpeaker):** If Moonshine's experimental diarization proves too unreliable in testing, replace the session-based speaker gating with [WeSpeaker](https://github.com/wenet-e2e/wespeaker) (ECAPA-TDNN, ONNX, ~15 MB). The user enrolls with a single passphrase at setup; at runtime each utterance is checked for embedding similarity against the enrolled voice. Shares the ONNX runtime Moonshine already bundles. However, it adds enrollment UX (one-time passphrase recording) that the diarization approach avoids entirely.

**Playback cross-correlation:**
- Only active when the audio route is **speaker** or **Bluetooth A2DP** (no microphone on the output device). Disabled entirely when using a headset — there is no acoustic path from headset speakers to the mic, so cross-correlation is wasted work and risks false positives.
- Maintain a rolling buffer of the last 2 seconds of audio played by the device (resampled to 16kHz if needed).
- For each mic utterance, compute normalized cross-correlation against the playback buffer at offsets corresponding to plausible acoustic delays (50–500 ms).
- If peak correlation exceeds a threshold, the utterance is classified as playback bleed and dropped.
- This directly exploits the fact that the app *knows* exactly what audio it is playing.

### SmolLM2 360M Integration (llama.cpp)

Unchanged from the prior design, but simplified — it is now the only native component:

- Compiled as a native library via CMake
- JNI bridge exposes `parseIntent(modelPath, prompt): String`
- Model file: `smolLM2-360M-instruct-Q4_K_M.gguf` (~200 MB)
- Strict system prompt listing all intents with JSON schema
- Single attempt per request — invalid JSON returns `none`

### Intent Schema

The intent parser outputs the same `VoicePlaybackIntent` JSON schema. Available intents:

| Intent | JSON |
|---|---|
| Pause | `{"intent": "pause"}` |
| Resume | `{"intent": "resume"}` |
| Seek relative | `{"intent": "seek_relative", "delta_seconds": <int>}` |
| Seek absolute | `{"intent": "seek_absolute", "position_seconds": <int>}` |
| Next chapter | `{"intent": "next_chapter"}` |
| Previous chapter | `{"intent": "previous_chapter"}` |
| Chapter by index | `{"intent": "chapter_by_index", "index": <int>}` |
| Chapter by title | `{"intent": "chapter_by_title", "query": "<str>"}` |
| Next episode | `{"intent": "next_episode"}` |
| Set speed | `{"intent": "set_speed", "speed": <0.5-5.0>}` |
| Adjust speed | `{"intent": "adjust_speed", "delta": <float>}` |
| Set volume | `{"intent": "set_volume", "volume": <0-100>}` |
| Adjust volume | `{"intent": "adjust_volume", "delta": <int>}` |
| Sleep timer | `{"intent": "sleep_timer", "minutes": <int; 0 = cancel>}` |
| Set trim | `{"intent": "set_trim", "mode": "off"\|"low"\|"medium"\|"high"}` |
| Set volume boost | `{"intent": "set_volume_boost", "enabled": true\|false}` |
| Add bookmark | `{"intent": "add_bookmark", "title": "<str>"}` |

Common aliases are defined in the system prompt (e.g., "play" → resume, "stop" → pause, "faster" → adjust_speed +0.5, "volume up" → adjust_volume +10).

### Orchestrator

`VoiceControlService` connects the stages directly — no separate `CascadedVoiceRecognizer` needed since Moonshine handles the capture-to-transcript cascade internally:

```
VoiceControlService:
  on TranscriptEvent.LineCompleted:
    line = event.line
    text = line.text
    audio = line.audioData
    hasSpeakerId = line.hasSpeakerId
    speakerIndex = line.speakerIndex

    if not utteranceFilter.shouldProcess(audio, hasSpeakerId, speakerIndex, playbackBuffer):
      return

    intent = smolLmIntentParser.parseIntent(text, context)
    if intent != null:
      intentExecutor.execute(intent)
```

### Model Management

Moonshine manages its own models (bundled in assets or downloaded via built-in downloader). Only SmolLM2 needs a download manager:

- `ModelManager` handles SmolLM2 GGUF download from HuggingFace
- SHA-256 verification, resume support, retry (5 attempts), atomic rename
- Progress tracking via `StateFlow<ModelDownloadState>`
- Model stored under `filesDir/smol-lm-model/`

### Error Handling

| Condition | Behavior |
|-----------|----------|
| Moonshine returns empty transcript | `none` intent |
| Utterance fails speaker diarization check | Dropped silently |
| Utterance fails cross-correlation check | Dropped silently |
| SmolLM2 returns invalid JSON | `none` intent (single attempt) |
| VAD false positive (no speech) | Moonshine returns empty → `none` |
| Model not yet downloaded | Queue utterance, process once model ready |

## Language Coverage

Moonshine Small Streaming is English-optimized (7.84% WER). Moonshine also provides mono-lingual models for Arabic, Japanese, Korean, Mandarin, Spanish, Ukrainian, and Vietnamese. Non-English utterances are transcribed in their source language. SmolLM2 360M is English-pretrained, so non-English transcripts may not parse correctly. Multilingual voice commands should be evaluated during testing.

## Component Map

| Component | Responsibility |
|---|---|
| Moonshine `MicTranscriber` | Audio capture (Oboe), VAD, streaming ASR |
| `UtteranceFilter` | Speaker consistency (Moonshine diarization) + playback cross-correlation |
| `SmolLmIntentParser` | Structured JSON intent parsing via llama.cpp |
| `ModelManager` | Downloads SmolLM2 GGUF from HuggingFace |
| `VoicePlaybackIntentExecutor` | Maps intents to `PlaybackManager` actions (unchanged) |

### Dependencies

```kotlin
// Gradle
implementation("ai.moonshine:moonshine-voice:0.0.61")

// CMake (only llama.cpp)
FetchContent: llama.cpp
// Links: LmJni.cpp for SmolLM2 inference
```

### JNI Surface

```
LmNative → parseIntent(modelPath, prompt): String
```

## Testing

- **Unit**: `UtteranceFilterTest` for diarization gating and cross-correlation threshold logic. `SmolLmIntentParserTest` with sample transcripts covering all intents, boundary cases (malformed input, empty string).
- **Integration**: Moonshine `MicTranscriber` + `UtteranceFilter` + SmolLM2 end-to-end with pre-recorded WAV clips. Test playback bleed rejection with real podcast audio.
- **Regression**: All existing `VoicePlaybackIntentExecutor` and gate rule tests continue to pass.

## Dependencies

- `ai.moonshine:moonshine-voice` — Maven Central (audio capture, VAD, ASR, speaker diarization)
- llama.cpp — CMake `FetchContent` (SmolLM2 intent parsing only)
- Android NDK CMake, JNI, Hilt DI
- Moonshine ASR runs on CPU via ONNX runtime (bundled by Moonshine). SmolLM2 runs on GPU via Vulkan.
