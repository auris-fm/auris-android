package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.Account
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease
import au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity
import au.com.shiftyjelly.pocketcasts.utils.Optional
import io.reactivex.Flowable
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit

/**
 * Full-shape test CredentialOwnershipOwner: mirrors the real owner's contract (one lock, one
 * immutable snapshot, conditional refresh, owner-transaction 401, narrow binding) so the
 * focused controls run against the ACTUAL shape of the production boundary.
 */
private class TestOwner(
    @Volatile var accountName: String? = "user@example.com",
) : CredentialOwnershipOwner {
    override val collectionSharedLock = Any()
    private val lock = Any()
    private var session = 1L
    private var credential = 1L

    @Volatile var token: AccessToken? = null
    var binding: CredentialOwnershipBinding? = null
    private val credentials = VoiceSharingCredentialStore()

    val invalidations = mutableListOf<AuthoritySnapshot>()

    override fun authoritySnapshot(): AuthoritySnapshot = synchronized(lock) {
        AuthoritySnapshot(accountName, session, credential, token)
    }

    override fun installOwnershipBinding(binding: CredentialOwnershipBinding?): AuthoritySnapshot? = synchronized(lock) {
        this.binding = binding
        if (accountName == null || token == null) return null
        binding?.onSessionStarted(session)
        authoritySnapshot()
    }

    override fun beginRefresh(observed: AuthoritySnapshot): RefreshLease? = synchronized(lock) {
        val name = accountName ?: return null
        // ORIGINAL account + session + credential validation; a same-account relogin before
        // admission (session moved) refuses exactly like a cross-account switch.
        if (name != observed.accountName || session != observed.session || credential != observed.credential) return null
        RefreshLease(name, session, credential, RefreshToken("lease-r"), AccountConstants.SignInType.Tokens)
    }

    override fun publishCredentials(observed: AuthoritySnapshot, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = synchronized(lock) {
        if (accountName == null || accountName != observed.accountName ||
            session != observed.session || credential != observed.credential
        ) {
            return false // CONDITIONAL: late password change after relogin/account-switch refuses
        }
        token = accessToken
        credential += 1
        true
    }

    override fun publishRefresh(lease: RefreshLease, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = synchronized(lock) {
        if (accountName != lease.accountName || session != lease.session || credential != lease.credential) return false
        token = accessToken
        credential += 1
        true
    }

    // The mirror-clear exchange class (reviewer 82a9958d): credential lock only.

    override fun <T> credentialTransaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }

    override fun invalidateIfCurrent(observed: AuthoritySnapshot, transaction: () -> Boolean): Boolean = synchronized(lock) {
        val rejected = observed.token ?: return false
        if (accountName != observed.accountName || session != observed.session ||
            credential != observed.credential || token?.value != rejected.value
        ) {
            return false
        }
        if (!transaction()) return false
        token = null
        credential += 1
        invalidations.add(observed)
        true
    }

    override fun reconcileExternalState(): AuthoritySnapshot = synchronized(lock) {
        if (accountName == null && token != null) {
            token = null
            credential += 1
            session += 1
            binding?.onSessionEnded(session)
        }
        authoritySnapshot()
    }

    override fun sessionGeneration(): Long = synchronized(lock) { session }

    // ---- test drive helpers (simulate the owner's real writes) ----

    /** W3-equivalent: owner-published token + credential bump + mirror. */
    fun issueToken(value: String) = synchronized(lock) {
        token = AccessToken(value)
        credential += 1
        credentials.publish("Bearer $value")
    }

    /** W1-equivalent: login/account switch — session + credential incarnations BOTH move. */
    fun login(value: String?) = synchronized(lock) {
        token = value?.let { AccessToken(it) }
        credential += 1
        session += 1
        binding?.onSessionStarted(session)
    }

    /** W1-equivalent session bump without re-arming the binding (test driver). */
    fun simulateAccountSwitch() = synchronized(lock) {
        session += 1
    }

    /** A DIFFERENT person signs in: new account name, new session, new credential. */
    fun switchAccount(to: String, token: String) = synchronized(lock) {
        accountName = to
        this.token = AccessToken(token)
        credential += 1
        session += 1
        binding?.onSessionStarted(session)
    }

    /** W2-equivalent: logout. */
    fun logout() = synchronized(lock) {
        token = null
        credential += 1
        session += 1
        binding?.onSessionEnded(session)
    }

    fun mirrorCurrent(): CredentialIncarnation? = credentials.current()
    val mirrorStore: VoiceSharingCredentialStore get() = credentials
}

/**
 * Task 2 routes/operations controls (real HTTP boundary via MockWebServer):
 *
 * - A current-session GET must succeed before any authority operation: enrollment with no
 *   current projection issues NO network call.
 * - Request bodies follow the contract exactly: enrollment carries a fresh request_id UUID,
 *   expected_membership_revision (the CURRENT revision captured BEFORE the call), and
 *   accepted_disclosure_version; withdrawal carries request_id, expected_revision and the
 *   nullable epoch; deletion is a distinct DELETE returning 202 deletion_pending — withdrawal
 *   is never deletion.
 * - Per-operation identity: a bounded immutable retry reuses the SAME request_id and the SAME
 *   captured revision; the session token is captured BEFORE the await, so a session ended while
 *   the call is in flight cannot have its response applied.
 */
class VoiceSharingAccountOperationsTest {

    private lateinit var server: MockWebServer
    private lateinit var state: VoiceSharingAccountState
    private lateinit var operations: VoiceSharingAccountOperations
    private lateinit var owner: TestOwner
    private val credentials get() = owner.mirrorStore
    private val invalidations = mutableListOf<AuthoritySnapshot>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        state = VoiceSharingAccountState()
        owner = TestOwner()
        // The suites issue a bearer per test; start from a published one so the fixture is
        // credential-complete (the bearer SOURCE is asserted in VoiceSharingAurisBearerTest).
        owner.issueToken("fixture-bearer")
        operations = VoiceSharingAccountOperations(api, state, owner, ownerToken(owner), onCredentialInvalid = { rejected -> invalidations.add(rejected) })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun assertApplied(result: VoiceSharingMutationResult) = assertTrue(result is VoiceSharingMutationResult.Applied)

    /** Owner-published credential (W3-equivalent); the mirror follows. */
    private fun issueBearer(value: String) {
        owner.issueToken(value.removePrefix("Bearer "))
    }

    private fun projectionJson(
        revision: String = "rev-1",
        withdrawal: String = "not_enrolled",
        enrolled: Boolean = false,
        epoch: String? = null,
        disclosure: String? = null,
    ): String = buildString {
        append("{\"schema_version\":1,")
        append("\"membership_revision\":\"$revision\",")
        append("\"plan\":\"free\",")
        append("\"voice_sharing_enrolled\":$enrolled,")
        append("\"enrollment_epoch\":${epoch?.let { "\"$it\"" } ?: "null"},")
        append("\"accepted_disclosure_version\":${disclosure?.let { "\"$it\"" } ?: "null"},")
        append("\"withdrawal_state\":\"$withdrawal\"}")
    }

    private fun enqueueProjection(revision: String = "rev-1") {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson(revision = revision)))
    }

    private fun sentAuthHeaders(): List<String> {
        val headers = mutableListOf<String>()
        while (server.requestCount > headers.size) {
            val r = server.takeRequest(5, TimeUnit.SECONDS) ?: break
            r.headers["Authorization"]?.let { headers.add(it) } ?: break
        }
        return headers
    }

    /** Transport that always throws [HttpException]; no HTTP response object is ever produced. */
    private class ThrowingHttpException(private val code: Int) : VoiceSharingAccountApi {
        var calls = 0

        private fun fail(): Nothing {
            calls++
            throw HttpException(Response.error<ResponseBody>(code, "".toResponseBody(null)))
        }

        override suspend fun getVoiceSharing(bearer: String?): Response<ResponseBody> = fail()

        override suspend fun postEnrollment(bearer: String?, body: okhttp3.RequestBody): Response<ResponseBody> = fail()

        override suspend fun postWithdrawal(bearer: String?, body: okhttp3.RequestBody): Response<ResponseBody> = fail()

        override suspend fun deleteData(bearer: String?, body: okhttp3.RequestBody): Response<ResponseBody> = fail()
    }

    @Test
    fun `current-session GET applies the projection and hits the account route`() = runTest {
        enqueueProjection()
        assertTrue(operations.refreshProjection())
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.startsWith("/api/v1/account/voice-sharing"))
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `enrollment posts the contract body and applies the response`() = runTest {
        // 1. current-session GET first.
        enqueueProjection()
        assertTrue(operations.refreshProjection())

        // 2. Enrollment: request_id UUID, expected_revision = current, disclosure version.
        enqueueProjection(revision = "rev-2")
        assertApplied(operations.enroll(acceptedDisclosureVersion = "v3"))
        server.takeRequest(5, TimeUnit.SECONDS)!! // drain the seeding GET
        val posted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", posted.method)
        assertTrue(posted.path!!.startsWith("/api/v1/account/voice-sharing/enrollment"))
        val body = posted.body.readUtf8()
        assertTrue(body.contains("\"accepted_disclosure_version\":\"v3\""))
        assertTrue(body.contains("\"expected_membership_revision\":\"rev-1\""))
        val requestId = Regex("\"request_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        assertNotNull(UUID.fromString(requestId))
        // The enrollment response (new projection) replaced the GET projection.
        assertEquals("rev-2", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `withdrawal posts revision and epoch - distinct from deletion`() = runTest {
        // Seed a current projection with an epoch; then the withdrawal's own projection.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(epoch = "epoch-9", enrolled = true, disclosure = "v3", withdrawal = "active"),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(revision = "rev-1", withdrawal = "withdrawn", enrolled = false, epoch = "epoch-9"),
            ),
        )
        assertTrue(operations.refreshProjection())

        assertApplied(operations.withdraw())
        server.takeRequest(5, TimeUnit.SECONDS)!! // drain the seeding GET
        val posted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", posted.method)
        assertTrue(posted.path!!.startsWith("/api/v1/account/voice-sharing/withdrawal"))
        val body = posted.body.readUtf8()
        assertTrue(body.contains("\"expected_membership_revision\":\"rev-1\""))
        assertTrue(body.contains("\"enrollment_epoch\":\"epoch-9\""))
        // Withdrawal applies a membership projection; it never deletes data.
        assertEquals(WithdrawalState.WITHDRAWN, state.projection.value!!.withdrawalState)
    }

    @Test
    fun `deletion is a distinct DELETE - 202 deletion_pending parsed, projection untouched`() = runTest {
        // Seed the current projection first; then the deletion's 202 receipt.
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("{\"schema_version\":1,\"deletion_request_id\":\"del-123\",\"status\":\"deletion_pending\"}"),
        )
        assertTrue(operations.refreshProjection())

        val receipt = operations.deleteData()
        assertTrue(receipt != null)
        assertEquals("del-123", receipt!!.deletionRequestId)
        assertEquals("deletion_pending", receipt.status)
        server.takeRequest(5, TimeUnit.SECONDS)!! // drain the seeding GET
        val deleted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", deleted.method)
        assertTrue(deleted.path!!.startsWith("/api/v1/account/voice-sharing/data"))
        assertTrue(deleted.body.readUtf8().contains("\"request_id\":"))
        // Membership projection unchanged by the deletion.
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `deletion receipt cutoff is optional, validated when present, rejected when malformed`() = runTest {
        val receipt202: suspend (String) -> DeletionReceipt? = { body: String ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
            server.enqueue(MockResponse().setResponseCode(202).setBody(body))
            assertTrue(operations.refreshProjection())
            val r = operations.deleteData()
            server.takeRequest(5, TimeUnit.SECONDS)!! // drain the seeding GET
            server.takeRequest(5, TimeUnit.SECONDS)!! // drain the DELETE
            r
        }
        // OPTIONAL present + valid RFC3339: accepted and carried.
        val withCutoff = receipt202(
            "{\"schema_version\":1,\"deletion_request_id\":\"del-1\",\"cutoff\":\"2026-10-31T06:00:00Z\",\"status\":\"deletion_pending\"}",
        )
        assertTrue(withCutoff != null)
        assertEquals("2026-10-31T06:00:00Z", requireNotNull(withCutoff).cutoff)
        // ABSENT: still valid (spec 9f7505d6: missing cutoff remains compatible).
        val withoutCutoff = receipt202(
            "{\"schema_version\":1,\"deletion_request_id\":\"del-2\",\"status\":\"deletion_pending\"}",
        )
        assertTrue(withoutCutoff != null && withoutCutoff.cutoff == null)
        // MALFORMED cutoff: a non-instant value is a malformed receipt, never ignored.
        val malformed = receipt202(
            "{\"schema_version\":1,\"deletion_request_id\":\"del-3\",\"cutoff\":\"not-a-time\",\"status\":\"deletion_pending\"}",
        )
        assertTrue("a malformed cutoff must not yield a receipt", malformed == null)
        // RFC3339Nano fractional seconds and an OFFSET timestamp are real instants: accepted.
        val nano = receipt202(
            "{\"schema_version\":1,\"deletion_request_id\":\"del-5\",\"cutoff\":\"2026-10-31T06:00:00.123456789Z\",\"status\":\"deletion_pending\"}",
        )
        assertTrue(nano != null && nano.cutoff == "2026-10-31T06:00:00.123456789Z")
        val offset = receipt202(
            "{\"schema_version\":1,\"deletion_request_id\":\"del-6\",\"cutoff\":\"2026-10-31T14:00:00+08:00\",\"status\":\"deletion_pending\"}",
        )
        assertTrue(offset != null && offset.cutoff == "2026-10-31T14:00:00+08:00")
        // JSON null / non-string / EMPTY cutoff values: all malformed, never tolerated.
        assertTrue(
            receipt202(
                "{\"schema_version\":1,\"deletion_request_id\":\"del-7\",\"cutoff\":null,\"status\":\"deletion_pending\"}",
            ) == null,
        )
        assertTrue(
            receipt202(
                "{\"schema_version\":1,\"deletion_request_id\":\"del-8\",\"cutoff\":17,\"status\":\"deletion_pending\"}",
            ) == null,
        )
        assertTrue(
            receipt202(
                "{\"schema_version\":1,\"deletion_request_id\":\"del-9\",\"cutoff\":\"\",\"status\":\"deletion_pending\"}",
            ) == null,
        )
        // DUPLICATE cutoff member: the strict decoder refuses duplicate keys.
        assertTrue(
            receipt202(
                "{\"schema_version\":1,\"deletion_request_id\":\"del-10\",\"cutoff\":\"2026-10-31T06:00:00Z\",\"cutoff\":\"2026-11-01T06:00:00Z\",\"status\":\"deletion_pending\"}",
            ) == null,
        )
        // UNKNOWN key: still rejected (the strict key set grows only by the ruled member).
        val unknownKey = receipt202(
            "{\"schema_version\":1,\"deletion_request_id\":\"del-4\",\"status\":\"deletion_pending\",\"surprise\":1}",
        )
        assertTrue("an unknown key must not yield a receipt", unknownKey == null)
    }

    @Test
    fun `a delayed answer for account A never becomes account B's consent`() = runTest {
        enqueueProjection("rev-A")
        assertTrue(operations.refreshProjection())
        // A's enrollment pauses on the wire long enough for a DIFFERENT person to sign in.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(revision = "rev-A-enrolled", withdrawal = "active", enrolled = true, epoch = "epoch-A", disclosure = "v3"))
                .setHeadersDelay(400, TimeUnit.MILLISECONDS),
        )
        val slow = async { operations.enroll(acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(150) } // A's request is now in flight

        owner.switchAccount(to = "other@example.com", token = "token-B")
        assertNotEquals("PREMISE: a different account owns the session now", "user@example.com", owner.authoritySnapshot().accountName)
        val outcome = slow.await()

        assertTrue("A's answer arrived for an account that no longer exists", outcome is VoiceSharingMutationResult.Failure)
        // The GET's own answer still stands (B has published nothing to replace it); what must NOT
        // happen is A's ENROLLMENT landing as B's consent evidence.
        assertEquals("rev-A", state.projection.value?.membershipRevision)
        assertFalse("B must NOT inherit A's enrollment as B's consent", state.projection.value?.voiceSharingEnrolled ?: false)
    }

    @Test
    fun `a delayed answer for a SIGNED-OUT session never republishes consent`() = runTest {
        enqueueProjection("rev-A")
        assertTrue(operations.refreshProjection())
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(revision = "rev-A-enrolled", withdrawal = "active", enrolled = true, epoch = "epoch-A", disclosure = "v3"))
                .setHeadersDelay(400, TimeUnit.MILLISECONDS),
        )
        val slow = async { operations.enroll(acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(150) }

        owner.logout() // the session the request was issued for is gone
        val outcome = slow.await()

        assertTrue(outcome is VoiceSharingMutationResult.Failure)
        assertEquals("rev-A", state.projection.value?.membershipRevision)
        assertFalse("a signed-out session must not regain consent evidence", state.projection.value?.voiceSharingEnrolled ?: false)
    }

    @Test
    fun `bounded immutable retry reuses the same request_id and captured revision`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection())

        server.enqueue(MockResponse().setResponseCode(503)) // retryable
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson(revision = "rev-2")))
        assertApplied(operations.enroll(acceptedDisclosureVersion = "v3"))

        server.takeRequest(5, TimeUnit.SECONDS)!! // the GET
        val attempt1 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val attempt2 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val id1 = Regex("\"request_id\":\"([^\"]+)\"").find(attempt1.body.readUtf8())!!.groupValues[1]
        val body2 = attempt2.body.readUtf8()
        val id2 = Regex("\"request_id\":\"([^\"]+)\"").find(body2)!!.groupValues[1]
        assertEquals(id1, id2)
        assertTrue(body2.contains("\"expected_membership_revision\":\"rev-1\""))
    }

    @Test
    fun `exhausted 5xx on the HttpException path is UNAVAILABLE, not an auth rejection`() = runTest {
        // The transport REALLY throws HttpException twice (no Response object is ever produced).
        val throwing = ThrowingHttpException(503)
        val observed = mutableListOf<VoiceSharingRefreshDiagnostic>()
        val ops = VoiceSharingAccountOperations(throwing, state, owner, ownerToken(owner), onCredentialInvalid = { rejected -> invalidations.add(rejected) })
        issueBearer("Bearer token-a")
        state.onRefreshObserved = { observed += it }
        assertFalse(ops.refreshProjection())
        assertEquals("the transport really threw, twice", 2, throwing.calls)
        assertTrue("a 5xx must never invalidate credentials", invalidations.isEmpty())
        assertEquals("Bearer token-a", credentials.current()?.bearer)
        assertEquals(503, observed.single().status) // the REAL status, not null
        assertTrue("must read as unavailable", observed.single().detail.contains("UNAVAILABLE"))
        assertFalse("must not read as an auth rejection", observed.single().detail.contains("401"))
    }

    @Test
    fun `server correlation ids are captured per HTTP attempt and bounded`() = runTest {
        issueBearer("Bearer token-a")
        server.enqueue(MockResponse().setResponseCode(503).setHeader("X-Request-Id", "id-one"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()).setHeader("X-Request-Id", "id-two"))
        val observed = mutableListOf<VoiceSharingRefreshDiagnostic>()
        state.onRefreshObserved = { observed += it }
        assertTrue(operations.refreshProjection())
        // One id per attempt, in call order: the retried call and the accepted one both report.
        assertEquals(listOf("id-one", "id-two"), observed.single().requestIds)
    }

    @Test
    fun `malformed operation response fails closed without applying`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection())
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"schema_version\":2}"))
        assertTrue(operations.withdraw() is VoiceSharingMutationResult.Failure)
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
    }
}

/**
 * The account boundary's ordinary lifecycle reaction: mirror the credential, drop the projection
 * on a session end. No identity, tokens or tickets of its own (spec 06392988).
 */
class VoiceSharingAccountSessionCoordinatorTest {

    @Test
    fun `session start mirrors the published credential - no identity is created`() {
        val state = VoiceSharingAccountState()
        val credentials = VoiceSharingCredentialStore()
        VoiceSharingAccountSessionCoordinator(state, credentials).onSessionStarted("Bearer token-a")
        assertEquals("Bearer token-a", credentials.current()!!.bearer)
        assertNull(state.projection.value)
    }

    @Test
    fun `session start without a credential clears the mirror`() {
        val state = VoiceSharingAccountState()
        val credentials = VoiceSharingCredentialStore()
        val coordinator = VoiceSharingAccountSessionCoordinator(state, credentials)
        coordinator.onSessionStarted("Bearer token-a")
        coordinator.onSessionStarted(null)
        assertNull(credentials.current())
    }

    @Test
    fun `session end clears the credential AND drops the projection`() {
        val state = VoiceSharingAccountState()
        val credentials = VoiceSharingCredentialStore()
        val coordinator = VoiceSharingAccountSessionCoordinator(state, credentials)
        coordinator.onSessionStarted("Bearer token-a")
        state.publish(state.parseProjection(projectionJson())!!)
        coordinator.onSessionEnded()
        assertNull(credentials.current())
        assertNull("consent evidence dies with the session", state.projection.value)
    }
}

@RunWith(RobolectricTestRunner::class) // android.accounts.Account needs the Robolectric shadow
class VoiceSharingAccountAuthBindingTest {

    @Test
    fun `install adopts the owner snapshot and arms the session UNDER the owner lock`() {
        val state = VoiceSharingAccountState()
        val credentials = VoiceSharingCredentialStore()
        val owner = TestOwner()
        owner.issueToken("real-token-1")
        val binding = VoiceSharingAccountAuthBinding(owner, VoiceSharingAccountSessionCoordinator(state, credentials))
        val snapshot = binding.install()
        assertEquals("real-token-1", snapshot!!.token!!.value)
        assertEquals("Bearer real-token-1", credentials.current()!!.bearer)
        assertNull(state.projection.value) // install publishes nothing; the GET does
    }

    @Test
    fun `install with NO live account or token is FAIL-CLOSED - no session, no authority`() {
        val state = VoiceSharingAccountState()
        val credentials = VoiceSharingCredentialStore()
        val owner = TestOwner(accountName = null) // no account
        val binding = VoiceSharingAccountAuthBinding(owner, VoiceSharingAccountSessionCoordinator(state, credentials))
        assertNull(binding.install())
        assertNull(credentials.current())
    }
}

/**
 * Suites whose subject is the OWNERSHIP boundary (issuance, fencing, scoped 401) rather than which
 * credential is presented use this provider, which mirrors whatever the owner published so every
 * existing control keeps its meaning. The bearer SOURCE is asserted in
 * [VoiceSharingAurisBearerTest], where the two values are deliberately different.
 */
internal fun ownerToken(owner: CredentialOwnershipOwner): au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
    override suspend fun currentToken(): String? = owner.authoritySnapshot().token?.value

    @Suppress("RemoveRedundantCallsOfConversionMethods")
    override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(owner.authoritySnapshot().token?.value)
}
