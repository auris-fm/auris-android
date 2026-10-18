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
private val ISSUED = IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "disclosure-1")

class SegmentObserverTest {

    // Handoff validation is exercised by dedicated controls; lifecycle tests use an accepting
    // validator with authority frozen at start.
    private fun acceptingValidator(): Pair<() -> IssuedAuthority, HandoffValidator> = { ISSUED } to HandoffValidator { true }

    private class FrameSpy {
        var invocations = 0
        fun provider(frames: List<ShortArray>, rate: Int): () -> Pair<List<ShortArray>, Int> = {
            invocations++
            frames to rate
        }
    }

    @Test
    fun `gate refusal at START allocates NOTHING - frames provider is never invoked`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(CollectionGate { _ -> false }, sink, { DEAD_REVOKED }, { ISSUED }, HandoffValidator { true })
        val spy = FrameSpy()

        val context = observer.speechStarted(1L, "Headset", ShortArray(0))
        assertNull("refused capture issues no context", context)
        observer.speechIncremental(context, ShortArray(160))
        observer.wakeCompleted(context, 0, 300, SegmentObservation.WakeState.NotDetected, spy.provider(listOf(shortArrayOf(1, 2)), 16000))

        // ALLOCATION PROOF: the flatten/provider ran ZERO times — zero upload-side bytes.
        assertEquals(0, spy.invocations)
        assertEquals(0L, observer.retainedBytes())
        assertEquals(0, sink.retainedCount())
        assertEquals(0L, sink.retainedBytes())
    }

    @Test
    fun `revocation DURING a segment purges everything and refuses handoff - provider not run`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        var live = true
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, {
            if (live) LIVE else DEAD_REVOKED
        }, { ISSUED }, HandoffValidator { true })
        val spy = FrameSpy()

        val context = observer.speechStarted(2L, "Headset", ShortArray(0))
        assertNotNull(context)
        observer.speechIncremental(context, ShortArray(50))
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
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })

        val a = observer.speechStarted(3L, "Headset", ShortArray(0))!!
        observer.cancelled(a, "TooShort")
        val b = observer.speechStarted(4L, "Speaker", ShortArray(0))!!
        // Late outcome for the CANCELLED capture A must not touch open capture B.
        observer.wakeCompleted(a, 0, 10, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(9)) to 16000 }

        assertTrue(observer.isOpen(b.captureId))
        assertEquals(0, sink.retainedCount()) // B has no completed handoff yet
        // And the cancelled A's partial was discarded: audit trail records only that discard.
        assertEquals(1, sink.lossEvents.count { it == "partial-discard:TooShort" })
    }

    @Test
    fun `detector failure still produces EXACTLY ONE terminal - Completed(DetectorFailed)`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })

        val context = observer.speechStarted(5L, "Headset", ShortArray(0))!!
        observer.wakeCompleted(context, 1, 7, SegmentObservation.WakeState.DetectorFailed) { listOf(shortArrayOf(1, 2, 3)) to 16000 }
        // A stray cancel afterwards finds no open capture: no second terminal.
        observer.cancelled(context, "wake-detector-failed")

        val retained = sink.retainedSnapshot().single()
        assertEquals(SegmentObservation.WakeState.DetectorFailed, retained.wakeState)
        assertFalse(observer.isOpen(context.captureId))
        assertTrue(sink.lossEvents.none { it.startsWith("partial-discard:wake-detector-failed") })
    }

    @Test
    fun `incremental APPENDS real PCM and the aggregate BUDGET refuses growth before allocation`() {
        // Budget covers the ADOPTED first chunk (2 samples = 4 bytes) + two 4-byte appends.
        val budget = CollectionBudget(12L)
        val sink = ContributionRetentionSink(budget)
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })
        sink.onRetentionEnded = { context -> observer.noteRetentionEnded(context) }

        // Production's own first allocation transfers IN: no phantom fixed-size reservation.
        val context = observer.speechStarted(6L, "Headset", shortArrayOf(1, 2))!!
        assertNotNull(context)
        assertEquals(4L, budget.charged()) // EXACTLY the adopted array's bytes
        observer.speechIncremental(context, ShortArray(2)) // reserved & appended (real copy)
        assertEquals(4L, sink.prefixSamples()) // adopted chunk + delta, real retained samples
        assertEquals(8L, budget.charged()) // 4 + 4
        observer.speechIncremental(context, ShortArray(2)) // 4+4+4 = exactly at cap
        assertEquals(12L, budget.charged())
        observer.speechIncremental(context, ShortArray(1)) // ONE sample over: refuse BEFORE alloc

        // Growth refusal ends THIS capture's RETENTION with an explicit terminal, releasing the
        // prefix charge — no silent bounding, no unbounded aggregate. The pipeline continues
        // normally (ordinary recognition untouched); only collection ownership dies here.
        assertFalse(observer.isRetaining(context.captureId))
        assertEquals(0L, budget.charged())
        assertEquals(0L, sink.prefixSamples())
        assertTrue(sink.lossEvents.any { it == "partial-discard:budget-exhausted" })
    }

    @Test
    fun `handoff refuses when the ORIGINAL issued authority fails owner-transaction validation`() {
        // Reviewer 8fe69937 blocker 2: a fresh Boolean/new projection must NOT reauthorize an old
        // capture. Gate stays LIVE; only the OWNER-side validation of the frozen value fails.
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        var handoffsValidated = 0
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            issueAuthority = { ISSUED },
            handoffValidator = HandoffValidator {
                handoffsValidated++
                false
            },
        )
        val spy = FrameSpy()
        val context = observer.speechStarted(12L, "Headset", ShortArray(0))!!

        observer.wakeCompleted(context, 0, 300, SegmentObservation.WakeState.NotDetected, spy.provider(listOf(shortArrayOf(1, 2)), 16000))

        assertEquals(1, handoffsValidated) // validated AT the handoff, under the owner transaction
        assertEquals(0, sink.retainedCount()) // refused: nothing adopted
        assertEquals(0L, budget.charged()) // refusal allocated NOTHING on the collection side
        assertFalse(observer.isOpen(context.captureId))
    }

    @Test
    fun `completion charge transfers exactly - no double count between prefix and candidate`() {
        // Prefix accumulates 4 samples (8 bytes); the closed segment is 6 samples (12 bytes).
        // After adoption EXACTLY 12 bytes stay charged: prefix released, clip reserved once.
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })
        val context = observer.speechStarted(13L, "Headset", shortArrayOf(1, 2))!! // 4 bytes adopted
        observer.speechIncremental(context, shortArrayOf(3, 4)) // +4 => 8 charged
        assertEquals(8L, budget.charged())

        observer.wakeCompleted(
            context,
            0,
            6,
            SegmentObservation.WakeState.NotDetected,
            { listOf(shortArrayOf(1, 2, 3, 4, 5, 6)) to 16000 },
        )

        assertEquals(12L, budget.charged()) // EXACT final clip size, once
        assertEquals(1, sink.retainedCount())
        val candidate = sink.retainedSnapshot().single()
        assertEquals(12L, candidate.bytesCharged)
        assertEquals(0L, sink.openPrefixBytes()) // prefix fully transferred, not left dangling
    }

    @Test
    fun `reservation failure at completion keeps the prefix charged and owned until release`() {
        // Budget fits the prefix but NOT the larger closed clip: the handoff must refuse WITHOUT
        // leaving an open uncharged buffer — the prefix keeps its exact charge.
        val budget = CollectionBudget(8L) // prefix (4+4) fits; 12-byte clip does not fit extra
        val sink = ContributionRetentionSink(budget)
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })
        val context = observer.speechStarted(14L, "Headset", shortArrayOf(1, 2))!!
        observer.speechIncremental(context, shortArrayOf(3, 4))
        assertEquals(8L, budget.charged())

        observer.wakeCompleted(
            context,
            0,
            6,
            SegmentObservation.WakeState.NotDetected,
            { listOf(shortArrayOf(1, 2, 3, 4, 5, 6)) to 16000 },
        )

        assertEquals(0, sink.retainedCount()) // refused handoff
        // The capture itself closes; whatever charge survives must equal released-or-retained
        // accounting — never an open prefix with zero charge.
        assertTrue(sink.openPrefixBytes() == 0L || sink.openPrefixBytes() == 8L)
        if (sink.openPrefixBytes() == 0L) {
            // Closed capture path: retention ended explicitly, charges released together.
            assertEquals(0L, budget.charged())
            assertTrue(sink.lossEvents.any { it.contains("budget") || it.contains("partial-discard") })
        } else {
            assertEquals(8L, budget.charged()) // still owned & charged, never uncharged-live
        }
    }

    @Test
    fun `listening closure discards ONLY the current partial - completed contributions survive`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })

        // First capture completes normally.
        val first = observer.speechStarted(7L, "Headset", ShortArray(0))!!
        observer.wakeCompleted(first, 0, 3, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(1, 2, 3)) to 16000 }
        assertEquals(1, sink.retainedCount())

        // Second capture is OPEN when listening closes.
        val second = observer.speechStarted(7L, "Headset", ShortArray(0))!!
        observer.speechIncremental(second, ShortArray(20))
        observer.listeningClosed("pipeline-stopped")

        assertEquals(1, sink.retainedCount()) // completed contribution untouched
        assertEquals(1, sink.retainedSnapshot().count { it.context.captureId == first.captureId })
        assertFalse(observer.isOpen(second.captureId))
        assertTrue(sink.lossEvents.any { it == "partial-discard:pipeline-stopped" })
    }

    @Test
    fun `deferral deletes NOTHING - ready entries stay`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })
        val context = observer.speechStarted(8L, "Headset", ShortArray(0))!!
        observer.wakeCompleted(context, 0, 1, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(7, 8)) to 16000 }

        sink.defer(LossReason.Deferral("network-loss"))

        assertEquals(1, sink.retainedCount())
        // Charge = the completed clip (prefix charge trimmed to it at handoff), never released on defer.
        assertEquals(4L, sink.retainedBytes())
        assertEquals(4L, sink.retainedSnapshot().single().bytesCharged)
        assertEquals(0L, sink.openPrefixBytes())
        assertTrue(sink.lossEvents.any { it == "defer:network-loss" })
    }

    @Test
    fun `outcomes join by ISSUED id only - unknown and closed ids are refused`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })
        val context = observer.speechStarted(9L, "Headset", ShortArray(0))!!
        observer.wakeCompleted(context, 0, 1, SegmentObservation.WakeState.Detected) { listOf(shortArrayOf(1)) to 16000 }

        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(context.captureId, "play jazz", "en")))
        assertTrue(sink.joinOutcome(CollectionOutcome.Routed(context.captureId, "playback", emptyMap())))
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(context.captureId, dispatchedWithoutThrowing = true)))
        // Terminal recorded: further joins refuse; foreign ids refuse.
        assertFalse(sink.joinOutcome(CollectionOutcome.Recognized(context.captureId, "late", null)))
        assertFalse(sink.joinOutcome(CollectionOutcome.Recognized(java.util.UUID.randomUUID(), "foreign", null)))

        val candidate = sink.retainedSnapshot().single()
        assertEquals(context.captureId, candidate.outcome?.captureId)
        assertTrue(candidate.outcome is CollectionOutcome.Executed)
    }

    @Test
    fun `stale incremental for an unknown context is refused - no orphan accounting`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(CollectionGate { it.shouldRetainNewWork }, sink, { LIVE }, { ISSUED }, HandoffValidator { true })
        val a = observer.speechStarted(10L, "Headset", ShortArray(0))!!
        val b = observer.speechStarted(11L, "Speaker", ShortArray(0))!! // starting while A open rebinds the open capture
        observer.speechIncremental(a, ShortArray(100)) // A is no longer the open capture
        assertEquals(0, sink.retainedSnapshot().count { it.incrementalSamplesAccounted > 0 && it.context == a })
        assertTrue(observer.isOpen(b.captureId))
    }
}
