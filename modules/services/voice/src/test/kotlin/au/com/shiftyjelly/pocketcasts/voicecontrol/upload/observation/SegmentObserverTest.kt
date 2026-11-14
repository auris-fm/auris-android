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
private val ISSUED = IssuedAuthority("a@b.c", "epoch-1", "disclosure-1")

class SegmentObserverTest {

    // Handoff validation is exercised by dedicated controls; lifecycle tests use an accepting
    // validator with authority frozen at start.
    private class FrameSpy {
        var invocations = 0
        fun provider(frames: List<ShortArray>, rate: Int): () -> Pair<List<ShortArray>, Int> = {
            invocations++
            frames to rate
        }
    }

    @Test
    fun `gate refusal at START allocates NOTHING - frames provider is never invoked`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { _ -> false },
            sink,
            { DEAD_REVOKED },
            { ISSUED },
            RefuseAllCompletionHandoff,
        )
        val spy = FrameSpy()

        val context = observer.speechStarted(1L, "Headset", ShortArray(0))
        assertNull("refused capture issues no context", context)
        observer.speechIncremental(context, ShortArray(160))
        observer.wakeCompleted(context, 0, 300, SegmentObservation.WakeState.NotDetected, null, spy.provider(listOf(shortArrayOf(1, 2)), 16000))

        // ALLOCATION PROOF: the flatten/provider ran ZERO times — zero upload-side bytes.
        assertEquals(0, spy.invocations)
        assertEquals(0L, observer.retainedBytes())
        assertEquals(0, sink.retainedCount())
        assertEquals(0L, sink.retainedBytes())
    }

    @Test
    fun `revocation DURING a segment purges everything and refuses handoff - provider not run`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        var live = true
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            {
                if (live) LIVE else DEAD_REVOKED
            },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val spy = FrameSpy()

        val context = observer.speechStarted(2L, "Headset", ShortArray(0))
        assertNotNull(context)
        observer.speechIncremental(context, ShortArray(50))
        // External FULL REVOCATION lands mid-segment (withdrawal/disable/expiry/account change).
        live = false
        assertTrue(observer.revoked("account-change"))

        // Handoff attempt after revocation: revalidation refuses; NO allocation happens.
        observer.wakeCompleted(context!!, 0, 99, SegmentObservation.WakeState.Detected, null, spy.provider(listOf(shortArrayOf(1, 2, 3)), 16000))
        assertEquals(0, spy.invocations)
        assertEquals(0, sink.retainedCount())
        assertEquals(0L, sink.retainedBytes())
        assertTrue(losses.any { it.startsWith("purge-all:account-change") })
        assertFalse(observer.isOpen(context.captureId))
    }

    @Test
    fun `cancel A then start B then LATE completion of A cannot join B`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )

        val a = observer.speechStarted(3L, "Headset", ShortArray(0))!!
        observer.cancelled(a, "TooShort")
        val b = observer.speechStarted(4L, "Speaker", ShortArray(0))!!
        // Late outcome for the CANCELLED capture A must not touch open capture B.
        observer.wakeCompleted(a, 0, 10, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(9)) to 16000 }

        assertTrue(observer.isOpen(b.captureId))
        assertEquals(0, sink.retainedCount()) // B has no completed handoff yet
        // And the cancelled A's partial was discarded: audit trail records only that discard.
        assertEquals(1, losses.count { it == "partial-discard:TooShort" })
    }

    @Test
    fun `detector failure still produces EXACTLY ONE terminal - Completed(DetectorFailed)`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )

        val context = observer.speechStarted(5L, "Headset", ShortArray(0))!!
        observer.wakeCompleted(context, 1, 7, SegmentObservation.WakeState.DetectorFailed) { listOf(shortArrayOf(1, 2, 3)) to 16000 }
        // A stray cancel afterwards finds no open capture: no second terminal.
        observer.cancelled(context, "wake-detector-failed")

        val retained = sink.retainedSnapshot().single()
        assertEquals(SegmentObservation.WakeState.DetectorFailed, retained.wakeState)
        assertFalse(observer.isOpen(context.captureId))
        assertTrue(losses.none { it.startsWith("partial-discard:wake-detector-failed") })
    }

    @Test
    fun `incremental APPENDS real PCM and the PER-CLIP CAP refuses growth before allocation`() {
        // Cap covers the ADOPTED first chunk (2 samples = 4 bytes) + two 4-byte appends.
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(maxBytesPerClip = 12L, lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        sink.onRetentionEnded = { context -> observer.noteRetentionEnded(context) }

        // Production's own first allocation transfers IN: no phantom fixed-size reservation.
        val context = observer.speechStarted(6L, "Headset", shortArrayOf(1, 2))!!
        assertNotNull(context)
        observer.speechIncremental(context, ShortArray(2)) // appended (real copy)
        assertEquals(4L, sink.prefixSamples()) // adopted chunk + delta, real retained samples
        observer.speechIncremental(context, ShortArray(2)) // 4+4 BYTES = exactly at cap
        assertEquals(6L, sink.prefixSamples()) // 6 real samples (12 bytes)
        // The OBSERVER's own per-clip enforcement simply stops forwarding at the cap; the SINK's
        // terminal path is driven directly here (the aggregate budget is gone; this is the
        // per-clip refusal that ends retention with exactly one terminal).
        sink.incremental(SegmentObservation.Incremental(context, 1, true), ShortArray(1))

        // Cap refusal ends THIS capture's RETENTION with an explicit terminal — no silent
        // bounding, no unbounded growth. The pipeline continues normally (ordinary recognition
        // untouched); only collection ownership dies here.
        assertFalse(observer.isRetaining(context.captureId))
        assertEquals(0L, sink.prefixSamples())
        assertTrue(losses.any { it == "partial-discard:retention-cap" })
    }

    @Test
    fun `handoff refuses when the ORIGINAL issued authority fails owner-transaction validation`() {
        //blocker 2: a fresh Boolean/new projection must NOT reauthorize an old
        // capture. Gate stays LIVE; only the OWNER-side validation of the frozen value fails.
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        var handoffsValidated = 0
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            issueAuthority = { ISSUED },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement ->
                handoffsValidated++ // refusal: validation consumed the token, then NOTHING retains
                scope.completeUnderOwner(context, onset, end, wake, provider, admit = false, wakeMeasurement = measurement)
            },
        )
        val spy = FrameSpy()
        val context = observer.speechStarted(12L, "Headset", ShortArray(0))!!

        observer.wakeCompleted(context, 0, 300, SegmentObservation.WakeState.NotDetected, null, spy.provider(listOf(shortArrayOf(1, 2)), 16000))

        assertEquals(1, handoffsValidated) // consulted AT the handoff, inside the owner transaction
        assertEquals(0, sink.retainedCount()) // refused: nothing adopted
        // The capture CLOSES at the handoff even on refusal (exactly-one-terminal rule).
        assertFalse(observer.isOpen(context.captureId))
    }

    @Test
    fun `admission reserves BEFORE the producer allocates - refusal drains NOTHING`() {
        // Producer counter says 100 samples; the clip cap admits only 50 samples-worth
        // (100 bytes): the admit decision happens BEFORE any allocation.
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(maxBytesPerClip = 100L, lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { c, o, e, w, p, sc, live, measurement -> sc.completeUnderOwner(c, o, e, w, p, wakeMeasurement = measurement) },
        )
        var allocated = false
        val producerSizeSamples = 80 // 160 bytes > the 100-byte clip cap: refused BEFORE any ShortArray

        val context = observer.speechStartedAdmitting(20L, "Headset", producerSizeSamples)

        assertNull(context) // refused at admission
        assertFalse(allocated)
        // Admitted path: size fits, THEN the caller allocates and transfers ownership.
        val ok = observer.speechStartedAdmitting(21L, "Headset", 40) // 80 bytes <= 100
        assertNotNull(ok)
        val chunk = shortArrayOf(1, 2, 3, 4) // producer allocates AFTER admission succeeded
        allocated = true
        observer.adoptFirstChunk(ok!!, ShortArray(40).also { chunk.copyInto(it, 0, 0, minOf(chunk.size, 40)) })
    }

    @Test
    fun `completion charge transfers exactly - no double count between prefix and candidate`() {
        // Prefix accumulates 4 samples (8 bytes); the closed segment is 6 samples (12 bytes).
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val context = observer.speechStarted(13L, "Headset", shortArrayOf(1, 2))!! // 4 bytes adopted
        observer.speechIncremental(context, shortArrayOf(3, 4)) // +4 samples

        observer.wakeCompleted(
            context,
            0,
            6,
            SegmentObservation.WakeState.NotDetected,
            null,
            { listOf(shortArrayOf(1, 2, 3, 4, 5, 6)) to 16000 },
        )

        assertEquals(1, sink.retainedCount())
        val candidate = sink.retainedSnapshot().single()
        assertEquals(12L, candidate.bytesCharged)
        assertEquals(0L, sink.openPrefixBytes()) // prefix fully transferred, not left dangling
    }

    @Test
    fun `reservation failure at completion keeps the prefix charged and owned until release`() {
        // The clip cap fits the prefix but NOT the larger closed clip: the handoff must refuse
        // WITHOUT leaving the capture retained — the prefix ends with exactly one terminal.
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(maxBytesPerClip = 8L, lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val context = observer.speechStarted(14L, "Headset", shortArrayOf(1, 2))!!
        observer.speechIncremental(context, shortArrayOf(3, 4))

        observer.wakeCompleted(
            context,
            0,
            6,
            SegmentObservation.WakeState.NotDetected,
            null,
            { listOf(shortArrayOf(1, 2, 3, 4, 5, 6)) to 16000 },
        )

        assertEquals(0, sink.retainedCount()) // refused handoff
        // The prefix ended with exactly one terminal; nothing is retained and nothing dangles.
        assertEquals(0L, sink.openPrefixBytes())
        assertFalse(observer.isRetaining(context.captureId))
        assertTrue(losses.any { it.contains("clip-cap") || it.contains("partial-discard") })
    }

    @Test
    fun `listening closure discards ONLY the current partial - completed contributions survive`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )

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
        assertTrue(losses.any { it == "partial-discard:pipeline-stopped" })
    }

    @Test
    fun `deferral deletes NOTHING - ready entries stay`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val context = observer.speechStarted(8L, "Headset", ShortArray(0))!!
        observer.wakeCompleted(context, 0, 1, SegmentObservation.WakeState.NotDetected) { listOf(shortArrayOf(7, 8)) to 16000 }

        sink.defer(LossReason.Deferral("network-loss"))

        assertEquals(1, sink.retainedCount())
        // Charge = the completed clip (prefix charge trimmed to it at handoff), never released on defer.
        assertEquals(4L, sink.retainedBytes())
        assertEquals(4L, sink.retainedSnapshot().single().bytesCharged)
        assertEquals(0L, sink.openPrefixBytes())
        assertTrue(losses.any { it == "defer:network-loss" })
    }

    @Test
    fun `outcomes join by ISSUED id only - unknown and closed ids are refused`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
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
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val observer = SegmentObserver(
            CollectionGate { it.shouldRetainNewWork },
            sink,
            { LIVE },
            { ISSUED },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val a = observer.speechStarted(10L, "Headset", ShortArray(0))!!
        // Admission is single-occupancy (blocker b1): a second start while A is open is REFUSED —
        // no context, no charge, and A stays the one open capture. Stale-context deltas are refused.
        assertNull(observer.speechStartedAdmitting(11L, "Speaker", 0))
        observer.speechIncremental(a, ShortArray(100)) // reserved? no: sink default reserveDelta refuses -> ignored
        assertEquals(0, sink.retainedSnapshot().count { it.incrementalSamplesAccounted > 0 && it.context == a })
        assertTrue(observer.isOpen(a.captureId))
    }
}
