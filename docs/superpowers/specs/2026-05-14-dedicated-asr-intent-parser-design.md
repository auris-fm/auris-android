# Dedicated ASR + Tiny LLM for Voice Control

**Date:** 2026-05-14

## Problem

The Gemma 4 E2B model (~2.58 GB) used for both ASR and intent parsing has high Word Error Rate (WER) across the 20 supported languages. The monolithic model also forces a single upgrade path — you cannot improve ASR independently of intent parsing.

## Proposed Architecture

Replace the monolithic Gemma 4 E2B with a cascaded pipeline:

```
Oboe → Silero VAD → Speaker Verify → Whisper base (translate=en) → SmolLM2 360M → Intent Executor
                                       (CPU, ~150 MB)                (CPU, ~200 MB Q4)
```

- **Whisper Base Multilingual** (~150 MB) — ASR with `translate=true`, outputting English text
- **SmolLM2 360M** (~200 MB Q4_K_M) — Intent parsing from English transcript, outputting the same `VoicePlaybackIntent` JSON schema Gemma 4 E2B currently produces
- Total: ~350 MB vs 2.58 GB, both CPU-bound

## Pipeline Detail

1. **Audio Capture** (unchanged): Oboe 16kHz/16-bit PCM mono → ring buffer
2. **VAD** (unchanged): Silero VAD segments speech utterances
3. **Speaker Verification** (unchanged): TFLite model runs on the speech segment
4. **ASR**: Speech segment fed to whisper.cpp → English transcript via `translate=true`
5. **Intent Parsing**: English transcript + playback context fed to SmolLM2 360M → structured JSON
6. **Intent Execution** (unchanged): `VoicePlaybackIntentExecutor` maps JSON to `PlaybackManager` actions

## Component Design

### whisper.cpp Integration

- Compiled as a native library target in `cpp/CMakeLists.txt`
- JNI bridge: `WhisperJni.cpp` with method `transcribe(ShortArray, sampleRate): String`
- Model file: `ggml-base-multilingual.bin` (~150 MB) downloaded via `ModelManager`
- Config: `whisper_full_params` with `language = "auto"`, `translate = true`, `n_threads = 4` (reduced to 2 under thermal throttling)
- Input: same 16kHz PCM clips Silero VAD produces today

### SmolLM2 360M Integration (llama.cpp)

- Compiled as a native library target in `cpp/CMakeLists.txt`
- JNI bridge: `LmJni.cpp` with method `parseIntent(String transcript, String context): String`
- Model file: `smolLM2-360M-instruct-Q4_K_M.gguf` (~200 MB)
- Strict system prompt listing all 18 intents with JSON schema (same content Gemma 4 E2B's system prompt uses today)
- Single attempt per request — invalid JSON returns `none`

### Files Changed

**New files:**
- `cpp/WhisperJni.cpp` / `WhisperJni.h` — JNI bridge for whisper.cpp
- `cpp/LmJni.cpp` / `LmJni.h` — JNI bridge for llama.cpp
- `cpp/CMakeLists.txt` — updated with whisper.cpp + llama.cpp targets
- `asr/WhisperRecognizer.kt` — Kotlin class wrapping the JNI call
- `intent/SmolLmIntentParser.kt` — Kotlin class wrapping the JNI call + prompt construction
- `model/ModelManager.kt` — Downloads whisper + SmolLM2 models (replaces `VoiceModelManager`)

**Modified files:**
- `voice/build.gradle.kts` — remove `litertlm-android` dependency
- `model/Gemma4VoiceRecognizer.kt` — replace in `VoiceControlModule` DI binding; delete file after migration
- `model/VoiceModelManager.kt` — replaced by `ModelManager`; delete after migration
- `di/VoiceControlModule.kt` — rebind `VoiceRecognizer` to `WhisperRecognizer`, add `SmolLmIntentParser` binding

### Error Handling

| Condition | Behavior |
|-----------|----------|
| Whisper returns empty | `none` intent |
| SmolLM2 returns invalid JSON | `none` intent (single attempt) |
| VAD false positive (no speech) | Whisper returns empty → `none` |
| Model not yet downloaded | Queue utterance, process once model ready |
| Thermal throttling | Reduce whisper.cpp threads to 2 |

### Language Coverage

Whisper base multilingual supports 99 languages natively — all 20 locales in scope are covered. With `translate=true`, the output is always English, so SmolLM2 360M (English-pretrained) handles intent parsing without multilingual gaps.

### Testing

- **Unit**: `WhisperRecognizerTest` with mock JNI returns. `SmolLmIntentParserTest` with sample transcripts covering all 18 intents, boundary cases (malformed input, empty string).
- **Native integration**: Pre-recorded WAV clips per language, run through full pipeline on device, verify correct `VoicePlaybackIntent` output.
- **Regression**: All existing voice control unit tests continue to pass (intent executor, gate rules, speaker verification).
