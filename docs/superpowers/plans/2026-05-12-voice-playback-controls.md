# Voice Playback Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up all voice intents through to actual playback actions — sleep timer, volume, trim mode, volume boost, and bookmarks — and refactor dual-param speed/volume intents into separate absolute/adjust types.

**Architecture:** Voice intents flow: Gemma 4 model → JSON parsing → sealed `VoicePlaybackIntent` → `VoicePlaybackIntentExecutor` → `VoicePlaybackSink` → `PlaybackManagerVoicePlaybackSink` → `PlaybackManager`/`SleepTimer`/`BookmarkManager`/`AudioManager`.

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

### Task 2: Refactor VoicePlaybackIntent sealed interface

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/VoicePlaybackIntent.kt`

- [ ] **Step 1: Read current file**

- [ ] **Step 2: Replace with new intent types**

Replace the file contents with:

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.intent

sealed interface VoicePlaybackIntent {
    data object Pause : VoicePlaybackIntent
    data object Resume : VoicePlaybackIntent
    data class SeekRelative(val deltaMs: Int) : VoicePlaybackIntent
    data class SeekAbsolute(val positionMs: Int) : VoicePlaybackIntent
    data object NextChapter : VoicePlaybackIntent
    data object PreviousChapter : VoicePlaybackIntent
    data class ChapterByIndex(val index: Int) : VoicePlaybackIntent
    data class ChapterByTitle(val query: String) : VoicePlaybackIntent {
        val normalizedQuery: String = query.trim()
    }
    data object NextEpisode : VoicePlaybackIntent
    data class SetSpeed(val speed: Double) : VoicePlaybackIntent
    data class AdjustSpeed(val delta: Double) : VoicePlaybackIntent
    data class SetVolume(val volume: Int) : VoicePlaybackIntent
    data class AdjustVolume(val delta: Int) : VoicePlaybackIntent
    data class SleepTimer(val minutes: Int) : VoicePlaybackIntent
    data class SetTrimMode(val mode: String) : VoicePlaybackIntent
    data class SetVolumeBoost(val enabled: Boolean) : VoicePlaybackIntent
    data class AddBookmark(val title: String) : VoicePlaybackIntent
}
```

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/VoicePlaybackIntent.kt
git commit -m "Refactor voice intents: split speed/volume, add trim/boost/bookmark"
```

---

### Task 3: Update system prompt in Gemma4VoiceRecognizer

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/Gemma4VoiceRecognizer.kt`

- [ ] **Step 1: Read the current systemPrompt block (lines ~34-71)**

- [ ] **Step 2: Replace the system prompt**

Replace the existing prompt with one that includes all new intents. The prompt structure stays the same (intent list + aliases + examples) but:
- Replace `set_speed` with absolute-only entries: `set_speed(speed)` + new `adjust_speed(delta)`
- Replace `set_volume` with absolute-only entries: `set_volume(volume)` + new `adjust_volume(delta)`
- Add `set_trim(mode: off/low/medium/high)`
- Add `set_volume_boost(enabled: true/false)`
- Add `add_bookmark(title: "...")`
- Add aliases for new intents: "louder" / "quieter", "trim silence" / "silence trimming", "boost", "bookmark this"
- Add examples for new intents

Updated prompt content:

```kotlin
private val systemPrompt = """
    You are a voice command processor for a podcast player. Given audio of a user speaking a
    command, respond with ONLY a JSON object representing the closest matching intent. Include
    the transcribed speech in a "text" field. The user may speak in any language. Available
    intents:

    {"intent": "pause", "text": "<transcribed speech>"}
    {"intent": "resume", "text": "<transcribed speech>"}
    {"intent": "seek_relative", "delta_seconds": <positive integer>, "text": "<transcribed speech>"}
    {"intent": "seek_absolute", "position_seconds": <positive integer>, "text": "<transcribed speech>"}
    {"intent": "next_chapter", "text": "<transcribed speech>"}
    {"intent": "previous_chapter", "text": "<transcribed speech>"}
    {"intent": "chapter_by_index", "index": <non-negative integer>, "text": "<transcribed speech>"}
    {"intent": "chapter_by_title", "query": "<chapter name>", "text": "<transcribed speech>"}
    {"intent": "next_episode", "text": "<transcribed speech>"}
    {"intent": "set_speed", "speed": <0.5 to 5.0>, "text": "<transcribed speech>"}
    {"intent": "adjust_speed", "delta": <signed increment>, "text": "<transcribed speech>"}
    {"intent": "set_volume", "volume": <0 to 100>, "text": "<transcribed speech>"}
    {"intent": "adjust_volume", "delta": <signed increment>, "text": "<transcribed speech>"}
    {"intent": "sleep_timer", "minutes": <positive integer; 0 to cancel>, "text": "<transcribed speech>"}
    {"intent": "set_trim", "mode": "off"|"low"|"medium"|"high", "text": "<transcribed speech>"}
    {"intent": "set_volume_boost", "enabled": true|false, "text": "<transcribed speech>"}
    {"intent": "add_bookmark", "title": "<bookmark label>", "text": "<transcribed speech>"}

    Common aliases:
    "play" → {"intent": "resume"} | "stop" → {"intent": "pause"}
    "next" → {"intent": "next_chapter"} | "previous" → {"intent": "previous_chapter"}
    "faster" / "speed up" → {"intent": "adjust_speed", "delta": 0.5}
    "slower" / "slow down" → {"intent": "adjust_speed", "delta": -0.5}
    "forward X" / "skip X" → {"intent": "seek_relative", "delta_seconds": X}
    "go back X" → {"intent": "seek_relative", "delta_seconds": -X}
    "turn off" → {"intent": "sleep_timer", "minutes": 0}
    "volume up" → {"intent": "adjust_volume", "delta": 10}
    "volume down" → {"intent": "adjust_volume", "delta": -10}
    "set volume X" → {"intent": "set_volume", "volume": X}
    "louder" → {"intent": "adjust_volume", "delta": 10}
    "quieter" → {"intent": "adjust_volume", "delta": -10}
    "trim silence" / "silence trimming" → {"intent": "set_trim", "mode": "medium"}
    "no trim" → {"intent": "set_trim", "mode": "off"}
    "boost" / "turn on boost" → {"intent": "set_volume_boost", "enabled": true}
    "no boost" → {"intent": "set_volume_boost", "enabled": false}
    "bookmark this" / "save this" → {"intent": "add_bookmark", "title": "Voice bookmark"}
    "set speed X" → {"intent": "set_speed", "speed": X}

    Examples:
    "暂停" → {"intent": "pause", "text": "暂停"}
    "快进30秒" → {"intent": "seek_relative", "delta_seconds": 30, "text": "快进30秒"}
    "下一章" → {"intent": "next_chapter", "text": "下一章"}
    "speed up" → {"intent": "adjust_speed", "delta": 0.5, "text": "speed up"}
    "set volume to 70" → {"intent": "set_volume", "volume": 70, "text": "set volume to 70"}
    "turn on trim" → {"intent": "set_trim", "mode": "medium", "text": "turn on trim"}
    "bookmark this" → {"intent": "add_bookmark", "title": "Voice bookmark", "text": "bookmark this"}

    If the speech is not a playback command, respond with {"intent": "none", "text": "<transcribed speech>"}.
""".trimIndent()
```

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/Gemma4VoiceRecognizer.kt
git commit -m "Update system prompt with new voice intents"
```

---

### Task 4: Update parseIntent branches

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/Gemma4VoiceRecognizer.kt`

- [ ] **Step 1: Read the parseIntent method (lines ~144-208)**

- [ ] **Step 2: Update the when-expression**

Replace the existing `set_speed`, `set_volume` branches and add new branches:

```kotlin
"set_speed" -> {
    val speed = json.optDouble("speed", -1.0)
    if (speed in 0.5..5.0) VoicePlaybackIntent.SetSpeed(speed) else null
}
"adjust_speed" -> {
    val delta = json.optDouble("delta", 0.0)
    if (delta != 0.0) VoicePlaybackIntent.AdjustSpeed(delta) else null
}
"set_volume" -> {
    val volume = json.optInt("volume", -1)
    if (volume in 0..100) VoicePlaybackIntent.SetVolume(volume) else null
}
"adjust_volume" -> {
    val delta = json.optInt("delta", 0)
    if (delta != 0) VoicePlaybackIntent.AdjustVolume(delta) else null
}
"set_trim" -> {
    val mode = json.optString("mode", "")
    if (mode in listOf("off", "low", "medium", "high")) VoicePlaybackIntent.SetTrimMode(mode) else null
}
"set_volume_boost" -> {
    val enabled = json.optBoolean("enabled", false)
    VoicePlaybackIntent.SetVolumeBoost(enabled)
}
"add_bookmark" -> {
    val title = json.optString("title", "")
    if (title.isNotBlank()) VoicePlaybackIntent.AddBookmark(title) else null
}
```

Remove the old `set_speed` and `set_volume` branches (with dual speed/delta and volume/delta). Keep the existing `sleep_timer` branch unchanged.

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/Gemma4VoiceRecognizer.kt
git commit -m "Update parseIntent with new voice intent branches"
```

---

### Task 5: Update executor when-branch and sink interface

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/VoicePlaybackIntentExecutor.kt`

- [ ] **Step 1: Read the current file**

- [ ] **Step 2: Update the VoicePlaybackSink interface**

Replace the existing interface (setSpeed, adjustSpeed, nextEpisode) with the expanded version:

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

Replace the current play/intent when-expression to handle all new intents:

```kotlin
suspend fun execute(intent: VoicePlaybackIntent) {
    when (intent) {
        VoicePlaybackIntent.Pause -> sink.pause()
        VoicePlaybackIntent.Resume -> sink.resume()
        is VoicePlaybackIntent.SeekRelative -> {
            val seconds = abs(intent.deltaMs / 1000)
            if (intent.deltaMs >= 0) sink.skipForward(seconds)
            else sink.skipBackward(seconds)
        }
        VoicePlaybackIntent.NextChapter -> sink.nextChapter()
        VoicePlaybackIntent.PreviousChapter -> sink.previousChapter()
        VoicePlaybackIntent.NextEpisode -> sink.nextEpisode()
        is VoicePlaybackIntent.ChapterByIndex -> sink.chapterByIndex(intent.index)
        is VoicePlaybackIntent.ChapterByTitle -> Unit // TODO: chapter search
        is VoicePlaybackIntent.SetSpeed -> sink.setSpeed(intent.speed)
        is VoicePlaybackIntent.AdjustSpeed -> sink.adjustSpeed(intent.delta)
        is VoicePlaybackIntent.SetVolume -> sink.setVolume(intent.volume)
        is VoicePlaybackIntent.AdjustVolume -> sink.adjustVolume(intent.delta)
        is VoicePlaybackIntent.SleepTimer -> sink.sleepAfter(intent.minutes)
        is VoicePlaybackIntent.SetTrimMode -> sink.setTrimMode(intent.mode)
        is VoicePlaybackIntent.SetVolumeBoost -> sink.setVolumeBoost(intent.enabled)
        is VoicePlaybackIntent.AddBookmark -> sink.addBookmark(intent.title)
    }
}
```

Remove the old `VoicePlaybackIntent.SeekAbsolute` branch (no longer exists in the intent hierarchy — wait, it does exist. Keep it.)

Actually, looking at the current executor:

```kotlin
is VoicePlaybackIntent.SeekAbsolute -> {
    sink.seekTo(intent.positionMs)
}
```

Keep that branch. And remove the old:

```kotlin
is VoicePlaybackIntent.SetPlaybackSpeed -> { ... }
is VoicePlaybackIntent.SetVolume -> Unit
is VoicePlaybackIntent.SleepTimer -> Unit
```

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/VoicePlaybackIntentExecutor.kt
git commit -m "Update executor and sink interface with new voice intents"
```

---

### Task 6: Implement PlaybackManagerVoicePlaybackSink new methods

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/VoicePlaybackIntentExecutor.kt`

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
override fun setVolume(volume: Int) {
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    val scaled = (volume * max / 100).coerceIn(0, max)
    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, scaled, 0)
}

override fun adjustVolume(delta: Int) {
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    val target = (current + delta * max / 100).coerceIn(0, max)
    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
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
        "low" -> au.com.shiftyjelly.pocketcasts.models.type.TrimMode.LOW
        "medium" -> au.com.shiftyjelly.pocketcasts.models.type.TrimMode.MEDIUM
        "high" -> au.com.shiftyjelly.pocketcasts.models.type.TrimMode.HIGH
        else -> au.com.shiftyjelly.pocketcasts.models.type.TrimMode.OFF
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
    bookmarkManager.sourceView = au.com.shiftyjelly.pocketcasts.analytics.SourceView.VOICE_COMMANDS
    kotlinx.coroutines.runBlocking {
        bookmarkManager.add(
            episode = episode,
            timeSecs = timeSecs,
            title = title,
            creationSource = com.automattic.eventhorizon.BookmarkSourceType.Headphones,
        )
    }
}
```

Also update the existing `setSpeed` and `adjustSpeed` methods to match the new interface signatures. They should stay the same as they are today but swap `SetPlaybackSpeed` → `setSpeed` / `adjustSpeed`.

- [ ] **Step 4: Add needed imports**

Add to the top of the file:
```kotlin
import au.com.shiftyjelly.pocketcasts.models.type.TrimMode
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimer
import com.automattic.eventhorizon.BookmarkSourceType
```

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/playback/VoicePlaybackIntentExecutor.kt
git commit -m "Implement sink methods for volume, sleep timer, trim, boost, and bookmark"
```

---

### Task 7: Build and verify

- [ ] **Step 1: Build the app**

```bash
./gradlew :app:assembleDebugProd 2>&1 | tail -30
```

Fix any compilation errors.

- [ ] **Step 2: Install on device**

```bash
./gradlew :app:installDebugProd 2>&1 | tail -10
```

- [ ] **Step 3: Final commit if any fixups**

```bash
git commit -am "fixup: address build errors"  
# or squash with amend if desired
```
