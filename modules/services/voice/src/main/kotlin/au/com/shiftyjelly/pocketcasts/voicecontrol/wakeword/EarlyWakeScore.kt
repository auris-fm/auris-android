package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

/**
 * When the wake word may be scored before the user has finished speaking.
 *
 * The detector scores classifier windows whose endpoint falls inside a fixed distance after
 * speech onset (see `WakeWordJni.cpp`: `kOnsetCutoffSamples`). Windows past that boundary are
 * never scored, so a segment truncated exactly there produces the same score the finished
 * segment would. That equivalence is what makes scoring early safe rather than approximate:
 * this object decides only WHEN to hand the detector that prefix, never what it concludes.
 */
object EarlyWakeScore {

    /**
     * The onset-relative prefix the detector can score, in samples at [SCORED_SAMPLE_RATE_HZ].
     *
     * `WakeWordJni.cpp` freezes this at 32000 samples after onset. Keeping the number here means
     * the client cannot quietly score a longer window than the detector supports.
     */
    const val CUTOFF_SAMPLES = 32_000

    /** The rate [CUTOFF_SAMPLES] is expressed in. The detector rejects any other rate. */
    const val SCORED_SAMPLE_RATE_HZ = 16_000

    /**
     * A prefix that may be scored now, as a window inside the segment being accumulated.
     *
     * [startSample] is the speech onset in the segment, so a caller can cut the same slice the
     * detector would see from a finished segment; [lengthSamples] is always [CUTOFF_SAMPLES], which
     * is what makes the early score equal the finished one.
     */
    data class Request(
        val startSample: Int,
        val lengthSamples: Int,
        val isGraceActive: Boolean,
    ) {
        /**
         * Whether transcription may begin before the segment closes.
         *
         * Outside grace the wake word is what makes an utterance addressed to Auris, so ASR waits
         * for a positive result — starting it on a negative would transcribe speech the user did
         * not direct at the app. Inside grace the user is already talking to Auris, so waiting
         * would add the score's cost to every follow-up and buys nothing.
         */
        fun letsAsrStartEarly(wakePositive: Boolean): Boolean = isGraceActive || wakePositive
    }

    /**
     * The prefix to score now, or null when there is nothing worth scoring yet.
     *
     * Null means "wait": either the utterance has not yet produced [CUTOFF_SAMPLES] of audio
     * after onset, in which case a short utterance simply runs at end of speech as it always did,
     * or the sample rate is unknown and the boundary cannot be placed in samples at all.
     */
    fun request(
        speechOnsetSample: Int,
        availableSamples: Int,
        sampleRateHz: Int,
        isGraceActive: Boolean,
    ): Request? {
        if (sampleRateHz != SCORED_SAMPLE_RATE_HZ) return null
        if (speechOnsetSample < 0) return null
        val availableAfterOnset = availableSamples - speechOnsetSample
        if (availableAfterOnset < CUTOFF_SAMPLES) return null
        return Request(
            startSample = speechOnsetSample,
            lengthSamples = CUTOFF_SAMPLES,
            isGraceActive = isGraceActive,
        )
    }

    /** [CUTOFF_SAMPLES] expressed in milliseconds at [sampleRateHz], for logging. */
    fun cutoffMillis(sampleRateHz: Int = SCORED_SAMPLE_RATE_HZ): Long = if (sampleRateHz <= 0) 0L else CUTOFF_SAMPLES * 1000L / sampleRateHz
}
