# Voice Control Core

## Summary

Add local, hands-free voice control as a core Android playback capability. After first-run setup, the app listens while the playback UI
or playback context is active and the current audio route satisfies the configured audio-route policy, so users can control playback
while jogging, biking, doing house work, or otherwise avoiding touch interaction.

Voice control is the main product interaction, not an auxiliary integration. It should not require a wake phrase. It should support
natural phrasing, accents, slang, and multiple languages by using a downloaded local model stack. Playback execution must remain
deterministic: the model can interpret intent, but only validated intents owned by Pocket Casts can affect playback.

## Goals

- Fast response for common playback controls.
- Treat voice control as the primary playback control surface.
- Fully local recognition and intent interpretation after any required model download.
- Android-first implementation.
- No wake phrase requirement for ordinary use.
- Listening only while the playback UI or playback context is active and the active route is allowed by `AudioRoutePolicyRule`.
- Continue listening while playback is paused, so hands-free "resume" and seek commands still work.
- Adjustable gate logic that can be tuned without changing recognition or playback command execution.
- Natural multilingual command support instead of a narrow phrase grammar.
- Reuse existing playback, chapter, transcript, and analytics infrastructure.

## Non-Goals

- Cloud speech recognition or cloud LLM inference.
- Removing Android media controls, headset button controls, Android Auto, or Tasker integration.
- Always listening outside the playback UI or playback context.
- Treating speaker output as equally safe as headset/earbud output in the first production milestone.
- Letting model output directly call arbitrary playback APIs.
- Solving Wear OS, Automotive, or casting in the first Android phone milestone.

## Architecture

```text
PlaybackManager.playbackStateFlow
        |
        v
VoiceControlGate
        |
        v
VoiceControlService
        |
        v
Moonshine MicTranscriber  (capture + VAD + ASR, one library)
        |
        v
UtteranceFilter          (speaker diarization + playback bleed rejection)
        |
        v
SmolLmIntentParser       (structured JSON intent via llama.cpp)
        |
        v
VoicePlaybackIntentExecutor
        |
        v
PlaybackManager
```

Audio capture, VAD, and ASR are handled by [Moonshine Voice](https://github.com/moonshine-ai/moonshine)
(`ai.moonshine:moonshine-voice` from Maven Central) as an integrated library — no separate Oboe JNI, Silero VAD, or
whisper.cpp integration. Structured intent parsing is kept as a separate SmolLM2 stage via llama.cpp for parameter
extraction. Full details are in the [ASR Intent Pipeline spec](asr-intent-pipeline.md).

### VoiceControlGate

`VoiceControlGate` is a standalone policy engine. It combines small, independently testable rules.

**Active rules:**
- `UserNotDisabledRule` — user can disable voice control in settings
- `PlaybackContextActiveRule` — a current episode must exist (playing or paused)
- `AudioRoutePolicyRule` — headset with microphone required (HeadsetOnly default)

**Planned rules:**
- `MicrophonePermissionRule` — runtime permission check
- `NotCastingRule` — block while casting to another device
- `NotInCallRule` — block during phone calls
- `ModelReadyRule` — ensure ASR + intent models are downloaded
- `OperationalKillSwitchRule` — server-driven remote disable
- `SupportedDeviceRule` — minimum device capability check
- `BatterySaverRule` — pause listening when battery is critically low

Each rule returns:

- `Allowed`
- `Blocked(reason)`
- `Unknown(reason)`

The combined gate emits a `StateFlow<VoiceControlGateState>` with the full rule breakdown for diagnostics and settings UI.

`PlaybackContextActiveRule` should mean the user is in a valid playback context, not that audio is currently playing. A current episode
must exist, and the player UI/session must be active enough that playback commands are meaningful. Paused playback remains allowed, so
users can say "resume", "skip thirty", "go back", or "jump to chapter three" without touching the device. Listening stops when the user
leaves the playback context, clears the current episode, or another required gate blocks.

`AudioRoutePolicyRule` should keep route logic adjustable instead of baking in a permanent headset-only restriction. The first reliable
production policy is `HeadsetOnly`, which allows wired headsets and Bluetooth earbuds/headsets with a usable microphone. The product
direction should also include `SpeakerExperimental`, but that policy should stay blocked behind runtime capability checks and staged
release data until speaker-mode false positives are understood. Speaker mode requires treating podcast playback as an echo source:
Android acoustic echo cancellation and noise suppression should be used when available, but they are device-dependent and not sufficient
on their own. A future speaker policy should combine playback-reference echo suppression, stricter segmenter thresholds, conservative
intent confidence thresholds, and automatic fallback to `HeadsetOnly` after repeated suspected false positives.

### VoiceControlService

`VoiceControlService` owns the foreground microphone lifecycle. It starts capture only when the gate is allowed, stops immediately when
any required gate blocks, and exposes a persistent visible state while listening.

The service should not parse commands itself. It coordinates the Moonshine pipeline, model readiness, command interpretation,
metrics, and error recovery.

Android microphone foreground-service requirements must be handled explicitly. The service should use a microphone foreground service
type and a notification that clearly indicates voice control is active.

The service lifecycle is strictly bound to gate state: microphone capture starts only when all required gates are allowed, and stops
immediately when any required gate blocks. This means:

- **App killed**: Process termination destroys the service and stops microphone capture.
- **App background, no playback**: `PlaybackContextActiveRule` blocks → service stops → microphone off.
- **App background, episode playing/paused**: Gate remains allowed → service continues → microphone active for hands-free commands.
- **App foreground, no episode**: Gate blocks → service stops → microphone off.

### Audio Capture, VAD, and ASR

Moonshine Voice (`ai.moonshine:moonshine-voice`) provides an integrated `MicTranscriber` that handles microphone capture
via Oboe, built-in voice activity detection, and streaming ASR (Moonshine Small Streaming, 123M params, 7.84% WER on
English) as a single library.

### UtteranceFilter

Before an utterance reaches the intent parser, `UtteranceFilter` runs two lightweight checks:

- **Speaker consistency**: Moonshine's built-in `identify_speakers` diarization sets `hasSpeakerId` and
  `speakerIndex` on each `TranscriptLine`. The first accepted command in a listening session establishes the
  target speaker; subsequent utterances from other speaker indices are dropped.
- **Playback bleed rejection**: Normalized cross-correlation of `TranscriptLine.audioData` against the playback
  buffer. If the mic signal strongly correlates with what the device is playing, the utterance is dropped.
  Disabled when using a headset (no acoustic path from speaker to mic).

See [ASR Intent Pipeline spec](asr-intent-pipeline.md) for full details.

### VoiceRecognizer

`VoiceRecognizer` accepts an utterance clip and playback context, and returns a typed playback intent or null:

```kotlin
interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>
    suspend fun recognize(transcript: String, context: VoiceRecognitionContext): VoicePlaybackIntent?
}
```

Moonshine `MicTranscriber` handles capture + VAD + ASR internally, emitting transcript events. `VoiceControlService`
receives completed transcript lines, runs them through `UtteranceFilter`, and passes filtered text to
`SmolLmIntentParser` (which implements `VoiceRecognizer`).

**Critical design constraint**: Audio capture must operate **without taking system audio focus**.
Android's built-in `SpeechRecognizer` is unsuitable because it internally acquires audio focus and interrupts media
playback. Moonshine `MicTranscriber` uses Oboe internally and does not acquire audio focus.

See [ASR Intent Pipeline spec](asr-intent-pipeline.md) for model choices, the system prompt, intent schema, and integration details.

### VoicePlaybackIntentExecutor

The executor is the only class allowed to mutate playback based on voice recognition. It maps validated intents to existing
`PlaybackManager` APIs and records source-specific analytics.

Execution examples:

- `SeekRelative(+30s)` -> `playbackManager.skipForwardSuspend(sourceView = VOICE_CONTROL, jumpAmountSeconds = 30)`
- `SeekRelative(-10s)` -> `playbackManager.skipBackwardSuspend(sourceView = VOICE_CONTROL, jumpAmountSeconds = 10)`
- `SeekAbsolute(12m)` -> `playbackManager.seekToTimeMsSuspend(720_000)`
- `NextChapter` -> `playbackManager.skipToNextSelectedOrLastChapter()`
- `PreviousChapter` -> `playbackManager.skipToPreviousSelectedOrLastChapter()`
- `ChapterByIndex(3)` -> `playbackManager.skipToChapter(3)`
- `ChapterByTitle("interview")` -> best chapter-title match, then `skipToChapter(chapter)`

The executor should clamp seek positions to valid episode duration and reject commands when no current episode exists.

## Model Management

Moonshine Voice manages its own ASR models — either bundled in app assets or downloaded via its built-in downloader.
The only model managed separately is the SmolLM2 GGUF for intent parsing, handled by `ModelManager`:

- Download SmolLM2 360M Q4_K_M GGUF (~200 MB) from HuggingFace.
- SHA-256 verification, resume support, retry on failure.
- Progress tracking via `StateFlow<ModelDownloadState>`.
- Model stored under `filesDir/smol-lm-model/`.

Specific model sizes, formats, and sources are detailed in the [ASR Intent Pipeline spec](asr-intent-pipeline.md).

## Lifecycle

1. First-run setup requests microphone permission, presents the local-listening privacy model, and prepares the local model.
2. Model manager downloads or verifies the selected local model.
3. Voice control becomes available as a default playback capability.
4. User enters a playback context with a current episode. Playback may be playing or paused.
5. Gate checks playback context, audio route policy, microphone permission, casting, call state, model readiness, and permission.
6. Service enters foreground microphone mode.
7. Moonshine MicTranscriber listens for speech, transcribes utterances, and emits transcript events.
8. UtteranceFilter checks speaker consistency and playback bleed, then passes to SmolLmIntentParser which returns a validated `VoicePlaybackIntent` or null.
9. Valid playback intent is executed through `VoicePlaybackIntentExecutor`.
10. Service keeps listening while gate remains allowed.
11. Listening stops immediately when the playback context ends, the current episode is cleared, the audio route becomes disallowed,
    casting starts, call state blocks, permission is revoked, or the user explicitly disables voice control.
12. **App killed or background without playback**: When the process is killed, the foreground service and microphone capture terminate
    with it. When the app enters the background without an active playback episode, the gate's `PlaybackContextActiveRule` blocks,
    the service stops, and microphone input ceases. Background playback (playing or paused episode) keeps the gate allowed and the
    microphone active so hands-free commands remain available.

## Latency Strategy

Target response for common commands should be under one second after the user finishes speaking on supported devices. The plan:

- Moonshine MicTranscriber stays warm while gated.
- Keep the selected model/runtime warm while playback is active, if memory permits.
- Use short utterance clips and trailing-silence detection to avoid waiting too long.
- Prefer deterministic parsing for simple time and chapter commands after recognition.
- Cache current chapter titles and transcript search structures per episode.
- Defer transcript-based semantic jumps until after core commands are fast.

## Battery Strategy

- **Microphone is off by default**: Capture starts only when the gate is fully allowed. App killed or background without playback → mic off.
- Never listen unless all required gates are allowed.
- Start with `HeadsetOnly` route policy to reduce false positives and avoid acoustic feedback.
- Moonshine VAD runs continuously at low cost; ASR inference only fires on completed speech segments.
- SmolLM2 intent parsing only runs on filtered utterances that pass UtteranceFilter.
- Stop listening when the playback context ends, not merely because audio is paused.
- Add diagnostics for segmenter duty cycle, model invocations, average inference time, and gate state.

## Privacy and UX

- Voice control is a core product capability, but first-run setup must be explicit because microphone permission and local model download
  are user-visible commitments.
- The app explains that voice audio is processed locally.
- Downloaded models are managed on-device.
- A persistent notification indicates active listening.
- Settings show current status: active, blocked by route, blocked by model download, blocked by permission, disabled by user, or disabled
  by operational kill switch.
- No raw audio should be logged.
- Debug logging should include only command type, confidence buckets, gate state, and latency.

## Error Handling

- Headset disconnects: stop capture immediately and update state to `Blocked(HeadsetDisconnected)`.
- Route changes to a disallowed route: stop capture immediately.
- Model not ready: do not start service; show model download state.
- VAD stuck in speech: Moonshine's `vad_max_segment_duration` forces a segment break to recover.
- Recognition timeout: discard clip and keep listening.
- Low confidence: discard command silently by default, optionally play a subtle feedback tone in later iterations.
- Invalid command JSON: discard and log sanitized error.
- Playback unavailable: reject command with no mutation.
- Repeated command duplicates: debounce identical commands within a short interval.

## Testing Strategy

Unit tests:

- Gate rule combinations and blocked reasons.
- Route changes and playback-state transitions.
- UtteranceFilter speaker gating and cross-correlation logic.
- SmolLM2 JSON validation and confidence thresholds.
- Executor command mapping and seek clamping.
- Duplicate command debounce.

Integration tests:

- Playback context opens with a current episode -> gate allowed -> service starts.
- Playback pauses while context remains active -> service remains active.
- Headset disconnect -> service stops.
- Cast starts -> service stops.
- Valid utterance -> typed intent -> `PlaybackManager` call.
- Low-confidence or invalid model output -> no playback mutation.

Manual/device tests:

- Wired headset, Bluetooth earbuds, headset without microphone, and speaker route with `SpeakerExperimental` disabled.
- Speaker route with experimental echo suppression enabled on selected test devices.
- Airplane mode after model download.
- Battery saver.
- Screen off.
- Long playback session.
- Accented English, slang-heavy English, and non-English commands.
- Podcast paused, resumed, and route changed while listening.

## Open Risks

- Android background microphone restrictions and OEM behavior may require foreground-service tuning.
- **Android SpeechRecognizer is incompatible with continuous listening**: It acquires audio focus and interrupts/pauses media playback.
  Moonshine uses Oboe internally and does not acquire audio focus, avoiding this issue.
- ASR model inference latency on mobile hardware is device-dependent and needs ongoing measurement.
- Continuous model warmup may be too memory intensive on lower-end devices, requiring on-demand loading.
- Bluetooth headset microphones vary widely in quality and latency.
- Speaker mode may be difficult to make reliable because podcast speech is semantically similar to user commands and room echo varies
  by device, volume, environment, and distance from the phone.
- Structured JSON parsing from model output carries risk: the model may produce malformed JSON, hallucinate intent types, or
  emit commands outside the `VoicePlaybackIntent` sealed interface.
- Multilingual natural commands may need language-specific evaluation data.
- False positives can still happen from nearby human speech, even without podcast feedback.
