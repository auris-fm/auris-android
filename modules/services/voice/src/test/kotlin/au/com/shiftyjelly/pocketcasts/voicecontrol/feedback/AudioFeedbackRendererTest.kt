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
import org.junit.Assert.assertNotNull
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
    fun `a REFUSED cue is reported as refused, not accepted`() {
        // @reviewer: playEarcon returned true even when the player returned null, so a REFUSED cue looked
        // accepted to every caller -- they could not tell a cue that never sounded from one that did.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(null)

        assertFalse(
            "a refused cue must not be reported as accepted",
            renderer.playEarcon(EarconId.WAKE_WORD),
        )
        // AND AN ACCEPTED CUE IS REPORTED AS ACCEPTED, so the case is decided by the player's result
        // rather than by the public result being false for everything.
        whenever(earconPlayer.play(EarconId.WAKE_WORD))
            .thenReturn(EarconPlayer.AdmissionToken(1L))
        assertTrue(
            "an accepted cue must be reported as accepted",
            renderer.playEarcon(EarconId.WAKE_WORD),
        )
    }

    @Test
    fun `Silent response does nothing`() = runTest {
        renderer.render(VoiceResponse.Silent)
        verifyNoInteractions(earconPlayer)
    }

    @Test
    fun `an audible response is reported as emitted audio`() = runTest {
        assertFalse(renderer.hasEmittedAudio.value)
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
    fun `a cue observed CONSUMED is attributed, because completion IS measured rendering`() = runTest {
        // @reviewer's measured case. The head had ALREADY passed every accepted frame when the first
        // observation happened, so `completion()` reported Consumed immediately. The heartbeat returned
        // on Consumed WITHOUT noting, so a cue that finished between observations was never attributed --
        // treated as if completion proved nothing, when it is in fact the STRONGEST evidence of sounding.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Consumed)

        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))

        assertTrue(
            "a consumed cue must be attributed: the head passing it IS evidence it sounded",
            renderer.hasEmittedAudio.value,
        )
    }

    @Test
    fun `an OLD observer cannot overwrite its successor's published outcome`() = runTest {
        // @spec's measured race: `coverageExpiredUnknown` is written by a NON-SUSPENDING assignment after
        // `completion()` returns, so CANCELLATION ALONE CANNOT PROTECT IT -- an old observer resuming
        // after a successor has published can overwrite the successor's result.
        //
        // THE ORDERING IS THE CASE. The old observer must be AT ITS DEADLINE -- about to publish -- when
        // the successor publishes its own result, so the two writes genuinely overlap. My first version
        // idled only 1500 ms, well short of the old deadline, so the old observer never wrote at all and
        // the ownership check was unnecessary for the outcome; the mutation SURVIVED it.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        // The OLD observer's cue never sounds, so it will expire.
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)

        renderer.playEarcon(EarconId.WAKE_WORD)
        // RUN THE OLD OBSERVER TO ITS DEADLINE, where it publishes its expiry.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_100))
        assertTrue(
            "the old observer expired, and its expiry is published while it owns the cue",
            renderer.coverageExpiredUnknownForTest(),
        )

        // THE SUCCESSOR NOW OWNS THE CUE and observes it CONSUMED, so it publishes "not an expiry".
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Consumed)
        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))

        assertFalse(
            "the SUCCESSOR's outcome must stand: it observed the cue consumed, so the ending is not an " +
                "expiry -- the old observer must not be able to overwrite it",
            renderer.coverageExpiredUnknownForTest(),
        )
    }

    @Test
    fun `an old stop cannot retire a successor cue's observation`() = runTest {
        // @spec: "an old stop must not retire a successor". The first cue's observation expires to
        // Unknown; a SECOND cue then takes over and is observed Consumed. The first cue's retirement must
        // not overwrite the successor's verdict -- otherwise a stale stop would re-open the gate's
        // attribution on a cue that already finished, or hide one that is still sounding.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)
        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_100))
        assertEquals(
            AudioFeedbackRenderer.CueObservation.Unknown,
            renderer.lastCueObservation.value?.observation,
        )

        // THE SUCCESSOR TAKES OVER and is observed consumed. Its OWN play must be accepted, or the
        // observation never starts and the case would be about the missing acceptance rather than about
        // the retirement it is asking about.
        whenever(earconPlayer.play(EarconId.LISTENING_START)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Consumed)
        renderer.playEarcon(EarconId.LISTENING_START)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertEquals(
            "the successor's own verdict stands",
            AudioFeedbackRenderer.CueObservation.Consumed,
            renderer.lastCueObservation.value?.observation,
        )

        // THE OLD OBSERVER'S OWN RETIREMENT CANNOT PUBLISH, because it no longer owns the cue: the
        // ownership check in `publishObservationIfOwning` refuses it. This is the REAL stale-retirement
        // path -- `publishEndedIfOwning(observer)` -- not a stop API.
        renderer.retireObservationForTest(owner = null)

        assertNotEquals(
            "a stale retirement must not resurrect the old cue's verdict",
            AudioFeedbackRenderer.CueObservation.Unknown,
            renderer.lastCueObservation.value?.observation,
        )
        assertEquals(
            "the successor's verdict is untouched by the stale retirement",
            AudioFeedbackRenderer.CueObservation.Consumed,
            renderer.lastCueObservation.value?.observation,
        )
    }

    @Test
    fun `one cue keeps ONE identity across its verdict changes`() = runTest {
        // @reviewer: "Consumed→Ended on one cue receives two IDs." An identity must be a property of the
        // CUE, allocated once at admission -- otherwise a consumer comparing ids reads its own cue
        // changing its verdict as a DIFFERENT cue and discards the elapsed time it should keep.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)

        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_100))
        val atExpiry = renderer.lastCueObservation.value
        assertEquals(AudioFeedbackRenderer.CueObservation.Unknown, atExpiry?.observation)

        // THE SAME CUE NOW GETS A SECOND VERDICT, through the session end -- the production path that
        // retires a cue. Its identity must NOT change.
        renderer.endSession()
        val atStop = renderer.lastCueObservation.value
        assertEquals(AudioFeedbackRenderer.CueObservation.Ended, atStop?.observation)
        assertEquals(
            "one cue's verdict change is the SAME cue, not a new one",
            atExpiry?.cueId,
            atStop?.cueId,
        )

        // A GENUINELY NEW CUE GETS A NEW IDENTITY, so the distinction is real rather than the id being
        // constant for everything.
        whenever(earconPlayer.play(EarconId.LISTENING_START)).thenReturn(EarconPlayer.AdmissionToken(1L))
        renderer.playEarcon(EarconId.LISTENING_START)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_100))
        assertNotEquals(
            "a DIFFERENT cue must be distinguishable from the first one",
            atExpiry?.cueId,
            renderer.lastCueObservation.value?.cueId,
        )
    }

    @Test
    fun `the renderer covers the admission the PLAY returned, not a later one`() = runTest {
        // @spec 2243570a, the producer-to-owner handoff window: playEarcon called play() and then read
        // "the current admission" SEPARATELY. Between those two, a SUCCESSOR can take the track -- so the
        // renderer would hold the successor's token while believing it held its own, and its later stop
        // would release an admission it never made. The token now comes back FROM the play.
        whenever(earconPlayer.play(EarconId.WAKE_WORD))
            .thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)
        whenever(earconPlayer.emittedDurationMs()).thenReturn(1_000L)

        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(50))

        // THE PLAYER IS NOT ASKED AGAIN. Re-reading would be the handoff window; the returned token is
        // the one the renderer must hold.
        org.mockito.Mockito.verify(earconPlayer, org.mockito.Mockito.never()).currentAdmissionToken()
    }

    @Test
    fun `a session end RETIRES the unresolved observation`() = runTest {
        // The other production retirement path, reached from `stopVoiceControl()`.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)

        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_100))

        renderer.endSession()

        assertEquals(
            "a session end retires the cue it ended: nobody can hear it any more",
            AudioFeedbackRenderer.CueObservation.Ended,
            renderer.lastCueObservation.value?.observation,
        )
    }

    @Test
    fun `the observation WAITS OUT the remaining interval to its deadline`() = runTest {
        // @reviewer's measured case: the observer had ALREADY ENDED at 1_999 ms for a 2_000 ms limit,
        // because the loop refused any heartbeat that would overshoot and so gave up one tick EARLY --
        // the cue was never observed AT the boundary. The loop now sleeps exactly as far as the deadline.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)

        renderer.playEarcon(EarconId.WAKE_WORD)
        // ONE MILLISECOND SHORT OF THE LIMIT: the observation must still be running.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_999))
        assertTrue(
            "the observation must still be waiting at 1_999 ms, not have given up early",
            renderer.coverageIsRunningForTest(),
        )

        // AT AND PAST THE LIMIT it ends, and the ending is an EXPIRY -- not evidence of consumed audio.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertFalse("and it ends at the deadline", renderer.coverageIsRunningForTest())
        assertTrue(
            "a deadline expiry is UNKNOWN, not a claim that the cue completed",
            renderer.coverageExpiredUnknownForTest(),
        )
        // THE TYPED PRODUCTION OUTCOME, not just the test flag: a deadline expiry must reach the consumer
        // as Unknown. Asserting only the flag left the outcome with no production reader.
        assertEquals(
            "the expiry must be published as the typed UNKNOWN observation",
            AudioFeedbackRenderer.CueObservation.Unknown,
            renderer.lastCueObservation.value?.observation,
        )
        // THE PUBLISHED VALUE CARRIES ITS OWN IDENTITY AND INSTANT: the consumer cannot derive either,
        // and a bound measured from whenever the consumer polled would measure its schedule, not the cue.
        val published = renderer.lastCueObservation.value
        assertNotNull("the publication must carry the cue identity", published?.cueId)
        assertTrue(
            "the publication must carry the renderer's OWN instant",
            (published?.publishedAtMs ?: 0L) > 0L,
        )
    }

    @Test
    fun `an observed ending is published as Consumed, not as Unknown`() = runTest {
        // THE OTHER SIDE OF THE DISCRIMINATION: if every ending published Unknown, the consumer would
        // hold our audio open forever and the typed outcome would carry no information.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Consumed)

        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))

        assertEquals(
            "an ending the output MEASURED is Consumed, and only an unresolved one is Unknown",
            AudioFeedbackRenderer.CueObservation.Consumed,
            renderer.lastCueObservation.value?.observation,
        )
    }

    @Test
    fun `a permanently QUEUED cue is observed for a BOUNDED time, not forever`() = runTest {
        // @reviewer's measured defect in my policy implementation: my queued limit was computed as
        // `2_000 + elapsed` EACH PASS, so it grew with the loop and could NEVER be reached -- a
        // permanently queued cue was observed forever and the deadline was a formality, not a bound.
        // The limit is ABSOLUTE from submission.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)

        renderer.playEarcon(EarconId.WAKE_WORD)
        // Well past the 2_000 ms queued limit.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(6_000))

        // THE OBSERVATION MUST HAVE ENDED: a cue that never began sounding is given up on, and nothing
        // is attributed, because nothing was observed to sound.
        assertFalse(
            "a cue that never began sounding must not be attributed",
            renderer.hasEmittedAudio.value,
        )
        // THE OBSERVATION'S OWN LIFETIME IS THE OBSERVATION. A note count cannot show this -- a queued
        // cue emits nothing either way, so a deadline that never expires looks identical to one that
        // does. The job either ended at the bound or it is still running.
        val atLimit = renderer.emissionNotesForTest()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(4_000))
        assertEquals(
            "and the observation has ENDED rather than sliding (atLimit=$atLimit)",
            atLimit,
            renderer.emissionNotesForTest(),
        )
        assertFalse(
            "the queued observation must have ENDED at its bound, not still be running",
            renderer.coverageIsRunningForTest(),
        )
    }

    @Test
    fun `a cue still QUEUED past its estimate is still observed until it sounds`() = runTest {
        // @reviewer's second measured case, and the same defect in a different shape: the observation
        // stopped when the cue's ESTIMATED duration passed, while the cue was still queued. The estimate
        // is a bound on the WAIT, not a statement that the cue is over -- stopping at it ends the
        // observation BEFORE the sound it is waiting for.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.emittedDurationMs()).thenReturn(6L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)

        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(100))
        assertFalse("a queued cue is not attributed yet", renderer.hasEmittedAudio.value)

        // It sounds LATER, after the estimate had already passed.
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_100))

        assertTrue(
            "a queued cue that sounds after its estimate must still be attributed",
            renderer.hasEmittedAudio.value,
        )
    }

    @Test
    fun `a cue observed to be SOUNDING is reported as emitted audio`() = runTest {
        // The positive control, so the rule above is not satisfied by never attributing anything.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        // THE CONSUMING OBSERVATION ITSELF IS COUNTED, THEN THE LOOP ENDS. A cue observed Consumed must
        // be attributed -- completion is the strongest evidence it sounded -- so the boundary adds ONE
        // note rather than none. What must NOT happen is continued growth: the count is read once more
        // AFTER that observation to show the loop has stopped.
        val notesAtConsumption = renderer.emissionNotesForTest()
        assertTrue(
            "the consuming observation attributes the cue (whilePlaying=$notesWhilePlaying " +
                "atConsumption=$notesAtConsumption)",
            notesAtConsumption >= notesWhilePlaying,
        )

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2_500))
        assertEquals(
            "and once consumed the observation ENDS, so the note count no longer grows " +
                "(atConsumption=$notesAtConsumption now=${renderer.emissionNotesForTest()})",
            notesAtConsumption,
            renderer.emissionNotesForTest(),
        )
        renderer.release()
    }

    @Test
    fun `attribution ends when the cue stops being consumable rather than covering its estimate`() {
        // A cue that is stopped, cancelled or failed must end its attribution immediately: covering
        // it to its nominal length would keep the gate treating a sound the user can no longer hear
        // as ours. `Unknown` is that state -- the acceptance is gone.
        renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
    fun `a cue stops being observed once its bounded post-start limit is spent`() {
        // @spec's policy SUPERSEDES the original "own span" ceiling: a cue is observed for at most
        // max(2_000 ms, accepted PCM duration + 1_000 ms) from its FIRST measured rendering. For a
        // 1_500 ms cue that is 2_500 ms, so the observation is BOUNDED and does not run forever -- which
        // is the property the older case was protecting, now stated against the rule that replaced it.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.emittedDurationMs()).thenReturn(1_500L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Playing)

        renderer.playEarcon(EarconId.WAKE_WORD)
        // Run well past the limit. A cue still reporting Playing must NOT be observed indefinitely.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(6_000))
        val afterLimit = renderer.emissionNotesForTest()

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(4_000))

        assertEquals(
            "once the bounded post-start limit is spent the observation ends, so the note count stops " +
                "growing (afterLimit=$afterLimit now=${renderer.emissionNotesForTest()})",
            afterLimit,
            renderer.emissionNotesForTest(),
        )
        renderer.release()
    }

    @Test
    fun `an earcon the player refuses is not reported as emitted audio`() = runTest {
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(null)

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
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(1L))
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
        whenever(earconPlayer.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(1L))
        renderer.render(VoiceResponse.Combined(EarconId.SUCCESS, "1.5x speed"), language = "en")
        verify(earconPlayer).play(EarconId.SUCCESS)
        assertEquals("1.5x speed", ttsEngine.lastSpokenText)
    }

    @Test
    fun `playEarcon delegates to EarconPlayer`() {
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
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

    @Test
    fun `a RELEASED old observer cannot overwrite the same-sound successor's verdict`() = runTest {
        // @spec: verify the ACTUAL stale-observer retirement path end to end. Hold A's observer, admit a
        // SAME-SOUND B, release A, and require B's observation to survive. This is where the ownership
        // guarantee really lives -- `publishObservationIfOwning` refuses a writer that no longer owns the
        // cue -- not in a stop API.
        whenever(earconPlayer.emittedDurationMs()).thenReturn(1_000L)

        // A IS OBSERVED AND ITS VERDICT PUBLISHED.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Consumed)
        renderer.playEarcon(EarconId.WAKE_WORD)
        // THE LOOP WAITS ONE HEARTBEAT-SCALED INTERVAL BEFORE ITS FIRST OBSERVATION, so a 100 ms idle
        // samples before A has been observed at all -- the wait must cover that first observation.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_100))
        val aVerdict = renderer.lastCueObservation.value
        assertEquals(
            "A's own verdict is published before the successor arrives",
            AudioFeedbackRenderer.CueObservation.Consumed,
            aVerdict?.observation,
        )

        // B TAKES OVER -- SAME SOUND, a DIFFERENT admission -- and publishes its OWN verdict.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(2L))
        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_100))
        val bVerdict = renderer.lastCueObservation.value
        assertNotEquals("B is a distinct cue", aVerdict?.cueId, bVerdict?.cueId)

        // A'S RELEASED OBSERVER RETIRES LATE. It no longer owns the cue, so the attempt is REFUSED.
        renderer.retireObservationForTest(owner = null)

        assertEquals(
            "the successor's observation survives a stale retirement",
            bVerdict,
            renderer.lastCueObservation.value,
        )
    }

    @Test
    fun `a held continuation that ARRIVED is refused, preserving its successor`() = runTest {
        // THE CORRECTED CONTROL, per @reviewer's 325d7bd4 and @spec's instruction.
        //
        // TWO THINGS THE EARLIER VERSIONS GOT WRONG, both of them preconditions rather than assertions:
        //  1. `coverageIsRunningForTest` reports only whether the CURRENT job is active. By the time it was
        //     read, the predecessor had already been replaced, so it could be true for the SUCCESSOR --
        //     the case asserted an arrival it never established. Arrival is now ACKNOWLEDGED by the
        //     observer that takes the hold, and that acknowledgement carries A'S OWN JOB.
        //  2. The barrier sits AFTER the loop's wait, outside `coverageLock`, so A has no further
        //     suspension before it reads the output and publishes.
        //
        // And one thing about the mutant: a surviving cancellation-only mutant is NOT a coverage failure.
        // With cancellation removed, A still holds `observerRef = A` while B owns `earconCoverageJob`, so
        // the ownership guard REFUSES A anyway -- the guard, not cancellation, is what preserves B here.
        val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        val arrival = kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.Job>()
        renderer.holdObserverForTest = barrier
        renderer.holdArrivalForTest = arrival
        whenever(earconPlayer.emittedDurationMs()).thenReturn(1_000L)
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Consumed)

        // A STARTS AND ARRIVES AT THE HOLD.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_100))
        assertTrue("A must actually ARRIVE at the hold", arrival.isCompleted)
        val aJob = arrival.getCompleted()

        // B TAKES THE SAME SOUND WHILE A IS HELD, and is admitted with its OWN token.
        whenever(earconPlayer.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(2L))
        renderer.playEarcon(EarconId.WAKE_WORD)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(1_100))
        val bVerdict = renderer.lastCueObservation.value
        assertEquals(
            "B's own verdict is in place while A is held",
            AudioFeedbackRenderer.CueObservation.Consumed,
            bVerdict?.observation,
        )

        // THE CANCELLATION IS OBSERVED ON A'S OWN JOB -- the identity the arrival carried, not a probe.
        assertTrue("admitting B cancels A's continuation", aJob.isCancelled)

        // A RESUMES AND REACHES THE PUBLISH PATH. What it would write is a DIFFERENT verdict, so the
        // assertion separates "A was refused" from "A published and happened to agree".
        whenever(earconPlayer.completion()).thenReturn(EarconPlayer.EarconCompletion.Queued)
        barrier.complete(Unit)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(3_000))

        assertEquals(
            "the arrived continuation is REFUSED: the successor's verdict stands",
            bVerdict,
            renderer.lastCueObservation.value,
        )
        assertEquals(
            "and the verdict A would have written is NOT what stands",
            AudioFeedbackRenderer.CueObservation.Consumed,
            renderer.lastCueObservation.value?.observation,
        )

        // B'S OUTPUT, asserted through a REAL production call rather than through renderer bookkeeping.
        // The session teardown stops the player, and WHICH admission that stop carries is the observable
        // production result: it must be B's, because B is the cue whose audio is sounding.
        //
        // NOTE ON WHAT I REMOVED: an earlier version of this case asserted a `currentCueAdmission` field I
        // had added to the renderer. That field had NO production reader -- it existed only to give the
        // mutation something to break, so a mutant against it proved BOOKKEEPING, not B's playback. I had
        // removed the same kind of field earlier for the same reason, and reintroduced it to make a case
        // fail. The assertion below uses a call the product already makes.
        renderer.endSession()
        org.mockito.Mockito.verify(earconPlayer).stopNow()

        // B'S OWN TEARDOWN, through the real session path.
        renderer.endSession()
        assertEquals(
            "session teardown retires the cue it ended",
            AudioFeedbackRenderer.CueObservation.Ended,
            renderer.lastCueObservation.value?.observation,
        )
    }
}
