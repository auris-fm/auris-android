package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentSink
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val LIVE = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(collectionPermitted = true, fullRevocation = false)
private val GATE_OFF = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(collectionPermitted = false, fullRevocation = false)
private val ISSUED = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "disclosure-1")

/**
 * Admission wiring controls (reviewer 2164aee3): the prepaid allowance authority sits on the
 * speech-start admission path, fail-closed, one token per admitted capture, taken BEFORE the
 * observer monitor and the sink reservation. These controls drive the REAL observer plus the
 * REAL retention sink; the positive companion uses a REAL provisioned owner, not a synthetic.
 */
class CaptureAllowanceAdmissionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")

    private fun area(name: String = "collection-usage"): File = File(tmp.root, name).also { it.mkdirs() }

    private fun limits(maxClipBytes: Long = 100_000L - 1024) = AllowanceLimits(10, 1_000_000, maxClipBytes = maxClipBytes, maxMetadataBytes = 1024, ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192))

    /** Real provisioned owner with one prepaid token minted off-path. */
    private fun provisionedOwner(dir: File, maxClipBytes: Long = 100_000L - 1024): CaptureAllowanceOwner = CaptureAllowanceOwner(dir, account, limits(maxClipBytes), window, AllowanceProvisioning.TestOnly)

    /** Delegating sink recording call order around the real retention sink. */
    private class RecordingSink(private val inner: ContributionRetentionSink, private val events: MutableList<String>) : SegmentSink by inner {
        override fun admit(observation: SegmentObservation.Admission): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ReservationToken? {
            events += "admit"
            return inner.admit(observation)
        }
    }

    private fun observer(
        sink: SegmentSink,
        authority: CaptureAllowanceAuthority,
        gateDecision: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision = LIVE,
    ) = SegmentObserver(
        gate = CollectionGate { it.shouldRetainNewWork },
        sink = sink,
        authority = { if (gateDecision.collectionPermitted) LIVE else GATE_OFF },
        issueAuthority = { ISSUED },
        allowance = authority,
    )

    @Test fun `unavailable authority refuses admission before any sink reservation or allocation`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, UnavailableCaptureAllowance)
        val admission = observer.speechStartedAdmitting(1L, "Headset", 8)
        assertNull("fail-closed: no allowance authority, no admission", admission)
        assertEquals("no sink reservation for a refused capture", 0L, sink.retainedBytes())
        assertEquals("nothing retained", 0, sink.retainedCount())
    }

    @Test fun `a provisioned prepaid owner admits exactly one real capture`() {
        val owner = provisionedOwner(area())
        assertTrue("preparation must mint the single token off-path", owner.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, owner)
        val admission = observer.speechStartedAdmitting(1L, "Headset", 8)
        assertTrue("positive companion: a provisioned owner admits", admission != null)
        // Engine boundary on success: allocate the array, drain, adopt into the sink.
        val firstChunk = ShortArray(8)
        assertTrue(observer.adoptFirstChunk(admission!!, firstChunk, 8))
        assertTrue("the open prefix lives in the real sink", sink.hasOpenPrefix(admission.context))
        assertTrue("the retained snapshot is empty only because the capture is still open", observer.isOpen(admission.context.captureId))
        assertFalse("the single token was consumed by this capture", owner.hasUsableToken())
    }

    @Test fun `gate-off consumes no token`() {
        val owner = provisionedOwner(area())
        assertTrue(owner.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, owner, gateDecision = GATE_OFF)
        assertNull("gate off refuses", observer.speechStartedAdmitting(1L, "Headset", 8))
        assertTrue("gate-off must NOT consume the prepaid token", owner.hasUsableToken())
    }

    @Test fun `sink refusal burns the one taken token with no refund`() {
        val owner = provisionedOwner(area())
        assertTrue(owner.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, owner)
        // producerSize*2 bytes exceed THIS sink's explicit maxBytesPerClip: sink.admit refuses.
        val smallSink = ContributionRetentionSink(CollectionBudget(1L shl 20), maxBytesPerClip = 100_000)
        val smallObserver = observer(smallSink, owner)
        assertNull("oversized capture is refused by the sink", smallObserver.speechStartedAdmitting(1L, "Headset", 60_000))
        assertFalse("the taken token is BURNED, not returned", owner.hasUsableToken())
        val second = provisionedOwner(area("second"))
        assertTrue(second.prepareToken())
        assertTrue("a fresh token admits again (burn was capture-local)", observer(ContributionRetentionSink(CollectionBudget(1L shl 20)), second).speechStartedAdmitting(2L, "Headset", 8) != null)
    }

    /**
     * MANUAL retirement only. This does NOT prove automatic account/policy lifecycle ownership:
     * switching the authority through the real composition on an account change is an OPEN
     * activation prerequisite (reviewer 174083e0 #3); until that composition exists, no claim is
     * made that a stale supplied owner cannot authorize a successor account by enforcement.
     */
    @Test fun `manual retirement refuses - lifecycle composition stays an open activation prerequisite`() {
        val dir = area()
        val ownerA = CaptureAllowanceOwner(dir, "a@example.com", limits(), window, AllowanceProvisioning.TestOnly)
        assertTrue(ownerA.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, ownerA)
        // MANUAL retirement of the old owner (an authority-loss stand-in). The successor owner is a
        // separately prepared owner for the successor account.
        val ownerB = CaptureAllowanceOwner(dir, "b@example.com", limits(), window, AllowanceProvisioning.TestOnly)
        assertTrue(ownerB.prepareToken())
        ownerA.retire()
        assertNull("stale retired owner must not admit", observer.speechStartedAdmitting(1L, "Headset", 8))
        val freshObserver = observer(sink, ownerB)
        assertTrue("successor account's owner admits for its own captures", freshObserver.speechStartedAdmitting(2L, "Headset", 8) != null)
    }

    @Test fun `held decision lock refuses admission within a bounded window`() {
        val owner = provisionedOwner(area())
        assertTrue(owner.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, owner)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        owner.onDecisionLockHeldForTest = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val holder = Thread {
            runCatching { owner.hasUsableToken() }
        }
        holder.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val decision = java.util.concurrent.CompletableFuture.supplyAsync { observer.speechStartedAdmitting(1L, "Headset", 8) }
        var result: Any? = "unset"
        try {
            result = decision.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            holder.join(5000)
        }
        assertNull("admission REFUSES rather than queuing behind the held decision lock", result)
    }

    /**
     * NARROW claim (reviewer 174083e0 #2): this proves take BEFORE sink.admit only. It does not
     * instrument the observer monitor boundary; take-before-monitor is guaranteed structurally
     * (the take happens in the gate block, the monitor only inside the Locked method).
     */
    @Test fun `prepaid clip bound binds tighter than the sink cap - mismatched-limits control`() {
        // Owner charged for a 4KB clip; the sink would happily allow 8MB. The capture must follow
        // the PREPAID bound, not the sink's: a 16KB initial allocation is refused and burns.
        val tiny = CaptureAllowanceOwner(
            area("tiny"),
            account,
            AllowanceLimits(10, 1_000_000, maxClipBytes = 4_000, maxMetadataBytes = 1_024, ceilings = Ceilings(10, 1_000_000, 4_000, 8_192)),
            window,
            AllowanceProvisioning.TestOnly,
        )
        assertTrue(tiny.prepareToken())
        val bigSink = ContributionRetentionSink(CollectionBudget(64L shl 20))
        val observer = observer(bigSink, tiny)
        assertNull("16KB initial must be refused by the prepaid bound, not the sink", observer.speechStartedAdmitting(1L, "Headset", 8_000))
        assertFalse("the oversize attempt burns the token", tiny.hasUsableToken())
        assertEquals("nothing was allocated", 0L, bigSink.retainedBytes())
    }

    @Test fun `growth beyond the prepaid clip bound is dropped even when the sink would allow it`() {
        val tiny = CaptureAllowanceOwner(
            area("tiny-growth"),
            account,
            AllowanceLimits(10, 1_000_000, maxClipBytes = 4_000, maxMetadataBytes = 1_024, ceilings = Ceilings(10, 1_000_000, 4_000, 8_192)),
            window,
            AllowanceProvisioning.TestOnly,
        )
        assertTrue(tiny.prepareToken())
        val bigSink = ContributionRetentionSink(CollectionBudget(64L shl 20))
        val observer = observer(bigSink, tiny)
        val admission = observer.speechStartedAdmitting(1L, "Headset", 1_000) // 2000 bytes <= 4000
        assertTrue(admission != null)
        val firstChunk = ShortArray(1_000)
        assertTrue(observer.adoptFirstChunk(admission!!, firstChunk, 1_000))
        // THE byte authority counts the initial reservation (2000) and every reservation;
        // the encoded-WAV bound includes the 44-byte header, so headroom is 4000-2000-44.
        // A 2000-sample delta (4000 bytes) would land at 6044 > 4000: refused uncharged.
        observer.speechIncremental(admission.context, ShortArray(2_000))
        assertEquals("growth beyond the prepaid bound is dropped uncharged", 2_000L, observer.chargedBytesForTest())
        // A delta that exactly MEETS the remaining bound (2000+1956+44 == 4000) is retained.
        observer.speechIncremental(admission.context, ShortArray(978))
        assertEquals("a boundary-meeting delta is retained", 3_956L, observer.chargedBytesForTest())
        // And the next 500-sample delta (1000 bytes) would exceed again: dropped.
        observer.speechIncremental(admission.context, ShortArray(500))
        assertEquals("further growth stays capped", 3_956L, observer.chargedBytesForTest())
    }

    @Test fun `capacity accounting - partial copies, oversized arrays, parallel and stale reservations`() {
        // TIGHT clip bound (4000) so an oversized commit capacity is genuinely over-bound:
        // initial admission 1000*2+44=2044 fits; 3500-500+2000+44=5044 does not.
        val owner = provisionedOwner(area(), maxClipBytes = 4_000)
        assertTrue(owner.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, owner)
        val admission = observer.speechStartedAdmitting(1L, "Headset", 1_000) // charged 2000
        assertTrue(admission != null)
        val ctx = admission!!.context
        val firstChunk = ShortArray(1_000)
        assertTrue(observer.adoptFirstChunk(admission, firstChunk, 1_000))

        // PARTIAL COPY: the sink retains the whole 500-sample array capacity, not the 200 copied.
        val t1 = observer.reserveDelta(ctx, 500)!! // charged 2000+1000=3000
        assertTrue(observer.commitDelta(ctx, ShortArray(500), 200, t1)) // capacity==reserved: stays 3000
        assertEquals("partial copy keeps the full array capacity charged", 3_000L, observer.chargedBytesForTest())

        // ARRAY LARGER THAN ITS RESERVATION, but the capacity would exceed the bound: REFUSED
        // before adoption (3500 - 500 + 2000 + 44 = 5044 > 4000), nothing retained, nothing charged.
        // Bound probe: 3000 + 3600 + 44 must not fit a 4000-byte bound.
        val probe = observer.reserveDelta(ctx, 1_800)
        assertNull("bound probe: charged=${observer.chargedBytesForTest()}", probe)
        val t2 = observer.reserveDelta(ctx, 250)!! // charged 3000+500=3500
        assertFalse(
            "capacity growth beyond the prepaid bound must refuse adoption (charged=${observer.chargedBytesForTest()})",
            observer.commitDelta(ctx, ShortArray(1_000), 400, t2),
        )
        assertEquals("the refused attempt charges nothing", 3_500L, observer.chargedBytesForTest())
        observer.releaseDelta(ctx, t2) // producer drop: the reservation returns
        assertEquals("the refused attempt's reservation leaves on drop", 3_000L, observer.chargedBytesForTest())

        // A capacity growth that FITS the bound is adopted at capacity.
        val t3 = observer.reserveDelta(ctx, 100)!! // charged 3000+200=3200
        assertTrue(observer.commitDelta(ctx, ShortArray(300), 300, t3)) // 3200-200+600+44=3644 <= 4000
        assertEquals("in-bound capacity growth is adopted at full capacity", 3_600L, observer.chargedBytesForTest())

        // PARALLEL outstanding reservations: two reserves, release settles only its own.
        val p1 = observer.reserveDelta(ctx, 50)!! // 3700
        val p2 = observer.reserveDelta(ctx, 50)!! // 3800
        assertEquals(3_800L, observer.chargedBytesForTest())
        observer.releaseDelta(ctx, p1)
        assertEquals("releasing one parallel reservation settles only that one", 3_700L, observer.chargedBytesForTest())
        assertTrue(observer.commitDelta(ctx, ShortArray(50), 50, p2)) // capacity==reserved: 3700
        assertEquals(3_700L, observer.chargedBytesForTest())
    }

    @Test fun `a stale release after a successor capture cannot corrupt the successor's accounting`() {
        val owner = provisionedOwner(area())
        assertTrue(owner.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, owner)
        val a = observer.speechStartedAdmitting(1L, "Headset", 100)!! // charged 200
        assertTrue(observer.adoptFirstChunk(a, ShortArray(100), 100)) // open prefix: deltas reserve against it
        val staleToken = observer.reserveDelta(a.context, 50)!! // charged 300
        observer.cancelled(a.context, "TooShort")
        // Successor capture (new prepaid token needed: owner has none after A — prepare again).
        assertTrue(owner.prepareToken())
        val b = observer.speechStartedAdmitting(2L, "Speaker", 100)!! // fresh authority: charged 200
        assertEquals("the successor starts from ITS OWN reservation", 200L, observer.chargedBytesForTest())
        observer.releaseDelta(a.context, staleToken) // STALE: belongs to the cancelled capture
        assertEquals("a stale release must not touch the successor's authority", 200L, observer.chargedBytesForTest())
    }

    @Test fun `token is taken before sink admit - direct ordering proof (monitor boundary not instrumented)`() {
        val events = mutableListOf<String>()
        val recordingAuthority = CaptureAllowanceAuthority {
            events.add("take")
            PrepaidTestAllowance().takePrepaidToken()
        }
        val sink = RecordingSink(ContributionRetentionSink(CollectionBudget(1L shl 20)), events)
        val observer = observer(sink, recordingAuthority)
        val admission = observer.speechStartedAdmitting(1L, "Headset", 8)
        assertTrue(admission != null)
        assertEquals(
            "the take must precede the sink admit — not the reverse, not interleaved",
            listOf("take", "admit"),
            events,
        )
    }
}
