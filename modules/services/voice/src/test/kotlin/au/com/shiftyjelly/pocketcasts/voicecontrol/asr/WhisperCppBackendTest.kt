package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhisperCppBackendTest {

    @Test
    fun `blank transcript is rejected`() {
        assertNull(normalizeWhisperTranscript("  "))
    }

    @Test
    fun `single bracketed annotation is rejected`() {
        assertNull(normalizeWhisperTranscript(" [Music] "))
    }

    @Test
    fun `multiple bracketed annotations are rejected`() {
        assertNull(normalizeWhisperTranscript("[door opens]  [door closes]"))
    }

    @Test
    fun `parenthesized annotation is rejected`() {
        assertNull(normalizeWhisperTranscript("(typing)"))
    }

    @Test
    fun `normal English transcript is retained`() {
        assertEquals("play the next episode", normalizeWhisperTranscript(" play the next episode "))
    }

    @Test
    fun `translated English transcript is retained`() {
        assertEquals("fast forward half a minute", normalizeWhisperTranscript("fast forward half a minute"))
    }

    @Test
    fun `mixed annotation and speech transcript is retained`() {
        assertEquals("[Music] play the next episode", normalizeWhisperTranscript("[Music] play the next episode"))
    }
}
