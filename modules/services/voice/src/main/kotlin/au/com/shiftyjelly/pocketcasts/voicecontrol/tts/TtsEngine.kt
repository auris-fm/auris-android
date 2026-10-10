package au.com.shiftyjelly.pocketcasts.voicecontrol.tts

interface TtsEngine {
    suspend fun warmUp(language: String)

    /**
     * Speaks [text], returning only once the audio has finished playing.
     *
     * @throws TtsPlaybackIncompleteException when playback did not drain within the wait bound, so a
     *   caller cannot mistake an incomplete answer for a completed one.
     */
    suspend fun speak(text: String, language: String)

    fun release()
}

/**
 * The utterance returned with audio still queued. A distinct type so the caller's failure path can
 * treat "did not finish playing" differently from "synthesis failed", and so a success report can
 * never be produced from an incomplete drain.
 */
class TtsPlaybackIncompleteException(
    val framesWritten: Int,
    val framesPlayed: Int,
) : Exception("TTS playback incomplete: played $framesPlayed of $framesWritten frames")

class FakeTtsEngine : TtsEngine {
    var isWarm = false
        private set
    var lastSpokenText: String? = null
        private set
    var lastSpokenLanguage: String? = null
        private set
    private var released = false

    override suspend fun warmUp(language: String) {
        isWarm = true
    }

    override suspend fun speak(text: String, language: String) {
        check(!released) { "Engine is released" }
        lastSpokenText = text
        lastSpokenLanguage = language
    }

    override fun release() {
        released = true
    }
}
