# Playback Controls — Implementation Plan

> **Spec:** [voice-intents spec](../specs/voice-intents.md) — intent definitions, executor wiring, sink interface.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up all voice intents through to actual playback actions — sleep timer, volume, trim mode, volume boost, and bookmarks — and refactor dual-param speed/volume intents into separate absolute/adjust types.

**Scope:** This plan covers only the **execution layer**: the sealed intent interface, the executor, the sink interface, and sink implementations. The recognition layer — intent matching and entity extraction — is handled by the [Recognition Pipeline plan](recognition-pipeline.md), which registers the intent keywords and slot grammars for every intent listed here.

**Tech Stack:** Kotlin, Hilt DI, PlaybackManager, SleepTimer, BookmarkManager, AudioManager, Android analytics (SourceView)

---

### Task 1: Add SourceView.VOICE_COMMANDS

**Files:**
- Modify: `modules/services/analytics/src/main/java/au/com/shiftyjelly/pocketcasts/analytics/SourceView.kt`

- [ ] **Step 1: Find the file and add the entry**

Read the file to find the existing enum values, then add:

```kotlin
VOICE_COMMANDS(key = "voice_commands", analyticsValue = SourceViewType.UNKNOWN),
```

Place it after `TASKER` or at the end of the enum list, following existing patterns.

- [ ] **Step 2: Commit**

```bash
git add modules/services/analytics/src/main/java/au/com/shiftyjelly/pocketcasts/analytics/SourceView.kt
git commit -m "Add SourceView.VOICE_COMMANDS for voice-triggered action analytics"
```

---

### Task 2: Add ToolCallMapper and VoiceResponse

**Files:**
- Create: `modules/services/voice/src/main/kotlin/.../intent/ToolCallMapper.kt`
- Create: `modules/services/voice/src/main/kotlin/.../intent/VoiceResponse.kt`

- [ ] **Step 1: `VoiceResponse`** — `sealed interface VoiceResponse { data object Silent; data class Earcon(val id: String); data class Spoken(val text: String) }`.

- [ ] **Step 2: `ToolCallMapper`** — converts FunctionGemma's JSON tool call output `(toolName, action, params)` to the typed `VoiceIntent` hierarchy. One `map<Domain>(action, params)` method per tool. Resolves shared ref types (`BookmarkRef`, `EpisodeRef`, etc.) via fuzzy title/index matching. Invalid actions or missing required params return null.

```kotlin
class ToolCallMapper {
    fun map(call: ToolCall): VoiceIntent? {
        if (call.name == "no_match") return null
        return when (call.name) {
            "playback" -> mapPlayback(call.action, call.params)
            "effects" -> mapEffects(call.action, call.params)
            "volume" -> mapVolume(call.action, call.params)
            // ... one branch per tool
            else -> null
        }
    }
}
```

- [ ] **Step 3: Compile and commit.**

### Task 3: Refactor VoiceIntent sealed interface

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/VoiceIntent.kt`

- [ ] **Step 1: Read current file**

- [ ] **Step 2: Replace with new intent types**

Replace the file contents with the domain-grouped `VoiceIntent` hierarchy from the [Voice Intents spec](../specs/voice-intents.md). Each tool domain is a sealed sub-interface with a data class per action. Example for the playback domain:

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

sealed interface VoiceIntent {
    sealed interface Playback : VoiceIntent
    sealed interface Effects : VoiceIntent
    sealed interface Volume : VoiceIntent
    sealed interface Sleep : VoiceIntent
    // ... one sub-interface per tool (25 total)
}

sealed interface PlaybackIntent : VoiceIntent.Playback {
    data object Pause : PlaybackIntent
    data object Resume : PlaybackIntent
    data class SeekRelative(val deltaMs: Int) : PlaybackIntent
    data class SeekAbsolute(val positionMs: Int) : PlaybackIntent
    data object NextEpisode : PlaybackIntent
}
```

The full hierarchy covers all 25 tool domains. See the spec for the complete list. This replaces the old flat `VoiceIntent` with the domain-grouped pattern and adds `AdjustSpeed`, `Sleep` variants, `SetTrimMode`, `SetVolumeBoost`, `AddBookmark` across the relevant sub-interfaces.

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/VoiceIntent.kt
git commit -m "Refactor voice intents: split speed/volume, add trim/boost/bookmark"
```

---

### Task 4: Update executor and sink interfaces

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/playback/VoiceIntentExecutor.kt`

- [ ] **Step 1: Read the current file**

- [ ] **Step 2: Update the VoicePlaybackSink interface**

Replace the existing interface with the per-domain sink pattern from the [Voice Intents spec](../specs/voice-intents.md). Each tool domain gets its own sink. Sinks return `VoiceResponse` for the confirmation strategy (silent, earcon, or spoken — as defined in the dialogs):

```kotlin
sealed interface VoiceResponse {
    data object Silent : VoiceResponse
    data class Earcon(val id: String) : VoiceResponse
    data class Spoken(val text: String) : VoiceResponse
}

interface VoicePlaybackSink {
    suspend fun pause(): VoiceResponse
    suspend fun resume(): VoiceResponse
    suspend fun skipForward(seconds: Int): VoiceResponse
    suspend fun skipBackward(seconds: Int): VoiceResponse
    suspend fun seekTo(positionMs: Int): VoiceResponse
    fun nextEpisode(): VoiceResponse
}

interface VoiceEffectsSink {
    fun setSpeed(speed: Double): VoiceResponse
    fun adjustSpeed(delta: Double): VoiceResponse
    fun setTrimMode(mode: String): VoiceResponse
    fun setVolumeBoost(enabled: Boolean): VoiceResponse
    fun queryEffects(): VoiceResponse.Spoken
}

interface VoiceVolumeSink {
    fun setVolume(volume: Int): VoiceResponse
    fun adjustVolume(delta: Int): VoiceResponse
    fun query(): VoiceResponse.Spoken
}

interface VoiceSleepSink {
    fun set(minutes: Int): VoiceResponse
    fun endOfEpisode(): VoiceResponse
    fun endOfChapter(): VoiceResponse
    fun addTime(minutes: Int): VoiceResponse
    fun cancel(): VoiceResponse
    fun query(): VoiceResponse.Spoken
}

// ... one sink per tool (VoiceChapterSink, VoiceBookmarkSink, VoiceQueueSink, etc.)
```

This replaces the old single `VoicePlaybackSink` that mixed playback, chapters, bookmarks, and effects into one interface. Each sink maps 1:1 to a FunctionGemma tool.

- [ ] **Step 3: Update the executor when-branch**

Replace the current when-expression with domain dispatch:

```kotlin
class VoiceIntentExecutor @Inject constructor(
    private val playbackSink: VoicePlaybackSink,
    private val effectsSink: VoiceEffectsSink,
    private val volumeSink: VoiceVolumeSink,
    private val sleepSink: VoiceSleepSink,
    // ... one sink per tool domain
) {
    suspend fun execute(intent: VoiceIntent): VoiceResponse = when (intent) {
        is PlaybackIntent -> executePlayback(intent)
        is EffectsIntent -> executeEffects(intent)
        is VolumeIntent -> executeVolume(intent)
        is SleepIntent -> executeSleep(intent)
        // ... one branch per domain
    }

    private suspend fun executePlayback(intent: PlaybackIntent): VoiceResponse = when (intent) {
        is PlaybackIntent.Pause -> playbackSink.pause()
        is PlaybackIntent.Resume -> playbackSink.resume()
        is PlaybackIntent.SeekRelative -> if (intent.deltaMs >= 0)
            playbackSink.skipForward(intent.deltaMs / 1000)
        else
            playbackSink.skipBackward(-intent.deltaMs / 1000)
        is PlaybackIntent.SeekAbsolute -> playbackSink.seekTo(intent.positionMs.coerceAtLeast(0))
        is PlaybackIntent.NextEpisode -> playbackSink.nextEpisode()
    }
}
```

Remove any obsolete branches (`SetPlaybackSpeed`). The `ChapterByTitle` branch remains a no-op until chapter search is implemented. See the spec for the complete executor dispatch across all 25 domains.

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/playback/VoiceIntentExecutor.kt
git commit -m "Update executor and sink interface with new voice intents"
```

---

### Task 5: Implement sink methods per domain

**Files:**
- Create: `modules/services/voice/src/main/kotlin/.../playback/PlaybackManagerPlaybackSink.kt`
- Create: `modules/services/voice/src/main/kotlin/.../playback/PlaybackManagerEffectsSink.kt`
- Create: `modules/services/voice/src/main/kotlin/.../playback/AudioManagerVolumeSink.kt`
- Create: `modules/services/voice/src/main/kotlin/.../playback/SleepTimerSink.kt`

Implement each domain sink as its own class, returning `VoiceResponse` per the confirmation strategy defined in the spec. The old single `PlaybackManagerVoicePlaybackSink` is split into domain-specific classes.

Example — effects sink:

```kotlin
@Singleton
class PlaybackManagerEffectsSink @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val settings: Settings,
) : VoiceEffectsSink {
    override fun setSpeed(speed: Double): VoiceResponse {
        val clamped = speed.coerceIn(0.5, 5.0)
        val effects = settings.globalPlaybackEffects.value
        effects.playbackSpeed = clamped
        settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
        playbackManager.updatePlayerEffects(effects = effects)
        return VoiceResponse.Earcon("speed")
    }

    override fun adjustSpeed(delta: Double): VoiceResponse {
        val current = playbackManager.getPlaybackSpeed()
        return setSpeed(current + delta)
    }
    // setTrimMode, setVolumeBoost, queryEffects similarly
}
```

Volume sink wraps `AudioManager`:

```kotlin
@Singleton
class AudioManagerVolumeSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceVolumeSink {
    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun setVolume(volume: Int): VoiceResponse {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max / 100).coerceIn(0, max), 0)
        return VoiceResponse.Earcon("volume")
    }
    // adjustVolume, query similarly
}
```

Sleep sink wraps `SleepTimer`:

```kotlin
@Singleton
class SleepTimerSink @Inject constructor(
    private val sleepTimer: SleepTimer,
) : VoiceSleepSink {
    override fun set(minutes: Int): VoiceResponse {
        sleepTimer.sleepAfter(java.time.Duration.ofMinutes(minutes.toLong()))
        return VoiceResponse.Earcon("sleep")
    }
    override fun addTime(minutes: Int): VoiceResponse {
        sleepTimer.addExtraTime(java.time.Duration.ofMinutes(minutes.toLong()))
        return VoiceResponse.Earcon("sleep")
    }
    // endOfEpisode, endOfChapter, cancel, query similarly
}
```

Update DI bindings in `VoiceControlModule.kt`:

```kotlin
@Binds abstract fun bindVoicePlaybackSink(impl: PlaybackManagerPlaybackSink): VoicePlaybackSink
@Binds abstract fun bindVoiceEffectsSink(impl: PlaybackManagerEffectsSink): VoiceEffectsSink
@Binds abstract fun bindVoiceVolumeSink(impl: AudioManagerVolumeSink): VoiceVolumeSink
@Binds abstract fun bindVoiceSleepSink(impl: SleepTimerSink): VoiceSleepSink
```

- [ ] **Step 2: Add needed imports**
- [ ] **Step 3: Commit**

---

### Task 6: Build and verify

- [ ] **Step 1: Build the app**

```bash
./gradlew :app:assembleDebugProd 2>&1 | tail -30
```

Fix any compilation errors.

- [ ] **Step 2: Run voice module unit tests**

```bash
./gradlew :modules:services:voice:testDebugUnitTest
```

Expected: all tests pass, including pre-existing tests for executor, gate rules, and segmenter.

- [ ] **Step 3: Install on device**

```bash
./gradlew :app:installDebugProd 2>&1 | tail -10
```

- [ ] **Step 4: Final commit if any fixups**

```bash
git commit -am "fixup: address build errors"
```
