# Voice Control Core

## Summary

Add local, hands-free voice control as a core Android playback capability. After first-run setup, the app listens while a listening
context is active, so users can control playback while jogging, biking, doing house work, or otherwise avoiding touch interaction.

Voice control is the main product interaction, not an auxiliary integration.

Whether a command needs a **wake word** depends on how exposed the microphone is to false activations:

- **No wake word** in low-false-positive contexts — when audio is playing through a headset/earbuds with a microphone, or when the
  app is in the foreground with the screen visible. Every recognized command executes directly.
- **Wake word required** in every other listening context, where the microphone is exposed to podcast audio or ambient speech.
  The wake word ("Auris" by default) must precede a command.

Recognition runs on a downloaded local model stack and supports natural phrasing, accents, slang, and multiple languages.
Playback execution stays deterministic: the model interprets intent, but only validated intents can affect playback.

## Goals

- Low-latency response for common playback commands (sub-second target on supported devices).
- A first-class voice control surface that complements existing media controls rather than replacing them.
- Fully on-device recognition and intent interpretation after the initial model download.
- Wake-word-free operation in low-false-positive contexts, with a wake word (built-in "Auris")
  required when the mic is exposed to podcast or ambient audio.
- Microphone off unless a listening context is active — including paused playback, so hands-free
  "resume" and seek still work — and stopped as soon as the context ends, for privacy and battery.
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
        device & playback signals
                   │
                   ▼
   ┌── decide if and how to listen ─────┐
   │  VoiceControlGate · MicExposure       │
   └───────────────┬─────────────────────┘
                   │  Off │ Continuous │ WakeWord
                   ▼
   VoiceControlService ─► Recognition pipeline ─► VoiceIntentExecutor ─► PlaybackManager
```

The diagram has two layers:

- **Decision layer** — decides whether to listen, and how. It reads device and playback signals (playback context, audio route,
  foreground/screen state, mic permission, casting, call, battery, model readiness, user setting) and outputs one of three
  values: `Off`, `Continuous`, or `WakeWord`. See [VoiceControlGate](#voicecontrolgate) and [Listening Modes](#listening-modes).
- **Runtime path** — runs only when the value is not `Off`. It sends microphone audio into the pipeline, the pipeline turns it
  into a validated intent, and the intent updates playback.

Two arrows point back. The pipeline tells the gate when its models are ready. Running a command changes the playback state, and
the decision layer reads that change.

This spec owns the gate, the listening-mode policy, the foreground-service lifecycle, the microphone-exposure classification, and the
privacy/battery/UX rules. Everything between "microphone audio in" and "validated `VoiceIntent` out" is the
**recognition pipeline**: capture, voice activity detection, playback-bleed filtering, wake-word detection, ASR backends and
their selection, intent matching, entity extraction, and model sources. The pipeline is owned by the
[Recognition Pipeline spec](recognition-pipeline.md). This spec uses only the pipeline's boundary contract (see
[Recognition pipeline](#recognition-pipeline)), so the pipeline can change inside without affecting this spec.

### VoiceControlGate

`VoiceControlGate` decides whether the microphone may listen, and in which mode. It is built from small conditions that can each
be tested on their own, grouped by the question they answer. It emits a `StateFlow<VoiceControlGateState>` carrying the full
breakdown, for diagnostics and the settings UI.

**Foundation — microphone permission.** The app always requests microphone permission during first-run setup. This is not a gate
condition: without permission, `VoiceControlService` never starts capture, so the pipeline receives no audio and does nothing.
Everything below assumes permission has been granted.

The gate then asks three questions. Each condition reports `Allowed`, `Blocked(reason)`, or `Unknown(reason)`, and the
combination is **fail-closed** — `Unknown` counts the same as `Blocked`, so the microphone never turns on from missing
information.

**1. Setup — is voice control ready?** (all must be `Allowed`; persistent — the user fixes these)
- `EnabledByUser` — the user has voice control turned on in settings
- `DeviceSupported` — the device meets the minimum capability bar
- `ModelsReady` — the recognition pipeline reports its models are downloaded and ready

**2. Conflicts — is anything blocking right now?** (none may block; transient — clears on its own)
- `NotOnCall` — no phone call is in progress
- `NotCasting` — not casting to another device
- `BatteryOk` — not in battery-saver or critically low battery

**3. Context — is there a reason to listen?** (at least one must be `Allowed`)
- `PlaybackContextActive` — a current episode exists (playing or paused)
- `AppInForeground` — the app is in the foreground with the screen on

When all three questions pass, the microphone turns on and the microphone exposure selects the mode (see
[Listening Modes](#listening-modes)).

`PlaybackContextActive` means the user is in a real playback context — not that audio is playing right now. A current episode
must exist, and the player screen or media session must be active enough for playback commands to make sense. Paused playback
still counts, so the user can say "resume", "skip thirty", "go back", or "jump to chapter three" without touching the device.
Listening stops when the user leaves the playback context, clears the current episode, or any condition above starts blocking.

`MicExposure` classifies the current audio route and microphone availability into one of the values below. It does not pass or
block — it is read only when picking the mode. The values describe how easily the microphone picks up sound that is not a
command — a "false activation". They are kept separate from the mode names so the two are never confused.

- **`Isolated`** — the microphone cannot hear the playback: a headset or earbuds with a working mic, where no sound travels from
  the speaker back into the mic. Safe to listen without a wake word.
- **`Exposed`** — the microphone shares the same air as the playback and the room (loudspeaker), so podcast audio or nearby
  speech can reach it. Usable only behind a wake word, which keeps false activations low.
- **`NoMic`** — the current route has no usable microphone (for example, a headset without a mic), so there is nothing to listen
  with.

`Exposed` listening still needs the usual echo care: Android acoustic echo cancellation and noise suppression when available,
plus the pipeline's playback-bleed filtering.

### Listening Modes

The gate turns the three questions and the microphone exposure into one **listening mode** — `Off`, `Continuous`, or `WakeWord`:

- **`Off`** — the gate did not pass: Setup is not ready, a conflict is blocking (call, casting, battery saver), or there is no
  reason to listen (neither `AppInForeground` nor `PlaybackContextActive`). Missing microphone permission lands here too —
  without it nothing runs at all.
- Otherwise the microphone turns on, and the mode is chosen in order:
  1. **`AppInForeground`** → `Continuous`. The user is looking at the app and can fix a wrong action right away, so commands run
     directly whatever the exposure — even with no episode loaded.
  2. **Background, active context, `Isolated`** → `Continuous`. The headset mic cannot hear the playback, so commands run directly.
  3. **Background, active context, `Exposed`** → `WakeWord`. The loudspeaker mic may pick up podcast or room audio, so a wake
     word must come before each command.
  4. **`NoMic`** → `Off`. There is no microphone to listen with.

The mode depends on **whether the playback context is active**, not on whether audio is playing right now. A paused episode keeps
listening — so "resume", "skip thirty", or "jump to chapter three" work hands-free — just as `PlaybackContextActive` intends.

In `WakeWord` mode, the app ignores speech until it hears the wake word. Hearing the wake word opens a **command window**. During
this window, follow-up commands do not need the wake word. The window stays open while the user keeps talking, and closes after a
silence longer than the conversation timeout (10 seconds by default). After that, the wake word is needed again.

The wake word is **"Auris"** by default. Wake-word detection, the model, and the command-window mechanics are
owned by the [Recognition Pipeline spec](recognition-pipeline.md); this spec owns only *when* each mode applies.

The mode reacts to change: it is recomputed when playback starts or stops, the route changes, or the app moves into or out of the
visible foreground. For example, unplugging a headset during playback switches `Continuous` → `WakeWord` without stopping the
service; bringing the app to the foreground switches `WakeWord` → `Continuous`.

### VoiceControlService

`VoiceControlService` owns the foreground microphone lifecycle. It starts capture only when the gate allows it, stops at once
when any required rule blocks, and shows a visible, ongoing status while it listens.

The service does not parse commands itself. It coordinates the recognition pipeline, model readiness, command interpretation,
metrics, and error recovery.

Android's rules for a microphone foreground service must be handled directly. The service must declare the microphone
foreground-service type and show a notification that clearly says voice control is active.

The service lifecycle follows the listening context, which decides whether the mic is on at all. The listening mode
(`Continuous` vs. `WakeWord`) only changes how the open mic is used, not whether the service runs. So:

- **App killed**: process termination destroys the service and stops microphone capture.
- **Background, no active playback context**: no context signal holds → service stops → microphone off.
- **Background, active context, `Isolated` exposure** (headset/earbuds with mic): microphone active, `Continuous` mode.
- **Background, active context, `Exposed` exposure** (loudspeaker): microphone active, `WakeWord` mode.
- **Background, active context, `NoMic` exposure**: no microphone on the route → microphone off.
- **Foreground, screen visible, no episode**: `AppInForeground` holds → microphone active, `Continuous` mode.
- **Foreground, screen visible, with playback**: microphone active, `Continuous` mode (foreground satisfies `Continuous` regardless of route).

### Recognition pipeline

The recognition pipeline is the single unit between the service and the executor. The core spec depends only on its boundary
contract; the pipeline's internal stages, components, ordering, models, and mechanisms are owned by the
[Recognition Pipeline spec](recognition-pipeline.md) and may change without touching this spec.

**Boundary contract:**

- **Input** — microphone audio, supplied only while the service holds the mic. The pipeline performs its own capture, voice
  activity detection, and playback-bleed filtering.
- **Output** — a validated `VoiceIntent` per recognized command, or null. The service forwards each recognized
  utterance to a backend-agnostic recognizer call:

  ```kotlin
  interface VoiceRecognizer {
      suspend fun ensureReady(): Result<Unit>
      suspend fun recognize(transcript: String, context: VoiceRecognitionContext): VoiceIntent?
  }
  ```

- **No audio focus** — capture must run **without taking system audio focus**, so media playback is never interrupted while the
  app listens. Android's built-in `SpeechRecognizer` does not fit here, because it takes audio focus and pauses playback.
- **Listening mode** — the pipeline follows the mode chosen by the gate: in wake-word mode it acts on a command only after the
  wake word opens a command window; in continuous mode every recognized command acts at once. This spec owns *when* each mode
  applies; the pipeline owns *how* the wake word is detected.
- **Readiness** — the pipeline reports model/readiness state, which the gate's `ModelsReady` condition consumes.

### VoiceIntentExecutor

The executor is the only class allowed to change playback from voice recognition. It maps each validated intent to an existing
`PlaybackManager` API and records analytics tagged with the voice source.

Execution examples:

- `SeekRelative(+30s)` -> `playbackManager.skipForwardSuspend(sourceView = VOICE_COMMANDS, jumpAmountSeconds = 30)`
- `SeekRelative(-10s)` -> `playbackManager.skipBackwardSuspend(sourceView = VOICE_COMMANDS, jumpAmountSeconds = 10)`
- `SeekAbsolute(12m)` -> `playbackManager.seekToTimeMsSuspend(720_000)`
- `NextChapter` -> `playbackManager.skipToNextSelectedOrLastChapter()`
- `PreviousChapter` -> `playbackManager.skipToPreviousSelectedOrLastChapter()`
- `ChapterByIndex(3)` -> `playbackManager.skipToChapter(3)`
- `ChapterByTitle("interview")` -> best chapter-title match, then `skipToChapter(chapter)`

The executor must keep seek positions within the episode's length, and must reject any command when no current episode exists.

## Model Management

Model download, verification, and storage are owned by the recognition pipeline
([Recognition Pipeline spec](recognition-pipeline.md)). The core spec consumes only the pipeline's readiness signal: the gate's
`ModelsReady` blocks listening until the pipeline reports its models are ready, and first-run setup surfaces download progress
to the user (see Lifecycle and Privacy and UX).

## Lifecycle

1. First-run setup requests microphone permission, presents the local-listening privacy model, and prepares the local model.
2. Model manager downloads or verifies the selected local model.
3. Voice control becomes available as a default playback capability.
4. A listening context begins — the user enters a playback context (playing or paused) or brings the app to the visible foreground.
5. Gate checks its three questions — Setup, Conflicts, Context — on top of the microphone-permission foundation.
6. Service enters foreground microphone mode.
7. The gate resolves `Continuous` vs. `WakeWord` from `AppInForeground` and the microphone exposure, and supplies it to the
   recognition pipeline.
8. The recognition pipeline processes microphone audio in the active mode and emits a validated `VoiceIntent`, or
   nothing, per recognized command.
9. Valid playback intent is executed through `VoiceIntentExecutor`.
10. Service keeps listening while the listening context holds, recomputing the mode on playback/route/foreground changes.
11. Listening stops immediately when no listening-context signal holds (playback context ends and the app is not foreground-visible),
    the route loses its microphone, casting starts, a call begins, permission is revoked, or the user disables voice control.
12. **App killed or fully idle**: process termination tears down the service and microphone. With no active playback context and
    the app not foreground-visible, no listening-context signal holds, so the service stops and the microphone is off.

## Latency Strategy

Target response for common commands should be under one second after the user finishes speaking on supported devices. The
core spec contributes by keeping the pipeline warm while listening is gated-allowed and tearing it down promptly otherwise.
Pipeline-internal latency tactics (model warmup, segmentation tuning, deterministic slot parsing) are owned by the
[Recognition Pipeline spec](recognition-pipeline.md).

## Battery Strategy

- **Microphone is off by default**: Capture starts only when a listening context holds. App killed, or backgrounded with no active playback context → mic off.
- Never listen unless all required gates are allowed and at least one listening-context signal holds.
- The active listening mode bounds pipeline work: wake-word mode keeps the heavier recognition stages idle until the wake word
  fires, which keeps speaker/background listening cheap. The per-stage cost behavior is owned by the
  [Recognition Pipeline spec](recognition-pipeline.md).
- Stop listening when no listening-context signal holds, not merely because audio is paused.
- Core diagnostics cover listening mode, gate state, and microphone on-time; pipeline-internal counters (inference time,
  segmenter duty cycle, wake-word activations) are reported by the pipeline.

## Privacy and UX

- Voice control is a core product capability, but first-run setup must be explicit because microphone permission and local model download
  are user-visible commitments.
- The app explains that voice audio is processed locally.
- Downloaded models are managed on-device.
- A persistent notification indicates active listening, and should reflect whether the current mode is continuous or wake-word.
- Settings let the user see the current wake word ("Auris"). Custom wake words require model retraining and are not supported in the initial release.
- Settings show current status: active (continuous), active (wake-word), no microphone on this route, blocked by model download,
  blocked by permission, blocked by a conflict (call, casting, or battery saver), or disabled by user.
- No raw audio should be logged, including wake-word enrollment audio.
- Debug logging should include only command type, confidence buckets, listening mode, wake-word activations, gate state, and latency.

## Error Handling

- Headset disconnects mid-playback: do not stop — recompute the mode (`Continuous` → `WakeWord` if the route is now `Exposed`/loudspeaker), or stop if no listening-context signal remains.
- Route loses its microphone (`NoMic`): stop capture immediately.
- Models not ready: `ModelsReady` blocks; do not start the service; surface download state.
- Playback unavailable: reject command with no mutation.
- Repeated command duplicates: debounce identical commands within a short interval.
- Recognition-internal failures (no intent, low confidence, recognition timeout, stuck segmentation, wake-word model/template
  fallback) resolve to "no validated intent" and are handled inside the recognition pipeline
  ([Recognition Pipeline spec](recognition-pipeline.md)); the core simply executes nothing.

## Testing Strategy

Unit tests:

- Gate condition combinations and blocked reasons across the Setup, Conflicts, and Context groups.
- Mode resolution: continuous vs. wake-word across foreground × route × playback-context combinations.
- Route changes and playback-state transitions, including continuous ↔ wake-word switches without stopping the service.
- Recognition-pipeline internals — playback-bleed filtering, backend selection, intent-match thresholds, entity extraction, and wake-word detection — are tested in the [Recognition Pipeline spec](recognition-pipeline.md).
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

- Wired headset, Bluetooth earbuds, headset without microphone, and speaker route.
- Continuous mode: headset playback and app-foreground-visible both trigger wake-word-free commands.
- Wake-word mode: speaker/background playback requires "Auris" before the first command; follow-up commands within the conversation need no wake word; the window times out after ~10s of silence and the wake word is required again.
- Wake-word detection of "Auris" in wake-word mode; command window opens on detection.
- Speaker route false-positive rate against podcast audio and ambient speech.
- Airplane mode after model download.
- Battery saver.
- Screen off (mode falls back to wake-word when foreground-visible no longer holds).
- Long playback session.
- Accented English, slang-heavy English, and non-English commands.
- Podcast paused, resumed, and route changed while listening (continuous ↔ wake-word transitions).

## Open Risks

- Android background microphone restrictions and OEM behavior may require foreground-service tuning.
- **Android SpeechRecognizer is incompatible with continuous listening**: It acquires audio focus and interrupts/pauses media playback.
  The pipeline's no-audio-focus capture requirement (see [Recognition pipeline](#recognition-pipeline)) avoids this.
- Bluetooth headset microphones vary widely in quality and latency.
- Speaker mode may be difficult to make reliable because podcast speech is semantically similar to user commands and room echo varies
  by device, volume, environment, and distance from the phone.
- Recognition only ever selects from the closed `VoiceIntent` set, so it cannot emit out-of-scope commands; the residual
  risk is misclassification or low-confidence misses, whose tuning is owned by the [Recognition Pipeline spec](recognition-pipeline.md).
- False positives can still happen from nearby human speech, even without podcast feedback.
