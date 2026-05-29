# Playback Controls — Implementation Plan

> **Spec:** [playback-controls spec](../specs/playback-controls.md) — intent definitions, executor wiring, sink interface.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up all voice intents through to actual playback actions — sleep timer, volume, trim mode, volume boost, and bookmarks — and refactor dual-param speed/volume intents into separate absolute/adjust types.

**Scope:** This plan covers only the **execution layer**: the sealed intent interface, the executor, the sink interface, and sink implementations. The recognition layer — intent matching and entity extraction — is handled by the [ASR Intent Pipeline plan](asr-intent-pipeline.md), which registers the intent keywords and slot grammars for every intent listed here.

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

### Task 2: Refactor VoiceIntent sealed interface

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/VoiceIntent.kt`

- [ ] **Step 1: Read current file**

- [ ] **Step 2: Replace with new intent types**

Replace the file contents with:

```kotlin
package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

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

This replaces the old `SetPlaybackSpeed` with the split `SetSpeed` / `AdjustSpeed` and adds `SetVolume`, `AdjustVolume`, `SleepTimer`, `SetTrimMode`, `SetVolumeBoost`, `AddBookmark`.

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/intent/VoiceIntent.kt
git commit -m "Refactor voice intents: split speed/volume, add trim/boost/bookmark"
```

---

### Task 3: Update executor and sink interface

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/playback/VoiceIntentExecutor.kt`

- [ ] **Step 1: Read the current file**

- [ ] **Step 2: Update the VoicePlaybackSink interface**

Replace the existing interface with the expanded version:

```kotlin
interface VoicePlaybackSink {
    suspend fun pause()
    suspend fun resume()
    suspend fun skipForward(seconds: Int)
    suspend fun skipBackward(seconds: Int)
    suspend fun seekTo(positionMs: Int)
    fun nextChapter()
    fun previousChapter()
    fun chapterByIndex(index: Int)
    fun nextEpisode()
    fun setSpeed(speed: Double)
    fun adjustSpeed(delta: Double)
    fun setVolume(volume: Int)
    fun adjustVolume(delta: Int)
    fun sleepAfter(minutes: Int)
    fun setTrimMode(mode: String)
    fun setVolumeBoost(enabled: Boolean)
    fun addBookmark(title: String)
}
```

- [ ] **Step 3: Update the executor when-branch**

Replace the current when-expression to handle all new intents:

```kotlin
suspend fun execute(intent: VoiceIntent) {
    when (intent) {
        VoiceIntent.Pause -> sink.pause()
        VoiceIntent.Resume -> sink.resume()
        is VoiceIntent.SeekRelative -> {
            val seconds = abs(intent.deltaMs / 1000)
            if (intent.deltaMs >= 0) sink.skipForward(seconds)
            else sink.skipBackward(seconds)
        }
        is VoiceIntent.SeekAbsolute -> sink.seekTo(intent.positionMs.coerceAtLeast(0))
        VoiceIntent.NextChapter -> sink.nextChapter()
        VoiceIntent.PreviousChapter -> sink.previousChapter()
        VoiceIntent.NextEpisode -> sink.nextEpisode()
        is VoiceIntent.ChapterByIndex -> sink.chapterByIndex(intent.index)
        is VoiceIntent.ChapterByTitle -> Unit // TODO: chapter search
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
```

Remove any obsolete branches (`SetPlaybackSpeed`). Keep `SeekAbsolute`. The `ChapterByTitle` branch remains a no-op until chapter search is implemented.

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/playback/VoiceIntentExecutor.kt
git commit -m "Update executor and sink interface with new voice intents"
```

---

### Task 4: Implement PlaybackManagerVoicePlaybackSink new methods

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/playback/VoiceIntentExecutor.kt`

- [ ] **Step 1: Read the PlaybackManagerVoicePlaybackSink class**

- [ ] **Step 2: Add SleepTimer and BookmarkManager to constructor**

```kotlin
class PlaybackManagerVoicePlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val settings: Settings,
    private val sleepTimer: SleepTimer,
    private val bookmarkManager: BookmarkManager,
    private val audioManager: android.media.AudioManager,
) : VoicePlaybackSink {
```

- [ ] **Step 3: Implement new methods, keep existing ones**

Add these methods:

```kotlin
override fun setSpeed(speed: Double) {
    val clamped = speed.coerceIn(0.5, 5.0)
    val effects = settings.globalPlaybackEffects.value
    effects.playbackSpeed = clamped
    settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
    playbackManager.updatePlayerEffects(effects = effects)
}

override fun adjustSpeed(delta: Double) {
    val current = playbackManager.getPlaybackSpeed()
    setSpeed(current + delta)
}

override fun setVolume(volume: Int) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val scaled = (volume * max / 100).coerceIn(0, max)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaled, 0)
}

override fun adjustVolume(delta: Int) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val target = (current + delta * max / 100).coerceIn(0, max)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
}

override fun sleepAfter(minutes: Int) {
    if (minutes > 0) {
        sleepTimer.sleepAfter(java.time.Duration.ofMinutes(minutes.toLong()))
    } else {
        sleepTimer.cancelTimer()
    }
}

override fun setTrimMode(mode: String) {
    val trimMode = when (mode.lowercase()) {
        "low" -> TrimMode.LOW
        "medium" -> TrimMode.MEDIUM
        "high" -> TrimMode.HIGH
        else -> TrimMode.OFF
    }
    val effects = settings.globalPlaybackEffects.value
    effects.trimMode = trimMode
    settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
    playbackManager.updatePlayerEffects(effects = effects)
}

override fun setVolumeBoost(enabled: Boolean) {
    val effects = settings.globalPlaybackEffects.value
    effects.isVolumeBoosted = enabled
    settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
    playbackManager.updatePlayerEffects(effects = effects)
}

override fun addBookmark(title: String) {
    val episode = playbackManager.getCurrentEpisode() ?: return
    val positionMs = playbackManager.getCurrentTimeMs(episode)
    val timeSecs = (positionMs / 1000).toInt()
    bookmarkManager.sourceView = SourceView.VOICE_COMMANDS
    runBlocking {
        bookmarkManager.add(
            episode = episode,
            timeSecs = timeSecs,
            title = title,
            creationSource = BookmarkSourceType.Headphones,
        )
    }
}
```

- [ ] **Step 4: Add needed imports**

Add to the top of the file:
```kotlin
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.type.TrimMode
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimer
import com.automattic.eventhorizon.BookmarkSourceType
import android.media.AudioManager
import kotlinx.coroutines.runBlocking
```

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/playback/VoiceIntentExecutor.kt
git commit -m "Implement sink methods for volume, sleep timer, trim, boost, and bookmark"
```

---

### Task 5: Build and verify

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
