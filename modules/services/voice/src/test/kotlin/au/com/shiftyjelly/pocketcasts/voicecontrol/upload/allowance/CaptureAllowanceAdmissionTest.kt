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

    private fun limits() = AllowanceLimits(10, 1_000_000, maxClipBytes = 100_000 - 1024, maxMetadataBytes = 1024, ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192))

    /** Real provisioned owner with one prepaid token minted off-path. */
    private fun provisionedOwner(dir: File): CaptureAllowanceOwner = CaptureAllowanceOwner(dir, account, limits(), window, AllowanceProvisioning.TestOnly)

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

    @Test fun `retired authority refuses - a stale owner cannot authorize after account change`() {
        val dir = area()
        val ownerA = CaptureAllowanceOwner(dir, "a@example.com", limits(), window, AllowanceProvisioning.TestOnly)
        assertTrue(ownerA.prepareToken())
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = observer(sink, ownerA)
        // Authority loss (account change): the composition retires the OLD owner and rebuilds the
        // authority for the successor; the stale owner must never admit for the successor.
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
        val result = decision.get(2, TimeUnit.SECONDS)
        release.countDown()
        holder.join(5000)
        assertNull("admission REFUSES rather than queuing behind the held decision lock", result)
    }

    @Test fun `token is taken before the sink reservation - direct ordering proof`() {
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
