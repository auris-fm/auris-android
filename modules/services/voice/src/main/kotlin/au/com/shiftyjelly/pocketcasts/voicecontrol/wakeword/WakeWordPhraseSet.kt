package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

/**
 * The wake phrases this client is configured for, and the renderings ASR is
 * observed to produce for them.
 *
 * This exists so that "is this transcript the wake phrase?" has one source. The
 * exclusion that keeps a wake phrase out of routing must not carry its own list:
 * a list that grows by one spelling every time someone says the word is not a
 * rule, it is a record of the last mis-hearings we happened to see. The
 * detector's own configuration is the authority for what the wake phrase is —
 * `assets/oww/auris.onnx` is trained for these — and this type is where that
 * configuration is stated for the rest of the pipeline.
 *
 * Known renderings, from real runs: "hey aris。" (a question follow-up on the
 * same utterance) and "Oace." — the classifier fires on the audio either way, so
 * the text comparison has to tolerate what the acoustic model accepts.
 *
 * If the bundled classifier is swapped for another keyword, this list changes
 * with it; that is the point of it living here rather than inside the check.
 */
internal object WakeWordPhraseSet {
    /** Leading forms people commonly say before the keyword. */
    private val LEADING_WORDS = listOf("hey", "ok", "okay", "hi")

    /**
     * The keyword and the renderings the detector tolerates. Matched after
     * normalisation, so punctuation, casing and spacing are already gone.
     */
    private val PHRASES = setOf(
        "auris",
        "aris",
        "oace",
    )

    /** True when nothing but a wake phrase survives in [normalised]. */
    fun isPhraseOnly(normalised: String): Boolean {
        if (normalised.isEmpty()) return true
        if (normalised in PHRASES) return true
        // Any leading form that leaves exactly a phrase counts; matching the
        // *first* prefix that fits would strip "ok" out of "okayauris" and miss
        // an ordinary way of saying it.
        return LEADING_WORDS.any { normalised.startsWith(it) && normalised.removePrefix(it) in PHRASES }
    }
}
