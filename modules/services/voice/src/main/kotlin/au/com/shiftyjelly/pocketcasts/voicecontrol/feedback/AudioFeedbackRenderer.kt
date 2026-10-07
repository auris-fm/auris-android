package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class AudioFeedbackRenderer(
    private val earconPlayer: EarconPlayer,
    private val ttsEngine: TtsEngine,
) {
    private val _hasEmittedAudio = MutableStateFlow(false)
    private val _lastEmittedAtMs = MutableStateFlow(SystemClock.elapsedRealtime())

    /**
     * Whether this app has ever emitted at least one audible response, and when it last did.
     *
     * The gate needs this because earcons and speech ride the media stream, so
     * `AudioManager.isMusicActive` is true while *we* are the ones making the sound. Without it the
     * app reads its own acknowledgement as a foreign app playing, blocks its own gate and closes the
     * microphone mid-conversation: the user hears the beep and the command they said next is
     * discarded, with the log blaming another app.
     */
    val hasEmittedAudio: StateFlow<Boolean> = _hasEmittedAudio
    val lastEmittedAtMs: StateFlow<Long> = _lastEmittedAtMs

    /**
     * Note that this app is making sound *now*, from a path that is not this renderer.
     *
     * [noteEmitted] is private because every caller used to be in this class — but the cloud
     * player is a second producer of audible output, and it was the one the gate did not know
     * about: a cloud answer played for seconds while the gate saw a stale self-emission, read
     * `isMusicActive` as a foreign app, and cut the microphone mid-answer. Rather than teach the
     * gate about cloud turns, the cloud path joins the signal that already answers this question.
     *
     * This is the same shape as [speakWithHeartbeat]: longer-than-the-window audio refreshes the
     * stamp for its own duration, because speech outlasting the window reads as a foreign app for
     * the rest of the answer.
     */
    fun noteAudibleNow() = noteEmitted()

    private fun noteEmitted() {
        _hasEmittedAudio.value = true
        _lastEmittedAtMs.value = SystemClock.elapsedRealtime()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentJob: Job? = null
    private var released = false

    fun render(response: VoiceResponse, language: String = "en") {
        if (released) return
        // Debug-only diagnostic: what the app is about to say, before TTS. This is the one
        // place every spoken response passes through, so it is where a response that is
        // identical regardless of the prompt can be told from one that differs. Debug-gated
        // because the standing rule for this pipeline is that server-derived text does not
        // reach device logs.
        if (BuildConfig.DEBUG) {
            Timber.d("[VoiceResponse] %s", describeForLog(response, language))
        }
        currentJob?.cancel()
        // Launched coroutine inherits the Job cancellation: when we cancel currentJob
        // above, any child suspend calls (like TtsEngine.speak) will be cancelled.
        currentJob = scope.launch {
            when (response) {
                is VoiceResponse.Silent -> { /* no-op */ }

                is VoiceResponse.Earcon -> playEarcon(response.id)

                is VoiceResponse.Spoken -> speakWithHeartbeat(response.text, language)

                is VoiceResponse.Combined -> {
                    playEarcon(response.earcon)
                    speakWithHeartbeat(response.spokenText, language)
                }
            }
        }
    }

    /**
     * Plays an earcon directly, outside a rendered response.
     *
     * This is the path the wake and listening cues take, and they are the two earcons that sound
     * while the microphone is open — so this is where the gate has to know the sound is ours.
     */
    fun playEarcon(id: EarconId): Boolean {
        if (released) return false
        val played = earconPlayer.play(id)
        if (played) noteEmitted()
        return played
    }

    /**
     * Speaks while refreshing what we last emitted, because speech outlasts the gate's attribution
     * window: a reply longer than it would otherwise read as a foreign app for the rest of its own
     * duration, and the microphone would be closed mid-answer.
     */
    private suspend fun speakWithHeartbeat(text: String, language: String) = coroutineScope {
        noteEmitted()
        val utterance = launch { ttsEngine.speak(text, language) }
        while (utterance.isActive) {
            delay(EMISSION_HEARTBEAT_MS)
            if (utterance.isActive) noteEmitted()
        }
    }

    fun release() {
        released = true
        scope.cancel()
        earconPlayer.release()
        ttsEngine.release()
    }

    internal companion object {
        /** Shorter than the gate's attribution window, so a long reply never falls out of it. */
        private const val EMISSION_HEARTBEAT_MS = 1_000L

        /**
         * One line describing what is about to be spoken, including the text itself, its length
         * and a digest. The length and digest answer "is this the same response" without having
         * to compare prose by eye, and the digest is stable for a given text within a build.
         */
        internal fun describeForLog(response: VoiceResponse, language: String): String = when (response) {
            is VoiceResponse.Silent -> "kind=silent nothing_spoken"

            is VoiceResponse.Earcon -> "kind=earcon earcon=${response.id} nothing_spoken"

            is VoiceResponse.Spoken -> spokenForLog("spoken", response.text, language)

            is VoiceResponse.Combined -> spokenForLog(
                "combined earcon=${response.earcon}",
                response.spokenText,
                language,
            )
        }

        private fun spokenForLog(prefix: String, text: String, language: String): String = "kind=$prefix lang=$language len=${text.length} digest=${digest(text)} text=\"${escapeForLog(text)}\""

        internal fun digest(text: String): String = text.hashCode().toUInt().toString(16)

        /**
         * Keeps one response on one line. An answer containing a newline would otherwise push the
         * rest of itself onto continuation lines that carry no prefix, so grepping responses would
         * compare only the first line of each and two different answers could look identical.
         */
        internal fun escapeForLog(text: String): String = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
