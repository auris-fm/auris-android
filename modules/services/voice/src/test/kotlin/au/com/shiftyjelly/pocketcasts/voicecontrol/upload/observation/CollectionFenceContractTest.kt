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
import org.junit.Assert.assertNotNull
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
@org.robolectric.annotation.Config(sdk = [35])
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
                observer.wakeCompleted(ctx.context, 0, 8, SegmentObservation.WakeState.NotDetected) {
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
        assertEquals(1, sink.retainedSnapshot().count { it.context == ctx.context })
    }

    /**
     * NEW-RETENTION DENIAL (spec 8b3d9269): a segment completing while the collection gate has
     * CLOSED must not be retained, even for a fully authorised, non-stale capture. Every other
     * refusal control here passes `liveGateAllowed = true`, so before this one the `!liveAllowed`
     * branch of [HandoffCoordinator] had no control at all.
     *
     * It must also preserve the distinction the other controls establish: the refusal terminates
     * THIS capture exactly once and releases its prefix, and it does not touch a different capture.
     */
    @Test
    fun `a handoff denied by the closed live gate retains nothing and terminates once`() {
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

        // Only ONE capture may be open at a time, so the "doesn't touch a different capture"
        // distinction is established SEQUENTIALLY: a bystander denied first must leave nothing that
        // affects the next capture, which then opens and is denied independently.
        val first = observer.speechStartedAdmitting(2L, "Headset", 8)!!
        observer.adoptFirstChunk(first, ShortArray(8))
        HandoffCoordinator(tx, validateIssuedLocked = { true }).handoff(
            first.context,
            0,
            8,
            SegmentObservation.WakeState.Detected,
            { listOf(ShortArray(8)) to 8 },
            observer,
            liveGateAllowed = false,
        )
        assertEquals("the first denied capture is closed", false, observer.isOpen(first.context.captureId))

        val denied = observer.speechStartedAdmitting(1L, "Headset", 8)!!
        observer.adoptFirstChunk(denied, ShortArray(8))
        assertTrue("the denied capture holds charged prefix bytes", sink.prefixBytesChargedForTest(denied.context) > 0L)
        assertTrue("it is genuinely a DIFFERENT capture", denied.context.captureId != first.context.captureId)

        // Authority is fully valid and the issued authority validates; ONLY the live gate is closed.
        HandoffCoordinator(tx, validateIssuedLocked = { true }).handoff(
            denied.context,
            0,
            8,
            SegmentObservation.WakeState.Detected,
            { listOf(ShortArray(8)) to 8 },
            observer,
            liveGateAllowed = false,
        )

        assertEquals("the denied capture is terminated", false, observer.isOpen(denied.context.captureId))
        assertEquals("no new retention occurs", 0, sink.retainedCount())
        assertEquals("its prefix charge is released", 0L, sink.prefixBytesChargedForTest(denied.context))
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
        assertTrue(observer.isOpen(ctx.context.captureId))
        // The OPEN capture's own invalid handoff terminates exactly once and releases everything.
        coordinator.handoff(ctx.context, 0, 8, SegmentObservation.WakeState.NotDetected, { emptyList<ShortArray>() to 0 }, observer, liveGateAllowed = true)
        assertFalse(observer.isOpen(ctx.context.captureId)) // terminated exactly once
        assertEquals(0L, sink.totalChargedBytesForTest()) // prefix charge released, nothing retained
        // Direct wake with a stale context is a NO-OP (late outcome), never a second terminal.
        val ctx2 = observer.speechStartedAdmitting(2L, "Headset", 8)!!
        observer.adoptFirstChunk(ctx2, ShortArray(8))
        observer.wakeCompleted(CaptureContext(java.util.UUID.randomUUID(), 9L, "X", IssuedAuthority(null, 0L, 0L, null, null)), 0, 8, SegmentObservation.WakeState.NotDetected) { emptyList<ShortArray>() to 0 }
        assertTrue(observer.isOpen(ctx2.context.captureId))
        // Null-context handoff through the coordinator closes the OPEN capture with one terminal.
        coordinator.handoff(null, 0, 8, SegmentObservation.WakeState.NotDetected, { emptyList<ShortArray>() to 0 }, observer, liveGateAllowed = true)
        assertFalse(observer.isOpen(ctx2.context.captureId))
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
        val leaseToken = sink.reserveDelta(ctx.context, 16)
        assertNotNull(leaseToken)
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
        assertFalse(sink.commitDelta(ctx.context, held, 16, leaseToken))
        // Producer finally drops the array: charge releases exactly then — THAT token only.
        sink.releaseDelta(ctx.context, leaseToken)
        assertEquals(0L, sink.outstandingLeaseBytesForTest())
        // Revocation released the finished prefix (16 bytes) immediately; the producer-held
        // lease (32 bytes) survived it and leaves only on drop → total after both = before − 48.
        assertEquals(beforeRevoke - 48L, sink.totalChargedBytesForTest())
    }

    @Test
    fun `token isolation - revoking capture A releases ONLY A tokens, never B charge`() {
        // Reviewer 23cae70e b3: a global pending counter let one capture's revocation release
        // ANOTHER capture's outstanding charge. Per-allocation tokens make that impossible.
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        val owner = FakeOwner()
        val tx = CollectionAuthorityTransaction(owner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)), sharedLock = owner.collectionSharedLock)
        val observer = SegmentObserver(
            gate = CollectionGate { true },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1") },
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        // Capture A opens and reserves a delta token; then it is revoked mid-copy (producer holds).
        val ctxA = observer.speechStartedAdmitting(1L, "Headset", 8)!!
        assertTrue(observer.adoptFirstChunk(ctxA, ShortArray(8)))
        // Delta reservation requires the sink's OPEN prefix for this exact context object.
        val tokenA = sink.reserveDelta(ctxA.context, 16)!!
        // Capture B opens AFTER A's revoke fan-out begins: fresh context, own tokens only.
        assertTrue(observer.revoked("account-change"))
        // Observer state was closed by A's revocation; B opens on a fresh observer. Same sink —
        // that is the point: B's tokens must be untouched by ANY later revoke of other captures.
        val observerB = SegmentObserver(
            gate = CollectionGate { true },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1") },
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        val ctxB = observerB.speechStartedAdmitting(2L, "Headset", 8)!!
        assertTrue(observerB.adoptFirstChunk(ctxB, ShortArray(8)))
        val tokenB = sink.reserveDelta(ctxB.context, 32)!!
        val chargedBefore = sink.totalChargedBytesForTest() // A lease 32 + B prefix 16 + B lease 64 = 112
        // FULL REVOCATION through the shared sink purges every FINISHED buffer (B's prefix goes),
        // but producer-held LEASE charges survive — for BOTH captures, each released only by its
        // own producer drop. No global counter may absorb the wrong bytes (reviewer 23cae70e b3).
        assertTrue(observerB.revoked("account-change"))
        assertEquals(chargedBefore - 16L, sink.totalChargedBytesForTest())
        // Stale commit for B with A's dead token: refuses, releases nothing of B.
        assertFalse(sink.commitDelta(ctxB.context, ShortArray(16), 16, tokenA))
        // Producer drops B's array: exactly B's token charge (32 samples * 2 = 64 bytes) leaves.
        sink.releaseDelta(ctxB.context, tokenB)
        val afterDrop = sink.totalChargedBytesForTest()
        assertEquals(chargedBefore - 80L, afterDrop)
        // Remaining charge == A's producer-held lease ONLY; A's finished prefix was purged at its
        // own revocation before this point.
        assertEquals(32L, afterDrop)
        // A's producer finally-path drop releases exactly A's remaining token charge — the ONLY
        // way that lease leaves the budget (revocation alone never touched it).
        sink.releaseDelta(ctxA.context, tokenA)
        assertEquals(0L, sink.totalChargedBytesForTest())
        // Double-release of the SAME token returns nothing further (idempotent per token).
        sink.releaseDelta(ctxB.context, tokenB)
        assertEquals(0L, sink.totalChargedBytesForTest())
    }

    @Test
    fun `held first copy then revoke plus successor - initial handle releases independently`() {
        // Reviewer 79b2b5c9 b2: releaseInitialChunk is addressed by the IMMUTABLE handle from
        // admission. Revocation closes capture A and a SUCCESSOR B opens; A's producer finally
        // must still return A's held lease — never silently no-op on "current open context".
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        val owner = FakeOwner()
        val tx = CollectionAuthorityTransaction(owner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)), sharedLock = owner.collectionSharedLock)
        val observer = SegmentObserver(
            gate = CollectionGate { true },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1") },
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        // A admits and holds its FIRST allocation (mid-copy): adopt deliberately NOT called yet.
        val admissionA = observer.speechStartedAdmitting(1L, "Headset", 8)!!
        assertNotNull(admissionA.initialToken)
        val chargedA = sink.totalChargedBytesForTest()
        assertEquals(16L, chargedA) // 8 samples * 2 bytes, producer-held
        // Revocation closes A while the producer still holds the array...
        assertTrue(observer.revoked("account-change"))
        // ...and a SUCCESSOR capture opens on the same observer/sink.
        val admissionB = observer.speechStartedAdmitting(2L, "Headset", 8)!!
        assertTrue(observer.adoptFirstChunk(admissionB, ShortArray(8)))
        // A's producer finally-path releases BY HANDLE despite closure + successor being open.
        observer.releaseInitialChunk(admissionA)
        assertEquals(sink.totalChargedBytesForTest(), 16L) // only B's prefix remains charged
        // Double-release via the same handle returns nothing further.
        val before = sink.totalChargedBytesForTest()
        observer.releaseInitialChunk(admissionA)
        assertEquals(before, sink.totalChargedBytesForTest())
    }

    @Test
    fun `partial first copy keeps capacity charged and valid range distinct`() {
        // Reviewer 79b2b5c9 b1/b3: drained VALID count passes separately; the adopted array's FULL
        // CAPACITY stays charged while live; readers see validSamples audio, never zero padding.
        val budget = CollectionBudget(1L shl 20)
        val sink = ContributionRetentionSink(budget)
        val owner = FakeOwner()
        val tx = CollectionAuthorityTransaction(owner, FakePolicyReader(PolicySnapshot(enabled = true, activationGeneration = 7, activatedAtMonotonicMs = 1000)), sharedLock = owner.collectionSharedLock)
        val observer = SegmentObserver(
            gate = CollectionGate { true },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e1", "d1") },
            fenceGate = object : FenceGate {
                override fun <R> gate(block: () -> R): R = tx.transaction(block)
            },
        )
        val admission = observer.speechStartedAdmitting(1L, "Headset", 16)!!
        // Distinctive PCM in the valid prefix ONLY; tail is untouched zero padding.
        val big = ShortArray(16)
        for (i in 0 until 8) big[i] = (100 + i).toShort()
        assertTrue(observer.adoptFirstChunk(admission, big, validSamples = 8))
        // FULL CAPACITY charged (32 bytes), not just the valid 16.
        assertEquals(32L, sink.totalChargedBytesForTest())
        // Valid SAMPLES are the drained count — zero padding is not audio.
        assertEquals(8L, sink.prefixSamples())
        // adoptFirstChunk reports the SINK result: after revocation closes the capture, a stale
        // adoption attempt with the live handle returns FALSE (never hard true — reviewer b3),
        // and the sink's own transfer is what gates the observer.
        assertTrue(observer.revoked("account-change"))
        val fresh = observer.speechStartedAdmitting(2L, "Headset", 16)!!
        assertFalse(observer.adoptFirstChunk(admission, big, validSamples = 8))
        assertTrue(observer.adoptFirstChunk(fresh, big, validSamples = 8))
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
