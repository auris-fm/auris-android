# Voice Intents

## Summary

Define the complete set of voice intents and wire them through to actual actions. The voice control system supports hands-free interaction across every app feature — everything a user can do by touch can also be done by voice.

The [dialog docs](../dialogs/) define the full interaction patterns (phrasing, confirmations, edge cases) for each action category. This spec owns the intent schema, executor wiring, and sink interfaces. How an utterance becomes a matched intent + extracted slots is owned by the [Recognition Pipeline spec](recognition-pipeline.md).

## Architecture

```text
  ASR transcript
       │
       ▼
  FunctionGemma ── tool schema (~25 tools, one per feature + no_match)
       │
       ▼
  ToolCallMapper ── (tool, action, params) → VoiceIntent
       │
       ▼
  VoiceIntentExecutor ─── dispatches by domain ───► VoiceSink (per domain)
       │
       ▼
  VoiceResponse (silent | earcon | spoken)
```

Each tool covers a coherent feature with 2–12 actions. The `action` enum discriminates the specific operation. This keeps individual tool schemas small enough for FunctionGemma-270M to reliably discriminate.

```kotlin
sealed interface VoiceResponse {
    data object Silent : VoiceResponse
    data class Earcon(val id: String) : VoiceResponse
    data class Spoken(val text: String) : VoiceResponse
}
```

## Tool Schema

Each tool's parameters are the union of all its actions' parameters. Most are optional since different actions use different params. The `action` field is always required.

### `playback`

```json
{
  "name": "playback",
  "description": "Basic playback controls: pause, resume, skip forward or backward, seek to a position, play next episode.",
  "parameters": {
    "action": {"type": "string", "enum": ["pause", "resume", "seek_relative", "seek_to", "next_episode"]},
    "seconds": {"type": "integer", "description": "Seconds. For seek_relative: signed delta (positive=forward, negative=backward). For seek_to: absolute position from 0."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `pause` | — | earcon |
| `resume` | — | silent |
| `seek_relative` | `seconds` | silent |
| `seek_to` | `seconds` | silent |
| `next_episode` | — | earcon + announce title |

### `effects`

```json
{
  "name": "effects",
  "description": "Playback effects: speed, trim silence, volume boost.",
  "parameters": {
    "action": {"type": "string", "enum": ["set_speed", "adjust_speed", "set_trim_mode", "set_volume_boost", "query_effects"]},
    "speed": {"type": "number", "description": "Playback speed (0.5–5.0)."},
    "delta": {"type": "number", "description": "Speed delta. Positive = faster, negative = slower."},
    "mode": {"type": "string", "enum": ["off", "low", "medium", "high"], "description": "Trim silence mode."},
    "enabled": {"type": "boolean", "description": "On/off for volume boost."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `set_speed` | `speed` | earcon + spoken value |
| `adjust_speed` | `delta` | earcon + spoken value |
| `set_trim_mode` | `mode` | earcon + spoken mode |
| `set_volume_boost` | `enabled` | earcon + spoken state |
| `query_effects` | — | spoken |

### `volume`

```json
{
  "name": "volume",
  "description": "Control device volume.",
  "parameters": {
    "action": {"type": "string", "enum": ["set_volume", "adjust_volume", "query"]},
    "volume": {"type": "integer", "description": "Volume level (0–100)."},
    "delta": {"type": "integer", "description": "Volume delta. Positive = louder, negative = quieter."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `set_volume` | `volume` | earcon |
| `adjust_volume` | `delta` | earcon |
| `query` | — | spoken |

### `sleep`

```json
{
  "name": "sleep",
  "description": "Sleep timer: set a timer, stop at end of episode or chapter, add time, cancel.",
  "parameters": {
    "action": {"type": "string", "enum": ["set", "end_of_episode", "end_of_chapter", "add_time", "cancel", "query"]},
    "minutes": {"type": "integer", "description": "Duration in minutes."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `set` | `minutes` | earcon + spoken duration |
| `end_of_episode` | — | earcon + spoken |
| `end_of_chapter` | — | earcon + spoken |
| `add_time` | `minutes` | earcon + spoken remaining |
| `cancel` | — | earcon + spoken |
| `query` | — | spoken |

### `chapter`

```json
{
  "name": "chapter",
  "description": "Navigate and query episode chapters.",
  "parameters": {
    "action": {"type": "string", "enum": ["next", "previous", "by_index", "by_title", "open_link", "query_list", "query_current", "query_count", "query_next"]},
    "index": {"type": "integer", "description": "Chapter number (1-based)."},
    "query": {"type": "string", "description": "Chapter title search query."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `next` | — | silent |
| `previous` | — | silent |
| `by_index` | `index` | silent |
| `by_title` | `query` | silent |
| `open_link` | `index` | earcon |
| `query_list` | — | spoken |
| `query_current` | — | spoken |
| `query_count` | — | spoken |
| `query_next` | — | spoken |

### `bookmark`

```json
{
  "name": "bookmark",
  "description": "Create, rename, play, delete, and query bookmarks.",
  "parameters": {
    "action": {"type": "string", "enum": ["add", "rename", "play", "delete", "delete_all", "query_list", "query_count", "query_nearby"]},
    "title": {"type": "string", "description": "Bookmark title. For add (optional), rename (new title)."},
    "ref": {"type": "string", "description": "Bookmark reference: title, index number (e.g. '3'), or 'latest'."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `add` | `title` (optional) | earcon + spoken position |
| `rename` | `ref`, `title` | earcon |
| `play` | `ref` | silent |
| `delete` | `ref` | earcon |
| `delete_all` | — | earcon (explicit confirm) |
| `query_list` | — | spoken |
| `query_count` | — | spoken |
| `query_nearby` | — | spoken |

### `queue`

```json
{
  "name": "queue",
  "description": "Manage the Up Next queue: add, remove, reorder, clear.",
  "parameters": {
    "action": {"type": "string", "enum": ["add_top", "add_bottom", "remove", "move_to_top", "move_to_bottom", "clear", "remove_by_podcast", "sort", "query_contents", "query_next", "query_length", "query_is_queued"]},
    "episode": {"type": "string", "description": "Episode title or description."},
    "podcast": {"type": "string", "description": "Podcast name."},
    "sort_order": {"type": "string", "enum": ["newest_first", "oldest_first"]}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `add_top` | `episode` | earcon |
| `add_bottom` | `episode` | earcon |
| `remove` | `episode` | earcon |
| `move_to_top` | `episode` | earcon |
| `move_to_bottom` | `episode` | earcon |
| `clear` | — | earcon (explicit confirm) |
| `remove_by_podcast` | `podcast` | earcon |
| `sort` | `sort_order` | earcon |
| `query_contents` | — | spoken |
| `query_next` | — | spoken |
| `query_length` | — | spoken |
| `query_is_queued` | `episode` | spoken |

### `episode`

```json
{
  "name": "episode",
  "description": "Manage individual episodes: download, star, archive, mark played, add to playlist.",
  "parameters": {
    "action": {"type": "string", "enum": ["download", "delete_download", "star", "unstar", "archive", "unarchive", "mark_played", "mark_unplayed", "remove_from_history", "add_to_playlist"]},
    "episode": {"type": "string", "description": "Episode title or description."},
    "playlist": {"type": "string", "description": "Playlist name. For add_to_playlist."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `download` | `episode` | earcon |
| `delete_download` | `episode` | earcon (explicit confirm) |
| `star` | `episode` | earcon |
| `unstar` | `episode` | earcon |
| `archive` | `episode` | earcon |
| `unarchive` | `episode` | earcon |
| `mark_played` | `episode` | earcon |
| `mark_unplayed` | `episode` | earcon |
| `remove_from_history` | `episode` | earcon (explicit confirm) |
| `add_to_playlist` | `episode`, `playlist` | earcon |

### `podcast`

```json
{
  "name": "podcast",
  "description": "Manage podcast subscriptions: subscribe, unsubscribe, rate, configure notifications and auto-add.",
  "parameters": {
    "action": {"type": "string", "enum": ["subscribe", "unsubscribe", "rate", "toggle_notifications", "auto_add", "auto_download"]},
    "podcast": {"type": "string", "description": "Podcast name."},
    "rating": {"type": "integer", "description": "Star rating (1–5)."},
    "enabled": {"type": "boolean"},
    "position": {"type": "string", "enum": ["top", "bottom"], "description": "Queue position for auto-add."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `subscribe` | `podcast` | earcon |
| `unsubscribe` | `podcast` | earcon |
| `rate` | `podcast`, `rating` | earcon |
| `toggle_notifications` | `podcast`, `enabled` | earcon |
| `auto_add` | `podcast`, `position` | earcon |
| `auto_download` | `podcast`, `enabled` | earcon |

### `bulk`

```json
{
  "name": "bulk",
  "description": "Bulk actions on episodes: bulk download, archive, or mark as played.",
  "parameters": {
    "action": {"type": "string", "enum": ["download", "archive", "mark_played"]},
    "podcast": {"type": "string", "description": "Podcast name. Optional — omit for global scope."},
    "filter": {"type": "string", "description": "Filter: 'unplayed', 'played', 'downloaded', or 'last N'."}
  }
}
```

All actions return earcon and require explicit confirmation.

### `folder`

```json
{
  "name": "folder",
  "description": "Manage podcast folders: create, rename, assign podcasts, remove, delete.",
  "parameters": {
    "action": {"type": "string", "enum": ["create", "rename", "assign", "remove_from", "delete"]},
    "folder": {"type": "string", "description": "Folder name."},
    "name": {"type": "string", "description": "New name. For create and rename."},
    "podcast": {"type": "string", "description": "Podcast name. For assign and remove_from."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `create` | `name` | earcon |
| `rename` | `folder`, `name` | earcon |
| `assign` | `podcast`, `folder` | earcon |
| `remove_from` | `podcast` | earcon |
| `delete` | `folder` | earcon (explicit confirm) |

### `playlist`

```json
{
  "name": "playlist",
  "description": "Manage playlists: create, delete, rename, play all, add/remove episodes.",
  "parameters": {
    "action": {"type": "string", "enum": ["create", "create_smart", "delete", "rename", "play_all", "download_all", "add_episode", "remove_episode", "archive_all", "unarchive_all", "auto_download"]},
    "playlist": {"type": "string", "description": "Playlist name."},
    "name": {"type": "string", "description": "New playlist name. For create, rename."},
    "episode": {"type": "string", "description": "Episode title."},
    "shuffle": {"type": "boolean"},
    "enabled": {"type": "boolean"},
    "rules": {"type": "string", "description": "Smart playlist rules as natural language."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `create` | `name` (optional) | earcon + spoken name |
| `create_smart` | `rules`, `name` (optional) | earcon + spoken name |
| `delete` | `playlist` | earcon (explicit confirm) |
| `rename` | `playlist`, `name` | earcon |
| `play_all` | `playlist`, `shuffle` (optional) | silent |
| `download_all` | `playlist` | earcon (explicit confirm) |
| `add_episode` | `episode`, `playlist` | earcon |
| `remove_episode` | `episode`, `playlist` | earcon |
| `archive_all` | `playlist` | earcon (explicit confirm) |
| `unarchive_all` | `playlist` | earcon |
| `auto_download` | `playlist`, `enabled` | earcon |

### `search`

```json
{
  "name": "search",
  "description": "Search for podcasts and episodes, browse trending and recommendations.",
  "parameters": {
    "action": {"type": "string", "enum": ["search", "filter", "subscribe_result", "play_result", "describe_result", "rerun", "clear_history", "trending", "recommendations", "category", "new_releases", "change_region"]},
    "query": {"type": "string"},
    "type": {"type": "string", "enum": ["podcasts", "episodes"]},
    "ref": {"type": "string", "description": "Result reference: title or index number (e.g. '3')."},
    "category": {"type": "string"},
    "timeframe": {"type": "string"},
    "region": {"type": "string"}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `search` | `query` | spoken results |
| `filter` | `type` | spoken results |
| `subscribe_result` | `ref` | earcon |
| `play_result` | `ref` | silent |
| `describe_result` | `ref` | spoken |
| `rerun` | `query` (optional) | spoken results |
| `clear_history` | — | earcon (explicit confirm) |
| `trending` | — | spoken |
| `recommendations` | — | spoken |
| `category` | `category` | spoken |
| `new_releases` | `timeframe` (optional) | spoken |
| `change_region` | `region` | earcon |

### `playback_query`

```json
{
  "name": "playback_query",
  "description": "Query current playback state and episode info.",
  "parameters": {
    "action": {"type": "string", "enum": ["whats_playing", "position", "time_remaining", "current_podcast", "episode_duration", "publish_date", "episode_description", "download_status", "episode_title"]}
  }
}
```

All actions return spoken. No additional parameters.

### `stats_query`

```json
{
  "name": "stats_query",
  "description": "Query listening statistics: listening time, top podcasts, streaks, subscription counts.",
  "parameters": {
    "action": {"type": "string", "enum": ["listening_time", "top_podcasts", "episodes_finished", "listening_streak", "subscription_count", "unplayed_total", "download_stats", "queue_total", "new_episodes", "time_since_last_listen"]},
    "period": {"type": "string", "description": "Time period: 'today', 'this week', 'this month', 'all time'."},
    "timeframe": {"type": "string", "description": "Time window for new_episodes."}
  }
}
```

All actions return spoken. `period` and `timeframe` are optional.

### `transcript`

```json
{
  "name": "transcript",
  "description": "Open, search, and navigate episode transcripts.",
  "parameters": {
    "action": {"type": "string", "enum": ["open", "search", "navigate", "seek_to_topic", "read_line", "query_topic", "seek_to_quote", "read_section"]},
    "term": {"type": "string"},
    "direction": {"type": "string", "enum": ["next", "previous"]},
    "topic": {"type": "string"},
    "quote": {"type": "string"},
    "start": {"type": "string"},
    "end": {"type": "string"}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `open` | — | earcon |
| `search` | `term` | spoken match count |
| `navigate` | `direction` | spoken context |
| `seek_to_topic` | `topic` | silent |
| `read_line` | — | spoken |
| `query_topic` | `topic` | spoken |
| `seek_to_quote` | `quote` | silent |
| `read_section` | `start`, `end` (optional) | spoken |

### `assistant`

```json
{
  "name": "assistant",
  "description": "AI assistant: ask about episodes, summarize, navigate by content.",
  "parameters": {
    "action": {"type": "string", "enum": ["ask", "summarize", "query_content", "jump_to_topic", "play_quote", "stop_quote", "retry", "clear_chat"]},
    "question": {"type": "string"},
    "topic": {"type": "string"},
    "ref": {"type": "string", "description": "Quote reference or timestamp."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `ask` | `question` | spoken (AI) |
| `summarize` | — | spoken (AI) |
| `query_content` | `question` | spoken (AI) |
| `jump_to_topic` | `topic` | silent |
| `play_quote` | `ref` (optional) | silent |
| `stop_quote` | — | silent |
| `retry` | — | earcon |
| `clear_chat` | — | earcon (explicit confirm) |

### `cast`

```json
{
  "name": "cast",
  "description": "Cast playback to a device or stop casting.",
  "parameters": {
    "action": {"type": "string", "enum": ["start", "stop"]},
    "device": {"type": "string", "description": "Cast device name. Optional — prompts if omitted."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `start` | `device` (optional) | spoken |
| `stop` | — | earcon |

### `stories`

```json
{
  "name": "stories",
  "description": "End of Year listening review stories.",
  "parameters": {
    "action": {"type": "string", "enum": ["view", "next", "previous", "share", "replay"]}
  }
}
```

| Action | Response |
|---|---|
| `view` | earcon |
| `next` | silent |
| `previous` | silent |
| `share` | earcon |
| `replay` | earcon |

### `guest_pass`

```json
{
  "name": "guest_pass",
  "description": "Send or claim a guest pass / referral link.",
  "parameters": {
    "action": {"type": "string", "enum": ["send", "claim"]}
  }
}
```

Both actions return earcon.

### `download_settings`

```json
{
  "name": "download_settings",
  "description": "Configure auto-download, WiFi/charging restrictions, download limits.",
  "parameters": {
    "action": {"type": "string", "enum": ["auto_download_up_next", "auto_download_new", "auto_download_on_follow", "wifi_only", "charging_only", "podcast_auto_download", "stop_all_downloads", "clear_errors", "download_limit"]},
    "enabled": {"type": "boolean"},
    "podcast": {"type": "string", "description": "Podcast name. For podcast_auto_download."},
    "count": {"type": "integer", "description": "Download limit per podcast."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `auto_download_up_next` | `enabled` | earcon |
| `auto_download_new` | `enabled` | earcon |
| `auto_download_on_follow` | `enabled` | earcon |
| `wifi_only` | `enabled` | earcon |
| `charging_only` | `enabled` | earcon |
| `podcast_auto_download` | `podcast`, `enabled` | earcon |
| `stop_all_downloads` | — | earcon (explicit confirm) |
| `clear_errors` | — | earcon (explicit confirm) |
| `download_limit` | `count` | earcon |

### `playback_settings`

```json
{
  "name": "playback_settings",
  "description": "Configure headphone actions, auto-add to queue, and auto-archive rules.",
  "parameters": {
    "action": {"type": "string", "enum": ["next_track_action", "previous_track_action", "confirmation_sound", "auto_add", "auto_add_position", "auto_add_limit", "archive_after_playing", "archive_inactive", "include_starred_auto_archive"]},
    "track_action": {"type": "string", "enum": ["skip_forward", "skip_backward", "add_bookmark"]},
    "enabled": {"type": "boolean"},
    "podcast": {"type": "string", "description": "Podcast name. For auto_add."},
    "position": {"type": "string", "enum": ["top", "bottom"]},
    "count": {"type": "integer"},
    "delay": {"type": "string", "description": "Archive delay: 'immediately', 'after_24_hours', etc."},
    "period": {"type": "string", "description": "Inactivity period: 'never', '2_weeks', etc."}
  }
}
```

All actions return earcon.

### `app_settings`

```json
{
  "name": "app_settings",
  "description": "App-wide settings: theme, notifications, cleanup, export.",
  "parameters": {
    "action": {"type": "string", "enum": ["set_theme", "notifications", "podcast_notifications", "manual_cleanup", "export_opml"]},
    "theme": {"type": "string", "enum": ["light", "dark", "classic_dark", "ink", "system"]},
    "enabled": {"type": "boolean"},
    "podcast": {"type": "string", "description": "Podcast name. For podcast_notifications."}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `set_theme` | `theme` | earcon |
| `notifications` | `enabled` | earcon |
| `podcast_notifications` | `podcast`, `enabled` | earcon |
| `manual_cleanup` | — | earcon (explicit confirm) |
| `export_opml` | — | earcon |

### `account`

```json
{
  "name": "account",
  "description": "Account management: sign in, sign out, change plan, manage subscription, redeem promo codes.",
  "parameters": {
    "action": {"type": "string", "enum": ["sign_in_email", "sign_in_google", "create_account", "change_email", "change_password", "reset_password", "redeem_promo", "sign_out", "change_plan", "claim_offer", "cancel_subscription", "keep_subscription"]},
    "email": {"type": "string"},
    "password": {"type": "string"},
    "new_email": {"type": "string"},
    "current_password": {"type": "string"},
    "new_password": {"type": "string"},
    "newsletter": {"type": "boolean"},
    "code": {"type": "string"},
    "plan": {"type": "string", "enum": ["monthly", "yearly"]},
    "offer": {"type": "string"}
  }
}
```

| Action | Params | Response |
|---|---|---|
| `sign_in_email` | `email`, `password` (slot-filled) | spoken (multi-turn) |
| `sign_in_google` | — | spoken (requires screen tap) |
| `create_account` | `email`, `password`, `newsletter` (slot-filled) | spoken (multi-turn) |
| `change_email` | `new_email`, `password` (slot-filled) | spoken (multi-turn) |
| `change_password` | `current_password`, `new_password` (slot-filled) | spoken (multi-turn) |
| `reset_password` | `email` (optional) | spoken |
| `redeem_promo` | `code` (optional) | spoken |
| `sign_out` | — | earcon (explicit confirm) |
| `change_plan` | `plan` | earcon (explicit confirm) |
| `claim_offer` | `offer` (optional) | spoken |
| `cancel_subscription` | — | earcon (explicit confirm) |
| `keep_subscription` | — | spoken |

### `sharing`

```json
{
  "name": "sharing",
  "description": "Share episodes, clips, bookmarks, podcasts, and transcript sections.",
  "parameters": {
    "action": {"type": "string", "enum": ["share_episode", "share_at_current_time", "share_at_time", "share_podcast", "share_clip", "share_bookmark", "share_transcript", "create_shared_list", "share_via_app", "accept_shared_list"]},
    "episode": {"type": "string"},
    "time": {"type": "string"},
    "podcast": {"type": "string"},
    "start": {"type": "string"},
    "end": {"type": "string"},
    "bookmark": {"type": "string"},
    "section": {"type": "string"},
    "list_name": {"type": "string"},
    "podcasts": {"type": "string"},
    "app": {"type": "string"},
    "accept_mode": {"type": "string", "enum": ["all", "select"]}
  }
}
```

All actions return earcon except `accept_shared_list` (spoken).

### `no_match`

```json
{
  "name": "no_match",
  "description": "No command was recognized. Select this when the user is not issuing a voice command."
}
```

Returns no intent (null). Explicit rejection for ambient speech, podcast bleed, questions, and other non-commands.

## Kotlin Model

The tool schema is the wire format. The Kotlin sealed-interface hierarchy is the domain model. A `ToolCallMapper` converts between them.

```kotlin
sealed interface VoiceIntent {
    // Each tool maps to a sealed sub-interface
    sealed interface Playback : VoiceIntent
    sealed interface Effects : VoiceIntent
    sealed interface Volume : VoiceIntent
    sealed interface Sleep : VoiceIntent
    sealed interface Chapter : VoiceIntent
    sealed interface Bookmark : VoiceIntent
    sealed interface Queue : VoiceIntent
    sealed interface Episode : VoiceIntent
    sealed interface Podcast : VoiceIntent
    sealed interface Bulk : VoiceIntent
    sealed interface Folder : VoiceIntent
    sealed interface Playlist : VoiceIntent
    sealed interface Search : VoiceIntent
    sealed interface PlaybackQuery : VoiceIntent
    sealed interface StatsQuery : VoiceIntent
    sealed interface Transcript : VoiceIntent
    sealed interface Assistant : VoiceIntent
    sealed interface Cast : VoiceIntent
    sealed interface Stories : VoiceIntent
    sealed interface GuestPass : VoiceIntent
    sealed interface DownloadSettings : VoiceIntent
    sealed interface PlaybackSettings : VoiceIntent
    sealed interface AppSettings : VoiceIntent
    sealed interface Account : VoiceIntent
    sealed interface Sharing : VoiceIntent
}
```

Each sub-interface has one data class (or data object) per action enum value. The tool schema tables above define the complete set; the Kotlin model mirrors them 1:1. Example:

```kotlin
sealed interface PlaybackIntent : VoiceIntent.Playback {
    data object Pause : PlaybackIntent
    data object Resume : PlaybackIntent
    data class SeekRelative(val deltaMs: Int) : PlaybackIntent
    data class SeekAbsolute(val positionMs: Int) : PlaybackIntent
    data object NextEpisode : PlaybackIntent
}
```

## ToolCallMapper

Converts FunctionGemma's JSON tool call output to the typed `VoiceIntent` hierarchy:

```kotlin
class ToolCallMapper {
    fun map(call: ToolCall): VoiceIntent? {
        if (call.name == "no_match") return null
        return when (call.name) {
            "playback" -> mapPlayback(call.action, call.params)
            "effects" -> mapEffects(call.action, call.params)
            "volume" -> mapVolume(call.action, call.params)
            "sleep" -> mapSleep(call.action, call.params)
            "chapter" -> mapChapter(call.action, call.params)
            "bookmark" -> mapBookmark(call.action, call.params)
            "queue" -> mapQueue(call.action, call.params)
            "episode" -> mapEpisode(call.action, call.params)
            "podcast" -> mapPodcast(call.action, call.params)
            "bulk" -> mapBulk(call.action, call.params)
            "folder" -> mapFolder(call.action, call.params)
            "playlist" -> mapPlaylist(call.action, call.params)
            "search" -> mapSearch(call.action, call.params)
            "playback_query" -> mapPlaybackQuery(call.action, call.params)
            "stats_query" -> mapStatsQuery(call.action, call.params)
            "transcript" -> mapTranscript(call.action, call.params)
            "assistant" -> mapAssistant(call.action, call.params)
            "cast" -> mapCast(call.action, call.params)
            "stories" -> mapStories(call.action, call.params)
            "guest_pass" -> mapGuestPass(call.action, call.params)
            "download_settings" -> mapDownloadSettings(call.action, call.params)
            "playback_settings" -> mapPlaybackSettings(call.action, call.params)
            "app_settings" -> mapAppSettings(call.action, call.params)
            "account" -> mapAccount(call.action, call.params)
            "sharing" -> mapSharing(call.action, call.params)
            else -> null
        }
    }
}
```

Each `map<Domain>` method switches on the `action` string and constructs the typed intent with extracted params. Invalid/unknown actions or missing required params return null.

## Sink Interfaces

Each domain has its own sink. Sinks return `VoiceResponse`.

```kotlin
interface VoicePlaybackSink {
    suspend fun pause(): VoiceResponse
    suspend fun resume(): VoiceResponse
    suspend fun seekRelative(deltaSeconds: Int): VoiceResponse
    suspend fun seekTo(positionSeconds: Int): VoiceResponse
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
```

Each tool maps to one sink. Sink method names match the action enum values. The pattern is the same for all 24 remaining sinks.

## Executor

```kotlin
class VoiceIntentExecutor @Inject constructor(
    private val playbackSink: VoicePlaybackSink,
    private val effectsSink: VoiceEffectsSink,
    private val volumeSink: VoiceVolumeSink,
    private val sleepSink: VoiceSleepSink,
    // ... one sink per tool
) {
    suspend fun execute(intent: VoiceIntent): VoiceResponse = when (intent) {
        is PlaybackIntent -> executePlayback(intent)
        is EffectsIntent -> executeEffects(intent)
        is VolumeIntent -> executeVolume(intent)
        is SleepIntent -> executeSleep(intent)
        // ... one branch per tool
    }
}
```

Each domain's dispatch method is exhaustive within its sealed sub-interface.

## Analytics

Tag all voice-initiated actions with `SourceView.VOICE_COMMANDS`. Each domain sink records domain-specific analytics using the voice source view.

## Multi-Turn and Confirmation

Some intents require multi-turn slot filling (account sign-in) or explicit confirmation (queue clear, bulk actions, sign out). These flows are owned by the dialog layer, not this spec. The intent is only dispatched once all slots are filled and confirmations are obtained.

The confirmation flow produces a `VoiceIntent` only when confirmed — a denied confirmation produces no intent. The executor never sees cancelled flows.
