package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioFeedbackRendererTest {
    private lateinit var earconPlayer: EarconPlayer
    private lateinit var ttsEngine: FakeTtsEngine
    private lateinit var renderer: AudioFeedbackRenderer

    @Before
    fun setUp() {
        earconPlayer = mock()
        ttsEngine = FakeTtsEngine()
        renderer = AudioFeedbackRenderer(earconPlayer, ttsEngine)
    }

    @Test
    fun `Silent response does nothing`() = runTest {
        renderer.render(VoiceResponse.Silent)
        verifyNoInteractions(earconPlayer)
    }

    @Test
    fun `an audible response is reported as emitted audio`() = runTest {
        assertFalse(renderer.hasEmittedAudio.value)
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(true)

        renderer.render(VoiceResponse.Earcon(EarconId.SUCCESS))

        assertTrue(renderer.hasEmittedAudio.value)
    }

    @Test
    fun `a silent response is not reported as emitted audio`() = runTest {
        renderer.render(VoiceResponse.Silent)

        assertFalse(renderer.hasEmittedAudio.value)
    }

    @Test
    fun `Earcon response plays via EarconPlayer`() = runTest {
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(true)
        renderer.render(VoiceResponse.Earcon(EarconId.SUCCESS))
        verify(earconPlayer).play(EarconId.SUCCESS)
    }

    @Test
    fun `Spoken response routes to TtsEngine`() = runTest {
        ttsEngine.warmUp("en")
        renderer.render(VoiceResponse.Spoken("1.5x speed"), language = "en")
        assertEquals("1.5x speed", ttsEngine.lastSpokenText)
        assertEquals("en", ttsEngine.lastSpokenLanguage)
    }

    @Test
    fun `Combined response plays earcon then speaks`() = runTest {
        ttsEngine.warmUp("en")
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(true)
        renderer.render(VoiceResponse.Combined(EarconId.SUCCESS, "1.5x speed"), language = "en")
        verify(earconPlayer).play(EarconId.SUCCESS)
        assertEquals("1.5x speed", ttsEngine.lastSpokenText)
    }

    @Test
    fun `playEarcon delegates to EarconPlayer`() {
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        renderer.playEarcon(EarconId.WAKE_WORD)
        verify(earconPlayer).play(EarconId.WAKE_WORD)
    }

    @Test
    fun `sequential render calls result in last-spoken text`() = runTest {
        ttsEngine.warmUp("en")
        renderer.render(VoiceResponse.Spoken("first"), language = "en")
        renderer.render(VoiceResponse.Spoken("second"), language = "en")
        assertEquals("second", ttsEngine.lastSpokenText)
    }

    @Test
    fun `release disposes both earcon and TTS resources`() {
        renderer.release()
        verify(earconPlayer).release()
    }

    // ── The pre-TTS diagnostic line ────────────────────────────────────

    @Test
    fun `a spoken response is described with its text, length and digest`() {
        val described = AudioFeedbackRenderer.describeForLog(
            VoiceResponse.Spoken("Cloud processing is coming soon"),
            language = "en",
        )

        assertTrue(described, described.contains("Cloud processing is coming soon"))
        assertTrue(described, described.contains("len=31"))
        assertTrue(described, described.contains("digest=${AudioFeedbackRenderer.digest("Cloud processing is coming soon")}"))
        assertTrue(described, described.contains("lang=en"))
    }

    @Test
    fun `the digest separates different responses and is stable for the same one`() {
        val first = AudioFeedbackRenderer.digest("here is one answer")
        val second = AudioFeedbackRenderer.digest("here is a different answer")

        assertNotEquals("different text must not look like the same response", first, second)
        assertEquals("the same text must digest the same way", first, AudioFeedbackRenderer.digest("here is one answer"))
    }

    @Test
    fun `an answer containing a newline or a quote stays on one line`() {
        // An answer spanning lines would otherwise push its remainder onto continuation lines
        // with no prefix, so a grep would compare only the first line of each response and two
        // different answers could look identical.
        val described = AudioFeedbackRenderer.describeForLog(
            VoiceResponse.Spoken("First line.\nSecond line, with a \"quote\"."),
            language = "en",
        )

        assertFalse("no raw newline may survive into the log line", described.contains('\n'))
        assertFalse("no raw carriage return either", described.contains('\r'))
        assertTrue(described, described.contains("First line.\\nSecond line, with a \\\"quote\\\"."))
    }

    @Test
    fun `responses that speak nothing say so rather than looking empty`() {
        val silent = AudioFeedbackRenderer.describeForLog(VoiceResponse.Silent, language = "en")
        val earcon = AudioFeedbackRenderer.describeForLog(VoiceResponse.Earcon(EarconId.SUCCESS), language = "en")

        assertTrue(silent, silent.contains("kind=silent"))
        assertTrue(earcon, earcon.contains("nothing_spoken"))
    }
}
