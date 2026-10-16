package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// @reviewer thread 4235702596: RETIREMENT OWNS THE STOP/DRAIN EVENT ITSELF, not successor
// supersession. The existing flush control in PlaybackBufferRecorderTest proves the paused producer
// dies when a SUCCESSOR emits -- that is supersession, and it leaves the real gap: pause/stop with
// NOTHING playing afterwards never reaches an epoch bump any stamper sees, so admission stayed live
// with no owner event closing it. This file isolates the NO-SUCCESSOR boundary on the production
// collector (recorder.start): A1 is emitted and delivered (admitted), A2 sits QUEUED behind it, the
// sink FLUSHES with NO successor emission at all, the clock passes the tail deadline, then A2
// finally drains. Three properties must hold together:
//  1. the held pre-flush chunk is REFUSED -- its ticket died at the flush;
//  2. A's retained audio EXPIRES from snapshot() and window() on elapsed time alone;
//  3. a late callback handed A's retired admission cannot write and cannot re-anchor the correlator
//     (admission CLOSED, not merely aged).
@OptIn(ExperimentalCoroutinesApi::class)
class FlushRetirementNoSuccessorTest {
    private fun s16le(samples: ShortArray): ByteArray = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
        samples.forEach { putShort(it) }
    }.array()

    @Test
    fun `a flush with no successor retires admission at the event - held chunk refused and tail expires`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        var nowMs = 0L
        recorder.monotonicNowMs = { nowMs }
        // THE PRODUCTION COLLECTOR does the stamping, delivery, and refusal. It registers the
        // stream-close retirement internally (the behavior under test); this test drives only tap
        // events and the recorder's public surface.
        val job = recorder.start(this)
        runCurrent()

        // Episode A plays; A1 is delivered and admitted. Anchors advance 100 ms per emit, inside the
        // tap's 200 ms reanchor tolerance, so the stream epoch NEVER changes before the flush -- any
        // held-chunk refusal below is proof of FLUSH retirement, not successor supersession.
        var anchorUs = 0L
        fun emit(value: Short) {
            tap.onSinkBuffer(presentationTimeUs = anchorUs)
            anchorUs += 100_000
            tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { value })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        }

        // Episode A plays. Amplitude 20_000 (~0.61 float) is the LOUD band the existing controls use;
        // the successor B below uses 5_000 (~0.15), so a surviving A tail and admitted B audio are
        // distinguishable by content, not just presence.
        emit(20_000)
        runCurrent()
        assertEquals("first emission recorded", 1_600, recorder.snapshot().size)
        assertTrue(
            "precondition: delivered A audio is present",
            recorder.snapshot().any { it > 0.2f },
        )

        // A2 is emitted but NOT drained: withholding dispatcher progress leaves the chunk in the tap's
        // buffer queue. It carries A's emission-time ticket; if retirement happens at the flush below,
        // delivery must refuse it. Distinct amplitude (5_000 ~ 0.15) so refusal is observable.
        emit(-5_000)
        assertEquals(
            "A2 has NOT been delivered yet -- it is held in flight across the coming flush",
            1_600,
            recorder.snapshot().size,
        )

        // THE STOP EVENT: the sink flushes and nothing plays afterwards. Retirement must have
        // happened HERE or it never happens.
        tap.onSinkFlush()

        // The tail deadline passes with elapsed time alone -- no writes, no successor.
        nowMs = 60_000L

        // Drain the held chunk NOW: its ticket died at the flush, so it must not land.
        runCurrent()

        val expired = recorder.snapshot()
        val heldA2 = -5_000 / 32768f
        val aTail = 20_000 / 32768f
        assertTrue(
            "held pre-flush A chunk must be REFUSED at delivery (retired ticket): after expiry the " +
                "buffer may hold no A2 amplitude at all",
            expired.none { kotlin.math.abs(it - heldA2) < 0.01f },
        )
        assertTrue(
            "A's delivered tail must expire from snapshot on elapsed time alone",
            expired.none { kotlin.math.abs(it - aTail) < 0.01f },
        )
        val window = recorder.window()
        assertTrue(
            "the post-expiry window must not resurrect the retired episode tail",
            window.samples.none { kotlin.math.abs(it - heldA2) < 0.01f },
        )

        // (3) Admission CLOSED, not merely aged: a late callback naming a retired admission cannot
        // re-anchor the correlator. The collector holds A's admission privately, so exercise the
        // closed-admission contract directly: an explicitly retired admission is refused.
        val staleA = recorder.openAdmission(7_777L)
        recorder.retireProducer(staleA)
        recorder.onRenderObservation(
            frameOnTrack = 1_600,
            timeMs = 90_000L,
            trackRateHz = 16_000,
            trackGeneration = 7_777L,
            admission = staleA,
        )
        assertNull(
            "a retired admission must not accept a late observation",
            recorder.renderedReference(),
        )
        job.cancel()
    }

    // REGISTRATION OWNERSHIP (@reviewer): a start() over a still-installed callback -- repeated start
    // without stop -- must replace, not stack: after the second start, a flush retires the NEW owner's
    // admission only. The stale closure firing would retire against collector state the old run no
    // longer controls, and a same-callback double registration would fire twice per event.
    @Test
    fun `a repeated start replaces the stream-close subscription - the stale owner's closure does not fire`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        var nowMs = 0L
        recorder.monotonicNowMs = { nowMs }

        var anchorUs = 0L
        fun emit(value: Short) {
            tap.onSinkBuffer(presentationTimeUs = anchorUs)
            anchorUs += 100_000
            tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { value })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        }

        // Owner A: admitted at its first emission, subscription installed. A writes MORE than the
        // 8_000-sample delay tail (6 x 1_600), so a retirement of A is OBSERVABLE as count != 9_600 --
        // a chunk smaller than the tail would survive retirement entirely and hide the stale firing.
        val jobA = recorder.start(this)
        runCurrent()
        repeat(6) { emit(20_000) }
        runCurrent()
        val aSnapshot = recorder.snapshot()
        assertEquals("owner A's audio is admitted", 9_600, aSnapshot.size)

        // REPEATED START WITHOUT STOP: same recorder, new collector. The old closure is stale.
        val jobB = recorder.start(this)
        runCurrent()

        // A flush now must retire owner B's held admission (none yet -- B has not emitted) and the
        // STALE A closure must not run: A's admission and audio were minted by the first collector,
        // and the test-visible proof is that A's tail keeps its original retirement deadline --
        // a stale firing would have retired it at the flush instant and, crucially, a SECOND stale
        // firing would have restarted the deadline (idempotency violation).
        val flushInstantMs = 10_000L
        nowMs = flushInstantMs
        tap.onSinkFlush()

        // Episode B plays after the flush under a fresh epoch stamp (the flush bumped the epoch).
        emit(5_000)
        runCurrent()
        val afterB = recorder.snapshot()
        assertTrue(
            "owner B's audio must be admitted after the flush",
            afterB.any { kotlin.math.abs(it - 5_000 / 32768f) < 0.01f },
        )
        // THE STALE CLOSURE DID NOT FIRE: with the defect (start() stacking registrations), A's old
        // closure runs at this flush and retires A at nowMs=10s -- zeroing A's audio beyond its
        // 500-sample tail immediately. With replacement ownership, A's admission was never touched
        // at the flush: its audio remains fully present (retirement of A is wholesale stop()'s job,
        // and the old collector is uninstalled -- only the stale closure could have retired it here).
        assertEquals(
            "the stale owner's closure must not run at the flush: A's audio is untouched beyond its " +
                "delay tail -- a stale firing would trim A to the 8_000-sample tail",
            9_600,
            afterB.count { kotlin.math.abs(it - 20_000 / 32768f) < 0.01f },
        )
        jobA.cancel()
        jobB.cancel()
    }

    // MEDIA3 PAUSE TRACE, PINNED AS BEHAVIOR (media3 1.10.1 DecoderAudioRenderer: onStopped calls
    // audioSink.pause() ONLY -- flush() fires in onPositionReset (seek) and reset() in onDisabled
    // (track change/stop/release)). So a pause delivers NO event, and correctly so: resume continues
    // the SAME stream with no onPositionReset, so admission must stay live across a pause. The stop
    // boundary the user eventually exercises (seek/stop) reaches onSinkFlush and retires there.
    @Test
    fun `a pause emits no event and keeps admission live - the later stop flush retires`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        var nowMs = 0L
        recorder.monotonicNowMs = { nowMs }
        val job = recorder.start(this)
        runCurrent()

        var anchorUs = 0L
        fun emit(value: Short) {
            tap.onSinkBuffer(presentationTimeUs = anchorUs)
            anchorUs += 100_000
            tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { value })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        }

        // Playing, then PAUSED: no event fires, the collector keeps its admission.
        emit(20_000)
        runCurrent()
        val ticketBeforePause = recorder.ownerTagIdsForTest().last()

        // RESUME after pause: same epoch (no flush happened), continued emission must carry the SAME
        // ticket -- a spurious pause retirement would stamp the resume under a fresh one.
        emit(20_000)
        runCurrent()
        val tagsAfterResume = recorder.ownerTagIdsForTest()
        assertTrue("resume after pause is the same stream", tagsAfterResume.all { it == ticketBeforePause })

        // THE STOP: seek/stop reaches the sink flush and retires with no successor emission.
        tap.onSinkFlush()
        nowMs = 60_000L
        val expired = recorder.snapshot()
        assertTrue(
            "the stop flush after a paused episode expires its audio on elapsed time alone",
            expired.none { kotlin.math.abs(it - 20_000 / 32768f) < 0.01f },
        )
        job.cancel()
    }
}
