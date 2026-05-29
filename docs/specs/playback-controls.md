# Playback Controls

## Summary

Extend the voice command system to wire up all currently defined intents through to actual playback actions, and add new intents for remaining player controls: trim silence, volume boost, and bookmarks. Refactor ambiguous dual-nullable-parameter intents into separate types.

This spec owns the intent schema and the playback-side wiring (executor, sink, sink implementation). How an utterance
becomes a matched intent + extracted slots is owned by the [ASR Intent Pipeline spec](asr-intent-pipeline.md).

## Intents

| Intent | Intent keyword(s) | Slot |
|---|---|---|
| `SetSpeed(speed: Double)` | "set speed", "change speed" | `speed` (0.5–5.0) |
| `AdjustSpeed(delta: Double)` | "faster", "slower", "speed up" | `delta` |
| `SetVolume(volume: Int)` | "set volume" | `volume` (0–100) |
| `AdjustVolume(delta: Int)` | "louder", "quieter", "volume up/down" | `delta` |
| `SetTrimMode(mode: String)` | "trim silence", "silence trimming" | `mode` (off/low/medium/high) |
| `SetVolumeBoost(enabled: Boolean)` | "boost", "volume boost" | `enabled` (true/false) |
| `AddBookmark(title: String)` | "bookmark", "save this" | `title` |

Existing intents that need sink wiring:

- `SleepTimer(minutes: Int)` — set (minutes > 0) or cancel (minutes = 0)
- `ChapterByTitle(query: String)` — chapter search (remains no-op)

### Sealed interface

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

## Recognition wiring

Registering these intents in the recognition pipeline (per the [ASR Intent Pipeline spec](asr-intent-pipeline.md)):

- **Intent matcher** — add a keyword entry per intent so the embedding matcher can classify it:
  - `set_speed` → "set speed", "change speed"
  - `adjust_speed_up` / `adjust_speed_down` → "faster"/"speed up", "slower"/"speed down"
  - `set_volume` → "set volume"
  - `adjust_volume_up` / `adjust_volume_down` → "louder"/"volume up", "quieter"/"volume down"
  - `set_trim` → "trim silence", "silence trimming"
  - `set_volume_boost` → "boost", "volume boost"
  - `add_bookmark` → "bookmark", "save this"
- **Entity extractor** — each parameterized intent reads its slot from the language grammars:
  - `set_speed` → `speed` (number, 0.5–5.0); `adjust_speed_*` → `speed` delta (default ±0.5)
  - `set_volume` → `volume` (0–100); `adjust_volume_*` → `volume` delta (default ±10)
  - `set_trim` → `trim_mode` (off/low/medium/high; bare "trim silence" defaults to medium, "no trim" → off)
  - `set_volume_boost` → boolean (affirmative/negative)
  - `add_bookmark` → `title` (free text after stripping the matched keyword)
  - `sleep_timer` → `duration` → minutes

The matched intent type plus its normalized slot value is assembled into the `VoiceIntent` below and dispatched to
the executor.

## Executor — `VoiceIntentExecutor`

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

Add methods to the existing interface:
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

Implementation details for the new sink methods:

| Method | Implementation |
|---|---|
| `setSpeed(speed)` | Clamp [0.5, 5.0], update `PlaybackEffects.playbackSpeed` + `updatePlayerEffects` |
| `adjustSpeed(delta)` | `playbackManager.getPlaybackSpeed() + delta`, clamp, same effects path |
| `setVolume(volume)` | Scale 0–100 to `AudioManager.STREAM_MUSIC` range, call `setStreamVolume` |
| `adjustVolume(delta)` | Read current stream volume, add delta (scaled), apply |
| `sleepAfter(minutes)` | If > 0: `sleepTimer.sleepAfter(minutes.minutes)`. Else: `sleepTimer.cancelTimer()` |
| `setTrimMode(mode)` | Parse mode string → `TrimMode`, update `PlaybackEffects.trimMode` + `updatePlayerEffects` |
| `setVolumeBoost(enabled)` | Update `PlaybackEffects.isVolumeBoosted` + `updatePlayerEffects` |
| `addBookmark(title)` | Get current episode + position from `playbackManager`, call `bookmarkManager.add(...)` |

## Analytics

Add `SourceView.VOICE_COMMANDS` to the `SourceView` enum for tracking voice-initiated actions.

## Out of scope

- Queue management (play next, clear up next, etc.)
- Speed presets / reset to 1x (covered by `SetSpeed(1.0)`)
- ChapterByTitle search logic (remains no-op)
- Play/pause toggle (pause and resume are already separate)
