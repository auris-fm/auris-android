package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Controls for the PRODUCTION install fence: attempt supersession, identity (credential
 * incarnation + session), deadline-at-install, and bind+publication all under ONE owner
 * transaction (reviewer 78acb0b2). The fake owner runs transactions inline against a mutable
 * snapshot, exactly the semantics the real SyncAccountManagerImpl lock provides.
 */
class CaptureAllowanceInstallFenceTest {

    /** The owner-interface contract with real discipline: one monitor, atomic snapshot read. */
    private class LockingOwnership : CredentialOwnershipOwner {
        override val collectionSharedLock = Any()

        @Volatile var snap = AuthoritySnapshot("acct", 10L, 100L, AccessToken("tok"))
        override fun authoritySnapshot(): AuthoritySnapshot = snap
        override fun sessionGeneration(): Long = snap.session

        // The mirror-clear exchange class (reviewer 82a9958d): credential lock only.
        override fun <T> credentialTransaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }

        override fun <T> transaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }
        override fun installOwnershipBinding(binding: CredentialOwnershipBinding?): AuthoritySnapshot? = snap
        override fun beginRefresh(observed: AuthoritySnapshot): RefreshLease? = null
        override fun publishRefresh(lease: RefreshLease, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = false
        override fun publishCredentials(observed: AuthoritySnapshot, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = false
        override fun invalidateIfCurrent(observed: AuthoritySnapshot, transaction: () -> Boolean): Boolean = transaction()
        override fun reconcileExternalState(): AuthoritySnapshot = snap

        fun setSnapshot(session: Long, credential: Long) = synchronized(collectionSharedLock) {
            snap = AuthoritySnapshot("acct", session, credential, AccessToken("tok"))
        }
    }

    private fun countingLifecycle(): Pair<CaptureAllowanceLifecycle, AtomicLong> {
        val lifecycle = CaptureAllowanceLifecycle()
        val binds = AtomicLong(0)
        lifecycle.onBound = { binds.incrementAndGet() }
        return lifecycle to binds
    }

    private val credential = CaptureAllowanceProvisioner.Credential(bearer = "tok", accountId = "acct-1", enrollmentEpoch = "ep-1", generation = 100L, session = 10L)

    private val grant = CaptureAllowanceProvisioner.Grant(batchId = "99999999-8888-4777-8666-555555555555", requestStartElapsedMs = 1_000, validForMs = 300_000)

    private var clock: Long = 2_000

    private var liveBasis: CaptureAllowanceInstallFence.EnrollmentBasis? =
        CaptureAllowanceInstallFence.EnrollmentBasis(enrollmentEpoch = "ep-1", membershipActive = true, policyLive = true)

    /** Set by [fence] so composition controls can read the installed authority it bound. */
    private var lifecycleUnderTest: CaptureAllowanceLifecycle? = null

    private fun fence(ownership: LockingOwnership = LockingOwnership()): Triple<CaptureAllowanceInstallFence, LockingOwnership, AtomicLong> {
        val (lifecycle, binds) = countingLifecycle()
        lifecycleUnderTest = lifecycle
        val f = CaptureAllowanceInstallFence(ownership, lifecycle, { liveBasis }) { clock }
        return Triple(f, ownership, binds)
    }

    private val requestId = "11111111-2222-3333-4444-555555555555"
    private val windowId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val batchId = "99999999-8888-4777-8666-555555555555"
    private val perCapture = 1_920_044L + 65_536L

    private fun goBody(): String = """
        {"schema_version":1,"request_id":"$requestId","window_id":"$windowId",
         "batch_id":"$batchId","enrollment_epoch":"ep-1","disposition":"issued",
         "valid_for_ms":300000,"batch_max_records":4,"batch_max_bytes":${4 * perCapture},
         "max_clip_ms":60000,"max_clip_bytes":1920044,"max_metadata_bytes":65536}
    """.trimIndent()

    private val ceilings = Ceilings(maxDailyRecords = 52, maxDailyBytes = 100L * 1024 * 1024, maxClipBytes = 1_920_044, maxMetadataBytes = 65_536)

    /** Canned server: rewrites the fixture request id with the one actually sent (real echo). */
    private class RecordingTransport(val cannedBody: String, val fixtureRequestId: String) : CaptureAllowanceProvisioner.CaptureAllowanceTransport {
        override suspend fun postCaptureAllowance(bearer: String?, body: okhttp3.RequestBody): Response<okhttp3.ResponseBody> {
            val sent = okio.Buffer().also { body.writeTo(it) }.readUtf8()
            val sentRequestId = sent.substringAfter("\"request_id\":\"").substringBefore("\"")
            val bytes = cannedBody.replace(fixtureRequestId, sentRequestId).toByteArray()
            return Response.success(bytes.toResponseBody("application/json".toMediaType()))
        }
    }

    private fun owner(): CaptureAllowanceOwner = CaptureAllowanceOwner(
        area = kotlin.io.path.createTempDirectory("fence-area").toFile(),
        account = "acct-1:batch",
        limits = AllowanceLimits(
            maxDailyRecords = 4,
            maxDailyBytes = 4 * (1_920_044L + 65_536L),
            maxClipBytes = 1_920_044,
            maxMetadataBytes = 65_536,
            ceilings = Ceilings(maxDailyRecords = 52, maxDailyBytes = 100L * 1024 * 1024, maxClipBytes = 1_920_044, maxMetadataBytes = 65_536),
        ),
        window = AllowanceWindow.Established("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
        provisioning = AllowanceProvisioning.ServerIssued("99999999-8888-4777-8666-555555555555", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
    )

    @Test
    fun `a live attempt installs binds and publishes inside one owner transaction`() {
        val (f, o, lifecycle) = fence()
        f.beginAttempt("a1")
        var published = false
        val installed = f.install(owner(), credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { published = true }
        assertTrue(installed)
        assertTrue(published)
        assertEquals(1, lifecycle.get())
        assertEquals(grant, f.lastInstalledGrant)
    }

    @Test
    fun `a superseded attempt is refused with zero effects`() {
        val (f, _, lifecycle) = fence()
        f.beginAttempt("a1")
        // A newer attempt begins while a1 is still in flight.
        f.beginAttempt("a2")
        var published = false
        assertFalse(f.install(owner(), credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { published = true })
        assertFalse(published)
        assertEquals(0, lifecycle.get())
        assertNull(f.lastInstalledGrant)
    }

    @Test
    fun `a credential incarnation change between request and install is refused`() {
        val (f, o, lifecycle) = fence()
        f.beginAttempt("a1")
        // Rotation while the request was in flight.
        o.setSnapshot(session = 10L, credential = 101L)
        var published = false
        assertFalse(f.install(owner(), credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { published = true })
        assertFalse(published)
        assertEquals(0, lifecycle.get())
        assertNull(f.lastInstalledGrant)
    }

    @Test
    fun `a session rotation between request and install is refused`() {
        val (f, o, lifecycle) = fence()
        f.beginAttempt("a1")
        o.setSnapshot(session = 11L, credential = 100L)
        assertFalse(f.install(owner(), credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { })
        assertEquals(0, lifecycle.get())
    }

    @Test
    fun `a grant that expired in transit is refused at install`() {
        val (f, _, lifecycle) = fence()
        f.beginAttempt("a1")
        clock = 301_000 // request-start anchor 1_000 + 300_000 boundary
        assertFalse(f.install(owner(), credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { })
        assertEquals(0, lifecycle.get())
        clock = 300_999
        assertTrue(f.install(owner(), credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { })
        assertEquals(1, lifecycle.get())
    }

    @Test
    fun `the fence requires observed snapshot values not the credential defaults`() {
        // A credential whose generation/session were not recorded must not match a live
        // snapshot with different values — identity fencing uses exact incarnation equality.
        val (f, _, lifecycle) = fence()
        f.beginAttempt("a1")
        assertFalse(f.install(owner(), 0L, 0L, "ep-1", "a1", grant) { })
        assertEquals(0, lifecycle.get())
    }

    @Test
    fun `an enrollment epoch change refuses install even with identical credentials`() {
        // Withdrawal + re-enroll: same session/credential, new epoch. The batch was fetched
        // under the OLD epoch and must never install.
        val (f, _, lifecycle) = fence()
        f.beginAttempt("a1")
        liveBasis = CaptureAllowanceInstallFence.EnrollmentBasis("ep-2", membershipActive = true, policyLive = true)
        assertFalse(f.install(owner(), credential.generation, credential.session, "ep-1", "a1", grant) { })
        assertEquals(0, lifecycle.get())
        // A grant fetched under the CURRENT epoch installs.
        assertTrue(f.install(owner(), credential.generation, credential.session, "ep-2", "a1", grant) { })
        assertEquals(1, lifecycle.get())
        // Missing basis entirely (never enrolled) refuses.
        liveBasis = null
        assertFalse(f.install(owner(), credential.generation, credential.session, "ep-2", "a1", grant) { })
        assertEquals(1, lifecycle.get())
    }

    @Test
    fun `a WITHDRAWN projection retaining the old epoch string refuses install`() {
        // VoiceSharingAccountState accepts withdrawn projections that keep the epoch string:
        // epoch equality alone must never restore capture authority.
        val (f, _, lifecycle) = fence()
        f.beginAttempt("a1")
        liveBasis = CaptureAllowanceInstallFence.EnrollmentBasis("ep-1", membershipActive = false, policyLive = true)
        assertFalse(f.install(owner(), credential.generation, credential.session, "ep-1", "a1", grant) { })
        assertEquals(0, lifecycle.get())
        // Policy disabled (expired/disabled snapshot) refuses under the same fence.
        liveBasis = CaptureAllowanceInstallFence.EnrollmentBasis("ep-1", membershipActive = true, policyLive = false)
        assertFalse(f.install(owner(), credential.generation, credential.session, "ep-1", "a1", grant) { })
        assertEquals(0, lifecycle.get())
    }

    @Test
    fun `beginAttempt cannot interleave with an in-flight install transaction`() {
        // The registration shares the owner lock: while install's transaction is mid-flight
        // (gated before its checks run), a concurrent beginAttempt BLOCKS on the same lock and
        // can never flip latestAttemptId inside the fence window.
        val ownership = LockingOwnership()
        val (lifecycle, binds) = countingLifecycle()
        val installStarted = java.util.concurrent.CountDownLatch(1)
        val releaseInstall = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.atomic.AtomicBoolean(false)
        // Owner whose transaction pauses mid-transaction while armed, holding the lock.
        val gatedOwnership = object : CredentialOwnershipOwner by ownership {
            override fun <T> transaction(block: () -> T): T = synchronized(ownership.collectionSharedLock) {
                if (gate.get()) {
                    installStarted.countDown()
                    // BOUNDED: a broken gate must not hold the owner lock forever.
                    releaseInstall.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                try {
                    block()
                } finally {
                    // Cleanup on any red-path exit so threads never leak holding the lock.
                    gate.set(false)
                }
            }
        }
        val f = CaptureAllowanceInstallFence(gatedOwnership, lifecycle, { liveBasis }) { clock }
        f.beginAttempt("a1") // unarmed transaction: registers normally
        gate.set(true) // arm the gate ONLY for the install under test
        val installResult = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            installResult.set(f.install(owner(), credential.generation, credential.session, "ep-1", "a1", grant) { })
        }
        try {
            t.start()
            assertTrue(installStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // beginAttempt("a2") must WAIT on the owner lock while a1's transaction is held.
            val regThread = Thread { f.beginAttempt("a2") }
            try {
                regThread.start()
                // PROVE the contender actually attempted the held lock: block until it is
                // observed in BLOCKED state (bounded), so an unlocked beginAttempt cannot
                // slip through by running late.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (regThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                assertEquals(Thread.State.BLOCKED, regThread.state)
            } finally {
                // GUARANTEED termination on any red path: free the install and join both.
                releaseInstall.countDown()
                t.join(5_000)
                regThread.join(5_000)
            }
            assertFalse(regThread.isAlive) // beginAttempt could not complete before install committed
            // a1 installed atomically: no interleaved supersession could stop it.
            assertTrue(installResult.get())
        } finally {
            releaseInstall.countDown() // idempotent belt for earlier exits
            t.join(5_000)
        }
        assertEquals(1, binds.get())
        // Only afterwards does a2 supersede; a replay of a1's install is now refused.
        assertFalse(f.install(owner(), credential.generation, credential.session, "ep-1", "a1", grant) { })
        assertEquals(1, binds.get())
    }

    /** Transport that pauses mid-call so identity can rotate WHILE the response is pending. */
    private class GatedTransport(
        val cannedBody: String,
        val fixtureRequestId: String,
        val entered: java.util.concurrent.CountDownLatch,
        val release: java.util.concurrent.CountDownLatch,
    ) : CaptureAllowanceProvisioner.CaptureAllowanceTransport {
        override suspend fun postCaptureAllowance(bearer: String?, body: okhttp3.RequestBody): Response<okhttp3.ResponseBody> {
            entered.countDown()
            // BOUNDED pause: never wedge the suite on a red path.
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val sent = okio.Buffer().also { body.writeTo(it) }.readUtf8()
            val sentRequestId = sent.substringAfter("\"request_id\":\"").substringBefore("\"")
            val bytes = cannedBody.replace(fixtureRequestId, sentRequestId).toByteArray()
            return Response.success(bytes.toResponseBody("application/json".toMediaType()))
        }
    }

    private fun composedProvisioner(
        ownership: LockingOwnership,
        lifecycle: CaptureAllowanceLifecycle,
        transport: CaptureAllowanceProvisioner.CaptureAllowanceTransport,
    ): CaptureAllowanceProvisioner {
        val fence = CaptureAllowanceInstallFence(ownership, lifecycle, { liveBasis }) { clock }
        return CaptureAllowanceProvisioner(
            api = transport,
            credential = { credential },
            baseArea = kotlin.io.path.createTempDirectory("fence-prov").toFile(),
            lifecycle = lifecycle,
            ceilings = ceilings,
            elapsedMs = { 1_000 },
            beginInstallAttempt = { fence.beginAttempt(it) },
            installAuthority = { owner, gen, session, epoch, attempt, g, publish ->
                fence.install(owner, gen, session, epoch, attempt, g, publish)
            },
            identityStore = CaptureAllowanceAllocationIdentityStore(
                java.io.File(kotlin.io.path.createTempDirectory("fence-identity").toFile(), "allocation_identity"),
            ),
        )
    }

    @Test
    fun `a session rotating WHILE the response is pending refuses at the fence`() = runBlocking {
        val ownership = LockingOwnership()
        val (lifecycle, binds) = countingLifecycle()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val p = composedProvisioner(ownership, lifecycle, GatedTransport(goBody(), requestId, entered, release))
        val provisioned = java.util.concurrent.atomic.AtomicReference<CaptureAllowanceProvisioner.Result?>(null)
        val thread = Thread { runBlocking { provisioned.set(p.provision()) } }
        thread.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Rotate the session WHILE the response is pending — the exact race window.
        ownership.setSnapshot(session = 11L, credential = 100L)
        release.countDown()
        thread.join(10_000)
        assertEquals(
            CaptureAllowanceProvisioner.REASON_IDENTITY_CHANGED,
            (provisioned.get() as CaptureAllowanceProvisioner.Result.Refused).reason,
        )
        assertEquals(0, binds.get())
        assertNull(p.currentGrant)
    }

    @Test
    fun `the same composition with an unchanged session provisions through the real fence`() = runBlocking {
        val ownership = LockingOwnership()
        val (lifecycle, binds) = countingLifecycle()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val p = composedProvisioner(ownership, lifecycle, GatedTransport(goBody(), requestId, entered, release))
        val provisioned = java.util.concurrent.atomic.AtomicReference<CaptureAllowanceProvisioner.Result?>(null)
        val thread = Thread { runBlocking { provisioned.set(p.provision()) } }
        thread.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // NO identity change while pending: the grant installs through the real fence.
        release.countDown()
        thread.join(10_000)
        val result = provisioned.get() as CaptureAllowanceProvisioner.Result.Provisioned
        assertEquals("issued", result.disposition)
        assertEquals(1, binds.get())
        assertEquals(1_000L, p.currentGrant!!.requestStartElapsedMs)
        assertTrue(p.currentGrant!!.isFresh(1_001))
    }

    // ---------------------------------------------------------------------------------------
    // ALLOWANCE DEADLINE AT ADMISSION (reviewer c226fb6f, cloud a3293810)
    //
    // `valid_for_ms` is min(policy freshness, until-midnight-UTC) and is thus INDEPENDENT of the
    // policy lease that installed the authority. These controls hold the policy side live and let
    // only the allowance deadline pass, which is the case an install-time-only check misses.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the INSTALLED authority enforces the allowance deadline - the composition wires it`() {
        // Composition-level, through the REAL fence: this is the control that fails if
        // CaptureAllowanceInstallFence stops wrapping the owner in the deadline guard. The two
        // wrapper-level controls below would keep passing through that regression, which is why
        // this one asserts the wiring rather than the arithmetic.
        val (f, _, binds) = fence()
        f.beginAttempt("a1")
        val installedOwner = owner()
        assertTrue(f.install(installedOwner, credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { })
        assertEquals(1, binds.get())
        // Admission through the PUBLIC path an observer uses -- takePrepaidToken -- so the
        // assertion cannot accidentally hold a reference the composition never installed.
        val installed: CaptureAllowanceAuthority = lifecycleUnderTest!!

        // Inside valid_for_ms (anchored at the grant's request start, 1_000 + 300_000).
        clock = 1_000L + 299_999L
        // A real prepared handoff: the owner only ever hands out a token it has readied.
        assertTrue(installedOwner.prepareToken())
        assertNotNull("inside the allowance window admission must proceed", installed.takePrepaidToken())

        // ONE millisecond past the ALLOWANCE deadline. Policy basis is still live and no timer
        // has fired, so only the installed guard can refuse here.
        clock = 1_000L + 300_000L
        assertTrue("the ledger can still prepare - only the allowance deadline refuses", installedOwner.prepareToken())
        assertNull("the installed authority must refuse past valid_for_ms", installed.takePrepaidToken())
    }

    @Test
    fun `the deadline is anchored at REQUEST start - transit time never extends it`() {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        // Server computed valid_for_ms = 1_000 at issue; the client anchors at its request start.
        val grant = CaptureAllowanceProvisioner.Grant(batchId = "b-2", requestStartElapsedMs = 0L, validForMs = 1_000L)
        var issued = 0
        val ledger = object : CaptureAllowanceAuthority {
            override fun takePrepaidToken(): CaptureAllowanceToken {
                issued++
                return CaptureAllowanceToken(generation = 1L, limitsBinding = "lb", windowBinding = "wb", selectedMaxClipBytes = 1L, selectedMaxMetadataBytes = 1)
            }
        }
        val guarded = CaptureAllowanceDeadlineAuthority(ledger, grant) { clock.get() }
        clock.set(1_000L) // the whole interval elapsed DURING the request; nothing is left
        assertNull("a spent interval refuses immediately, even on a freshly installed authority", guarded.takePrepaidToken())
        assertEquals(0, issued)
    }

    @Test
    fun `across an expiry SEQUENCE every refusal leaves the ledger uncharged`() {
        // cloud ffd363a2: "charged but not admitted" is invisible to the server -- no request, no
        // log line -- so the batch would quietly shrink while the server still believes those
        // records are available. That makes this a LOCAL, EXACT assertion, and it has to hold
        // across the SEQUENCE (expire, attempt, attempt again, retry) rather than one call.
        //
        // Per spec be7941a1, a charge already taken and then abandoned is NOT refunded; what must
        // hold is that an expired authority never takes one in the first place.
        val (f, _, binds) = fence()
        f.beginAttempt("a1")
        val o = owner()
        assertTrue(f.install(o, credential.generation, credential.session, credential.enrollmentEpoch, "a1", grant) { })
        assertEquals(1, binds.get())
        val installed = lifecycleUnderTest!!

        // A fresh area reports Snapshot.Closed (-1) until the first durable charge establishes it;
        // that is "not yet charged", not corrupt (isClosedForTest() tells them apart).
        assertEquals("the area is fresh, not closed", false, o.isClosedForTest())
        // Step 1: inside the window, a preparation charges exactly once and is admitted.
        clock = 1_000L + 100_000L
        assertTrue(o.prepareToken())
        assertEquals("one preparation charges one record", 1, o.ledgerChargesForTest())
        assertNotNull(installed.takePrepaidToken())
        val afterAdmission = o.ledgerChargesForTest()

        // Step 2: PAST the allowance deadline. Repeated attempts, and a retry after each, must
        // not move the ledger at all -- the batch is not the client's to spend once the server's
        // interval is over.
        clock = 1_000L + 300_000L
        repeat(3) {
            assertNull("an expired authority must refuse every attempt", installed.takePrepaidToken())
            assertEquals("a refused attempt must not charge", afterAdmission, o.ledgerChargesForTest())
        }

        // Step 3: the property that matters, stated at the end of the sequence rather than per
        // call: total charged equals what was actually admitted.
        assertEquals("total charged must equal total admitted", 1, o.ledgerChargesForTest())
    }
}
