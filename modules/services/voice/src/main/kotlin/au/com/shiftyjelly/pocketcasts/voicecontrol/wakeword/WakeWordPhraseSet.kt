package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

/**
 * What this client's wake word is.
 *
 * One value, not a list: the wake-only decision is made from the detector's
 * timing (was anything spoken after the wake band?), and this is only the
 * fallback for an utterance that had no wake detection to time. Spellings such
 * as `aris` or `oace` used to live here and are deliberately gone — a list that
 * grows by one entry per mis-hearing is a record of our last bad nights, not a
 * rule, and it cannot separate `oace`/`auris` (distance 5) from `iris`/`oris`
 * (distance 1) at any threshold.
 *
 * `auris.onnx` is the bundled classifier; changing the model means changing this.
 */
internal object WakeWordPhraseSet {
    private const val CONFIGURED_PHRASE = "auris"

    /** True when nothing but the configured wake phrase is in [transcript]. */
    fun matches(transcript: String): Boolean = transcript.lowercase().filter(Char::isLetterOrDigit) == CONFIGURED_PHRASE
}
