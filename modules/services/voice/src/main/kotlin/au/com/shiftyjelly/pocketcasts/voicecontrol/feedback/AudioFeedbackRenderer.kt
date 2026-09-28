package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class AudioFeedbackRenderer(
    private val earconPlayer: EarconPlayer,
    private val ttsEngine: TtsEngine,
) {
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

                is VoiceResponse.Earcon -> earconPlayer.play(response.id)

                is VoiceResponse.Spoken -> ttsEngine.speak(response.text, language)

                is VoiceResponse.Combined -> {
                    earconPlayer.play(response.earcon)
                    ttsEngine.speak(response.spokenText, language)
                }
            }
        }
    }

    fun playEarcon(id: EarconId) {
        if (released) return
        earconPlayer.play(id)
    }

    fun release() {
        released = true
        scope.cancel()
        earconPlayer.release()
        ttsEngine.release()
    }

    internal companion object {
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
