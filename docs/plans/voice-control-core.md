# Voice Control Core — Implementation Plan

> **Spec:** [voice-control-core spec](../specs/voice-control-core.md) — architecture, interfaces, gate policies, lifecycle, error handling.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android voice-control core: module wiring, settings, gate policy, audio route detection, typed intents, playback executor, foreground service shell, and tests.

**Tech Stack:** Kotlin, Coroutines/Flow, Hilt, Android `AudioManager`, foreground service with microphone type, JUnit, Mockito, Turbine.

---

## Scope

This plan builds the voice-control core with `HeadsetOnly` as the default audio route policy. Audio capture, VAD, ASR, and intent parsing are covered in the [ASR Pipeline](asr-intent-pipeline.md) plan.

## File Structure

- `settings.gradle.kts`: include the new voice service module.
- `app/build.gradle.kts`: add the voice module dependency so the app receives the merged service manifest.
- `modules/services/voice/build.gradle.kts`: new Android library module.
- `modules/services/voice/src/main/AndroidManifest.xml`: microphone permissions and `VoiceControlService` declaration.
- `modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/model/VoiceControlAudioRoutePolicy.kt`: persisted policy enum.
- `modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/Settings.kt`: voice-control settings contract.
- `modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/SettingsImpl.kt`: voice-control settings storage.
- `modules/services/analytics/src/main/java/au/com/shiftyjelly/pocketcasts/analytics/SourceView.kt`: add `VOICE_CONTROL` entry.
- `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/gate/*.kt`: gate state, rules, and coordinator.
- `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/route/*.kt`: audio route monitoring and route policy.
- `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/audio/*.kt`: PCM frame types and audio types.
- `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/*.kt`: intent model types.
- `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/*.kt`: playback context monitor and intent executor.
- `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/VoiceControlService.kt`: foreground service shell.
- `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/di/VoiceControlModule.kt`: Hilt bindings.
- `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/**/*.kt`: unit tests.

## Task 1: Add Voice Service Module

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `modules/services/voice/build.gradle.kts`
- Create: `modules/services/voice/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add the failing module reference**

Add this include near the other services in `settings.gradle.kts`:

```kotlin
include(":modules:services:voice")
```

Add this dependency to `app/build.gradle.kts` with the other service dependencies:

```kotlin
implementation(projects.modules.services.voice)
```

- [ ] **Step 2: Run Gradle to verify the module is missing**

Run:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

Expected: fail because `:modules:services:voice` has no build file.

- [ ] **Step 3: Create the voice module build file**

Create `modules/services/voice/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.voice"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.hilt.compiler)

    api(libs.dagger.hilt.android)

    implementation(libs.androidx.core.ktx)
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

- [ ] **Step 4: Create the voice service manifest**

Create `modules/services/voice/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

    <application>
        <service
            android:name=".service.VoiceControlService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
    </application>
</manifest>
```

- [ ] **Step 5: Run dependency resolution**

Run:

```bash
./gradlew :modules:services:voice:dependencies --configuration debugRuntimeClasspath
```

Expected: pass dependency resolution.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts modules/services/voice
git commit -m "Add voice control service module"
```

## Task 2: Add Core Voice Settings

**Files:**
- Create: `modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/model/VoiceControlAudioRoutePolicy.kt`
- Modify: `modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/Settings.kt`
- Modify: `modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/SettingsImpl.kt`

- [ ] **Step 1: Add the policy enum**

Create `VoiceControlAudioRoutePolicy.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.preferences.model

enum class VoiceControlAudioRoutePolicy(val value: String) {
    HeadsetOnly("headset_only"),
    SpeakerExperimental("speaker_experimental"),
    ;

    companion object {
        fun fromValue(value: String): VoiceControlAudioRoutePolicy {
            return entries.firstOrNull { it.value == value } ?: HeadsetOnly
        }
    }
}
```

- [ ] **Step 2: Add settings to the interface**

Add the import to `Settings.kt`:

```kotlin
import au.com.shiftyjelly.pocketcasts.preferences.model.VoiceControlAudioRoutePolicy
```

Add these properties near playback/headphone settings:

```kotlin
val voiceControlUserDisabled: UserSetting<Boolean>
val voiceControlSetupCompleted: UserSetting<Boolean>
val voiceControlAudioRoutePolicy: UserSetting<VoiceControlAudioRoutePolicy>
```

- [ ] **Step 3: Add settings storage**

Add the import to `SettingsImpl.kt`:

```kotlin
import au.com.shiftyjelly.pocketcasts.preferences.model.VoiceControlAudioRoutePolicy
```

Add these settings near `headphoneControlsPlayBookmarkConfirmationSound`:

```kotlin
override val voiceControlUserDisabled = UserSetting.BoolPref(
    sharedPrefKey = "voiceControlUserDisabled",
    defaultValue = false,
    sharedPrefs = sharedPreferences,
)

override val voiceControlSetupCompleted = UserSetting.BoolPref(
    sharedPrefKey = "voiceControlSetupCompleted",
    defaultValue = false,
    sharedPrefs = sharedPreferences,
)

override val voiceControlAudioRoutePolicy = UserSetting.PrefFromString(
    sharedPrefKey = "voiceControlAudioRoutePolicy",
    defaultValue = VoiceControlAudioRoutePolicy.HeadsetOnly,
    sharedPrefs = sharedPreferences,
    fromString = VoiceControlAudioRoutePolicy::fromValue,
    toString = VoiceControlAudioRoutePolicy::value,
)
```

- [ ] **Step 4: Compile preferences**

Run:

```bash
./gradlew :modules:services:preferences:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add modules/services/preferences
git commit -m "Add voice control settings"
```

## Task 3: Add Voice Intent and Recognition Types

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/VoiceIntent.kt`
- Create: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/VoiceIntentTest.kt`

- [ ] **Step 1: Write the intent test**

Create `VoiceIntentTest.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentTest {
    @Test
    fun `seek relative stores milliseconds`() {
        val intent = VoiceIntent.SeekRelative(deltaMs = 30_000)

        assertEquals(30_000, intent.deltaMs)
    }

    @Test
    fun `chapter title trims query`() {
        val intent = VoiceIntent.ChapterByTitle(query = "  interview  ")

        assertEquals("interview", intent.normalizedQuery)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntentTest
```

Expected: fail because the intent model has not been added yet.

- [ ] **Step 3: Add the intent model**

Create `VoiceIntent.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.intent

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

This defines the full intent set. Intents beyond the core playback set are wired in [Playback Controls](playback-controls.md).
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntentTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent
git commit -m "Add voice playback intent model"
```

## Task 4: Add Gate State and Rule Coordinator

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/gate/VoiceControlRule.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/gate/VoiceControlGate.kt`
- Create: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/gate/VoiceControlGateTest.kt`

- [ ] **Step 1: Write gate tests**

Create `VoiceControlGateTest.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.gate

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceControlGateTest {
    @Test
    fun `gate is allowed when all required rules are allowed`() = runTest {
        val rule = FakeRule("playback", VoiceControlRuleState.Allowed)
        val gate = VoiceControlGate(listOf(rule))

        gate.state.test {
            assertEquals(VoiceControlGateState.Allowed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `gate is blocked when required rule is blocked`() = runTest {
        val gate = VoiceControlGate(
            listOf(FakeRule("route", VoiceControlRuleState.Blocked("disallowed_route"))),
        )

        gate.state.test {
            assertEquals(
                VoiceControlGateState.Blocked(mapOf("route" to VoiceControlRuleState.Blocked("disallowed_route"))),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeRule(
        override val id: String,
        initialState: VoiceControlRuleState,
    ) : VoiceControlRule {
        override val state = MutableStateFlow(initialState)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGateTest
```

Expected: fail because gate types do not exist.

- [ ] **Step 3: Add rule state and gate implementation**

Create `VoiceControlRule.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.gate

import kotlinx.coroutines.flow.StateFlow

interface VoiceControlRule {
    val id: String
    val state: StateFlow<VoiceControlRuleState>
}

sealed interface VoiceControlRuleState {
    data object Allowed : VoiceControlRuleState
    data class Blocked(val reason: String) : VoiceControlRuleState
    data class Unknown(val reason: String) : VoiceControlRuleState
}
```

Create `VoiceControlGate.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.gate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted

class VoiceControlGate(
    rules: List<VoiceControlRule>,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    val state: StateFlow<VoiceControlGateState> = combine(rules.map { rule ->
        rule.state.mapRule(rule.id)
    }) { states ->
        val byRule = states.toMap()
        val blocked = byRule.filterValues { it is VoiceControlRuleState.Blocked }
        if (blocked.isEmpty()) VoiceControlGateState.Allowed else VoiceControlGateState.Blocked(blocked)
    }.stateIn(scope, SharingStarted.Eagerly, VoiceControlGateState.Blocked(emptyMap()))

    private fun Flow<VoiceControlRuleState>.mapRule(id: String): Flow<Pair<String, VoiceControlRuleState>> {
        return kotlinx.coroutines.flow.map { state -> id to state }
    }
}

sealed interface VoiceControlGateState {
    data object Allowed : VoiceControlGateState
    data class Blocked(val rules: Map<String, VoiceControlRuleState>) : VoiceControlGateState
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGateTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/gate modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/gate
git commit -m "Add voice control gate"
```

## Task 5: Add Audio Route Policy

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/route/AudioRoute.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/route/AudioRouteMonitor.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/route/AndroidAudioRouteMonitor.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/route/AudioRoutePolicyRule.kt`
- Create: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/route/AudioRoutePolicyRuleTest.kt`

- [ ] **Step 1: Write route policy tests**

Create `AudioRoutePolicyRuleTest.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.route

import au.com.shiftyjelly.pocketcasts.preferences.model.VoiceControlAudioRoutePolicy
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRuleState
import org.junit.Assert.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class AudioRoutePolicyRuleTest {
    @Test
    fun `headset policy allows headset with microphone`() {
        val rule = AudioRoutePolicyRule(
            route = MutableStateFlow(AudioRoute.Headset(hasMicrophone = true)),
            policy = MutableStateFlow(VoiceControlAudioRoutePolicy.HeadsetOnly),
        )

        assertEquals(VoiceControlRuleState.Allowed, rule.evaluate())
    }

    @Test
    fun `headset policy blocks speaker`() {
        val rule = AudioRoutePolicyRule(
            route = MutableStateFlow(AudioRoute.Speaker),
            policy = MutableStateFlow(VoiceControlAudioRoutePolicy.HeadsetOnly),
        )

        assertEquals(VoiceControlRuleState.Blocked("audio_route_disallowed"), rule.evaluate())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.route.AudioRoutePolicyRuleTest
```

Expected: fail because route types do not exist.

- [ ] **Step 3: Add route types and rule**

Create `AudioRoute.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.route

sealed interface AudioRoute {
    data class Headset(val hasMicrophone: Boolean) : AudioRoute
    data object Speaker : AudioRoute
    data object BluetoothA2dpOnly : AudioRoute
    data object Unknown : AudioRoute
}
```

Create `AudioRouteMonitor.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.route

import kotlinx.coroutines.flow.StateFlow

interface AudioRouteMonitor {
    val route: StateFlow<AudioRoute>
}
```

Create `AudioRoutePolicyRule.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.route

import au.com.shiftyjelly.pocketcasts.preferences.model.VoiceControlAudioRoutePolicy
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRuleState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AudioRoutePolicyRule(
    private val route: StateFlow<AudioRoute>,
    private val policy: StateFlow<VoiceControlAudioRoutePolicy>,
    scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
) : VoiceControlRule {
    override val id = "audio_route_policy"
    override val state: StateFlow<VoiceControlRuleState> = kotlinx.coroutines.flow.combine(route, policy) { _, _ ->
        evaluate()
    }.stateIn(
        scope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        evaluate(),
    )

    fun evaluate(): VoiceControlRuleState {
        return when (policy.value) {
            VoiceControlAudioRoutePolicy.HeadsetOnly -> when (route.value) {
                AudioRoute.Headset(hasMicrophone = true) -> VoiceControlRuleState.Allowed
                else -> VoiceControlRuleState.Blocked("audio_route_disallowed")
            }
            VoiceControlAudioRoutePolicy.SpeakerExperimental -> when (route.value) {
                AudioRoute.Headset(hasMicrophone = true), AudioRoute.Speaker -> VoiceControlRuleState.Allowed
                else -> VoiceControlRuleState.Blocked("audio_route_disallowed")
            }
        }
    }
}
```

Create `AndroidAudioRouteMonitor.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.route

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AndroidAudioRouteMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : AudioRouteMonitor {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableRoute = MutableStateFlow(readRoute())
    override val route: StateFlow<AudioRoute> = mutableRoute.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        mutableRoute.value = readRoute()
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                        mutableRoute.value = readRoute()
                    }
                },
                null,
            )
        }
    }

    private fun readRoute(): AudioRoute {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return AudioRoute.Unknown
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val hasHeadsetOutput = outputs.any { it.type in headsetTypes }
        val hasHeadsetInput = inputs.any { it.type in headsetTypes }
        return when {
            hasHeadsetOutput -> AudioRoute.Headset(hasMicrophone = hasHeadsetInput)
            outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioRoute.Speaker
            outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> AudioRoute.BluetoothA2dpOnly
            else -> AudioRoute.Unknown
        }
    }

    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.route.AudioRoutePolicyRuleTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/route modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/route
git commit -m "Add voice audio route policy"
```

## Task 6: Add Playback Context Rule

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/PlaybackContextMonitor.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/PlaybackContextRule.kt`
- Create: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/PlaybackContextRuleTest.kt`

- [ ] **Step 1: Write playback context tests**

Create `PlaybackContextRuleTest.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRuleState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackContextRuleTest {
    @Test
    fun `current episode allows listening even when paused`() {
        val rule = PlaybackContextRule(MutableStateFlow(PlaybackContext.Active(currentEpisodeUuid = "episode-id")))

        assertEquals(VoiceControlRuleState.Allowed, rule.evaluate())
    }

    @Test
    fun `missing episode blocks listening`() {
        val rule = PlaybackContextRule(MutableStateFlow(PlaybackContext.Inactive))

        assertEquals(VoiceControlRuleState.Blocked("playback_context_inactive"), rule.evaluate())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContextRuleTest
```

Expected: fail because playback context types do not exist.

- [ ] **Step 3: Add playback context types and rule**

Create `PlaybackContextMonitor.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

sealed interface PlaybackContext {
    data class Active(val currentEpisodeUuid: String) : PlaybackContext
    data object Inactive : PlaybackContext
}

class PlaybackContextMonitor(
    playbackManager: PlaybackManager,
    scope: CoroutineScope,
) {
    val context: StateFlow<PlaybackContext> = playbackManager.playbackStateFlow
        .map { state ->
            if (state.episodeUuid.isNotBlank() && !state.isStopped && !state.isEmpty) {
                PlaybackContext.Active(state.episodeUuid)
            } else {
                PlaybackContext.Inactive
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, PlaybackContext.Inactive)
}
```

Create `PlaybackContextRule.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRuleState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PlaybackContextRule(
    private val playbackContext: StateFlow<PlaybackContext>,
    scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
) : VoiceControlRule {
    override val id = "playback_context"
    override val state: StateFlow<VoiceControlRuleState> = playbackContext
        .map { evaluate() }
        .stateIn(
            scope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            evaluate(),
        )

    fun evaluate(): VoiceControlRuleState {
        return when (playbackContext.value) {
            is PlaybackContext.Active -> VoiceControlRuleState.Allowed
            PlaybackContext.Inactive -> VoiceControlRuleState.Blocked("playback_context_inactive")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContextRuleTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback
git commit -m "Add voice playback context rule"
```

## Task 7: Add Playback Intent Executor

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/VoiceIntentExecutor.kt`
- Create: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/VoiceIntentExecutorTest.kt`

- [ ] **Step 1: Write executor tests with a fake sink**

Create `VoiceIntentExecutorTest.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentExecutorTest {
    @Test
    fun `relative positive seek skips forward`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoiceIntentExecutor(sink).execute(VoiceIntent.SeekRelative(30_000))

        assertEquals(listOf("skipForward:30"), sink.calls)
    }

    @Test
    fun `relative negative seek skips backward`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoiceIntentExecutor(sink).execute(VoiceIntent.SeekRelative(-10_000))

        assertEquals(listOf("skipBackward:10"), sink.calls)
    }

    private class FakeVoicePlaybackSink : VoicePlaybackSink {
        val calls = mutableListOf<String>()
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun skipForward(seconds: Int) { calls += "skipForward:$seconds" }
        override suspend fun skipBackward(seconds: Int) { calls += "skipBackward:$seconds" }
        override suspend fun seekTo(positionMs: Int) { calls += "seekTo:$positionMs" }
        override fun nextChapter() { calls += "nextChapter" }
        override fun previousChapter() { calls += "previousChapter" }
        override fun chapterByIndex(index: Int) { calls += "chapterByIndex:$index" }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.playback.VoiceIntentExecutorTest
```

Expected: fail because executor types do not exist.

- [ ] **Step 3: Add executor and sink**

Create `VoiceIntentExecutor.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntent
import javax.inject.Inject
import kotlin.math.abs

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
            is VoiceIntent.ChapterByIndex -> sink.chapterByIndex(intent.index)
            is VoiceIntent.ChapterByTitle -> Unit
            is VoiceIntent.SetSpeed -> Unit  // wired in playback-controls plan
            is VoiceIntent.AdjustSpeed -> Unit  // wired in playback-controls plan
        }
    }
}

interface VoicePlaybackSink {
    suspend fun pause()
    suspend fun resume()
    suspend fun skipForward(seconds: Int)
    suspend fun skipBackward(seconds: Int)
    suspend fun seekTo(positionMs: Int)
    fun nextChapter()
    fun previousChapter()
    fun chapterByIndex(index: Int)
}

class PlaybackManagerVoicePlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
) : VoicePlaybackSink {
    override suspend fun pause() = playbackManager.pauseSuspend(sourceView = SourceView.UNKNOWN)
    override suspend fun resume() = playbackManager.playQueueSuspend(sourceView = SourceView.UNKNOWN)
    override suspend fun skipForward(seconds: Int) = playbackManager.skipForwardSuspend(SourceView.UNKNOWN, seconds)
    override suspend fun skipBackward(seconds: Int) = playbackManager.skipBackwardSuspend(SourceView.UNKNOWN, seconds)
    override suspend fun seekTo(positionMs: Int) = playbackManager.seekToTimeMsSuspend(positionMs)
    override fun nextChapter() = playbackManager.skipToNextSelectedOrLastChapter()
    override fun previousChapter() = playbackManager.skipToPreviousSelectedOrLastChapter()
    override fun chapterByIndex(index: Int) = playbackManager.skipToChapter(index)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests au.com.shiftyjelly.pocketcasts.voice.playback.VoiceIntentExecutorTest
```

Expected: pass.

- [ ] **Step 5: Compile voice module**

Run:

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: pass with `SourceView.UNKNOWN` used as the temporary analytics source for voice-triggered playback actions.

- [ ] **Step 6: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback
git commit -m "Add voice playback intent executor"
```

## Task 8: Add Foreground Service Shell and Hilt Bindings

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/VoiceControlService.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/di/VoiceControlModule.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceRecognizer.kt`

- [ ] **Step 1: Add recognizer interface**

Create `VoiceRecognizer.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntent

data class VoiceRecognitionContext(
    val playbackContext: PlaybackContext,
    val audioRoute: AudioRoute,
)

data class VoiceUtteranceClip(
    val frames: List<PcmAudioFrame>,
    val sampleRateHz: Int = 16000,
) {
    companion object {
        fun fromFrames(frames: List<PcmAudioFrame>): VoiceUtteranceClip {
            return VoiceUtteranceClip(frames.toList(), frames.firstOrNull()?.sampleRateHz ?: 16000)
        }
    }
}

interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>
    suspend fun recognize(transcript: String, context: VoiceRecognitionContext): VoiceIntent?
}

class NoOpVoiceRecognizer @javax.inject.Inject constructor() : VoiceRecognizer {
    override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
    override suspend fun recognize(transcript: String, context: VoiceRecognitionContext): VoiceIntent? = null
}
```

`EmbeddingIntentMatcher` (from the [ASR Intent Pipeline](asr-intent-pipeline.md) plan) implements `VoiceRecognizer`.

- [ ] **Step 2: Add the service shell**

Create `VoiceControlService.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {
    @Inject lateinit var gate: VoiceControlGate

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("Voice control service started")
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("Voice control service stopped")
        super.onDestroy()
    }
}
```

- [ ] **Step 3: Add Hilt bindings**

Create `VoiceControlModule.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.di

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.voice.intent.EmbeddingIntentMatcher
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackManagerVoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voice.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContextRule
import au.com.shiftyjelly.pocketcasts.voice.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voice.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voice.route.AudioRoutePolicyRule
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceControlModule {
    @Binds abstract fun bindVoiceRecognizer(impl: EmbeddingIntentMatcher): VoiceRecognizer
    @Binds abstract fun bindVoicePlaybackSink(impl: PlaybackManagerVoicePlaybackSink): VoicePlaybackSink
    @Binds abstract fun bindAudioRouteMonitor(impl: AndroidAudioRouteMonitor): AudioRouteMonitor

    companion object {
        @Provides
        fun provideVoiceControlGate(
            playbackContextMonitor: PlaybackContextMonitor,
            audioRouteMonitor: AudioRouteMonitor,
            settings: Settings,
            @ApplicationScope scope: CoroutineScope,
        ): VoiceControlGate {
            val rules: List<VoiceControlRule> = listOf(
                PlaybackContextRule(playbackContextMonitor.context, scope),
                AudioRoutePolicyRule(audioRouteMonitor.route, settings.voiceControlAudioRoutePolicy.flow, scope),
            )
            return VoiceControlGate(rules = rules, scope = scope)
        }
    }
}
```

Add `@Inject constructor` to `PlaybackContextMonitor` before compiling:

```kotlin
class PlaybackContextMonitor @javax.inject.Inject constructor(
    playbackManager: PlaybackManager,
    @ApplicationScope scope: CoroutineScope,
) {
```

- [ ] **Step 4: Compile voice module**

Run:

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice
git commit -m "Add voice control service shell"
```

## Task 9: Add App-Level Service Starter

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/VoiceControlServiceController.kt`
- Modify: `app/src/main/java/au/com/shiftyjelly/pocketcasts/PocketCastsApplication.kt`

- [ ] **Step 1: Add service controller**

Create `VoiceControlServiceController.kt`:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceControlServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, VoiceControlService::class.java))
    }

    fun stop() {
        context.stopService(Intent(context, VoiceControlService::class.java))
    }
}
```

- [ ] **Step 2: Wire controller into application without starting it**

In `PocketCastsApplication.kt`, add:

```kotlin
import au.com.shiftyjelly.pocketcasts.voice.service.VoiceControlServiceController
```

Add an injected field near other injected managers:

```kotlin
@Inject lateinit var voiceControlServiceController: VoiceControlServiceController
```

Do not call `start()` in this task. Starting policy belongs in the setup/gate orchestration task after notification and permission UX exists.

- [ ] **Step 3: Compile app**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service app/src/main/java/au/com/shiftyjelly/pocketcasts/PocketCastsApplication.kt
git commit -m "Wire voice service controller"
```

## Task 10: Build and Verify

**Files:**
- No source edits unless verification exposes a compile or test failure.

- [ ] **Step 1: Run voice tests**

Run:

```bash
./gradlew :modules:services:voice:testDebugUnitTest
```

Expected: pass.

- [ ] **Step 2: Run compile checks**

Run:

```bash
./gradlew :modules:services:voice:compileDebugKotlin :app:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 3: Run formatting check for touched Kotlin files**

Run:

```bash
./gradlew spotlessCheck
```

Expected: pass. If formatting fails, run `./gradlew spotlessApply`, inspect the diff, and rerun `./gradlew spotlessCheck`.

- [ ] **Step 4: Commit verification fixes if any**

If verification caused formatting or compile-fix edits:

```bash
git add .
git commit -m "Fix voice control foundation verification"
```

If there are no edits after verification, do not create an empty commit.
