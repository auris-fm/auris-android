# ASR Intent Pipeline

## Problem

The original monolithic model (~2.58 GB) used for both ASR and intent parsing had high Word Error Rate (WER) across the 20 supported languages. A single model also forces a single upgrade path — you cannot improve ASR independently of intent parsing.

## Architecture

Replace the monolithic model with a cascaded pipeline:

```
Oboe → Silero VAD → Speaker Verify → Moonshine base → SmolLM2 360M → Intent Executor
                                      (CPU, ONNX)     (CPU, ~200 MB Q4)
```

- **Moonshine base** ([github.com/moonshine-ai/moonshine](https://github.com/moonshine-ai/moonshine)) — ASR optimized for mobile/edge, based on Whisper architecture. Runs on CPU via ONNX runtime or native C API.
- **SmolLM2 360M** (~200 MB Q4_K_M) — Intent parsing from English transcript, outputting a `VoicePlaybackIntent` JSON schema. Runs on CPU via llama.cpp.
- Both CPU-bound; total size approximately the SmolLM2 size plus the Moonshine ONNX model size.

## Pipeline Detail

1. **Audio Capture** (unchanged): Oboe 16kHz/16-bit PCM mono → ring buffer
2. **VAD** (unchanged): Silero VAD segments speech utterances
3. **Speaker Verification** (unchanged): TFLite model runs on the speech segment
4. **ASR**: Speech segment fed to Moonshine base → English transcript
5. **Intent Parsing**: English transcript + playback context fed to SmolLM2 360M → structured JSON
6. **Intent Execution** (unchanged): `VoicePlaybackIntentExecutor` maps JSON to `PlaybackManager` actions

## Component Design

### Moonshine Integration

Moonshine provides fast, on-device ASR optimized for mobile CPUs. It is based on the Whisper architecture with optimizations for edge inference.

- Compiled as a native library target via CMake
- JNI bridge exposes `transcribe(ShortArray, sampleRate): String`
- Model file: `moonshine-base.onnx` downloaded via `ModelManager`
- Input: same 16kHz PCM clips Silero VAD produces
- Output: transcribed English text

The exact C API (`moonshine_init`, `moonshine_transcribe`, `moonshine_free`) and build integration depend on Moonshine's CMake configuration. See [Moonshine's C API](https://github.com/moonshine-ai/moonshine/tree/main/c) for details.

### SmolLM2 360M Integration (llama.cpp)

- Compiled as a native library target via CMake alongside Moonshine
- JNI bridge exposes `parseIntent(String transcript, String context): String`
- Model file: `smolLM2-360M-instruct-Q4_K_M.gguf` (~200 MB)
- Strict system prompt listing all intents with JSON schema
- Single attempt per request — invalid JSON returns `none`

### Intent Schema

The intent parser outputs the same `VoicePlaybackIntent` JSON schema the monolithic model previously produced. Available intents:

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

> **Note on `VoiceRecognitionResult`:** The foundation plan originally defined a `VoiceRecognitionResult(transcript, confidence)` type for the monolithic model. The cascaded pipeline returns `VoicePlaybackIntent?` directly, so `VoiceRecognitionResult` is unused. It should be removed or left as dead code until the foundation plan is updated.

### Orchestrator

A `CascadedVoiceRecognizer` implements the existing `VoiceRecognizer` interface and wires the two stages together:

```
CascadedVoiceRecognizer:
  recognize(clip, context):
    transcript = moonshineRecognizer.transcribe(clip)
    if transcript.isBlank() → return null
    return smolLmIntentParser.parseIntent(transcript, context)
```

### Model Management

A `ModelManager` replaces the old monolithic `VoiceModelManager`. It downloads two model files:

- Moonshine base ONNX model (exact size TBD)
- SmolLM2 360M Q4_K_M GGUF (~200 MB)

Both downloaded from HuggingFace. Resumable download with retry, progress tracking via `StateFlow<ModelDownloadState>`.

### Error Handling

| Condition | Behavior |
|-----------|----------|
| Moonshine returns empty | `none` intent |
| SmolLM2 returns invalid JSON | `none` intent (single attempt) |
| VAD false positive (no speech) | Moonshine returns empty → `none` |
| Model not yet downloaded | Queue utterance, process once model ready |

## Language Coverage

Moonshine base is built on the Whisper architecture and is primarily optimized for English ASR on mobile/edge devices. It may support additional languages through Whisper-derived tokenizers, but its primary focus is fast, accurate English transcription. Unlike the previous Whisper-based plan, `translate=true` is not available — non-English utterances will be transcribed in their source language. This means SmolLM2 360M (English-pretrained) may not handle non-English transcripts well. Multilingual voice commands should be evaluated during testing to determine if a language-specific SmolLM2 variant or a different intent parsing approach is needed for non-English users.

## Testing

- **Unit**: `MoonshineRecognizerTest` with mock JNI returns. `SmolLmIntentParserTest` with sample transcripts covering all intents, boundary cases (malformed input, empty string). `CascadedVoiceRecognizerTest` for orchestration logic.
- **Native integration**: Pre-recorded WAV clips per language, run through full pipeline on device, verify correct `VoicePlaybackIntent` output.
- **Regression**: All existing voice control unit tests continue to pass (intent executor, gate rules, speaker verification).

## Dependencies

- Moonshine C API library (fetched via CMake `FetchContent`)
- llama.cpp (fetched via CMake `FetchContent`)
- Android NDK CMake, JNI, Hilt DI
- Both models run on CPU — no GPU delegate required
