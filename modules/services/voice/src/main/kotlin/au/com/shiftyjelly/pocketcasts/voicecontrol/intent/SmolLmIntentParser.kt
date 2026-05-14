package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import androidx.annotation.VisibleForTesting
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object LmNative {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }

    external fun parseIntent(modelPath: String, prompt: String): String
}

@Singleton
open class SmolLmIntentParser @Inject constructor(
    private val modelFile: File,
) {
    @VisibleForTesting
    internal var nativeImpl: ((modelPath: String, prompt: String) -> String)? = null

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    suspend fun parseIntent(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? = withContext(Dispatchers.IO) {
        parseIntentSync(transcript, context)
    }

    internal fun parseIntentSync(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? {
        if (transcript.isBlank()) return null
        val prompt = buildPrompt(transcript, context)
        try {
            val output = nativeImpl?.invoke(modelFile.absolutePath, prompt)
                ?: LmNative.parseIntent(modelFile.absolutePath, prompt)
            Timber.i("SmolLM: '%s'", output)
            return parseIntentJson(output)
        } catch (e: Exception) {
            Timber.e(e, "SmolLM intent parsing failed")
            return null
        }
    }

    private fun buildPrompt(transcript: String, ctx: VoiceRecognitionContext): String {
        val playbackInfo = when (val pc = ctx.playbackContext) {
            is PlaybackContext.Active -> "Current playback state: ${if (pc.isPlaying) "playing" else "paused"}"
            PlaybackContext.Inactive -> "Current playback state: inactive"
        }
        return """<|system|>
You are a voice command processor for a podcast player. Given a transcript of the user's speech, respond with ONLY a JSON object representing the closest matching intent. Available intents:

{"intent": "pause"}
{"intent": "resume"}
{"intent": "seek_relative", "delta_seconds": <positive integer>}
{"intent": "seek_absolute", "position_seconds": <positive integer>}
{"intent": "next_chapter"}
{"intent": "previous_chapter"}
{"intent": "chapter_by_index", "index": <non-negative integer>}
{"intent": "chapter_by_title", "query": "<chapter name>"}
{"intent": "next_episode"}
{"intent": "set_speed", "speed": <0.5 to 5.0>}
{"intent": "adjust_speed", "delta": <signed increment>}
{"intent": "set_volume", "volume": <0 to 100>}
{"intent": "adjust_volume", "delta": <signed increment>}
{"intent": "sleep_timer", "minutes": <positive integer; 0 to cancel>}
{"intent": "set_trim", "mode": "off"|"low"|"medium"|"high"}
{"intent": "set_volume_boost", "enabled": true|false}
{"intent": "add_bookmark", "title": "<bookmark label>"}

Common aliases:
"play" -> {"intent": "resume"} | "stop" -> {"intent": "pause"}
"next" -> {"intent": "next_chapter"} | "previous" -> {"intent": "previous_chapter"}
"faster" / "speed up" -> {"intent": "adjust_speed", "delta": 0.5}
"slower" / "slow down" -> {"intent": "adjust_speed", "delta": -0.5}
"forward X" / "skip X" -> {"intent": "seek_relative", "delta_seconds": X}
"go back X" -> {"intent": "seek_relative", "delta_seconds": -X}
"turn off" -> {"intent": "sleep_timer", "minutes": 0}
"volume up" -> {"intent": "adjust_volume", "delta": 10}
"volume down" -> {"intent": "adjust_volume", "delta": -10}
"set volume X" -> {"intent": "set_volume", "volume": X}
"louder" -> {"intent": "adjust_volume", "delta": 10}
"quieter" -> {"intent": "adjust_volume", "delta": -10}
"trim silence" / "silence trimming" -> {"intent": "set_trim", "mode": "medium"}
"no trim" -> {"intent": "set_trim", "mode": "off"}
"boost" / "turn on boost" -> {"intent": "set_volume_boost", "enabled": true}
"no boost" -> {"intent": "set_volume_boost", "enabled": false}
"bookmark this" / "save this" -> {"intent": "add_bookmark", "title": "Voice bookmark"}
"set speed X" -> {"intent": "set_speed", "speed": X}

$playbackInfo
Audio route: ${ctx.audioRoute}
If the speech is not a playback command, respond with {"intent": "none"}.
<|user|>
$transcript
<|assistant|>
        """.trimIndent()
    }

    internal fun parseIntentJson(output: String): VoicePlaybackIntent? {
        if (output.isBlank()) return null
        return try {
            val trimmed = output.trim()
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) return null
            val json = trimmed.substring(jsonStart, jsonEnd + 1)
            val intent = extractJsonString(json, "intent") ?: return null
            if (intent == "none") return null
            parseKnownIntent(intent, json)
        } catch (e: Exception) {
            Timber.w(e, "SmolLM: failed to parse intent JSON")
            null
        }
    }

    private fun parseKnownIntent(intent: String, json: String): VoicePlaybackIntent? {
        return when (intent) {
            "pause" -> VoicePlaybackIntent.Pause

            "resume" -> VoicePlaybackIntent.Resume

            "seek_relative" -> VoicePlaybackIntent.SeekRelative(
                (extractJsonDouble(json, "delta_seconds") ?: 30.0).let { (it * 1000).toInt() },
            )

            "seek_absolute" -> VoicePlaybackIntent.SeekAbsolute(
                (extractJsonDouble(json, "position_seconds") ?: 0.0).let { (it * 1000).toInt() },
            )

            "next_chapter" -> VoicePlaybackIntent.NextChapter

            "previous_chapter" -> VoicePlaybackIntent.PreviousChapter

            "next_episode" -> VoicePlaybackIntent.NextEpisode

            "chapter_by_index" -> {
                val index = extractJsonInt(json, "index") ?: return null
                if (index < 0) null else VoicePlaybackIntent.ChapterByIndex(index)
            }

            "chapter_by_title" -> {
                val query = extractJsonString(json, "query") ?: return null
                if (query.isBlank()) null else VoicePlaybackIntent.ChapterByTitle(query)
            }

            "set_speed" -> {
                val speed = extractJsonDouble(json, "speed") ?: return null
                if (speed in 0.5..5.0) VoicePlaybackIntent.SetSpeed(speed) else null
            }

            "adjust_speed" -> {
                val delta = extractJsonDouble(json, "delta") ?: return null
                if (delta != 0.0) VoicePlaybackIntent.AdjustSpeed(delta) else null
            }

            "set_volume" -> {
                val volume = extractJsonInt(json, "volume") ?: return null
                if (volume in 0..100) VoicePlaybackIntent.SetVolume(volume) else null
            }

            "adjust_volume" -> {
                val delta = extractJsonInt(json, "delta") ?: return null
                if (delta != 0) VoicePlaybackIntent.AdjustVolume(delta) else null
            }

            "sleep_timer" -> {
                val minutes = extractJsonInt(json, "minutes") ?: return null
                if (minutes < 0) null else VoicePlaybackIntent.SleepTimer(minutes)
            }

            "set_trim" -> {
                val mode = extractJsonString(json, "mode") ?: return null
                if (mode in listOf("off", "low", "medium", "high")) VoicePlaybackIntent.SetTrimMode(mode) else null
            }

            "set_volume_boost" -> VoicePlaybackIntent.SetVolumeBoost(
                extractJsonBoolean(json, "enabled") ?: false,
            )

            "add_bookmark" -> {
                val title = extractJsonString(json, "title") ?: return null
                if (title.isNotBlank()) VoicePlaybackIntent.AddBookmark(title) else null
            }

            else -> {
                Timber.w("SmolLM: unknown intent '%s'", intent)
                null
            }
        }
    }

    /**
     * Extracts a string value for the given key from a simple JSON object string.
     * Handles both `"key":"value"` and `"key": "value"` formats.
     */
    private fun extractJsonString(json: String, key: String): String? {
        val searchKey = "\"$key\":"
        val idx = json.indexOf(searchKey)
        if (idx == -1) return null
        val afterKey = json.substring(idx + searchKey.length).trimStart()
        if (!afterKey.startsWith('"')) return null
        val closeQuote = afterKey.indexOf('"', 1)
        if (closeQuote == -1) return null
        return afterKey.substring(1, closeQuote)
    }

    /**
     * Extracts a double value for the given key from a simple JSON object string.
     */
    private fun extractJsonDouble(json: String, key: String): Double? {
        val searchKey = "\"$key\":"
        val idx = json.indexOf(searchKey)
        if (idx == -1) return null
        val afterKey = json.substring(idx + searchKey.length).trimStart()
        // Read until comma or closing brace
        val end = afterKey.indexOfAny(charArrayOf(',', '}'))
        val raw = if (end == -1) afterKey.trim() else afterKey.substring(0, end).trim()
        return raw.toDoubleOrNull()
    }

    /**
     * Extracts an int value for the given key from a simple JSON object string.
     */
    private fun extractJsonInt(json: String, key: String): Int? {
        return extractJsonDouble(json, key)?.toInt()
    }

    /**
     * Extracts a boolean value for the given key from a simple JSON object string.
     */
    private fun extractJsonBoolean(json: String, key: String): Boolean? {
        val searchKey = "\"$key\":"
        val idx = json.indexOf(searchKey)
        if (idx == -1) return null
        val afterKey = json.substring(idx + searchKey.length).trimStart()
        return when {
            afterKey.startsWith("true") -> true
            afterKey.startsWith("false") -> false
            else -> null
        }
    }
}
