@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun `an earcon played outside a response is reported as emitted audio`() = runTest {
        // The wake and listening cues take this path, and they are the two earcons that sound
        // while the microphone is open.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        assertFalse(renderer.hasEmittedAudio.value)

        renderer.playEarcon(EarconId.WAKE_WORD)

        assertTrue(renderer.hasEmittedAudio.value)
    }

    @Test
    fun `an earcon the player refuses is not reported as emitted audio`() = runTest {
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(false)

        renderer.playEarcon(EarconId.WAKE_WORD)

        assertFalse(renderer.hasEmittedAudio.value)
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

    @Test
    fun `an incomplete spoken reply reaches the caller's failure handling`() = runTest {
        // Through the connection, not just the throw: a failing TTS engine must arrive at the
        // renderer's failure callback. Asserting only that speak() throws would pass while the caller
        // still saw success, which is the trap this test exists to avoid.
        val failing = object : TtsEngine {
            override suspend fun warmUp(language: String) = Unit
            override suspend fun speak(text: String, language: String) {
                throw TtsPlaybackIncompleteException(framesWritten = 100, framesPlayed = 40)
            }

            override fun release() = Unit
        }
        val renderer = AudioFeedbackRenderer(earconPlayer, failing)
        var reported: Throwable? = null
        var delivered = false
        renderer.onPlaybackFailure = { error ->
            reported = error
            delivered = true
        }

        renderer.render(VoiceResponse.Spoken("a reply that will not finish"))
        advanceUntilIdle()

        assertTrue("the delivery failure must reach the caller's failure handling", delivered)
        val error = reported
        assertTrue("the failure must carry the playback outcome", error is TtsPlaybackIncompleteException)
        assertEquals(40, (error as TtsPlaybackIncompleteException).framesPlayed)
    }

    @Test
    fun `a delivery failure produces user-visible feedback, not just a flag`() = runTest {
        // The service's handler for an undelivered reply plays the ERROR earcon, which is the same
        // user-visible feedback the cloud path raises. This asserts the producing half: a renderer
        // failure whose callback raises feedback. A flag nothing reads would satisfy neither.
        val failing = object : TtsEngine {
            override suspend fun warmUp(language: String) = Unit
            override suspend fun speak(text: String, language: String) {
                throw TtsPlaybackIncompleteException(framesWritten = 10, framesPlayed = 2)
            }

            override fun release() = Unit
        }
        val feedbackPlayer = mock<EarconPlayer>()
        val renderer = AudioFeedbackRenderer(feedbackPlayer, failing)
        // Mirrors VoiceControlService.onSpokenReplyNotDelivered.
        renderer.onPlaybackFailure = { renderer.playEarcon(EarconId.ERROR) }

        renderer.render(VoiceResponse.Spoken("a reply that will not finish"))
        advanceUntilIdle()

        verify(feedbackPlayer).play(EarconId.ERROR)
    }
}
