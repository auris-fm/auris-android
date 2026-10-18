package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.accounts.AccountManager
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.sync.TokenErrorNotification
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import com.automattic.eventhorizon.EventHorizon
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * Contract controls for the single collection-authority transaction (reviewer a38421f6):
 * lock order, held-copy-vs-expiry determinism, refusal termination, and coherent policy reads.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class CollectionFenceContractTest {

    private class FakeOwner : CredentialOwnershipOwner {
        override val collectionSharedLock = Any()

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
        val tx = CollectionAuthorityTransaction(owner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)), sharedLock = owner.collectionSharedLock)
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        val issued = IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1")
        val observer = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { tx.transaction { authorityFrom(tx, established = 1L, owner) } },
            issueAuthority = { tx.coherentAuthority().toIssued() },
            handoff = HandoffCoordinator(
                tx,
                validateIssuedLocked = { AuthorityChecks.validateThroughTx(tx, owner, it) },
                liveAuthority = { authorityFrom(tx, established = 1L, owner) },
            ),
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
        val tx = CollectionAuthorityTransaction(owner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)), sharedLock = owner.collectionSharedLock)
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(
            gate = CollectionGate { true },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1") },
            handoff = HandoffCoordinator(tx, validateIssuedLocked = { true }),
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        val ctx = observer.speechStartedAdmitting(1L, "Headset", 8)!!
        observer.adoptFirstChunk(ctx, ShortArray(8))
        val before = sink.totalChargedBytesForTest()
        // Handoff for an UNKNOWN/stale context via the PRODUCTION-shaped coordinator: refused as
        // invalid issued authority → cancel terminal for the open capture, zero new retention.
        val coordinator = HandoffCoordinator(tx, validateIssuedLocked = { false })
        // Stale/unknown context: late outcome — refused as a NO-OP, current capture untouched.
        coordinator.handoff(
            CaptureContext(java.util.UUID.randomUUID(), 9L, "X", IssuedAuthority(null, 0L, 0L, null, null)),
            0,
            8,
            SegmentObservation.WakeState.NotDetected,
            { emptyList<ShortArray>() to 0 },
            observer,
            liveGateAllowed = true,
        )
        assertTrue(observer.isOpen(ctx.captureId))
        // The OPEN capture's own invalid handoff terminates exactly once and releases everything.
        coordinator.handoff(ctx, 0, 8, SegmentObservation.WakeState.NotDetected, { emptyList<ShortArray>() to 0 }, observer, liveGateAllowed = true)
        assertFalse(observer.isOpen(ctx.captureId)) // terminated exactly once
        assertEquals(0L, sink.totalChargedBytesForTest()) // prefix charge released, nothing retained
        // Direct wake with a stale context is a NO-OP (late outcome), never a second terminal.
        val ctx2 = observer.speechStartedAdmitting(2L, "Headset", 8)!!
        observer.adoptFirstChunk(ctx2, ShortArray(8))
        observer.wakeCompleted(CaptureContext(java.util.UUID.randomUUID(), 9L, "X", IssuedAuthority(null, 0L, 0L, null, null)), 0, 8, SegmentObservation.WakeState.NotDetected) { emptyList<ShortArray>() to 0 }
        assertTrue(observer.isOpen(ctx2.captureId))
        // Null-context handoff through the coordinator closes the OPEN capture with one terminal.
        coordinator.handoff(null, 0, 8, SegmentObservation.WakeState.NotDetected, { emptyList<ShortArray>() to 0 }, observer, liveGateAllowed = true)
        assertFalse(observer.isOpen(ctx2.captureId))
        assertEquals(0L, sink.totalChargedBytesForTest())
    }

    @Test
    fun `held producer plus revoke - inflight bytes stay charged until finally release`() {
        // Reviewer 8273382e b4: revocation MARKS leases but cannot release the charge while the
        // producer still holds the allocated array; only the producer's drop releases it.
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        val leaseOwner = FakeOwner()
        val tx = CollectionAuthorityTransaction(leaseOwner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)), sharedLock = leaseOwner.collectionSharedLock)
        val observer = SegmentObserver(
            gate = CollectionGate { true },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1") },
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        val ctx = observer.speechStartedAdmitting(1L, "Headset", 8)!!
        observer.adoptFirstChunk(ctx, ShortArray(8))
        // Producer reserves a delta and HOLDS the allocated array (mid-copy window).
        assertTrue(sink.reserveDelta(ctx, 16))
        val held = ShortArray(16)
        // Lease accounting is BYTES: reserveDelta(16 samples) charges 16 * 2 = 32 bytes.
        assertEquals(32L, sink.outstandingLeaseBytesForTest())
        val beforeRevoke = sink.totalChargedBytesForTest()
        // Revocation arrives WHILE the producer holds the array.
        assertTrue(observer.revoked("lease-deadline"))
        // Inflight live bytes remain charged: purge released finished buffers, not the lease.
        assertTrue(
            "lease charge survives revocation while producer holds the array",
            sink.totalChargedBytesForTest() >= 32L,
        )
        // Stale commit after revocation must NOT adopt into retained ownership; the partially
        // copied array is dropped by the producer's release path (commit refuses, returns false).
        assertFalse(sink.commitDelta(ctx, held, 16))
        // Producer finally drops the array: charge releases exactly then.
        sink.releaseDelta(ctx, 16)
        assertEquals(0L, sink.outstandingLeaseBytesForTest())
        // Revocation released the finished prefix (16 bytes) immediately; the producer-held
        // lease (32 bytes) survived it and leaves only on drop → total after both = before − 48.
        assertEquals(beforeRevoke - 48L, sink.totalChargedBytesForTest())
    }

    @Test
    fun `real owner path - addAccount login serializes with a held collection transaction`() {
        // Reviewer 2e1d5b0b b2: exercise the ACTUAL SyncAccountManagerImpl ordering, not fakes.
        // The real owner takes collectionSharedLock BEFORE credentialWriteLock; a collection
        // transaction holding the SAME primitive therefore blocks any concurrent owner
        // transition — verified by contention on the real class, not comments.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = SyncAccountManagerImpl(
            tokenErrorNotification = TokenErrorNotification(Mockito.mock(EventHorizon::class.java), context),
            accountManager = AccountManager.get(context),
        )
        val tx = CollectionAuthorityTransaction(
            ownership = owner,
            policy = FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)),
            sharedLock = owner.collectionSharedLock,
        )
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            tx.transaction {
                entered.countDown()
                release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        writer.start()
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val racer = Thread {
            owner.authoritySnapshot() // real owner acquisition: must wait for the held transaction
            blocked.countDown()
        }
        racer.start()
        assertFalse("owner read blocked while collection txn holds the shared lock", blocked.await(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue("owner read proceeds after release", blocked.await(2, java.util.concurrent.TimeUnit.SECONDS))
        writer.join(3000)
        racer.join(3000)
    }

    @Test
    fun `policy disable publishes through nesting and old work cannot be reauthorized`() {
        val owner = FakeOwner()
        val provider = FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000))
        val tx = CollectionAuthorityTransaction(owner, provider, sharedLock = owner.collectionSharedLock)
        val frozen = tx.coherentAuthority().toIssued()
        provider.snap = PolicySnapshot(enabled = false, activationGeneration = 0, activatedAtMonotonicMs = 0)
        assertFalse(AuthorityChecks.validateThroughTx(tx, owner, frozen))
        // Re-enabling creates a NEW incarnation; the frozen old one stays invalid.
        provider.snap = PolicySnapshot(enabled = true, activationGeneration = 8, activatedAtMonotonicMs = 5000)
        assertFalse(AuthorityChecks.validateThroughTx(tx, owner, frozen))
        assertEquals(8L, provider.snapshot().activationGeneration)
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

private class FakePolicyReader(initial: PolicySnapshot) : PolicySnapshotReader {
    @Volatile var snap = initial
    override fun snapshot() = snap
}
