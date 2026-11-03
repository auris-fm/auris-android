package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.FenceGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.VoiceObservationModule
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * Batch A controls (reviewer c5d94af4 #4 / b256b011 / corrections 3adcf0f3): the automatic
 * owner/account/policy retirement composition. Every retirement serializes with take on ONE
 * lock (tryLock: admission stays non-waiting — that try-acquisition is also the deadlock
 * argument for the two nesting orders), the session listener is CHAINED not overwritten,
 * lease expiry is exact against a virtual clock with NO publication, stale callbacks are
 * GENERATION-BOUND and never retire a successor, dispose cancels every job it owns, and a
 * retirement invalidates an outstanding capture (transferred after take) through the REAL
 * shared collection fence.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureAllowanceLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")

    private fun limits() = AllowanceLimits(
        10,
        1_000_000,
        maxClipBytes = 100_000 - 1024,
        maxMetadataBytes = 1024,
        ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192),
    )

    private fun provisionedOwner(dir: File): CaptureAllowanceOwner = CaptureAllowanceOwner(dir, account, limits(), window, AllowanceProvisioning.TestOnly)

    private fun startup(): VoiceSharingAccountStartup = VoiceUploadCompositionFixtures.startup(ApplicationProvider.getApplicationContext() as android.app.Application)

    /** Provider over the SAME virtual monotonic clock the watcher under test uses. */
    private fun policyProvider(fakeNow: AtomicLong): VoiceSharingPolicyProvider = VoiceSharingPolicyProvider(
        remoteConfig = mock(),
        featureProvider = mock(),
        scope = CoroutineScope(Job().apply { cancel() }),
        nowMs = { fakeNow.get() },
        sleepMs = { },
        refreshTimeoutMs = VoiceSharingPolicyProvider.REFRESH_TIMEOUT_MS,
        sharedCollectionLock = Any(),
    )

    /** Rendezvous sleep: each deadline arm parks here until the test drains it deterministically. */
    private class VirtualClock {
        val now = AtomicLong(10_000L)
        val sleeps = Channel<Long>(Channel.RENDEZVOUS) // explicit rendezvous: the watcher job truly suspends

        suspend fun sleep(ms: Long) {
            sleeps.send(ms)
        }

        /**
         * Drains the OLDEST parked sleep: advances the virtual clock by exactly its span, then
         * pumps the resumption. The deadline job resumes on this thread's unconfined event loop,
         * which may run AFTER [runBlocking] returns — the bounded pump lets the resumed callback
         * finish its revalidation/retirement before the caller asserts.
         */
        fun drainOne(deadlineMs: Long? = null) {
            // Advance FIRST: the rendezvous receive resumes the deadline job inline, and its
            // revalidation reads the clock — the expiry must already be true at resumption.
            if (deadlineMs != null) now.set(deadlineMs)
            val ms = runBlocking { withTimeout(4_000) { sleeps.receive() } }
            if (deadlineMs == null) now.addAndGet(ms)
            // Pump the resumed callback: its unconfined resumption runs on this thread's event
            // loop; a bounded pump lets it finish revalidation/retirement before the asserts.
            runBlocking { repeat(30) { delay(5) } }
        }
    }

    @Test
    fun `account replacement retires the prepared token and keeps the prior listener in the chain`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val priorFired = CountDownLatch(1)
        // An earlier composition (CollectionLossWatcher) sits on the listener first.
        startup.sessionEndedListener = { priorFired.countDown() }
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        // The watcher starts BEFORE the bind: its initial reconcile observes the unavailable
        // default and must retire nothing.
        watcher(lifecycle, startup, p, scope, clock).start()
        // A binding must be governed by a LIVE policy: publish a fresh activation BEFORE binding
        // (the bind reconciliation retires a binding under a disabled/expired policy).
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        bindInstalled(lifecycle, startup, owner)
        assertTrue("premise: the freshly bound owner is live BEFORE the account event", owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // The REAL fan-out: whatever observes account loss invokes the chained listener.
        startup.sessionEndedListener?.invoke()

        assertTrue("the prior composition's listener must still fire (chained, not overwritten)", priorFired.await(2, TimeUnit.SECONDS))
        assertTrue("the retirement must fire the revocation hook", retired.await(2, TimeUnit.SECONDS))
        assertFalse("the prepared token is RETIRED, not usable", owner.hasUsableToken())
        assertNull("the retired slot can no longer hand out a token", lifecycle.takePrepaidToken())
        scope.cancel()
    }

    @Test
    fun `a WITHDRAWN projection retaining the epoch retires the bound authority through the evidence wakeup`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val priorFired = CountDownLatch(1)
        // An earlier composition sits on the evidence listener first.
        startup.enrollmentEvidenceListener = { _, _ -> priorFired.countDown() }
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        watcher(lifecycle, startup, p, scope, clock).start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        bindInstalled(lifecycle, startup, owner)
        assertTrue("premise: the freshly bound owner is live BEFORE the withdrawal", owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // A WITHDRAWN projection that RETAINS the epoch string (the exact state the fence's
        // enrollment basis must see): the evidence wakeup must retire, not just the fence refuse.
        val withdrawn = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection(
            membershipRevision = "rev-2",
            plan = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan.PRO,
            voiceSharingEnrolled = false,
            enrollmentEpoch = "epoch-retained",
            acceptedDisclosureVersion = "1",
            withdrawalState = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState.WITHDRAWN,
        )
        startup.enrollmentEvidenceListener?.invoke(withdrawn, 1L)

        assertTrue("the prior composition's evidence listener must still fire (chained)", priorFired.await(2, TimeUnit.SECONDS))
        assertTrue("the withdrawal must fire the revocation hook", retired.await(2, TimeUnit.SECONDS))
        assertFalse("the prepared token is RETIRED, not usable", owner.hasUsableToken())
        assertNull(lifecycle.takePrepaidToken())
        scope.cancel()
    }

    @Test
    fun `an ACTIVE enrolled projection does not retire the bound authority`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        watcher(lifecycle, startup, p, scope, clock).start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        bindInstalled(lifecycle, startup, owner)
        assertTrue(owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        val active = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection(
            membershipRevision = "rev-1",
            plan = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan.PRO,
            voiceSharingEnrolled = true,
            enrollmentEpoch = "epoch-1",
            acceptedDisclosureVersion = "1",
            withdrawalState = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState.ACTIVE,
        )
        startup.enrollmentEvidenceListener?.invoke(active, 1L)

        assertFalse("an active membership must NOT retire", retired.await(1, TimeUnit.SECONDS))
        assertTrue("the bound authority stays usable", owner.hasUsableToken())
        scope.cancel()
    }

    @Test
    fun `an unchanged ACTIVE refresh through the REAL store apply does not retire`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        watcher(lifecycle, startup, p, scope, clock).start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        // Commit ACTIVE epoch-1, bind under it, then refresh the SAME identity twice through the
        // REAL store path — where applyOutcome fires BOTH the evidence and the invalidation
        // listener (the invalidation callback carries a NON-NULL payload: a commit, not loss).
        val state = startup.state
        val token = state.sessionStarted(0L)
        val ticket = state.beginOperation(token, requireProjection = false, ownerSession = 0L)
        assertNotNull(ticket)
        val activeJson = """
            {"schema_version":1,"membership_revision":"rev-1","plan":"pro",
             "voice_sharing_enrolled":true,"enrollment_epoch":"epoch-1",
             "accepted_disclosure_version":"1","withdrawal_state":"active"}
        """.trimIndent().replace("\n", "")
        assertTrue(state.applyOutcome(token, ticket!!, activeJson, ownerSession = 0L))
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner, CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 0L))
        assertTrue(owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }
        assertTrue(state.applyOutcome(token, ticket, activeJson, ownerSession = 0L))
        assertFalse("an unchanged ACTIVE refresh must NOT retire", retired.await(1, TimeUnit.SECONDS))
        assertTrue(owner.hasUsableToken())
        scope.cancel()
    }

    @Test
    fun `a bind racing an epoch replacement survives with the NEW identity and the old batch retires`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        val w = watcher(lifecycle, startup, p, scope, clock)
        w.start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)
        val old = provisionedOwner(tmp.newFolder())
        assertTrue(old.prepareToken())
        lifecycle.bind(old, CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 1L))
        assertTrue(old.hasUsableToken())
        val retiredOld = CountDownLatch(1)
        lifecycle.onRetired = { retiredOld.countDown() }

        // Interleave INSIDE the watcher decision (onRevalidatedForTest runs after revalidation,
        // before the retirement decision): commit the replacement identity AND bind a successor.
        // The decision's guarded retire then loses its race (the generation moved); the
        // re-decision must SPARE the successor — its onBound snapshot installed epoch-2 — and
        // must keep refusing the stale epoch-1 grant (already retired by the rebind).
        val successor = provisionedOwner(tmp.newFolder())
        assertTrue(successor.prepareToken())
        w.onRevalidatedForTest = {
            w.onRevalidatedForTest = null
            startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-2", revision = "rev-2"), 1L)
            lifecycle.bind(successor, CaptureAllowanceLifecycle.GrantIdentity("epoch-2", 1L))
        }
        // Trigger the decision through an unchanged-identity evidence wakeup.
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)

        assertTrue("the stale epoch-1 batch must retire", retiredOld.await(2, TimeUnit.SECONDS))
        assertFalse("the OLD token is retired", old.hasUsableToken())
        assertTrue("the successor bound under epoch-2 SURVIVES the lost race", successor.hasUsableToken())
        // A later decision keeps the successor alive (stable installed-identity invariant).
        val noSecondRetire = CountDownLatch(1)
        lifecycle.onRetired = { noSecondRetire.countDown() }
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-2"), 1L)
        Thread.sleep(200)
        assertFalse("the epoch-2 authority must not be retired again", noSecondRetire.await(500, TimeUnit.MILLISECONDS))
        assertTrue(successor.hasUsableToken())
        scope.cancel()
    }

    @Test
    fun `a DELAYED older bound callback cannot relabel the installed identity of a newer bind`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        val w = watcher(lifecycle, startup, p, scope, clock)
        w.start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)
        val old = provisionedOwner(tmp.newFolder())
        assertTrue(old.prepareToken())
        val oldGen = lifecycle.bind(old, CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 1L))
        assertTrue(old.hasUsableToken())
        // Replacement identity + successor bind (the rebind retires the old slot).
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-2", revision = "rev-2"), 1L)
        val successor = provisionedOwner(tmp.newFolder())
        assertTrue(successor.prepareToken())
        lifecycle.bind(successor, CaptureAllowanceLifecycle.GrantIdentity("epoch-2", 1L))
        assertTrue(successor.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // A DELAYED callback from the OLD bind (as a slow thread would deliver it) must NOT
        // relabel the installed identity: the hook reads the coherent CURRENT snapshot.
        lifecycle.onBound?.invoke(oldGen)

        assertFalse("the stale callback must not retire the epoch-2 authority", retired.await(1, TimeUnit.SECONDS))
        assertTrue("the epoch-2 authority stays usable", successor.hasUsableToken())
        scope.cancel()
    }

    @Test
    fun `a pre-existing binding WITHOUT install identity fails closed at watcher startup`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        publish(p, enabled = true, stamp = clock.now.get(), generation = 1L)
        // A binding that predates the watcher and carries NO install identity (legacy or
        // restart): the watcher must fail closed, not treat Unknown as permitted.
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner)
        assertTrue(owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        watcher(lifecycle, startup, p, scope, clock).start()

        assertTrue("the identity-less binding must retire at start", retired.await(2, TimeUnit.SECONDS))
        assertFalse(owner.hasUsableToken())
        scope.cancel()
    }

    @Test
    fun `startup after a committed same-session 401 (null projection) fails closed for a bound grant`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        publish(p, enabled = true, stamp = clock.now.get(), generation = 1L)
        // Committed ACTIVE identity, bind under it, then a committed 401 NULL loss — all
        // through the REAL store path — BEFORE the watcher starts.
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner, CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 1L))
        assertTrue(owner.hasUsableToken())
        startup.state.projectionInvalidatedListener?.invoke(null, 1L)
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // START: seed sees no committed projection (Unknown) with a bound grant → fail closed.
        watcher(lifecycle, startup, p, scope, clock).start()

        assertTrue("the post-401 bound grant must retire at start", retired.await(2, TimeUnit.SECONDS))
        assertFalse(owner.hasUsableToken())
        scope.cancel()
    }

    @Test
    fun `retirement clears the installed record - installedAuthority is null afterward`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        watcher(lifecycle, startup, p, scope, clock).start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        val gen = lifecycle.bind(owner, CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 1L))
        assertEquals(gen, lifecycle.installedAuthority()!!.generation)
        assertEquals("epoch-1", lifecycle.installedAuthority()!!.identity!!.enrollmentEpoch)
        assertTrue(lifecycle.retireCurrent(gen))
        // The retired grant must NOT keep reporting itself as installed.
        assertNull(lifecycle.installedAuthority())
        scope.cancel()
    }

    private fun activeProjection(epoch: String, revision: String = "rev-1") = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection(
        membershipRevision = revision,
        plan = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan.PRO,
        voiceSharingEnrolled = true,
        enrollmentEpoch = epoch,
        acceptedDisclosureVersion = "1",
        withdrawalState = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState.ACTIVE,
    )

    @Test
    fun `a same-session 401 projection loss retires the bound authority`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        watcher(lifecycle, startup, p, scope, clock).start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        bindInstalled(lifecycle, startup, owner)
        assertTrue(owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // processUnauthorized commits a NULL projection in the same session — the invalidation
        // listener (not the evidence listener) carries the loss.
        startup.state.projectionInvalidatedListener?.invoke(null, 1L)

        assertTrue("the null loss must fire the revocation hook", retired.await(2, TimeUnit.SECONDS))
        assertFalse(owner.hasUsableToken())
        assertNull(lifecycle.takePrepaidToken())
        scope.cancel()
    }

    @Test
    fun `an ACTIVE epoch replacement retires the batch installed under the previous epoch`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        watcher(lifecycle, startup, p, scope, clock).start()
        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        // First committed identity: ACTIVE epoch-1, same session. The subsequent bind installs
        // UNDER this identity (onBound snapshots it — the fence would refuse a basis mismatch).
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner, CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 1L))
        assertTrue(owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // Re-enrollment: ACTIVE epoch-2, SAME session. The batch installed under epoch-1 retires.
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-2", revision = "rev-2"), 1L)

        assertTrue("the epoch replacement must fire the revocation hook", retired.await(2, TimeUnit.SECONDS))
        assertFalse(owner.hasUsableToken())
        assertNull(lifecycle.takePrepaidToken())
        scope.cancel()
    }

    @Test
    fun `a watcher started after an already-committed withdrawal retires at start`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        // Commit a WITHDRAWN projection (retaining the epoch string) through the REAL store
        // path BEFORE the watcher exists.
        val state = startup.state
        val token = state.sessionStarted(0L)
        val ticket = state.beginOperation(token, requireProjection = false, ownerSession = 0L)
        assertNotNull(ticket)
        val withdrawnJson = """
            {"schema_version":1,"membership_revision":"rev-2","plan":"free",
             "voice_sharing_enrolled":false,"enrollment_epoch":"epoch-retained",
             "accepted_disclosure_version":null,"withdrawal_state":"withdrawn"}
        """.trimIndent().replace("\n", "")
        assertTrue(state.applyOutcome(token, ticket!!, withdrawnJson, ownerSession = 0L))
        assertEquals(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState.WITHDRAWN,
            state.projection.value!!.withdrawalState,
        )

        val stamp = clock.now.get()
        publish(p, enabled = true, stamp = stamp, generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner)
        assertTrue(owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // START: the seed reads the already-committed withdrawal — retire at start.
        watcher(lifecycle, startup, p, scope, clock).start()

        assertTrue("the seeded withdrawal must retire at start", retired.await(2, TimeUnit.SECONDS))
        assertFalse(owner.hasUsableToken())
        assertNull(lifecycle.takePrepaidToken())
        scope.cancel()
    }

    @Test
    fun `a successor owner binds and admits while the retired one cannot`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val old = provisionedOwner(tmp.newFolder())
        assertTrue(old.prepareToken())
        val oldGen = lifecycle.bind(old)
        assertTrue(lifecycle.retireCurrent(oldGen))
        assertNull("the stale owner is gone from the slot", lifecycle.takePrepaidToken())
        val fresh = provisionedOwner(tmp.newFolder())
        assertTrue(fresh.prepareToken())
        val newGen = lifecycle.bind(fresh)
        assertTrue("the rebind advanced the generation", newGen > oldGen)
        assertNotNull("the successor's token admits", lifecycle.takePrepaidToken())
    }

    @Test
    fun `expiry covers publish-before-bind and same-lease rebinds, spares a successor under a NEWER valid policy, and re-arms after disable`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val startup = startup()
        val p = policyProvider(clock.now)
        val grantIdentity = CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 1L)
        watcher(lifecycle, startup, p, scope, clock).start()
        // Production-realistic identity: commit once; every bind below carries it (same lease).
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)

        // (a) PUBLISH-BEFORE-BIND ESCAPE IS CLOSED: a fresh policy published while the slot is
        // unavailable still retires an owner bound LATER under that same lease.
        val stamp1 = clock.now.get()
        publish(p, enabled = true, stamp = stamp1, generation = 1L)
        val owner1 = provisionedOwner(tmp.newFolder())
        assertTrue(owner1.prepareToken())
        lifecycle.bind(owner1, grantIdentity) // bound AFTER the publication that armed the deadline
        assertTrue("premise: live immediately before expiry", owner1.hasUsableToken())
        clock.drainOne(deadlineFor(stamp1)) // exact expiry, no publication in between
        assertEquals("a bind after the publication still expires with its lease", false, owner1.hasUsableToken())
        assertNull(lifecycle.takePrepaidToken())

        // (b) SAME-LEASE REBIND RETIRES: a successor bound under the SAME policy's lease is
        // governed by it and retires at that lease's expiry.
        val stamp2 = clock.now.get()
        publish(p, enabled = true, stamp = stamp2, generation = 2L)
        val owner2 = provisionedOwner(tmp.newFolder())
        assertTrue(owner2.prepareToken())
        lifecycle.bind(owner2, grantIdentity)
        val rebind = provisionedOwner(tmp.newFolder())
        assertTrue(rebind.prepareToken())
        lifecycle.bind(rebind, grantIdentity) // same lease; the rebind itself retires owner2 (previous slot)
        assertTrue("premise: the same-lease rebind is live before expiry", rebind.hasUsableToken())
        clock.drainOne(deadlineFor(stamp2))
        assertEquals("a successor using the SAME expired lease must retire", false, rebind.hasUsableToken())

        // (c) SUCCESSOR UNDER A NEWER VALID POLICY IS SPARED: the newer publication supersedes
        // the old deadline; nothing is parked for the old stamp; expiry of the NEW lease is what
        // finally retires.
        val stamp3 = clock.now.get()
        publish(p, enabled = true, stamp = stamp3, generation = 3L) // arms deadline3
        val spared = provisionedOwner(tmp.newFolder())
        assertTrue(spared.prepareToken())
        lifecycle.bind(spared, grantIdentity)
        val stamp4 = clock.now.get()
        publish(p, enabled = true, stamp = stamp4, generation = 4L) // supersedes deadline3
        clock.now.set(deadlineFor(stamp3)) // the OLD lease's expiry instant passes
        runBlocking { repeat(10) { delay(5) } } // pump: only the superseded job could react — it was cancelled
        assertTrue("a successor governed by a newer valid policy survives the old lease expiry", spared.hasUsableToken())
        clock.drainOne(deadlineFor(stamp4))
        assertEquals("its own lease expiry retires it exactly", false, spared.hasUsableToken())

        // (d) BIND UNDER AN ALREADY-EXPIRED POLICY RETIRES IMMEDIATELY via the bind
        // reconciliation (no armed deadline needed).
        val lapsed = provisionedOwner(tmp.newFolder())
        assertTrue(lapsed.prepareToken())
        lifecycle.bind(lapsed, grantIdentity)
        assertEquals("binding under an expired lease retires via reconciliation", false, lapsed.hasUsableToken())

        // (e) DISABLE retires; DISABLE then ENABLE re-arms expiry coverage.
        publish(p, enabled = false, stamp = 0L, generation = 5L)
        val afterDisable = provisionedOwner(tmp.newFolder())
        assertTrue(afterDisable.prepareToken())
        lifecycle.bind(afterDisable, grantIdentity)
        assertEquals("a binding under a disabled policy retires", false, afterDisable.hasUsableToken())
        val stamp5 = clock.now.get()
        publish(p, enabled = true, stamp = stamp5, generation = 6L)
        val revived = provisionedOwner(tmp.newFolder())
        assertTrue(revived.prepareToken())
        lifecycle.bind(revived, grantIdentity)
        assertTrue("premise: live under the re-enabled policy", revived.hasUsableToken())
        clock.drainOne(deadlineFor(stamp5))
        assertEquals("expiry coverage is re-armed after disable then enable", false, revived.hasUsableToken())
        scope.cancel()
    }

    @Test
    fun `cancelling the OWNING SCOPE disposes the watcher - prepared owner retired, listener detached, use refused`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val priorFired = CountDownLatch(1)
        val prior: () -> Unit = { priorFired.countDown() }
        startup.sessionEndedListener = prior
        val clock = VirtualClock()
        val p = policyProvider(clock.now)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val w = watcher(lifecycle, startup, p, scope, clock)
        w.start()
        publish(p, enabled = true, stamp = clock.now.get(), generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        bindInstalled(lifecycle, startup, owner)
        assertTrue("premise: live before the owning scope is cancelled", owner.hasUsableToken())
        val retired = CountDownLatch(1)
        lifecycle.onRetired = { retired.countDown() }

        // THE TEARDOWN: the owning scope's cancellation drives disposal (scope-completion
        // hookup), not a manually invoked method.
        scope.cancel()

        assertTrue("scope cancellation must retire the bound owner", retired.await(2, TimeUnit.SECONDS))
        assertFalse("the prepared token is retired", owner.hasUsableToken())
        assertSame("the watcher's own chain link is detached, restoring the prior listener", prior, startup.sessionEndedListener)
        assertNull("takes refuse after scope-driven disposal", lifecycle.takePrepaidToken())
        val genAfter = lifecycle.currentGeneration()
        assertEquals("binds refuse after scope-driven disposal", genAfter, lifecycle.bind(provisionedOwner(tmp.newFolder())))
        // The restored prior listener still fires; the disposed watcher cannot react.
        startup.sessionEndedListener?.invoke()
        assertTrue(priorFired.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a bind racing a paused stale revalidation survives the atomic guarded retirement`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val startup = startup()
        val p = policyProvider(clock.now)
        val w = watcher(lifecycle, startup, p, scope, clock)
        w.start()
        // Production-realistic identity, committed and carried by the racing successor bind.
        startup.enrollmentEvidenceListener?.invoke(activeProjection("epoch-1"), 1L)

        // Deterministic pause BETWEEN authoritative revalidation and the retirement call: the
        // first revalidation to run sees a stale authority and parks in the seam.
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pauseArmed = java.util.concurrent.atomic.AtomicBoolean(true)
        w.onRevalidatedForTest = {
            if (pauseArmed.compareAndSet(true, false)) {
                paused.countDown()
                resume.await()
            }
        }
        val background = thread(name = "stale-reconcile") {
            // An expired authoritative snapshot drives reconcile() on THIS thread, which parks
            // in the seam right after revalidation, BEFORE the retirement call.
            publish(p, enabled = true, stamp = clock.now.get() - VoiceSharingPolicyProvider.DEFAULT_LEASE_MS - 10L, generation = 1L)
        }
        assertTrue("premise: reconcile is paused after revalidation, before retirement", paused.await(4, TimeUnit.SECONDS))

        // While paused: a NEWER LIVE policy is published and a successor binds under it. The
        // pause holds the watcher lock, so the bind runs on ANOTHER thread; the successor's
        // binding generation (set before the bind hook runs) is the readiness signal.
        val stampNew = clock.now.get()
        publish(p, enabled = true, stamp = stampNew, generation = 2L)
        val successor = provisionedOwner(tmp.newFolder())
        assertTrue(successor.prepareToken())
        val bindThread = thread(name = "racing-bind") {
            lifecycle.bind(successor, CaptureAllowanceLifecycle.GrantIdentity("epoch-1", 1L))
        }
        val deadlineForBind = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (lifecycle.currentGeneration() == 0L) {
            check(System.nanoTime() < deadlineForBind) { "racing bind never became visible" }
            Thread.sleep(10)
        }
        val genAtPauseEnd = lifecycle.currentGeneration()

        resume.countDown()
        background.join(4_000)
        bindThread.join(4_000)
        runBlocking { repeat(20) { delay(5) } } // let the re-decision settle

        assertTrue("the racing bind completed", !bindThread.isAlive)
        assertTrue("the successor bound under the NEWER live policy must survive the stale decision", successor.hasUsableToken())
        assertEquals("the guarded retire refused the superseded generation", genAtPauseEnd, lifecycle.currentGeneration())

        // SAME-EXPIRED-POLICY retirement is preserved: after the new lease lapses the successor retires.
        clock.drainOne(deadlineFor(stampNew))
        assertEquals("its own lease expiry still retires it", false, successor.hasUsableToken())
        scope.cancel()
    }

    /** Production-realistic install: the committed ACTIVE identity, carried on the binding. */
    private fun bindInstalled(
        lifecycle: CaptureAllowanceLifecycle,
        startup: VoiceSharingAccountStartup,
        owner: CaptureAllowanceOwner,
        epoch: String = "epoch-1",
        session: Long = 1L,
    ): Long {
        startup.enrollmentEvidenceListener?.invoke(activeProjection(epoch), session)
        return lifecycle.bind(owner, CaptureAllowanceLifecycle.GrantIdentity(epoch, session))
    }

    private fun watcher(
        lifecycle: CaptureAllowanceLifecycle,
        startup: VoiceSharingAccountStartup,
        provider: VoiceSharingPolicyProvider,
        scope: CoroutineScope,
        clock: VirtualClock,
    ) = AllowanceRetirementWatcher(lifecycle, provider, startup, scope, nowMs = { clock.now.get() }, sleepMs = { clock.sleep(it) })

    @Test
    fun `a retirement invalidates the outstanding capture through the real shared fence - transfer after take`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val clock = VirtualClock()
        publish(policyProvider(clock.now), enabled = true, stamp = clock.now.get(), generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner)
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        // The production fence SHAPE: one shared transaction lock behind the FenceGate, exactly as
        // VoiceObservationModule composes it over the credential owner's transaction.
        val fence = Any()
        val txGate = object : FenceGate {
            override fun <R> gate(block: () -> R): R = synchronized(fence) { block() }
        }
        val observer = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@x", 7L, 1L, "epoch-1", "1") },
            fenceGate = txGate,
            allowance = lifecycle,
        )
        val admission = observer.speechStartedAdmitting(
            captureSession = 1L,
            routeClass = "Bluetooth",
            producerSize = 800,
            asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
        )
        assertNotNull("the allowance-admitted capture must start", admission)
        // TRANSFER AFTER TAKE: ownership moved to the observer BEFORE the retirement.
        assertTrue(observer.adoptFirstChunk(admission!!, ShortArray(800), 800))
        // The composer's hook: retirement purge enters the SAME shared fence.
        lifecycle.onRetired = { synchronized(fence) { observer.revoked("allowance-retired") } }
        assertTrue(lifecycle.retireCurrent())
        assertEquals(
            "retirement must invalidate the outstanding capture: open work is purged via the real revocation",
            0,
            sink.retainedCount(),
        )
        assertEquals(0L, sink.retainedBytes())
        assertFalse(observer.isOpen(admission.context.captureId))
        assertNull("no further token can be issued from the retired slot", lifecycle.takePrepaidToken())
    }

    @Test
    fun `admission NEVER blocks on the lifecycle lock - contention refuses, no deadlock cycle`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val clock = VirtualClock()
        publish(policyProvider(clock.now), enabled = true, stamp = clock.now.get(), generation = 1L)
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        // A retirement in flight holds the lifecycle lock...
        val retirementThread = thread(name = "retirement-in-flight") {
            lifecycle.withLifecycleLockForTest {
                inside.countDown()
                release.await()
            }
        }
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        val pool = Executors.newSingleThreadExecutor()
        try {
            // ...an admission arriving under contention is REFUSED, not queued: the call RETURNS
            // (a blocking take would time out this Future and deadlock against the cycle).
            val refused = pool.submit { lifecycle.takePrepaidToken() }
            assertNull("contention must refuse (tryLock), proving fence→tryLock(lifecycle) never blocks", refused.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        // Deterministic quiescence: the management path has FULLY left the lock before the
        // recovery asserts run.
        retirementThread.join(4_000)
        assertTrue(!retirementThread.isAlive)
        // Once the management path leaves the lock, admission works again (the refused take
        // never consumed the prepared token — take it now, then mint a fresh one).
        assertNotNull(lifecycle.takePrepaidToken())
        assertTrue(owner.prepareToken())
        assertNotNull(lifecycle.takePrepaidToken())
    }

    @Test
    fun `dispose cancels the collection and parked deadlines, detaches only its own chain link, and refuses everything after`() {
        val lifecycle = CaptureAllowanceLifecycle()
        val startup = startup()
        val priorFired = CountDownLatch(1)
        val prior: () -> Unit = { priorFired.countDown() }
        startup.sessionEndedListener = prior
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val p = policyProvider(clock.now)
        val w = watcher(lifecycle, startup, p, scope, clock)
        w.start()
        val owner = provisionedOwner(tmp.newFolder())
        assertTrue(owner.prepareToken())
        lifecycle.bind(owner)
        // Arm a deadline so a callback is PARKED when disposal happens (arming is synchronous
        // under the Unconfined collector: the deadline job parks in the rendezvous sleep).
        publish(p, enabled = true, stamp = clock.now.get(), generation = 1L)

        w.dispose()

        assertSame("dispose must detach ITS OWN chain link, restoring the prior listener", prior, startup.sessionEndedListener)
        assertNull("takes refuse after dispose", lifecycle.takePrepaidToken())
        val genAfterDispose = lifecycle.currentGeneration()
        assertEquals("binds refuse after dispose (generation unchanged)", genAfterDispose, lifecycle.bind(provisionedOwner(tmp.newFolder())))
        assertFalse("retire after dispose must not fire the hook", lifecycle.retireCurrent())
        // NO SURVIVING WATCHER WORK: the parked deadline was cancelled — it never resumes, never
        // drains, never retires anything after disposal.
        runBlocking {
            val leaked = withTimeoutOrNull(400) { clock.sleeps.receive() }
            assertNull("a disposed watcher must have no parked deadline left", leaked)
        }
        // The restored prior listener still fires; the (detached) watcher cannot react.
        startup.sessionEndedListener?.invoke()
        assertTrue(priorFired.await(2, TimeUnit.SECONDS))
        scope.cancel()
    }

    private fun deadlineFor(stamp: Long) = stamp + VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1L

    private fun publish(provider: VoiceSharingPolicyProvider, enabled: Boolean, stamp: Long, generation: Long) {
        provider.applySnapshotForTest(PolicySnapshot(enabled = enabled, activatedAtMonotonicMs = stamp, activationGeneration = generation))
    }

    @Test
    fun `the module binds the lifecycle ITSELF as the authority - composition identity`() {
        val lifecycle = CaptureAllowanceLifecycle()
        assertSame(
            "the observer must consult the SAME slot the lifecycle mutates",
            lifecycle,
            VoiceObservationModule.provideCaptureAllowanceAuthority(lifecycle),
        )
    }
}
