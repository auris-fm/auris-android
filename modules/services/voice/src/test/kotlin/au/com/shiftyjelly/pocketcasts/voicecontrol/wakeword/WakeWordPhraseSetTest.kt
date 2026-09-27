package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fallback rule, for utterances with no wake timing to lean on. */
class WakeWordPhraseSetTest {

    @Test
    fun `only the configured phrase matches`() {
        assertTrue(WakeWordPhraseSet.matches("Auris"))
        assertTrue(WakeWordPhraseSet.matches("auris."))
        assertFalse("a spelling is not the rule", WakeWordPhraseSet.matches("Oace."))
        assertFalse(WakeWordPhraseSet.matches("hey aris"))
    }

    @Test
    fun `anything with content is not the wake phrase`() {
        assertFalse(WakeWordPhraseSet.matches("Auris, what is AI"))
        assertFalse(WakeWordPhraseSet.matches("pause"))
        assertFalse(WakeWordPhraseSet.matches("Explain what is AI."))
    }
}
