# Local Voice Control Design

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

## Existing Repository Fit

The implementation should be a thin command layer over existing services:

- `PlaybackManager` already owns play, pause, seek, skip, queue, chapter, effects, sleep timer, and playback state behavior.
- `MediaSessionActions` and `Media3SessionCallback` already centralize media-session playback commands.
- `ChapterManager`, `Chapters`, and player chapter UI provide chapter data and chapter skipping primitives.
- `TranscriptManager` can load episode transcripts for future semantic "jump to the part where..." commands.
- The Tasker playback plugin already maps external commands into `PlaybackManager`, which is a useful reference for command execution.

The repo does not currently contain a microphone voice activity detector, `AudioRecord` capture path, on-device speech recognizer,
LiteRT, MediaPipe, TFLite, or ONNX integration. `ShiftyTrimSilenceProcessor` is playback-audio processing and should not be reused
as a microphone segmenter.

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
VoiceAudioSegmenter
        |
        v
VoiceRecognizer (Gemma 4 E2B — ASR + Intent in one pass)
        |
        v
VoicePlaybackIntentExecutor
        |
        v
PlaybackManager
```

Gemma 4 E2B collapses ASR and intent interpretation into a single model inference.
The separate `VoiceIntentInterpreter` layer was removed — the recognizer now returns
`VoicePlaybackIntent` directly from the model's structured JSON output.

### VoiceControlGate

`VoiceControlGate` is a standalone policy engine. It combines small, independently testable rules:

- `UserNotDisabledRule`
- `OperationalKillSwitchRule`
- `PlaybackContextActiveRule`
- `AudioRoutePolicyRule`
- `MicrophonePermissionRule`
- `NotCastingRule`
- `NotInCallRule`
- `SupportedDeviceRule`
- `ModelReadyRule`
- `BatterySaverRule`

Each rule returns:

- `Allowed`
- `Blocked(reason)`
- `Unknown(reason)`

The combined gate emits a `StateFlow<VoiceControlGateState>` with the full rule breakdown for diagnostics and settings UI. The first
release should require the user-not-disabled, operational-kill-switch, playback-context-active, audio-route-policy, microphone-permission,
not-casting, not-in-call, and model-ready rules. Battery saver and device support can start as warnings unless testing shows they need
to block.

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

The service should not parse commands itself. It coordinates capture, segmenter, model providers, command interpretation, metrics, and
error recovery.

Android microphone foreground-service requirements must be handled explicitly. The service should use a microphone foreground service
type and a notification that clearly indicates voice control is active.

The service lifecycle is strictly bound to gate state: microphone capture starts only when all required gates are allowed, and stops
immediately when any required gate blocks. This means:

- **App killed**: Process termination destroys the service and stops microphone capture.
- **App background, no playback**: `PlaybackContextActiveRule` blocks → service stops → microphone off.
- **App background, episode playing/paused**: Gate remains allowed → service continues → microphone active for hands-free commands.
- **App foreground, no episode**: Gate blocks → service stops → microphone off.

### VoiceAudioSegmenter

Add a new replaceable segmenter interface:

```kotlin
interface VoiceAudioSegmenter {
    fun process(frame: PcmAudioFrame): VoiceSegmenterResult
}
```

Candidate results:

- `Silence`
- `SpeechStarted`
- `SpeechContinuing`
- `SpeechEnded(segment: VoiceUtteranceClip)`
- `Rejected(reason)`

The segmenter should be cheap enough to run continuously while gated. Gemma 4 must not be used as the always-running segmenter because
it is too heavy for continuous detection.

Initial implementations:

- `EnergyVoiceAudioSegmenter`: MVP/prototype based on RMS energy, adaptive noise floor, minimum speech duration, and trailing silence.
- `NoOpVoiceAudioSegmenter`: tests.
- Production candidate: WebRTC VAD or a small LiteRT VAD model if prototype data shows the energy segmenter is not robust enough.

### VoiceRecognizer

`VoiceRecognizer` accepts an utterance clip and returns a typed playback intent directly. The interface collapses
what was previously two stages (ASR text generation + deterministic intent parsing) into a single model pass:

```kotlin
interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>
    suspend fun recognize(clip: VoiceUtteranceClip, context: VoiceRecognitionContext): VoicePlaybackIntent?
}
```

**Critical design constraint**: The recognizer must process `AudioRecord`-captured PCM buffers **without taking system audio focus**.
Android's built-in `SpeechRecognizer` is unsuitable because it internally acquires audio focus and interrupts media playback. All
providers operate entirely in-process on already-captured audio via Oboe `AudioRecordCaptureEngine`.

The active provider is **Gemma 4 E2B** via LiteRT-LM:

- Audio PCM frames from the segmenter are passed to LiteRT-LM via `Content.AudioBytes()`.
- The model outputs structured JSON (e.g. `{"intent": "seek_relative", "delta_seconds": 30}`).
- The recognizer parses the JSON into `VoicePlaybackIntent`. Invalid or low-confidence output returns `null`.
- ~2.6 GB model, downloaded and managed by `VoiceModelManager`.

No other providers are active. Vosk was removed as part of the architecture simplification — Gemma 4 E2B's
multimodal input makes a separate ASR + rule-based intent pipeline unnecessary.

Provider selection in DI:

```kotlin
@Binds fun bindVoiceRecognizer(impl: Gemma4VoiceRecognizer): VoiceRecognizer
```

### VoiceIntentInterpreter (Removed)

A separate `VoiceIntentInterpreter` layer was part of the initial design (Vosk ASR → text → rule-based parser → intent).
Gemma 4 E2B's multimodal audio-to-structured-JSON capability eliminates this stage entirely. The recognizer now returns
`VoicePlaybackIntent` directly from the model.

The `VoicePlaybackIntent` sealed interface is unchanged — it is the output type that both the old two-stage pipeline
and the current single-pass pipeline produce, so `VoicePlaybackIntentExecutor` needs no modification.

### VoicePlaybackIntentExecutor

The executor is the only class allowed to mutate playback based on voice recognition. It maps validated intents to existing
`PlaybackManager` APIs and records source-specific analytics.

Suggested source value:

- Add `SourceView.VOICE_CONTROL` or the EventHorizon equivalent if generated analytics supports it.

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

`VoiceModelManager` owns downloadable model lifecycle:

- Discover supported local model packs.
- Download over Wi-Fi by default unless user opts into cellular.
- Verify checksum/signature.
- Track model version, size, language support, runtime backend, and compatibility.
- Expose download progress and readiness to settings UI and `VoiceControlGate`.
- Allow model deletion.
- Provide fallback model selection.

The Gemma 4 E2B model (~2.6 GB, `litertlm` format on HuggingFace) is the active model. Performance measurement is ongoing:

- Cold start and warm inference latency on target devices.
- Audio clip input support via LiteRT-LM `Content.AudioBytes()`.
- Memory pressure during playback with model loaded.
- Battery impact during repeated commands.
- Quality on accented, slang-heavy, and multilingual commands.
- Structured JSON output reliability and parse rate.

## Lifecycle

1. First-run setup requests microphone permission, presents the local-listening privacy model, and prepares the local model.
2. Model manager downloads or verifies the selected local model.
3. Voice control becomes available as a default playback capability.
4. User enters a playback context with a current episode. Playback may be playing or paused.
5. Gate checks playback context, audio route policy, microphone permission, casting, call state, model readiness, and permission.
6. Service enters foreground microphone mode.
7. Segmenter listens for candidate utterances.
8. Candidate utterance is passed to recognizer, which returns a validated `VoicePlaybackIntent` or null.
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

- Keep the segmenter always warm while gated.
- Keep the selected model/runtime warm while playback is active, if memory permits.
- Use short utterance clips and trailing-silence detection to avoid waiting too long.
- Prefer deterministic parsing for simple time and chapter commands after recognition.
- Cache current chapter titles and transcript search structures per episode.
- Defer transcript-based semantic jumps until after core commands are fast.

## Battery Strategy

- **Microphone is off by default**: Capture starts only when the gate is fully allowed. App killed or background without playback → mic off.
- Never listen unless all required gates are allowed.
- Start with `HeadsetOnly` route policy to reduce false positives and avoid acoustic feedback.
- Run only a tiny segmenter continuously.
- Invoke Gemma 4 (the only recognizer) only on completed candidate utterances, not on every audio frame.
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
- Segmenter stuck in speech: timeout and reset.
- Recognition timeout: discard clip and keep listening.
- Low confidence: discard command silently by default, optionally play a subtle feedback tone in later iterations.
- Invalid command JSON: discard and log sanitized error.
- Playback unavailable: reject command with no mutation.
- Repeated command duplicates: debounce identical commands within a short interval.

## Rollout Plan

Voice control is core product functionality, but delivery should still use staged engineering gates and an operational kill switch:

1. ✅ Prototype gate state, audio route policy, microphone capture, and energy segmenter.
2. ✅ Implement typed intent interpreter and executor for core commands.
3. ✅ Implement Gemma 4 E2B recognizer: single-pass audio-to-intent via LiteRT-LM, replacing separate Vosk ASR + rule-based parser.
4. ✅ Add voice model manager with Gemma 4 E2B model download (~2.6 GB).
5. ✅ Wire service orchestration: auto-start/stop based on gate state, foreground notification.
6. Measure Gemma 4 E2B Android inference latency, memory, battery, and audio quality.
7. Add settings UI (enable/disable, route policy selector, gate status display).
8. Add first-run setup and microphone permission UX.
9. Add chapter title matching.
10. Add transcript-assisted section jumps.
11. Internal dogfood with diagnostics.
12. Limited release with kill-switch monitoring.
13. Broader release after false positive, latency, and battery thresholds are met.

## Testing Strategy

Unit tests:

- Gate rule combinations and blocked reasons.
- Route changes and playback-state transitions.
- Segmenter state machine using synthetic PCM fixtures.
- Interpreter JSON validation and confidence thresholds.
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
  This was confirmed during testing and led to the Oboe AudioRecord + LiteRT-LM pipeline.
- Gemma 4 E2B audio input on Android needs validation for short-command latency and quality. The model is ~2.6 GB and inference
  latency on mobile hardware is unmeasured.
- LiteRT-LM has a known potential AAudio ring-buffer assertion (`releaseBuffer: mUnreleased out of range`) when using
  `Content.AudioBytes()` or `Content.AudioFile()`. If this reproduces, alternate audio input paths or runtime configurations
  should be evaluated.
- Gemma 4 E2B model size (~2.6 GB) is significantly larger than the 40 MB Vosk model it replaces. Model download should prefer Wi-Fi.
  Devices with limited storage may not accommodate the download.
- Continuous model warmup may be too memory intensive on lower-end devices, requiring on-demand loading.
- Bluetooth headset microphones vary widely in quality and latency.
- Speaker mode may be difficult to make reliable because podcast speech is semantically similar to user commands and room echo varies
  by device, volume, environment, and distance from the phone.
- Structured JSON parsing from model output is a new risk: the model may produce malformed JSON, hallucinate intent types, or
  emit commands outside the `VoicePlaybackIntent` sealed interface.
- Multilingual natural commands may need language-specific evaluation data.
- False positives can still happen from nearby human speech, even without podcast feedback.

## References

- Gemma 4 core docs: https://ai.google.dev/gemma/docs/core
- Gemma mobile deployment docs: https://ai.google.dev/gemma/docs/integrations/mobile
- LiteRT-LM overview: https://ai.google.dev/edge/litert-lm/overview
- LiteRT-LM Android Kotlin docs: https://ai.google.dev/edge/litert-lm/android
- Gemma 4 E2B LiteRT-LM model on HuggingFace: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Android foreground service types: https://developer.android.com/develop/background-work/services/fg-service-types
- Media3 SessionCommand API: https://developer.android.com/reference/androidx/media3/session/SessionCommand
