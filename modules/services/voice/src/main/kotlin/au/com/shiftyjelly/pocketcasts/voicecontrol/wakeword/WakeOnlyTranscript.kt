package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

/**
 * A transcript that is the wake phrase and nothing else.
 *
 * Such an utterance is the signal that the user started talking, not a question
 * to answer: it must not be routed, and it must not be a cloud-escalation
 * candidate. Without this the wake phrase itself reaches the router, which
 * classifies it `no_match`, and `no_match` escalates — so saying "Auris" spends
 * the window's single dispatch and the real question behind it is refused.
 *
 * The test is **equality after normalisation**, never a prefix: a wake phrase
 * followed by words the router could not classify is still a question, and
 * follows the ordinary rules.
 *
 * [WAKE_PHRASES] is this client's configured set for the bundled classifier
 * (`assets/oww/auris.onnx`), including the renderings ASR is known to produce
 * for it. A different or localised wake word belongs in this list rather than in
 * a string literal at the call site.
 */
internal object WakeOnlyTranscript {
    /** Common leading forms people say before the keyword. */
    private val LEADING_WORDS = listOf("hey", "ok", "okay", "hi")

    /** The configured wake phrases, and the renderings the detector tolerates. */
    private val WAKE_PHRASES = setOf("auris", "aris")

    fun isWakeOnly(transcript: String): Boolean {
        val normalised = normalise(transcript)
        if (normalised.isEmpty()) return true
        // Any leading form that leaves exactly a wake phrase counts; matching
        // the *first* prefix that fits would strip "ok" out of "okayauris" and
        // miss a perfectly ordinary way of saying it.
        return LEADING_WORDS.any { normalised.removePrefix(it) in WAKE_PHRASES && normalised != it } ||
            normalised in WAKE_PHRASES
    }

    /** Lowercased, punctuation and spacing removed — 'Hey aris。' → "hey aris". */
    private fun normalise(transcript: String): String = transcript.lowercase().filter { it.isLetterOrDigit() }
}
