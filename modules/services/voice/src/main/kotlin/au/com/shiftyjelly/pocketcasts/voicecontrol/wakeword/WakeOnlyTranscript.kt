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
 * The phrases come from [WakeWordPhraseSet], which is the one place the client's
 * wake configuration is stated, so this check cannot drift from it.
 */
internal object WakeOnlyTranscript {
    fun isWakeOnly(transcript: String): Boolean = WakeWordPhraseSet.isPhraseOnly(normalise(transcript))

    /** Lowercased, punctuation and spacing removed — 'Hey aris。' → "hey aris". */
    private fun normalise(transcript: String): String = transcript.lowercase().filter { it.isLetterOrDigit() }
}
