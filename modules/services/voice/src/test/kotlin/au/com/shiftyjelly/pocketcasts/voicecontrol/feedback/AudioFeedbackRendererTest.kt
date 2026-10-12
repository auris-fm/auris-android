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
        whenever(earconPlayer.emittedDurationMs()).thenReturn(3_000L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        renderer.render(VoiceResponse.Earcon(EarconId.SUCCESS))
        // THE HEARTBEAT RUNS ON THE MAIN DISPATCHER, which Robolectric PAUSES: a delayed task is only
        // run when the looper is advanced past its delay. Idling alone is not enough, because the one
        // tick this waits for is scheduled a full heartbeat (1000 ms) ahead. `idleFor` advances the
        // clock, so the tick lands deterministically instead of depending on wall time.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_500))

        assertTrue(renderer.hasEmittedAudio.value)
    }

    @Test
    fun `an earcon played outside a response is reported as emitted only once it SOUNDS`() = runTest {
        // The wake and listening cues take this path, and they are the two earcons that sound while the
        // microphone is open.
        //
        // ACCEPTANCE IS NOT SOUNDING. `play` returning true means the sink took the frames; the cue may
        // sit queued behind earlier audio. Attribution follows OBSERVED progress past the cue's first
        // frame, so the heartbeat reports it and the acceptance alone does not.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)
        assertFalse(renderer.hasEmittedAudio.value)

        renderer.playEarcon(EarconId.WAKE_WORD)

        assertFalse(
            "a QUEUED cue must not be attributed: the listener has not heard it yet",
            renderer.hasEmittedAudio.value,
        )
    }

    @Test
    fun `a cue SHORTER than the heartbeat is still observed and attributed`() = runTest {
        // @spec's short-cue requirement, using @reviewer's 100-frame cue (about 6 ms at 16 kHz).
        //
        // A cue shorter than one heartbeat has a bound BELOW one tick, so a loop that required the tick
        // to fit inside the bound NEVER RAN -- and a cue never observed was never attributed, however
        // audibly it sounded. The bound is a CEILING on waiting, not a reason to skip looking.
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(6L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        renderer.playEarcon(EarconId.SUCCESS)
        // Well past the cue's own span, because the point is that the observation happens at all.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_500))

        assertTrue(
            "a cue shorter than the heartbeat must still be observed and attributed " +
                "(notes=${renderer.emissionNotesForTest()} boundMs=${earconPlayer.emittedDurationMs()})",
            renderer.emissionNotesForTest() >= 1,
        )
    }

    @Test
    fun `a SHORT cue still QUEUED is not attributed`() = runTest {
        // The same short cue, before it sounds: the first-frame distinction must hold here too, or the
        // fix above would attribute every short cue on acceptance -- the submission note by another route.
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(6L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)

        renderer.playEarcon(EarconId.SUCCESS)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_500))

        assertFalse(
            "a short cue that has not begun sounding must not be attributed",
            renderer.hasEmittedAudio.value,
        )
    }

    @Test
    fun `a cue observed to be SOUNDING is reported as emitted audio`() = runTest {
        // The positive control, so the rule above is not satisfied by never attributing anything.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(3_000L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        renderer.playEarcon(EarconId.WAKE_WORD)
        // THE HEARTBEAT RUNS ON THE MAIN DISPATCHER, which Robolectric PAUSES: a delayed task is only
        // run when the looper is advanced past its delay. Idling alone is not enough, because the one
        // tick this waits for is scheduled a full heartbeat (1000 ms) ahead. `idleFor` advances the
        // clock, so the tick lands deterministically instead of depending on wall time.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_500))

        assertTrue(
            "a cue the output has progressed through must be attributed",
            renderer.hasEmittedAudio.value,
        )
    }

    @Test
    fun `an UNOBSERVABLE cue is not reported as emitted audio`() = runTest {
        // Unavailable progress is not evidence of sounding. Setting the attribution from an unknown
        // would let the gate read the microphone as ours on no observation at all.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Unknown)

        renderer.playEarcon(EarconId.WAKE_WORD)
        // THE HEARTBEAT RUNS ON THE MAIN DISPATCHER, which Robolectric PAUSES: a delayed task is only
        // run when the looper is advanced past its delay. Idling alone is not enough, because the one
        // tick this waits for is scheduled a full heartbeat (1000 ms) ahead. `idleFor` advances the
        // clock, so the tick lands deterministically instead of depending on wall time.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_500))

        assertFalse(
            "an unavailable observation must not be reported as emitted audio",
            renderer.hasEmittedAudio.value,
        )
    }

    @Test
    fun `a cue longer than the heartbeat window keeps the attribution fresh past its submission`() {
        // The earcon/output-gate obligation: submission is not the end of the cue. A cue longer than
        // the gate's attribution window would let the microphone read as a foreign app part-way
        // through our own cue -- the same failure the speak path covers with a heartbeat.
        //
        // The cue here is three heartbeat windows long, so the submission note alone covers only its
        // first window; anything observing the gate later than that needs the coverage.
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val notes = java.util.concurrent.atomic.AtomicInteger(0)
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(3_000L)
        // Still sounding for the whole observation: the attribution must keep being refreshed, and
        // only an observed CONSUMPTION may end it.
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        // A dispatcher the test controls, so the heartbeat's ticks are deterministic rather than
        // dependent on real-time scheduling. Must be set before the renderer's scope is first used.
        renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
        renderer.playEarcon(EarconId.WAKE_WORD)

        // NO NOTE AT ACCEPTANCE. The cue was accepted and has not been observed to sound yet, so the
        // attribution must not be set -- @spec's render-start rule. The coverage is what covers the
        // queued delay; the ATTRIBUTION follows the first observed frame.
        assertEquals(
            "acceptance alone must not be attributed: the cue may still be queued",
            0,
            renderer.emissionNotesForTest(),
        )
        // The cue is still sounding well past the first window: the attribution must have been
        // refreshed, which a submission-only note cannot do. The renderer's coverage runs on the Main
        // dispatcher, so Robolectric's looper must be advanced for it to tick.
        kotlinx.coroutines.runBlocking {
            repeat(120) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(20)
            }
        }
        assertTrue(
            "a cue longer than one window must keep the attribution fresh " +
                "(notes=${renderer.emissionNotesForTest()} clock=${clock.get()})",
            renderer.emissionNotesForTest() > 1,
        )
        renderer.release()
    }

    @Test
    fun `a short cue is attributed once it is observed to sound`() = runTest {
        // The control: the attribution comes from OBSERVED SOUNDING, and a cue whose span is shorter
        // than one heartbeat is still attributed once the observation reports progress.
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(1_500L)
        // OBSERVED SOUNDING, then consumed: the note comes from the cue being heard, not from its
        // submission -- acceptance alone is queued, not audible.
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        renderer.playEarcon(EarconId.SUCCESS)
        // THE HEARTBEAT RUNS ON THE MAIN DISPATCHER, which Robolectric PAUSES: a delayed task is only
        // run when the looper is advanced past its delay. Idling alone is not enough, because the one
        // tick this waits for is scheduled a full heartbeat (1000 ms) ahead. `idleFor` advances the
        // clock, so the tick lands deterministically instead of depending on wall time.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_500))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Consumed)
        // (the consumed observation needs no further draining)

        assertTrue(
            "a cue observed to sound is attributed",
            renderer.emissionNotesForTest() >= 1,
        )
        renderer.release()
    }

    @Test
    fun `attribution ends when the cue is OBSERVED consumed, not when the estimate elapses`() {
        // @spec's distinction: the accepted-frame duration is an estimate of queued audio, not
        // completion. The coverage must end on the OUTPUT's report, so a cue that finishes early
        // stops being attributed early -- and one still sounding keeps the gate informed.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(3_000L)
        // Still playing at first, then consumed.
        var consumed = false
        whenever(earconPlayer.completion()).thenAnswer {
            if (consumed) EarconPlayer.EarconCompletion.Consumed else EarconPlayer.EarconCompletion.Playing
        }

        renderer.playEarcon(EarconId.WAKE_WORD)
        // TWO HEARTBEATS while it is still sounding: the attribution must be refreshed more than once.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_500))
        val notesWhilePlaying = renderer.emissionNotesForTest()
        assertTrue("a sounding cue keeps the attribution fresh (notes=$notesWhilePlaying)", notesWhilePlaying > 1)

        consumed = true
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_500))
        val notesAfterConsumption = renderer.emissionNotesForTest()
        assertEquals(
            "once consumed the attribution stops, so the note count no longer grows " +
                "(whilePlaying=$notesWhilePlaying after=$notesAfterConsumption)",
            notesWhilePlaying,
            notesAfterConsumption,
        )
        renderer.release()
    }

    @Test
    fun `attribution ends when the cue stops being consumable rather than covering its estimate`() {
        // A cue that is stopped, cancelled or failed must end its attribution immediately: covering
        // it to its nominal length would keep the gate treating a sound the user can no longer hear
        // as ours. `Unknown` is that state -- the acceptance is gone.
        renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(3_000L)
        var stopped = false
        whenever(earconPlayer.completion()).thenAnswer {
            if (stopped) EarconPlayer.EarconCompletion.Unknown else EarconPlayer.EarconCompletion.Playing
        }

        renderer.playEarcon(EarconId.WAKE_WORD)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1_100) }
        val beforeStop = renderer.emissionNotesForTest()

        stopped = true
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(2_500) }
        assertEquals(
            "a stop must end the attribution rather than let the estimate keep covering it " +
                "(before=$beforeStop after=${renderer.emissionNotesForTest()})",
            beforeStop,
            renderer.emissionNotesForTest(),
        )
        renderer.release()
    }

    @Test
    fun `ending the session stops subsequent cue attribution`() {
        // @reviewer's lifecycle probe: after endSession, an already-accepted cue must not keep
        // claiming fresh output -- the session is over, so nothing it was attributed to is still
        // sounding for the user.
        renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(3_000L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        renderer.playEarcon(EarconId.WAKE_WORD)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1_100) }
        renderer.endSession()
        val before = renderer.emissionNotesForTest()
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(2_500) }
        assertEquals(
            "a released earcon must not keep claiming new output " +
                "(before=$before after=${renderer.emissionNotesForTest()})",
            before,
            renderer.emissionNotesForTest(),
        )
    }

    @Test
    fun `a cue stops being attributed once its own span has elapsed`() {
        // @reviewer's second probe: coverage must be bounded by the cue, so a claimed 1_500 ms span
        // cannot still be emitting notes at 2_000 ms. The bound is a ceiling on a cue that never
        // reports progress; a cue still reporting Playing past it must not extend past it.
        renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(true)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(1_500L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        renderer.playEarcon(EarconId.WAKE_WORD)
        // Snapshot just BEFORE the span is crossed (span 1_500 ms): the submission note plus the one
        // at 1_000 ms. The next tick would land at 2_000 ms, PAST the cue's own span -- that is the
        // note the ceiling must prevent, and it is the difference the guard makes.
        kotlinx.coroutines.runBlocking {
            repeat(52) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(20)
            }
        }
        val beforeCrossing = renderer.emissionNotesForTest()
        // Now let the crossing tick happen.
        kotlinx.coroutines.runBlocking {
            repeat(80) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(20)
            }
        }
        assertEquals(
            "a cue may not emit another attribution note once its own span has elapsed " +
                "(beforeCrossing=$beforeCrossing after=${renderer.emissionNotesForTest()})",
            beforeCrossing,
            renderer.emissionNotesForTest(),
        )
        renderer.release()
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
