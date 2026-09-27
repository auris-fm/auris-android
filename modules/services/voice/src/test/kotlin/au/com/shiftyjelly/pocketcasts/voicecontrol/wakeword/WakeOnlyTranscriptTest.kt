package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake phrase on its own is not a question (owner's run, 2026-09-28). */
class WakeOnlyTranscriptTest {

    @Test
    fun `the wake phrase alone is wake-only`() {
        assertTrue(WakeOnlyTranscript.isWakeOnly("Auris"))
        assertTrue(WakeOnlyTranscript.isWakeOnly("Auris."))
        assertTrue(WakeOnlyTranscript.isWakeOnly("Hey Auris!"))
    }

    @Test
    fun `the run's exact transcript is wake-only`() {
        // What the device heard: the phrase with a CJK full stop, as ASR renders it.
        assertTrue(WakeOnlyTranscript.isWakeOnly("hey aris。"))
        assertTrue(WakeOnlyTranscript.isWakeOnly("hey aris"))
    }

    @Test
    fun `a wake phrase followed by words is a question, not wake-only`() {
        // Equality after normalisation, never a prefix: these must still escalate.
        assertFalse(WakeOnlyTranscript.isWakeOnly("hey aris, what is AI"))
        assertFalse(WakeOnlyTranscript.isWakeOnly("Auris, explain quantum computing"))
        assertFalse(WakeOnlyTranscript.isWakeOnly("hey auris pause"))
    }

    @Test
    fun `overlapping leading forms are matched whole`() {
        // "okayauris" starts with "ok" too: stripping the shorter prefix would
        // leave "ayauris" and miss it, so any full match counts.
        assertTrue(WakeOnlyTranscript.isWakeOnly("okay auris"))
        assertTrue(WakeOnlyTranscript.isWakeOnly("Okay, Auris."))
        assertFalse(WakeOnlyTranscript.isWakeOnly("okay auris, what is AI"))
    }

    @Test
    fun `ordinary utterances are untouched`() {
        assertFalse(WakeOnlyTranscript.isWakeOnly("pause"))
        assertFalse(WakeOnlyTranscript.isWakeOnly("Explain what is AI."))
        assertFalse(WakeOnlyTranscript.isWakeOnly("解释一下什么是AI。"))
    }
}
