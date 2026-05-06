# Local Voice Control Design

## Summary

Add a local, hands-free voice control mode for Android playback. When enabled, the app listens while the playback UI or playback
context is active and the audio route is a headset or earbuds, so users can control playback while jogging, biking, doing house work,
or otherwise avoiding touch interaction.

The feature should not require a wake phrase. It should support natural phrasing, accents, slang, and multiple languages by using a
downloaded local model stack. Playback execution must remain deterministic: the model can interpret intent, but only validated intents
owned by Pocket Casts can affect playback.

## Goals

- Fast response for common playback controls.
- Fully local recognition and intent interpretation after any required model download.
- Android-first implementation.
- No wake phrase requirement for ordinary use.
- Listening only while the playback UI or playback context is active and the active route is headset/earbuds.
- Continue listening while playback is paused, so hands-free "resume" and seek commands still work.
- Adjustable gate logic that can be tuned without changing recognition or playback command execution.
- Natural multilingual command support instead of a narrow phrase grammar.
- Reuse existing playback, chapter, transcript, and analytics infrastructure.

## Non-Goals

- Cloud speech recognition or cloud LLM inference.
- Replacing Android media controls, headset button controls, Android Auto, or Tasker integration.
- Always listening outside the playback UI or playback context.
- Listening on speaker output.
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
VoiceRecognizer
        |
        v
VoiceIntentInterpreter
        |
        v
VoicePlaybackIntentExecutor
        |
        v
PlaybackManager
```

### VoiceControlGate

`VoiceControlGate` is a standalone policy engine. It combines small, independently testable rules:

- `UserEnabledRule`
- `PlaybackContextActiveRule`
- `HeadsetRouteRule`
- `HeadsetMicrophoneRule`
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
release should require the user-enabled, playback-context-active, headset-route, headset-microphone, microphone-permission, not-casting,
not-in-call, and model-ready rules. Battery saver and device support can start as warnings unless testing shows they need to block.

`PlaybackContextActiveRule` should mean the user is in a valid playback context, not that audio is currently playing. A current episode
must exist, and the player UI/session must be active enough that playback commands are meaningful. Paused playback remains allowed, so
users can say "resume", "skip thirty", "go back", or "jump to chapter three" without touching the device. Listening stops when the user
leaves the playback context, clears the current episode, or another required gate blocks.

### VoiceControlService

`VoiceControlService` owns the foreground microphone lifecycle. It starts capture only when the gate is allowed, stops immediately when
any required gate blocks, and exposes a persistent visible state while listening.

The service should not parse commands itself. It coordinates capture, segmenter, model providers, command interpretation, metrics, and
error recovery.

Android microphone foreground-service requirements must be handled explicitly. The service should use a microphone foreground service
type and a notification that clearly indicates voice control is active.

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

`VoiceRecognizer` converts an utterance clip into text or structured speech-understanding candidates.

```kotlin
interface VoiceRecognizer {
    suspend fun recognize(clip: VoiceUtteranceClip, context: VoiceRecognitionContext): VoiceRecognitionResult
}
```

Primary provider:

- Downloaded Gemma 4 E2B-class local model through Google AI Edge / LiteRT-LM if Android latency, model size, audio support, and device
  compatibility are acceptable in prototype testing.

Fallback providers:

- Android on-device `SpeechRecognizer` for devices with suitable on-device support.
- A later offline ASR provider if Gemma 4 audio support is not practical for short-command latency.

The provider boundary is required because Gemma 4 Android support and audio-input behavior need prototype validation. Official docs list
Gemma 4 and mobile deployment paths, and LiteRT-LM lists Android Kotlin support and Gemma4-E2B, but direct audio behavior must be tested
on target devices before committing to it as the only provider.

### VoiceIntentInterpreter

`VoiceIntentInterpreter` maps flexible language into a closed set of playback intents.

```kotlin
sealed interface VoicePlaybackIntent {
    data object Pause : VoicePlaybackIntent
    data object Resume : VoicePlaybackIntent
    data class SeekRelative(val deltaMs: Long) : VoicePlaybackIntent
    data class SeekAbsolute(val positionMs: Long) : VoicePlaybackIntent
    data object NextEpisode : VoicePlaybackIntent
    data object NextChapter : VoicePlaybackIntent
    data object PreviousChapter : VoicePlaybackIntent
    data class ChapterByIndex(val index: Int) : VoicePlaybackIntent
    data class ChapterByTitle(val query: String) : VoicePlaybackIntent
    data class SetPlaybackSpeed(val speed: Double) : VoicePlaybackIntent
}
```

The interpreter receives playback context:

- Current episode title and duration.
- Current position.
- Chapter titles, indices, and timestamps.
- Available transcript excerpts or search index if loaded.
- User locale and downloaded model language capabilities.

The model must emit JSON or another strict structured format that is validated before execution. Invalid or low-confidence output is
discarded. High-risk commands should require stronger confidence than reversible commands. Examples:

- Low risk: skip forward/back, next chapter, previous chapter.
- Medium risk: pause, resume, seek absolute.
- Higher risk: next episode, mark played, archive, delete. Higher-risk commands should be excluded from the MVP executor policy even if
  command types exist for later rollout stages.

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

Gemma 4 E2B-class local inference is the preferred target. Because current docs indicate mobile support through LiteRT-LM and MediaPipe
paths, the first implementation phase must include a spike that measures:

- Model download and storage size.
- Cold start and warm inference latency.
- Audio clip input support on Android.
- Memory pressure during playback.
- Battery impact during repeated commands.
- Quality on accented, slang-heavy, and multilingual commands.

## Lifecycle

1. User enables Voice Control in settings.
2. App requests microphone permission and presents the headset-only privacy model.
3. Model manager downloads or verifies the selected local model.
4. User enters a playback context with a current episode. Playback may be playing or paused.
5. Gate checks playback context, headset route, microphone, casting, call state, model readiness, and permission.
6. Service enters foreground microphone mode.
7. Segmenter listens for candidate utterances.
8. Candidate utterance is passed to recognizer and interpreter.
9. Valid playback intent is executed through `VoicePlaybackIntentExecutor`.
10. Service keeps listening while gate remains allowed.
11. Listening stops immediately when the playback context ends, the current episode is cleared, headset disconnects, route changes to
    speaker, casting starts, call state blocks, permission is revoked, or user disables the feature.

## Latency Strategy

Target response for common commands should be under one second after the user finishes speaking on supported devices. The plan:

- Keep the segmenter always warm while gated.
- Keep the selected model/runtime warm while playback is active, if memory permits.
- Use short utterance clips and trailing-silence detection to avoid waiting too long.
- Prefer deterministic parsing for simple time and chapter commands after recognition.
- Cache current chapter titles and transcript search structures per episode.
- Defer transcript-based semantic jumps until after core commands are fast.

## Battery Strategy

- Never listen unless all required gates are allowed.
- Use headset-only route to reduce false positives and avoid acoustic feedback.
- Run only a tiny segmenter continuously.
- Invoke Gemma 4 or heavier ASR only on completed candidate utterances.
- Stop listening when the playback context ends, not merely because audio is paused.
- Add diagnostics for segmenter duty cycle, model invocations, average inference time, and gate state.

## Privacy and UX

- The feature is opt-in.
- The app explains that voice audio is processed locally.
- Downloaded models are managed on-device.
- A persistent notification indicates active listening.
- Settings show current status: active, blocked by route, blocked by model download, blocked by permission, or disabled.
- No raw audio should be logged.
- Debug logging should include only command type, confidence buckets, gate state, and latency.

## Error Handling

- Headset disconnects: stop capture immediately and update state to `Blocked(HeadsetDisconnected)`.
- Route changes to speaker: stop capture immediately.
- Model not ready: do not start service; show model download state.
- Segmenter stuck in speech: timeout and reset.
- Recognition timeout: discard clip and keep listening.
- Low confidence: discard command silently by default, optionally play a subtle feedback tone in later iterations.
- Invalid command JSON: discard and log sanitized error.
- Playback unavailable: reject command with no mutation.
- Repeated command duplicates: debounce identical commands within a short interval.

## Rollout Plan

Use a feature flag and staged milestones:

1. Prototype gate state, headset route detection, microphone capture, and energy segmenter.
2. Prototype Gemma 4 E2B Android inference and measure latency, memory, battery, and audio support.
3. Implement typed intent interpreter and executor for core commands.
4. Add settings UI and model manager.
5. Add chapter title matching.
6. Add transcript-assisted section jumps.
7. Internal dogfood with diagnostics.
8. Limited beta with feature flag.
9. Broader release after false positive, latency, and battery thresholds are met.

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

- Wired headset, Bluetooth earbuds, and headset without microphone.
- Airplane mode after model download.
- Battery saver.
- Screen off.
- Long playback session.
- Accented English, slang-heavy English, and non-English commands.
- Podcast paused, resumed, and route changed while listening.

## Open Risks

- Android background microphone restrictions and OEM behavior may require foreground-service tuning.
- Gemma 4 audio input on Android needs validation for short-command latency and quality.
- Continuous model warmup may be too memory intensive on lower-end devices.
- Bluetooth headset microphones vary widely in quality and latency.
- Multilingual natural commands may need language-specific evaluation data.
- False positives can still happen from nearby human speech, even without podcast feedback.

## References

- Gemma 4 core docs: https://ai.google.dev/gemma/docs/core
- Gemma mobile deployment docs: https://ai.google.dev/gemma/docs/integrations/mobile
- LiteRT-LM overview: https://ai.google.dev/edge/litert-lm/overview
- LiteRT-LM Android Kotlin docs: https://ai.google.dev/edge/litert-lm/android
- Android SpeechRecognizer API: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android foreground service types: https://developer.android.com/develop/background-work/services/fg-service-types
- Media3 SessionCommand API: https://developer.android.com/reference/androidx/media3/session/SessionCommand
