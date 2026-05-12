package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

@Singleton
class Gemma4VoiceRecognizer @Inject constructor(
    private val modelManager: VoiceModelManager,
) : VoiceRecognizer {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val mutex = Mutex()
    private var modelReady = false
    private var modelPath: String? = null

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
        {"intent": "set_speed", "delta": <signed increment>, "text": "<transcribed speech>"}
        {"intent": "set_volume", "volume": <0 to 100>, "text": "<transcribed speech>"}
        {"intent": "set_volume", "delta": <signed increment>, "text": "<transcribed speech>"}
        {"intent": "sleep_timer", "minutes": <positive integer; 0 to cancel>, "text": "<transcribed speech>"}

        Common aliases:
        "play" → {"intent": "resume"} | "stop" → {"intent": "pause"}
        "next" → {"intent": "next_chapter"} | "previous" → {"intent": "previous_chapter"}
        "faster" / "speed up" → {"intent": "set_speed", "delta": 0.5}
        "slower" / "slow down" → {"intent": "set_speed", "delta": -0.5}
        "forward X" / "skip X" → {"intent": "seek_relative", "delta_seconds": X}
        "go back X" → {"intent": "seek_relative", "delta_seconds": -X}
        "turn off" → {"intent": "sleep_timer", "minutes": 0}
        "volume up" → {"intent": "set_volume", "delta": 10}
        "volume down" → {"intent": "set_volume", "delta": -10}

        Examples:
        "暂停" → {"intent": "pause", "text": "暂停"}
        "快进30秒" → {"intent": "seek_relative", "delta_seconds": 30, "text": "快进30秒"}
        "下一章" → {"intent": "next_chapter", "text": "下一章"}

        If the speech is not a playback command, respond with {"intent": "none", "text": "<transcribed speech>"}.
    """.trimIndent()

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        modelManager.ensureModel().map { modelPath ->
            initializeConversation(modelPath)
        }
    }

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? = withContext(Dispatchers.IO) {
        mutex.withLock {
            var c = conversation
            if (c == null || !modelReady) return@withLock null
            if (!c.isAlive) {
                Timber.w("Conversation dead, recreating")
                c = recreateConversation()
                if (c == null) return@withLock null
            }

            try {
                val audioBytes = pcmToWav(clip)
                val response = c.sendMessage(
                    Contents.Companion.of(Content.AudioBytes(audioBytes)),
                )
                val text = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .firstOrNull()
                    ?.text
                if (text.isNullOrBlank()) {
                    Timber.w("Gemma 4: empty response")
                    return@withLock null
                }
                Timber.i("Gemma 4: %s", text)
                parseIntent(text)
            } catch (e: Exception) {
                Timber.e(e, "Gemma 4 E2B inference failed")
                null
            }
        }
    }

    private fun initializeConversation(path: String) {
        modelPath = path
        try {
            val eng = Engine(
                EngineConfig(
                    modelPath = path,
                    backend = Backend.GPU(),
                    audioBackend = Backend.CPU(),
                ),
            )
            eng.initialize()
            engine = eng
            conversation = eng.createConversation(
                ConversationConfig(
                    Contents.Companion.of(Content.Text(systemPrompt)),
                ),
            )
            modelReady = true
            Timber.i("Gemma 4 E2B model loaded")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Gemma 4 E2B conversation")
        }
    }

    private fun recreateConversation(): Conversation? {
        val eng = engine
        if (eng == null || !eng.isInitialized()) return null
        conversation?.close()
        return try {
            val c = eng.createConversation(
                ConversationConfig(
                    Contents.Companion.of(Content.Text(systemPrompt)),
                ),
            )
            conversation = c
            Timber.i("Conversation recreated")
            c
        } catch (e: Exception) {
            Timber.e(e, "Failed to recreate conversation")
            conversation = null
            null
        }
    }

    private fun parseIntent(output: String): VoicePlaybackIntent? {
        if (output.isBlank()) return null

        return try {
            val trimmed = output.trim()
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) return null

            val json = JSONObject(trimmed.substring(jsonStart, jsonEnd + 1))
            val intent = json.optString("intent", "")

            if (intent == "none") return null

            @Suppress("NONEXHAUSTIVE_WHEN")
            when (intent) {
                "pause" -> VoicePlaybackIntent.Pause
                "resume" -> VoicePlaybackIntent.Resume
                "seek_relative" -> {
                    val seconds = json.optDouble("delta_seconds", 30.0)
                    VoicePlaybackIntent.SeekRelative((seconds * 1000).toInt())
                }
                "seek_absolute" -> {
                    val seconds = json.optDouble("position_seconds", 0.0)
                    VoicePlaybackIntent.SeekAbsolute((seconds * 1000).toInt())
                }
                "next_chapter" -> VoicePlaybackIntent.NextChapter
                "previous_chapter" -> VoicePlaybackIntent.PreviousChapter
                "next_episode" -> VoicePlaybackIntent.NextEpisode
                "chapter_by_index" -> {
                    val index = json.optInt("index", -1)
                    if (index < 0) null else VoicePlaybackIntent.ChapterByIndex(index)
                }
                "chapter_by_title" -> {
                    val query = json.optString("query", "")
                    if (query.isBlank()) null else VoicePlaybackIntent.ChapterByTitle(query)
                }
                "set_speed" -> {
                    val speed = if (json.has("speed")) json.optDouble("speed", -1.0) else -1.0
                    val delta = if (json.has("delta")) json.optDouble("delta", 0.0) else null
                    if (speed in 0.5..5.0) VoicePlaybackIntent.SetPlaybackSpeed(speed = speed)
                    else if (delta != null && delta != 0.0) VoicePlaybackIntent.SetPlaybackSpeed(delta = delta)
                    else null
                }
                "set_volume" -> {
                    val volume = if (json.has("volume")) json.optInt("volume", -1) else -1
                    val delta = if (json.has("delta")) json.optInt("delta", 0) else null
                    if (volume in 0..100) VoicePlaybackIntent.SetVolume(volume = volume)
                    else if (delta != null && delta != 0) VoicePlaybackIntent.SetVolume(delta = delta)
                    else null
                }
                "sleep_timer" -> {
                    val minutes = json.optInt("minutes", -1)
                    if (minutes < 0) null else VoicePlaybackIntent.SleepTimer(minutes)
                }
                else -> {
                    Timber.w("Gemma 4: unknown intent '%s'", intent)
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Gemma 4: failed to parse intent JSON")
            null
        }
    }

    private fun pcmToWav(clip: VoiceUtteranceClip): ByteArray {
        val sampleRate = clip.sampleRateHz
        val totalSamples = clip.frames.sumOf { it.samples.size }
        val dataSize = totalSamples * 2
        val headerSize = 44
        val totalSize = headerSize + dataSize

        val buffer = ByteArrayOutputStream(totalSize)
        val header = ByteBuffer.allocate(headerSize).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
            putInt(36 + dataSize)
            put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
            put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
            putInt(16); putShort(1); putShort(1); putInt(sampleRate)
            putInt(sampleRate * 2); putShort(2); putShort(16)
            put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
            putInt(dataSize)
        }

        buffer.write(header.array())
        for (frame in clip.frames) {
            val bytes = ByteBuffer.allocate(frame.samples.size * 2).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (sample in frame.samples) {
                    putShort(sample.toInt().coerceIn(-0x8000, 0x7FFF).toShort())
                }
            }
            buffer.write(bytes.array())
        }

        return buffer.toByteArray()
    }

    fun isModelReady(): Boolean = modelReady

    fun release() {
        conversation?.close()
        conversation = null
        modelReady = false
        Timber.i("Gemma 4 E2B recognizer released")
    }
}
