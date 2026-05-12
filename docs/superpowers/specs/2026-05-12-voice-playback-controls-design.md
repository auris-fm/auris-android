# Voice Playback Controls — Design Spec

## Summary

Extend the voice command system to wire up all currently defined intents through to
actual playback actions, and add new intents for remaining player "knobs": trim
silence, volume boost, and bookmarks. Refactor ambiguous dual-nullable-parameter
intents into separate types.

## Intents

| Intent | System prompt JSON |
|---|---|
| `SetSpeed(speed: Double)` | `{"intent": "set_speed", "speed": 2.0}` |
| `AdjustSpeed(delta: Double)` | `{"intent": "adjust_speed", "delta": 0.5}` |
| `SetVolume(volume: Int)` | `{"intent": "set_volume", "volume": 50}` |
| `AdjustVolume(delta: Int)` | `{"intent": "adjust_volume", "delta": 10}` |
| `SetTrimMode(mode: String)` | `{"intent": "set_trim", "mode": "low"}` |
| `SetVolumeBoost(enabled: Boolean)` | `{"intent": "set_volume_boost", "enabled": true}` |
| `AddBookmark(title: String)` | `{"intent": "add_bookmark", "title": "good part"}` |

Existing intents that need sink wiring:

- `SleepTimer(minutes: Int)` — set (minutes > 0) or cancel (minutes = 0)
- `ChapterByTitle(query: String)` — chapter search (remains no-op)

### Sealed interface

```kotlin
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

## System Prompt

Update `Gemma4VoiceRecognizer.systemPrompt`:
- Add `set_speed` with `speed` (absolute, 0.5–5.0)
- Add `adjust_speed` with `delta` (relative adjustment)
- Add `set_volume` with `volume` (absolute, 0–100)
- Add `adjust_volume` with `delta` (relative adjustment)
- Add `set_trim` with `mode` (off/low/medium/high)
- Add `set_volume_boost` with `enabled` (true/false)
- Add `add_bookmark` with `title`
- Keep existing intents (pause, resume, seek, chapter nav, sleep_timer, next_episode)
- Update aliases to include trim/boost/bookmark variations

## Intent Parser

Update `parseIntent()` in `Gemma4VoiceRecognizer`:
- `"set_speed"` — reads `speed` (required, 0.5–5.0). Route to `SetSpeed`.
- `"adjust_speed"` — reads `delta` (required). Route to `AdjustSpeed`.
- `"set_volume"` — reads `volume` (required, 0–100). Route to `SetVolume`.
- `"adjust_volume"` — reads `delta` (required). Route to `AdjustVolume`.
- `"sleep_timer"` — reads `minutes`. Route to `SleepTimer`.
- `"set_trim"` — reads `mode` (off/low/medium/high). Route to `SetTrimMode`.
- `"set_volume_boost"` — reads `enabled` (boolean). Route to `SetVolumeBoost`.
- `"add_bookmark"` — reads `title`. Route to `AddBookmark`.

## Executor — `VoicePlaybackIntentExecutor`

Map each intent to a `VoicePlaybackSink` method:

| Intent | Sink method |
|---|---|
| `SetSpeed(speed)` | `setSpeed(speed)` |
| `AdjustSpeed(delta)` | `adjustSpeed(delta)` |
| `SetVolume(volume)` | `setVolume(volume)` |
| `AdjustVolume(delta)` | `adjustVolume(delta)` |
| `SleepTimer(minutes)` | `sleepAfter(minutes)` if > 0, else `cancelSleepTimer()` |
| `SetTrimMode(mode)` | `setTrimMode(mode)` |
| `SetVolumeBoost(enabled)` | `setVolumeBoost(enabled)` |
| `AddBookmark(title)` | `addBookmark(title)` |

## Sink Interface — `VoicePlaybackSink`

Add methods:
```kotlin
fun setSpeed(speed: Double)
fun adjustSpeed(delta: Double)
fun setVolume(volume: Int)
fun adjustVolume(delta: Int)
fun sleepAfter(minutes: Int)
fun setTrimMode(mode: String)
fun setVolumeBoost(enabled: Boolean)
fun addBookmark(title: String)
```

## `PlaybackManagerVoicePlaybackSink`

Inject `SleepTimer` and `BookmarkManager` (both already `@Singleton`).

| Method | Implementation |
|---|---|
| `setSpeed(speed)` | Clamp [0.5, 5.0], update `PlaybackEffects.playbackSpeed` + `updatePlayerEffects` |
| `adjustSpeed(delta)` | `playbackManager.getPlaybackSpeed() + delta`, clamp, same effects path |
| `setVolume(volume)` | Scale 0–100 to `AudioManager.STREAM_MUSIC` range, call `setStreamVolume` |
| `adjustVolume(delta)` | Read current stream volume, add delta, apply |
| `sleepAfter(minutes)` | If > 0: `sleepTimer.sleepAfter(minutes.minutes)`. Else: `sleepTimer.cancelTimer()` |
| `setTrimMode(mode)` | Parse mode string → `TrimMode`, update `PlaybackEffects.trimMode` + `updatePlayerEffects` |
| `setVolumeBoost(enabled)` | Update `PlaybackEffects.isVolumeBoosted` + `updatePlayerEffects` |
| `addBookmark(title)` | Get current episode + position from `playbackManager`, call `bookmarkManager.add(...)` |

## Analytics

Add `SourceView.VOICE_COMMANDS` to the `SourceView` enum for tracking voice-initiated actions.

## Files to change

| File | Changes |
|---|---|
| `modules/services/analytics/.../SourceView.kt` | Add `VOICE_COMMANDS` entry |
| `modules/services/voice/.../intent/VoicePlaybackIntent.kt` | Add new intent types; remove `SetPlaybackSpeed`/`SetVolume` dual-param variants |
| `modules/services/voice/.../model/Gemma4VoiceRecognizer.kt` | Update system prompt, parseIntent branches |
| `modules/services/voice/.../playback/VoicePlaybackIntentExecutor.kt` | Add sink methods, update executor when-branch |
| `modules/services/voice/.../playback/VoicePlaybackSink` (in same file) | New interface methods |

## Out of scope

- Queue management (play next, clear up next, etc.)
- Speed presets / reset to 1x (covered by `SetSpeed(1.0)`)
- ChapterByTitle search logic (remains no-op)
- Play/pause toggle (pause and resume are already separate)
