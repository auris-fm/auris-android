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

    /**
     * CONTROLLABLY SUSPENDED BACKEND (spec c9c37325): when set, [speak] suspends until the gate
     * completes — modelling synthesis+playback in flight — so a control can prove the hold survives
     * sink return and queued/playing audio, and releases only when output truly finishes.
     */
    var speakGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Completed when [speak] RETURNS (true output terminal). Test visibility. */
    var speakReturned: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun warmUp(language: String) {
        isWarm = true
    }

    /** When set, [speak] throws this instead of completing — models a TTS failure mid-render. */
    var speakFailure: Throwable? = null

    /**
     * When set, [speak]'s NonCancellable cleanup waits on this gate before completing — so the
     * control can prove the hold survives while output stop/clear is blocked, then releases once
     * after the gate completes. Models a real TTS engine whose stop/clear suspends (e.g. AudioTrack
     * drain, hardware flush).
     */
    var clearCompletedGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Set to true when the NonCancellable cleanup has begun (stop requested). Test visibility. */
    @Volatile var clearRequested: Boolean = false
        private set

    override suspend fun speak(text: String, language: String) {
        check(!released) { "Engine is released" }
        try {
            speakGate?.await()
            speakFailure?.let { throw it }
            lastSpokenText = text
            lastSpokenLanguage = language
            speakReturned?.complete(Unit)
        } finally {
            // NON-CANCELLABLE CLEANUP (reviewer 7a04a952): models a real engine's stop/clear that
            // suspends. The control asserts the hold remains while this is blocked, then releases
            // exactly once after the gate completes.
            if (clearCompletedGate != null) {
                clearRequested = true
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    clearCompletedGate?.await()
                }
            }
        }
    }

    override fun release() {
        released = true
    }
}
