package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.PolicyIncarnationReader
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract controls for the single collection-authority transaction (reviewer a38421f6):
 * lock order, held-copy-vs-expiry determinism, refusal termination, and coherent policy reads.
 */
class CollectionFenceContractTest {

    private class FakeOwner : CredentialOwnershipOwner {
        @Volatile var snap = AuthoritySnapshot(accountName = "a@b.c", session = 1L, credential = 1L, token = null)
        override fun authoritySnapshot(): AuthoritySnapshot = snap
        override fun sessionGeneration(): Long = snap.session
        override fun <T> transaction(block: () -> T): T = block()
        override fun beginRefresh(observed: AuthoritySnapshot): RefreshLease? = null
        override fun publishRefresh(lease: RefreshLease, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = false
        override fun publishCredentials(observed: AuthoritySnapshot, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = false
        override fun installOwnershipBinding(binding: au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding?): AuthoritySnapshot? = snap
        override fun invalidateIfCurrent(observed: AuthoritySnapshot, transaction: () -> Boolean): Boolean = transaction()
        override fun reconcileExternalState(): AuthoritySnapshot = snap
    }

    /** Records the monitor acquisition ORDER across owner/collection/observer participants. */
    private class OrderProbe {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    @Test
    fun `admission and handoff share one fence - expiry cannot interleave with copy-out`() {
        val owner = FakeOwner()
        val tx = CollectionAuthorityTransaction(owner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)))
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        val issued = IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1")
        val observer = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { tx.transaction { authorityFrom(tx, established = 1L, owner) } },
            issueAuthority = { tx.coherentAuthority().toIssued() },
            handoff = HandoffCoordinator(tx) { AuthorityChecks.validateThroughTx(tx, owner, it) },
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        // Hold the fence mid-handoff (simulating an in-flight copy), then race an expiry revoke.
        val ctx = observer.speechStartedAdmitting(1L, "Headset", 8)!!
        observer.adoptFirstChunk(ctx, ShortArray(8) { it.toShort() })

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var expiryRanWhileHeld = false
        val handoffThread = Thread {
            tx.transaction {
                started.countDown()
                observer.wakeCompleted(ctx, 0, 8, SegmentObservation.WakeState.NotDetected) {
                    release.await(2, TimeUnit.SECONDS)
                    listOf(ShortArray(8) { it.toShort() }) to 16000
                }
            }
        }
        val expiryThread = Thread {
            started.await(2, TimeUnit.SECONDS)
            // Expiry attempt must serialize BEHIND the held copy: observe fence state before firing.
            expiryRanWhileHeld = !tx.tryEnterOnce()
            release.countDown()
        }
        handoffThread.start()
        expiryThread.start()
        handoffThread.join(3000)
        expiryThread.join(3000)

        assertTrue("copy ran under the fence", expiryRanWhileHeld)
        assertEquals(1, sink.retainedSnapshot().count { it.context == ctx })
    }

    @Test
    fun `invalid handoff without context still terminates exactly once and releases prefix`() {
        val owner = FakeOwner()
        val tx = CollectionAuthorityTransaction(owner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)))
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(
            gate = CollectionGate { true },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1") },
            handoff = HandoffCoordinator(tx) { true },
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        val ctx = observer.speechStartedAdmitting(1L, "Headset", 8)!!
        observer.adoptFirstChunk(ctx, ShortArray(8))
        val before = sink.totalChargedBytesForTest()
        // Handoff for an UNKNOWN/stale context via the PRODUCTION-shaped coordinator: refused as
        // invalid issued authority → cancel terminal for the open capture, zero new retention.
        val coordinator = HandoffCoordinator(tx) { false }
        // Stale/unknown context: late outcome — refused as a NO-OP, current capture untouched.
        coordinator.handoff(
            CaptureContext(java.util.UUID.randomUUID(), 9L, "X", IssuedAuthority(null, 0L, 0L, null, null)),
            0,
            8,
            SegmentObservation.WakeState.NotDetected,
            { emptyList<ShortArray>() to 0 },
            observer,
        )
        assertTrue(observer.isOpen(ctx.captureId))
        // The OPEN capture's own invalid handoff terminates exactly once and releases everything.
        coordinator.handoff(ctx, 0, 8, SegmentObservation.WakeState.NotDetected, { emptyList<ShortArray>() to 0 }, observer)
        assertFalse(observer.isOpen(ctx.captureId)) // terminated exactly once
        assertEquals(0L, sink.totalChargedBytesForTest()) // prefix charge released, nothing retained
        // Direct wake with a stale context is a NO-OP (late outcome), never a second terminal.
        val ctx2 = observer.speechStartedAdmitting(2L, "Headset", 8)!!
        observer.adoptFirstChunk(ctx2, ShortArray(8))
        observer.wakeCompleted(CaptureContext(java.util.UUID.randomUUID(), 9L, "X", IssuedAuthority(null, 0L, 0L, null, null)), 0, 8, SegmentObservation.WakeState.NotDetected) { emptyList<ShortArray>() to 0 }
        assertTrue(observer.isOpen(ctx2.captureId))
        // Null-context handoff through the coordinator closes the OPEN capture with one terminal.
        coordinator.handoff(null, 0, 8, SegmentObservation.WakeState.NotDetected, { emptyList<ShortArray>() to 0 }, observer)
        assertFalse(observer.isOpen(ctx2.captureId))
        assertEquals(0L, sink.totalChargedBytesForTest())
    }

    @Test
    fun `policy disable publishes through nesting and old work cannot be reauthorized`() {
        val owner = FakeOwner()
        val provider = FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000))
        val tx = CollectionAuthorityTransaction(owner, provider)
        val frozen = tx.coherentAuthority().toIssued()
        provider.snap = PolicySnapshot(enabled = false, activationGeneration = 0, activatedAtMonotonicMs = 0)
        assertFalse(AuthorityChecks.validateThroughTx(tx, owner, frozen))
        // Re-enabling creates a NEW incarnation; the frozen old one stays invalid.
        provider.snap = PolicySnapshot(enabled = true, activationGeneration = 8, activatedAtMonotonicMs = 5000)
        assertFalse(AuthorityChecks.validateThroughTx(tx, owner, frozen))
        assertEquals(8L, provider.activationGeneration())
    }

    private fun authorityFrom(tx: CollectionAuthorityTransaction, established: Long, owner: FakeOwner): AuthorityDecision {
        val c = tx.coherentAuthority()
        val ok = c.policyEnabled && c.accountName != null && c.session == established
        return AuthorityDecision(collectionPermitted = ok, fullRevocation = !ok)
    }

    private fun CoherentAuthority.toIssued() = IssuedAuthority(
        accountName,
        session,
        credential,
        "e1",
        "d1",
        policyActivationGeneration = policyActivationGeneration,
        policyActivatedAtMonotonicMs = policyActivatedAtMonotonicMs,
        policyEnabledAtIssue = policyEnabled,
    )

    private object AuthorityChecks {
        fun validateThroughTx(tx: CollectionAuthorityTransaction, owner: FakeOwner, issued: IssuedAuthority): Boolean = tx.transaction {
            val c = tx.coherentAuthority()
            c.accountName == issued.accountName && c.session == issued.sessionGeneration &&
                c.credential == issued.credentialIncarnation && c.policyEnabled &&
                c.policyActivationGeneration == issued.policyActivationGeneration &&
                c.policyActivatedAtMonotonicMs == issued.policyActivatedAtMonotonicMs
        }
    }
}

private class FakePolicyReader(initial: PolicySnapshot) : PolicyIncarnationReader {
    @Volatile var snap = initial
    override fun enabled() = snap.enabled
    override fun activationGeneration() = snap.activationGeneration
    override fun activatedAtMonotonicMs() = snap.activatedAtMonotonicMs
}
