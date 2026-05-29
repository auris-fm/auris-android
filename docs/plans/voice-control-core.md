# Voice Control Core — Implementation Plan

> **Spec:** [voice-control-core spec](../specs/voice-control-core.md) — architecture, gate conditions, microphone-exposure classification, listening modes, foreground-service lifecycle, privacy/battery/UX.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android voice-control core: module wiring, settings, the gate (three condition groups), microphone-exposure classification, listening-mode resolution (`Off` / `Continuous` / `WakeWord`), typed intents, playback executor, foreground microphone service, and tests.

**Tech Stack:** Kotlin, Coroutines/Flow, Hilt, Android `AudioManager` / `TelephonyManager` / `PowerManager`, `ProcessLifecycleOwner`, foreground service with microphone type, JUnit, Mockito, Turbine.

---

## Scope

This plan owns everything between **device & playback signals** and the **`VoiceIntentExecutor` → `PlaybackManager`** call: the gate, microphone-exposure classification, listening-mode policy, foreground-service lifecycle, and the executor. Everything between "microphone audio in" and "validated `VoiceIntent` out" — capture, VAD, signal filtering, wake-word detection, ASR backends, intent matching, entity extraction, and model sources — is owned by the [ASR Intent Pipeline](asr-intent-pipeline.md) plan. This plan consumes only the pipeline's boundary contract (`VoiceRecognizer`) and its readiness signal.

The audio route is classified into a `MicExposure` value, and the gate plus exposure resolve a `ListeningMode` (`Off` / `Continuous` / `WakeWord`). The mode decides whether a wake word is required.

## File Structure

- `settings.gradle.kts`: include the voice service module.
- `app/build.gradle.kts`: add the voice module dependency so the app receives the merged service manifest.
- `modules/services/voice/build.gradle.kts`: Android library module.
- `modules/services/voice/src/main/AndroidManifest.xml`: microphone permission + `FOREGROUND_SERVICE_MICROPHONE` + `VoiceControlService` declaration.
- `modules/services/preferences/.../Settings.kt` + `SettingsImpl.kt`: `voiceControlUserDisabled`, `voiceControlSetupCompleted`.
- `.../voice/gate/*.kt`: rule contract, rule state, condition groups, and the gate combinator.
- `.../voice/gate/conditions/*.kt`: the eight conditions (Setup / Conflicts / Context).
- `.../voice/route/*.kt`: audio route monitoring and `MicExposure` classification.
- `.../voice/mode/*.kt`: `ListeningMode` and `ListeningModePolicy`.
- `.../voice/intent/*.kt`: intent model types.
- `.../voice/playback/*.kt`: playback context monitor and `VoiceIntentExecutor`.
- `.../voice/service/VoiceControlService.kt`: foreground microphone service.
- `.../voice/di/VoiceControlModule.kt`: Hilt bindings.
- `.../voice/src/test/**/*.kt`: unit tests.

---

## Task 1: Add Voice Service Module

**Files:**
- Modify: `settings.gradle.kts`, `app/build.gradle.kts`
- Create: `modules/services/voice/build.gradle.kts`, `modules/services/voice/src/main/AndroidManifest.xml`

- [ ] **Step 1: Include the module** — add `include(":modules:services:voice")` to `settings.gradle.kts` and `implementation(projects.modules.services.voice)` to `app/build.gradle.kts`.

- [ ] **Step 2: Module build file** — `modules/services/voice/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.voicecontrol"
    buildFeatures { buildConfig = true }
}

dependencies {
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.hilt.compiler)
    api(libs.dagger.hilt.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.coroutines.core)
    implementation(libs.timber)

    implementation(projects.modules.services.analytics)
    implementation(projects.modules.services.coroutines)
    implementation(projects.modules.services.localization)
    implementation(projects.modules.services.preferences)
    implementation(projects.modules.services.repositories)
    implementation(projects.modules.services.utils)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(projects.modules.services.sharedtest)
}
```

- [ ] **Step 3: Manifest** — `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` permissions and a `VoiceControlService` declared with `android:foregroundServiceType="microphone"` and `android:exported="false"`.

- [ ] **Step 4: Verify** `./gradlew :modules:services:voice:dependencies --configuration debugRuntimeClasspath` resolves.

- [ ] **Step 5: Commit.**

## Task 2: Add Core Voice Settings

**Files:**
- Modify: `Settings.kt`, `SettingsImpl.kt`

The core needs two persisted settings.

- [ ] **Step 1: Interface** — add to `Settings.kt`:

```kotlin
val voiceControlUserDisabled: UserSetting<Boolean>
val voiceControlSetupCompleted: UserSetting<Boolean>
```

- [ ] **Step 2: Storage** — add `BoolPref` implementations in `SettingsImpl.kt` (`voiceControlUserDisabled` default `false`, `voiceControlSetupCompleted` default `false`).

- [ ] **Step 3: Compile** `./gradlew :modules:services:preferences:compileDebugKotlin`.

- [ ] **Step 4: Commit.**

## Task 3: Add Voice Intent and Recognition Types

**Files:**
- Create: `.../voice/intent/VoiceIntent.kt` + `VoiceIntentTest.kt`

- [ ] **Step 1: Write the intent test** — assert `SeekRelative(30_000).deltaMs == 30_000` and `ChapterByTitle("  interview  ").normalizedQuery == "interview"`. Run it; it fails.

- [ ] **Step 2: Add the full intent model** — the closed `VoiceIntent` set owned by [Playback Controls](playback-controls.md); the core defines all types so the executor's `when` is exhaustive:

```kotlin
sealed interface VoiceIntent {
    data object Pause : VoiceIntent
    data object Resume : VoiceIntent
    data class SeekRelative(val deltaMs: Int) : VoiceIntent
    data class SeekAbsolute(val positionMs: Int) : VoiceIntent
    data object NextChapter : VoiceIntent
    data object PreviousChapter : VoiceIntent
    data class ChapterByIndex(val index: Int) : VoiceIntent
    data class ChapterByTitle(val query: String) : VoiceIntent {
        val normalizedQuery: String = query.trim()
    }
    data object NextEpisode : VoiceIntent
    data class SetSpeed(val speed: Double) : VoiceIntent
    data class AdjustSpeed(val delta: Double) : VoiceIntent
    data class SetVolume(val volume: Int) : VoiceIntent
    data class AdjustVolume(val delta: Int) : VoiceIntent
    data class SleepTimer(val minutes: Int) : VoiceIntent
    data class SetTrimMode(val mode: String) : VoiceIntent
    data class SetVolumeBoost(val enabled: Boolean) : VoiceIntent
    data class AddBookmark(val title: String) : VoiceIntent
}
```

- [ ] **Step 3: Run the test** — it passes. Commit.

## Task 4: Gate Framework (rules, groups, fail-closed combinator)

**Files:**
- Create: `.../voice/gate/VoiceControlRule.kt`, `.../voice/gate/VoiceControlGate.kt`, `.../voice/gate/VoiceControlGateTest.kt`

The gate asks three questions on top of the microphone-permission foundation (permission is handled in first-run setup, not as a gate condition). Each condition reports `Allowed` / `Blocked(reason)` / `Unknown(reason)`, and the combination is **fail-closed**: `Unknown` counts the same as `Blocked` for Setup and Conflicts.

- [ ] **Step 1: Rule contract + state + groups**

```kotlin
enum class VoiceControlRuleGroup { Setup, Conflicts, Context }

sealed interface VoiceControlRuleState {
    data object Allowed : VoiceControlRuleState
    data class Blocked(val reason: String) : VoiceControlRuleState
    data class Unknown(val reason: String) : VoiceControlRuleState
}

interface VoiceControlRule {
    val id: String
    val group: VoiceControlRuleGroup
    val state: StateFlow<VoiceControlRuleState>
}
```

- [ ] **Step 2: Gate state + combinator**

```kotlin
data class VoiceControlGateState(
    val allowed: Boolean,
    val rules: Map<String, VoiceControlRuleState>,
)
```

Combination rules (per spec):
- **Setup** group: every condition must be `Allowed` (`Blocked`/`Unknown` → fail).
- **Conflicts** group: no condition may be `Blocked` or `Unknown` (fail-closed).
- **Context** group: at least one condition must be `Allowed`.
- The gate is `allowed` only when all three groups pass. `rules` carries the full per-condition breakdown for diagnostics and the settings UI.

- [ ] **Step 3: Tests** — `VoiceControlGateTest` with fake rules per group: all-allowed → `allowed = true`; a blocked Setup rule → `false`; an `Unknown` Conflicts rule → `false` (fail-closed); Context with one `Allowed` + one `Blocked` → passes Context; Context all-blocked → `false`. Use Turbine on `state`.

- [ ] **Step 4: Commit.**

## Task 5: Audio Route Monitor + MicExposure

**Files:**
- Create: `.../voice/route/AudioRoute.kt`, `AudioRouteMonitor.kt`, `AndroidAudioRouteMonitor.kt`, `MicExposure.kt`, `MicExposureTest.kt`

`MicExposure` describes how easily the mic picks up non-command sound. It does **not** pass or block — it is read only when resolving the mode.

- [ ] **Step 1: Route + exposure types**

```kotlin
sealed interface AudioRoute {
    data class Headset(val hasMicrophone: Boolean) : AudioRoute   // wired / BT-SCO / BLE
    data object Speaker : AudioRoute                              // built-in loudspeaker
    data object BluetoothA2dpOnly : AudioRoute                    // external speaker, output-only
    data object Unknown : AudioRoute
}

enum class MicExposure { Isolated, Exposed, NoMic }
```

- [ ] **Step 2: Classification** — `AudioRoute → MicExposure`:
  - `Headset(hasMicrophone = true)` → `Isolated` (mic cannot hear the playback; safe without a wake word).
  - `Speaker`, `BluetoothA2dpOnly` → `Exposed` (mic shares air with playback/room; wake word required).
  - `Headset(hasMicrophone = false)` → `NoMic` (nothing to listen with).
  - `Unknown` → `Exposed` (conservative: require the wake word rather than risk a false activation).

- [ ] **Step 3: `AndroidAudioRouteMonitor`** — `@Singleton`, reads `AudioManager.getDevices(...)` outputs/inputs and registers an `AudioDeviceCallback` to push a `StateFlow<AudioRoute>` on add/remove. Headset types: `TYPE_WIRED_HEADSET`, `TYPE_BLUETOOTH_SCO`, `TYPE_BLE_HEADSET`; a headset output with a matching input device is `hasMicrophone = true`.

- [ ] **Step 4: `MicExposureTest`** — every `AudioRoute` maps to the expected `MicExposure`. Commit.

## Task 6: Gate Conditions (Setup / Conflicts / Context)

**Files:**
- Create: `.../voice/gate/conditions/*.kt` (+ monitors) + tests
- Create: `.../voice/playback/PlaybackContextMonitor.kt`, `.../voice/foreground/ForegroundStateMonitor.kt`

Each condition implements `VoiceControlRule` with its `group`, derives `state` from a source `StateFlow`, and exposes a pure `evaluate()` for unit testing.

- [ ] **Step 1: Setup conditions** (`group = Setup`, all must be `Allowed`)
  - `EnabledByUserCondition` — `Allowed` when `settings.voiceControlUserDisabled` is `false`, else `Blocked("disabled_by_user")`.
  - `DeviceSupportedCondition` — `Allowed` when the device meets the minimum capability bar (API level / RAM probe), else `Blocked("device_unsupported")`.
  - `ModelsReadyCondition` — consumes the pipeline's readiness signal (Task 9 wires `VoiceRecognizer.ensureReady()` into a `StateFlow<Boolean>`); `Allowed` when ready, `Unknown("models_loading")` while loading, `Blocked("model_download_failed")` on failure.

- [ ] **Step 2: Conflicts conditions** (`group = Conflicts`, none may block; transient)
  - `NotOnCallCondition` — `TelephonyManager`/`AudioManager` call state; in a call → `Blocked("on_call")`.
  - `NotCastingCondition` — repositories' cast state; casting → `Blocked("casting")`.
  - `BatteryOkCondition` — `PowerManager.isPowerSaveMode` or critically low battery → `Blocked("battery_saver")`.

- [ ] **Step 3: Context conditions** (`group = Context`, at least one `Allowed`)
  - `PlaybackContextMonitor` — maps `playbackManager.playbackStateFlow` to `Active(episodeUuid)` when a current episode exists and the player is not stopped/empty (paused still counts), else `Inactive`.
  - `PlaybackContextActiveCondition` — `Active` → `Allowed`, else `Blocked("no_playback_context")`.
  - `ForegroundStateMonitor` — `ProcessLifecycleOwner` foreground + screen-on state.
  - `AppInForegroundCondition` — foreground & screen on → `Allowed`, else `Blocked("not_foreground")`.

- [ ] **Step 4: Tests** — per-condition `evaluate()` cases (paused-but-active context → `Allowed`; inactive → blocked; power-save → blocked; call → blocked). Commit.

## Task 7: Listening Mode Policy

**Files:**
- Create: `.../voice/mode/ListeningMode.kt`, `.../voice/mode/ListeningModePolicy.kt`, `ListeningModePolicyTest.kt`

This is the `ListeningModePolicy` the [ASR Intent Pipeline spec](../specs/asr-intent-pipeline.md) refers to: it turns the gate result + microphone exposure into the mode the pipeline runs in.

- [ ] **Step 1: Mode type** — `enum class ListeningMode { Off, Continuous, WakeWord }`.

- [ ] **Step 2: Resolution** — combine `gate.state`, `micExposure`, and the `AppInForeground` / `PlaybackContextActive` condition states into a `StateFlow<ListeningMode>`, resolved in order:
  1. Gate not `allowed` → `Off`.
  2. `MicExposure.NoMic` → `Off`.
  3. `AppInForeground` allowed → `Continuous` (user can fix a wrong action; route-independent, even with no episode loaded).
  4. Background + active context + `Isolated` → `Continuous`.
  5. Background + active context + `Exposed` → `WakeWord`.

  The mode depends on **whether the playback context is active**, not on whether audio is playing — a paused episode keeps listening. The flow recomputes when playback starts/stops, the route changes, or foreground/screen state changes, so `Continuous ↔ WakeWord` switches happen without stopping the service.

- [ ] **Step 3: Tests** — foreground → `Continuous` regardless of exposure; background + Isolated → `Continuous`; background + Exposed → `WakeWord`; NoMic → `Off`; gate blocked → `Off`; paused-but-active context still yields a listening mode.

- [ ] **Step 4: Commit.**

## Task 8: Voice Intent Executor

**Files:**
- Create: `.../voice/playback/VoiceIntentExecutor.kt` + `VoiceIntentExecutorTest.kt`

The executor is the only class allowed to change playback from voice recognition. It maps each validated intent to a `VoicePlaybackSink` method, keeps seek positions within the episode, and rejects any command when no current episode exists. The added playback intents (speed/volume/sleep/trim/boost/bookmark) and their sink implementations are wired in [Playback Controls](playback-controls.md); this task establishes the executor, the sink interface, and the core playback mappings.

- [ ] **Step 1: Executor test with a fake sink** — `SeekRelative(30_000)` → `skipForward:30`; `SeekRelative(-10_000)` → `skipBackward:10`. Run; it fails.

- [ ] **Step 2: Executor + sink**

```kotlin
class VoiceIntentExecutor @Inject constructor(
    private val sink: VoicePlaybackSink,
) {
    suspend fun execute(intent: VoiceIntent) {
        when (intent) {
            VoiceIntent.Pause -> sink.pause()
            VoiceIntent.Resume -> sink.resume()
            is VoiceIntent.SeekRelative -> {
                val seconds = abs(intent.deltaMs / 1000)
                if (intent.deltaMs >= 0) sink.skipForward(seconds) else sink.skipBackward(seconds)
            }
            is VoiceIntent.SeekAbsolute -> sink.seekTo(intent.positionMs.coerceAtLeast(0))
            VoiceIntent.NextChapter -> sink.nextChapter()
            VoiceIntent.PreviousChapter -> sink.previousChapter()
            VoiceIntent.NextEpisode -> sink.nextEpisode()
            is VoiceIntent.ChapterByIndex -> sink.chapterByIndex(intent.index)
            is VoiceIntent.ChapterByTitle -> Unit // chapter search wired in playback-controls
            is VoiceIntent.SetSpeed -> sink.setSpeed(intent.speed)
            is VoiceIntent.AdjustSpeed -> sink.adjustSpeed(intent.delta)
            is VoiceIntent.SetVolume -> sink.setVolume(intent.volume)
            is VoiceIntent.AdjustVolume -> sink.adjustVolume(intent.delta)
            is VoiceIntent.SleepTimer -> sink.sleepAfter(intent.minutes)
            is VoiceIntent.SetTrimMode -> sink.setTrimMode(intent.mode)
            is VoiceIntent.SetVolumeBoost -> sink.setVolumeBoost(intent.enabled)
            is VoiceIntent.AddBookmark -> sink.addBookmark(intent.title)
        }
    }
}
```

The `VoicePlaybackSink` interface and `PlaybackManagerVoicePlaybackSink` are defined here (core playback methods) and extended in [Playback Controls](playback-controls.md). Tag analytics with `SourceView.VOICE_COMMANDS` (added by the Playback Controls plan).

- [ ] **Step 3: Run the test** — it passes. Commit.

## Task 9: Foreground Service, Recognizer Boundary, and DI

**Files:**
- Create: `.../voice/model/VoiceRecognizer.kt`, `.../voice/service/VoiceControlService.kt`, `.../voice/service/VoiceControlNotificationManager.kt`, `.../voice/di/VoiceControlModule.kt`

- [ ] **Step 1: Recognizer boundary contract** — the pipeline boundary the core depends on (text in, validated intent out):

```kotlin
data class VoiceRecognitionContext(
    val listeningMode: ListeningMode,
    val micExposure: MicExposure,
)

interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>
    suspend fun recognize(transcript: String, context: VoiceRecognitionContext): VoiceIntent?
}

class NoOpVoiceRecognizer @Inject constructor() : VoiceRecognizer {
    override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
    override suspend fun recognize(transcript: String, context: VoiceRecognitionContext): VoiceIntent? = null
}
```

`EmbeddingIntentMatcher` (from the [ASR Intent Pipeline](asr-intent-pipeline.md) plan) implements `VoiceRecognizer`; `ModelsReadyCondition` consumes `ensureReady()` state.

- [ ] **Step 2: Foreground service** — `@AndroidEntryPoint VoiceControlService` owns the foreground microphone lifecycle. It observes `ListeningModePolicy.mode`: starts capture and goes foreground when the mode is `Continuous` or `WakeWord`, stops capture and leaves foreground when it is `Off`. Capture runs **without taking audio focus** (so playback is never interrupted). The service coordinates the pipeline, readiness, and the executor; it does not parse commands itself.

- [ ] **Step 3: Notification** — `VoiceControlNotificationManager` shows a persistent notification that clearly states voice control is active and reflects whether the current mode is `Continuous` or `WakeWord`. No raw audio is logged.

- [ ] **Step 4: Hilt module** — bind `VoiceRecognizer`, `VoicePlaybackSink`, `AudioRouteMonitor`; provide the rule list (the eight conditions grouped), `VoiceControlGate`, `MicExposure` flow, and `ListeningModePolicy`.

- [ ] **Step 5: Compile** `./gradlew :modules:services:voice:compileDebugKotlin`. Commit.

## Task 10: App-Level Service Controller

**Files:**
- Create: `.../voice/service/VoiceControlServiceController.kt`
- Modify: `app/src/main/java/au/com/shiftyjelly/pocketcasts/PocketCastsApplication.kt`

- [ ] **Step 1: Controller** — `@Singleton VoiceControlServiceController` with `start()` (`ContextCompat.startForegroundService`) and `stop()`. The service itself self-stops when the mode resolves to `Off`; the controller exists so first-run setup/gate orchestration can start it once setup completes.

- [ ] **Step 2: Inject** the controller into `PocketCastsApplication` without calling `start()` (starting policy belongs to the setup/permission UX, not this task).

- [ ] **Step 3: Compile** `./gradlew :app:compileDebugKotlin`. Commit.

## Task 11: Build and Verify

- [ ] **Step 1: Unit tests** `./gradlew :modules:services:voice:testDebugUnitTest` — gate combination across groups (incl. fail-closed `Unknown`), `MicExposure` classification, mode resolution across foreground × exposure × context, executor mapping + seek clamping.
- [ ] **Step 2: Compile** `./gradlew :modules:services:voice:compileDebugKotlin :app:compileDebugKotlin`.
- [ ] **Step 3: Spotless** `./gradlew spotlessCheck` (run `spotlessApply` if it fails, then re-check).
- [ ] **Step 4: Commit any verification fixups** (no empty commit if there are none).
