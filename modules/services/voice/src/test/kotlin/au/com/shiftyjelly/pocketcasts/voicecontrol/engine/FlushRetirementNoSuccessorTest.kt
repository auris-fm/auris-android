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
}
