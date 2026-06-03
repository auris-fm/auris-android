package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import org.json.JSONObject

data class ToolCall(
    val name: String,
    val action: String,
    val params: Map<String, Any?>,
) {
    fun stringParam(key: String): String? = params[key] as? String
    fun intParam(key: String): Int? = (params[key] as? Number)?.toInt()
    fun doubleParam(key: String): Double? = (params[key] as? Number)?.toDouble()
    fun boolParam(key: String): Boolean? = params[key] as? Boolean

    companion object {
        private const val TOOL_CALL_START = "▎"
        private const val ACTION_KEY = "action"

        fun parse(response: String): ToolCall? {
            val json = try {
                JSONObject(response.trim())
            } catch (_: Exception) {
                return null
            }

            val name = json.optString("name") ?: return null
            if (name == "no_match" || !json.has("name")) {
                return if (name == "no_match") ToolCall("no_match", "", emptyMap()) else null
            }

            val action = json.optString(ACTION_KEY) ?: return null
            val params = mutableMapOf<String, Any?>()
            val paramsObj = json.optJSONObject("parameters") ?: json.optJSONObject("params")
            if (paramsObj != null) {
                for (key in paramsObj.keys()) {
                    params[key] = when (val v = paramsObj.get(key)) {
                        is JSONObject -> v.toString()
                        else -> v
                    }
                }
            }

            return ToolCall(name, action, params)
        }
    }
}

object ToolSchema {
    val json: String = """
        [
          {
            "name": "playback",
            "description": "Basic playback controls: pause, resume, skip forward or backward, seek to a position, play next episode.",
            "parameters": {
              "action": {"type": "string", "enum": ["pause", "resume", "seek_relative", "seek_to", "next_episode"]},
              "seconds": {"type": "integer", "description": "Seconds. For seek_relative: signed delta (positive=forward, negative=backward). For seek_to: absolute position from 0."}
            }
          },
          {
            "name": "effects",
            "description": "Playback effects: speed, trim silence, volume boost.",
            "parameters": {
              "action": {"type": "string", "enum": ["set_speed", "adjust_speed", "set_trim_mode", "set_volume_boost", "query_effects"]},
              "speed": {"type": "number", "description": "Playback speed (0.5-5.0)."},
              "delta": {"type": "number", "description": "Speed delta. Positive = faster, negative = slower."},
              "mode": {"type": "string", "enum": ["off", "low", "medium", "high"], "description": "Trim silence mode."},
              "enabled": {"type": "boolean", "description": "On/off for volume boost."}
            }
          },
          {
            "name": "volume",
            "description": "Control device volume.",
            "parameters": {
              "action": {"type": "string", "enum": ["set_volume", "adjust_volume", "query"]},
              "volume": {"type": "integer", "description": "Volume level (0-100)."},
              "delta": {"type": "integer", "description": "Volume delta. Positive = louder, negative = quieter."}
            }
          },
          {
            "name": "sleep",
            "description": "Sleep timer: set a timer, stop at end of episode or chapter, add time, cancel.",
            "parameters": {
              "action": {"type": "string", "enum": ["set", "end_of_episode", "end_of_chapter", "add_time", "cancel", "query"]},
              "minutes": {"type": "integer", "description": "Duration in minutes."}
            }
          },
          {
            "name": "chapter",
            "description": "Navigate and query episode chapters.",
            "parameters": {
              "action": {"type": "string", "enum": ["next", "previous", "by_index", "by_title", "open_link", "query_list", "query_current", "query_count", "query_next"]},
              "index": {"type": "integer", "description": "Chapter number (1-based)."},
              "query": {"type": "string", "description": "Chapter title search query."}
            }
          },
          {
            "name": "bookmark",
            "description": "Create, rename, play, delete, and query bookmarks.",
            "parameters": {
              "action": {"type": "string", "enum": ["add", "rename", "play", "delete", "delete_all", "query_list", "query_count", "query_nearby"]},
              "title": {"type": "string", "description": "Bookmark title."},
              "ref": {"type": "string", "description": "Bookmark reference: title, index, or 'latest'."}
            }
          },
          {
            "name": "queue",
            "description": "Manage the Up Next queue: add, remove, reorder, clear.",
            "parameters": {
              "action": {"type": "string", "enum": ["add_top", "add_bottom", "remove", "move_to_top", "move_to_bottom", "clear", "remove_by_podcast", "sort", "query_contents", "query_next", "query_length", "query_is_queued"]},
              "episode": {"type": "string", "description": "Episode title or description."},
              "podcast": {"type": "string", "description": "Podcast name."},
              "sort_order": {"type": "string", "enum": ["newest_first", "oldest_first"]}
            }
          },
          {
            "name": "episode",
            "description": "Manage individual episodes: download, star, archive, mark played, add to playlist.",
            "parameters": {
              "action": {"type": "string", "enum": ["download", "delete_download", "star", "unstar", "archive", "unarchive", "mark_played", "mark_unplayed", "remove_from_history", "add_to_playlist"]},
              "episode": {"type": "string", "description": "Episode title or description."},
              "playlist": {"type": "string", "description": "Playlist name."}
            }
          },
          {
            "name": "podcast",
            "description": "Manage podcast subscriptions: subscribe, unsubscribe, rate, configure notifications and auto-add.",
            "parameters": {
              "action": {"type": "string", "enum": ["subscribe", "unsubscribe", "rate", "toggle_notifications", "auto_add", "auto_download"]},
              "podcast": {"type": "string", "description": "Podcast name."},
              "rating": {"type": "integer", "description": "Star rating (1-5)."},
              "enabled": {"type": "boolean"},
              "position": {"type": "string", "enum": ["top", "bottom"], "description": "Queue position for auto-add."}
            }
          },
          {
            "name": "bulk",
            "description": "Bulk actions on episodes: bulk download, archive, or mark as played.",
            "parameters": {
              "action": {"type": "string", "enum": ["download", "archive", "mark_played"]},
              "podcast": {"type": "string", "description": "Podcast name. Optional."},
              "filter": {"type": "string", "description": "Filter: 'unplayed', 'played', 'downloaded', or 'last N'."}
            }
          },
          {
            "name": "folder",
            "description": "Manage podcast folders: create, rename, assign podcasts, remove, delete.",
            "parameters": {
              "action": {"type": "string", "enum": ["create", "rename", "assign", "remove_from", "delete"]},
              "folder": {"type": "string", "description": "Folder name."},
              "name": {"type": "string", "description": "New name."},
              "podcast": {"type": "string", "description": "Podcast name."}
            }
          },
          {
            "name": "playlist",
            "description": "Manage playlists: create, delete, rename, play all, add/remove episodes.",
            "parameters": {
              "action": {"type": "string", "enum": ["create", "create_smart", "delete", "rename", "play_all", "download_all", "add_episode", "remove_episode", "archive_all", "unarchive_all", "auto_download"]},
              "playlist": {"type": "string", "description": "Playlist name."},
              "name": {"type": "string", "description": "New playlist name."},
              "episode": {"type": "string", "description": "Episode title."},
              "shuffle": {"type": "boolean"},
              "enabled": {"type": "boolean"},
              "rules": {"type": "string", "description": "Smart playlist rules."}
            }
          },
          {
            "name": "search",
            "description": "Search for podcasts and episodes, browse trending and recommendations.",
            "parameters": {
              "action": {"type": "string", "enum": ["search", "filter", "subscribe_result", "play_result", "describe_result", "rerun", "clear_history", "trending", "recommendations", "category", "new_releases", "change_region"]},
              "query": {"type": "string"},
              "type": {"type": "string", "enum": ["podcasts", "episodes"]},
              "ref": {"type": "string", "description": "Result reference."},
              "category": {"type": "string"},
              "timeframe": {"type": "string"},
              "region": {"type": "string"}
            }
          },
          {
            "name": "playback_query",
            "description": "Query current playback state and episode info.",
            "parameters": {
              "action": {"type": "string", "enum": ["whats_playing", "position", "time_remaining", "current_podcast", "episode_duration", "publish_date", "episode_description", "download_status", "episode_title"]}
            }
          },
          {
            "name": "stats_query",
            "description": "Query listening statistics.",
            "parameters": {
              "action": {"type": "string", "enum": ["listening_time", "top_podcasts", "episodes_finished", "listening_streak", "subscription_count", "unplayed_total", "download_stats", "queue_total", "new_episodes", "time_since_last_listen"]},
              "period": {"type": "string", "description": "Time period."},
              "timeframe": {"type": "string", "description": "Time window."}
            }
          },
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
          },
          {
            "name": "assistant",
            "description": "AI assistant: ask about episodes, summarize, navigate by content.",
            "parameters": {
              "action": {"type": "string", "enum": ["ask", "summarize", "query_content", "jump_to_topic", "play_quote", "stop_quote", "retry", "clear_chat"]},
              "question": {"type": "string"},
              "topic": {"type": "string"},
              "ref": {"type": "string"}
            }
          },
          {
            "name": "cast",
            "description": "Cast playback to a device or stop casting.",
            "parameters": {
              "action": {"type": "string", "enum": ["start", "stop"]},
              "device": {"type": "string", "description": "Cast device name."}
            }
          },
          {
            "name": "stories",
            "description": "End of Year listening review stories.",
            "parameters": {
              "action": {"type": "string", "enum": ["view", "next", "previous", "share", "replay"]}
            }
          },
          {
            "name": "guest_pass",
            "description": "Send or claim a guest pass / referral link.",
            "parameters": {
              "action": {"type": "string", "enum": ["send", "claim"]}
            }
          },
          {
            "name": "download_settings",
            "description": "Configure auto-download, WiFi/charging restrictions, download limits.",
            "parameters": {
              "action": {"type": "string", "enum": ["auto_download_up_next", "auto_download_new", "auto_download_on_follow", "wifi_only", "charging_only", "podcast_auto_download", "stop_all_downloads", "clear_errors", "download_limit"]},
              "enabled": {"type": "boolean"},
              "podcast": {"type": "string"},
              "count": {"type": "integer"}
            }
          },
          {
            "name": "playback_settings",
            "description": "Configure headphone actions, auto-add to queue, and auto-archive rules.",
            "parameters": {
              "action": {"type": "string", "enum": ["next_track_action", "previous_track_action", "confirmation_sound", "auto_add", "auto_add_position", "auto_add_limit", "archive_after_playing", "archive_inactive", "include_starred_auto_archive"]},
              "track_action": {"type": "string", "enum": ["skip_forward", "skip_backward", "add_bookmark"]},
              "enabled": {"type": "boolean"},
              "podcast": {"type": "string"},
              "position": {"type": "string", "enum": ["top", "bottom"]},
              "count": {"type": "integer"},
              "delay": {"type": "string"},
              "period": {"type": "string"}
            }
          },
          {
            "name": "app_settings",
            "description": "App-wide settings: theme, notifications, cleanup, export.",
            "parameters": {
              "action": {"type": "string", "enum": ["set_theme", "notifications", "podcast_notifications", "manual_cleanup", "export_opml"]},
              "theme": {"type": "string", "enum": ["light", "dark", "classic_dark", "ink", "system"]},
              "enabled": {"type": "boolean"},
              "podcast": {"type": "string"}
            }
          },
          {
            "name": "account",
            "description": "Account management: sign in, sign out, change plan, manage subscription.",
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
          },
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
          },
          {
            "name": "no_match",
            "description": "No command was recognized. Select this when the user is not issuing a voice command."
          }
        ]
    """.trimIndent()
}
