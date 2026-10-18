package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val LIVE = AuthorityDecision(collectionPermitted = true, fullRevocation = false)
private val DEAD_REVOKED = AuthorityDecision(collectionPermitted = false, fullRevocation = true)
private val DEAD_DISABLED = AuthorityDecision(collectionPermitted = false, fullRevocation = false)

class SegmentObserverTest {

    private class FrameSpy {
        var invocations = 0
        fun provider(frames: List<ShortArray>, rate: Int): () -> Pair<List<ShortArray>, Int> = {
            invocations++
            frames to rate
        }
    }

    @Test
    fun `gate refusal at START allocates NOTHING - frames provider is never invoked`() {
        val sink = ContributionRetentionSink()
        val observer = SegmentObserver(CollectionGate { _ -> false }, sink) { DEAD_REVOKED }
        val spy = FrameSpy()

        val context = observer.speechStarted(1L, "Headset")
        assertNull("refused capture issues no context", context)
        observer.speechIncremental(context, 160)
        observer.wakeCompleted(context, 0, 300, SegmentObservation.WakeState.NotDetected, spy.provider(listOf(shortArrayOf(1, 2)), 16000))

        // ALLOCATION PROOF: the flatten/provider ran ZERO times — zero upload-side bytes.
        assertEquals(0, spy.invocations)
        assertEquals(0L, observer.retainedBytes())
        assertEquals(0, sink.retainedCount())
        assertEquals(0L, sink.retainedBytes())
    }

    @Test
    fun `revocation DURING a segment purges everything and refuses handoff - provider not run`() {
        val sink = ContributionRetentionSink()
        var live = true
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) {
            if (live) LIVE else DEAD_REVOKED
        }
        val spy = FrameSpy()

        val context = observer.speechStarted(2L, "Headset")
        assertNotNull(context)
        observer.speechIncremental(context, 50)
        // External FULL REVOCATION lands mid-segment (withdrawal/disable/expiry/account change).
        live = false
        assertTrue(observer.revoked("account-change"))

        // Handoff attempt after revocation: revalidation refuses; NO allocation happens.
        observer.wakeCompleted(context!!, 0, 99, SegmentObservation.WakeState.Detected, spy.provider(listOf(shortArrayOf(1, 2, 3)), 16000))
        assertEquals(0, spy.invocations)
        assertEquals(0, sink.retainedCount())
        assertEquals(0L, sink.retainedBytes())
        assertTrue(sink.lossEvents.any { it.startsWith("purge-all:account-change") })
        assertFalse(observer.isOpen(context.captureId))
    }

    @Test
    fun `cancel A then start B then LATE completion of A cannot join B`() {
        val sink = ContributionRetentionSink()
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) { LIVE }

        val a = observer.speechStarted(3L, "Headset")!!
        observer.cancelled(a, "TooShort")
        val b = observer.speechStarted(4L, "Speaker")!!
        // Late outcome for the CANCELLED capture A must not touch open capture B.
        observer.wakeCompleted(a, 0, 10, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(9)) to 16000 }

        assertTrue(observer.isOpen(b.captureId))
        assertEquals(0, sink.retainedCount()) // B has no completed handoff yet
        // And the cancelled A's partial was discarded: audit trail records only that discard.
        assertEquals(1, sink.lossEvents.count { it == "partial-discard:TooShort" })
    }

    @Test
    fun `detector failure still produces EXACTLY ONE terminal - Completed(DetectorFailed)`() {
        val sink = ContributionRetentionSink()
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) { LIVE }

        val context = observer.speechStarted(5L, "Headset")!!
        observer.wakeCompleted(context, 1, 7, SegmentObservation.WakeState.DetectorFailed) { listOf(shortArrayOf(1, 2, 3)) to 16000 }
        // A stray cancel afterwards finds no open capture: no second terminal.
        observer.cancelled(context, "wake-detector-failed")

        val retained = sink.retainedSnapshot().single()
        assertEquals(SegmentObservation.WakeState.DetectorFailed, retained.wakeState)
        assertFalse(observer.isOpen(context.captureId))
        assertTrue(sink.lossEvents.none { it.startsWith("partial-discard:wake-detector-failed") })
    }

    @Test
    fun `incremental uses REAL deltas under ownership and the BYTE CAP ends retention early`() {
        val sink = ContributionRetentionSink(maxBytesPerClip = 8L) // tiny cap for the control
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) { LIVE }
        observer.maxRetainedBytes = 16L // 8 samples

        val context = observer.speechStarted(6L, "Headset")!!
        observer.speechIncremental(context, 6) // 12 bytes ≤ cap → accounted
        assertEquals(12L, observer.retainedBytes())
        observer.speechIncremental(context, 5) // would exceed: 12 + 10 > 16 → cap cancels THIS capture

        // Retention ended by the cap, not silently bounded: a cancellation terminal exists.
        assertFalse(observer.isOpen(context.captureId))
        assertTrue(sink.lossEvents.any { it == "partial-discard:retention-cap" })
    }

    @Test
    fun `listening closure discards ONLY the current partial - completed contributions survive`() {
        val sink = ContributionRetentionSink()
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) { LIVE }

        // First capture completes normally.
        val first = observer.speechStarted(7L, "Headset")!!
        observer.wakeCompleted(first, 0, 3, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(1, 2, 3)) to 16000 }
        assertEquals(1, sink.retainedCount())

        // Second capture is OPEN when listening closes.
        val second = observer.speechStarted(7L, "Headset")!!
        observer.speechIncremental(second, 20)
        observer.listeningClosed("pipeline-stopped")

        assertEquals(1, sink.retainedCount()) // completed contribution untouched
        assertEquals(1, sink.retainedSnapshot().count { it.context.captureId == first.captureId })
        assertFalse(observer.isOpen(second.captureId))
        assertTrue(sink.lossEvents.any { it == "partial-discard:pipeline-stopped" })
    }

    @Test
    fun `deferral deletes NOTHING - ready entries stay`() {
        val sink = ContributionRetentionSink()
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) { LIVE }
        val context = observer.speechStarted(8L, "Headset")!!
        observer.wakeCompleted(context, 0, 1, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(7, 8)) to 16000 }

        sink.defer(LossReason.Deferral("network-loss"))

        assertEquals(1, sink.retainedCount())
        assertEquals(4L, sink.retainedBytes())
        assertTrue(sink.lossEvents.any { it == "defer:network-loss" })
    }

    @Test
    fun `outcomes join by ISSUED id only - unknown and closed ids are refused`() {
        val sink = ContributionRetentionSink()
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) { LIVE }
        val context = observer.speechStarted(9L, "Headset")!!
        observer.wakeCompleted(context, 0, 1, SegmentObservation.WakeState.Detected) { listOf(shortArrayOf(1)) to 16000 }

        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(context.captureId, "play jazz", "en")))
        assertTrue(sink.joinOutcome(CollectionOutcome.Routed(context.captureId, "playback", emptyMap())))
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(context.captureId, success = true)))
        // Terminal recorded: further joins refuse; foreign ids refuse.
        assertFalse(sink.joinOutcome(CollectionOutcome.Recognized(context.captureId, "late", null)))
        assertFalse(sink.joinOutcome(CollectionOutcome.Recognized(java.util.UUID.randomUUID(), "foreign", null)))

        val candidate = sink.retainedSnapshot().single()
        assertEquals(context.captureId, candidate.outcome?.captureId)
        assertTrue(candidate.outcome is CollectionOutcome.Executed)
    }

    @Test
    fun `stale incremental for an unknown context is refused - no orphan accounting`() {
        val sink = ContributionRetentionSink()
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink) { LIVE }
        val a = observer.speechStarted(10L, "Headset")!!
        val b = observer.speechStarted(11L, "Speaker")!! // starting while A open rebinds the open capture
        observer.speechIncremental(a, 100) // A is no longer the open capture
        assertEquals(0, sink.retainedSnapshot().count { it.incrementalSamplesAccounted > 0 && it.context == a })
        assertTrue(observer.isOpen(b.captureId))
    }
}
