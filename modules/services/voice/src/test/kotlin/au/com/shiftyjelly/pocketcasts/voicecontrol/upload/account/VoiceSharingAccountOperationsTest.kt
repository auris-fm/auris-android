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
internal class TestOwner(
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
    fun issueToken(value: String) = synchronized(lock) {
        token = AccessToken(value)
        credential += 1
        credentials.publish("Bearer $value")
    }
    fun login(value: String?) = synchronized(lock) {
        token = value?.let { AccessToken(it) }
        credential += 1
        session += 1
        binding?.onSessionStarted(session)
    }
    fun simulateAccountSwitch() = synchronized(lock) {
        session += 1
    }
    fun switchAccount(to: String, token: String) = synchronized(lock) {
        accountName = to
        this.token = AccessToken(token)
        credential += 1
        session += 1
        binding?.onSessionStarted(session)
    }
    fun logout() = synchronized(lock) {
        token = null
        credential += 1
        session += 1
        binding?.onSessionEnded(session)
    }
    fun mirrorCurrent(): CredentialIncarnation? = credentials.current()
    val mirrorStore: VoiceSharingCredentialStore get() = credentials
}
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
        owner.issueToken("fixture-bearer")
        operations = VoiceSharingAccountOperations(api, state, owner, ownerToken(owner), onCredentialInvalid = { rejected -> invalidations.add(rejected) })
    }
    @After
    fun tearDown() {
        server.shutdown()
    }
    private fun assertApplied(result: VoiceSharingMutationResult) = assertTrue(result is VoiceSharingMutationResult.Applied)
    private fun issueBearer(value: String) {
        owner.issueToken(value.removePrefix("Bearer "))
    }
    private fun projectionJson(
        withdrawal: String = "not_enrolled",
        enrolled: Boolean = false,
        epoch: String? = null,
        disclosure: String? = null,
    ): String = buildString {
        append("{\"schema_version\":1,")
        append("\"plan\":\"free\",")
        append("\"voice_sharing_enrolled\":$enrolled,")
        append("\"enrollment_epoch\":${epoch?.let { "\"$it\"" } ?: "null"},")
        append("\"accepted_disclosure_version\":${disclosure?.let { "\"$it\"" } ?: "null"},")
        append("\"withdrawal_state\":\"$withdrawal\"}")
    }
    private fun enqueueProjection(epoch: String = "epoch-1", enrolled: Boolean = false) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                if (enrolled) {
                    projectionJson(withdrawal = "active", enrolled = true, epoch = epoch, disclosure = "v3")
                } else {
                    projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = epoch, disclosure = null)
                },
            ),
        )
    }
    private fun sentAuthHeaders(): List<String> {
        val headers = mutableListOf<String>()
        while (server.requestCount > headers.size) {
            val r = server.takeRequest(5, TimeUnit.SECONDS) ?: break
            r.headers["Authorization"]?.let { headers.add(it) } ?: break
        }
        return headers
    }
    private class ThrowingHttpException(private val code: Int) : VoiceSharingAccountApi {
        var calls = 0
        private fun fail(): Nothing {
            calls++
            throw HttpException(Response.error<ResponseBody>(code, "".toResponseBody(null)))
        }
        override suspend fun getVoiceSharing(bearer: String?): Response<ResponseBody> = fail()
        override suspend fun putVoiceSharing(bearer: String?, body: okhttp3.RequestBody): Response<ResponseBody> = fail()
        override suspend fun deleteData(bearer: String?): Response<ResponseBody> = fail()
    }
    @Test
    fun `current-session GET applies the projection and hits the account route`() = runTest {
        enqueueProjection()
        assertTrue(operations.refreshProjection())
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.startsWith("/api/v1/account/voice-sharing"))
        assertEquals(WithdrawalState.WITHDRAWN, state.projection.value!!.withdrawalState)
    }
    @Test
    fun `enrollment posts the contract body and applies the response`() = runTest {
        enqueueProjection()
        assertTrue(operations.refreshProjection())
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-2", disclosure = "v3")),
        )
        assertApplied(operations.enroll(acceptedDisclosureVersion = "v3"))
        server.takeRequest(5, TimeUnit.SECONDS)!! // drain the seeding GET
        val posted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", posted.method)
        assertEquals("/api/v1/account/voice-sharing", posted.path)
        assertTrue("no precondition is sent: ${posted.headers}", posted.headers.values("If-Match").isEmpty())
        assertEquals(
            "the PUT body is exactly the desired state — no request_id, no derived fields",
            "{\"enrolled\":true,\"disclosure_version\":\"v3\"}",
            posted.body.readUtf8(),
        )
        assertEquals("epoch-2", state.projection.value!!.enrollmentEpoch)
    }
    @Test
    fun `withdrawal posts revision and epoch - distinct from deletion`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(epoch = "epoch-9", enrolled = true, disclosure = "v3", withdrawal = "active"),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-9"),
            ),
        )
        assertTrue(operations.refreshProjection())
        assertApplied(operations.withdraw())
        server.takeRequest(5, TimeUnit.SECONDS)!! // drain the seeding GET
        val posted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", posted.method)
        assertEquals("/api/v1/account/voice-sharing", posted.path)
        assertTrue("no precondition is sent: ${posted.headers}", posted.headers.values("If-Match").isEmpty())
        val body = posted.body.readUtf8()
        assertTrue(body.contains("\"enrolled\":false"))
        assertTrue("no disclosure is claimed when turning sharing off: $body", !body.contains("disclosure"))
        assertEquals(WithdrawalState.WITHDRAWN, state.projection.value!!.withdrawalState)
    }
    @Test
    fun `deletion is a synchronous DELETE that answers 204 and leaves the projection untouched`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(operations.refreshProjection())
        assertTrue("a confirmed 204 means the data is deleted", operations.deleteData())
        server.takeRequest(5, TimeUnit.SECONDS)!! // the seeding GET
        val deleted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", deleted.method)
        assertTrue(deleted.path!!.startsWith("/api/v1/account/voice-sharing/data"))
        assertEquals("", deleted.body.readUtf8())
        assertEquals(WithdrawalState.NOT_ENROLLED, state.projection.value!!.withdrawalState)
    }
    @Test
    fun `a deletion the server did not confirm is reported as failure, not assumed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertTrue(operations.refreshProjection())
        assertFalse("an unconfirmed deletion must not be reported as done", operations.deleteData())
    }
    @Test
    fun `deletion requires a bearer and never sends without one`() = runTest {
        assertFalse(operations.deleteData())
    }
    @Test
    fun `a bearer acquired for A is never rebound to B, even without any suspension mid-request`() = runTest {
        enqueueProjection(epoch = "epoch-A")
        assertTrue(operations.refreshProjection())
        lateinit var switched: VoiceSharingAccountOperations
        val ownerA = owner
        val aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
            var switchedOnce = false
            override suspend fun currentToken(): String? {
                val value = ownerA.authoritySnapshot().token?.value
                if (!switchedOnce) {
                    switchedOnce = true
                    ownerA.switchAccount(to = "other@example.com", token = "token-B")
                }
                return value // A's bearer, handed over just before B became the owner
            }
            override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(ownerA.authoritySnapshot().token?.value)
        }
        switched = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            ownership = owner,
            aurisToken = aurisToken,
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-B", disclosure = "v3")))
        val outcome = switched.enroll(acceptedDisclosureVersion = "v3")
        assertEquals("PREMISE: B owns the account now", "other@example.com", owner.authoritySnapshot().accountName)
        assertEquals("no request may carry A's bearer under B's ownership", 1, server.requestCount) // only the seeding GET
        assertTrue("the operation is refused, not rebound", outcome is VoiceSharingMutationResult.Failure)
        assertFalse("nothing may publish consent from that request", state.projection.value?.voiceSharingEnrolled ?: false)
    }
    @Test
    fun `a delayed answer for account A never becomes account B's consent`() = runTest {
        enqueueProjection(epoch = "epoch-A")
        assertTrue(operations.refreshProjection())
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-A", disclosure = "v3"))
                .setHeadersDelay(400, TimeUnit.MILLISECONDS),
        )
        val slow = async { operations.enroll(acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(150) } // A's request is now in flight
        owner.switchAccount(to = "other@example.com", token = "token-B")
        assertNotEquals("PREMISE: a different account owns the session now", "user@example.com", owner.authoritySnapshot().accountName)
        val outcome = slow.await()
        assertTrue("A's answer arrived for an account that no longer exists", outcome is VoiceSharingMutationResult.Failure)
        assertEquals("epoch-A", state.projection.value?.enrollmentEpoch)
        assertFalse("B must NOT inherit A's enrollment as B's consent", state.projection.value?.voiceSharingEnrolled ?: false)
    }
    @Test
    fun `a delayed answer for a SIGNED-OUT session never republishes consent`() = runTest {
        enqueueProjection(epoch = "epoch-A")
        assertTrue(operations.refreshProjection())
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-A", disclosure = "v3"))
                .setHeadersDelay(400, TimeUnit.MILLISECONDS),
        )
        val slow = async { operations.enroll(acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(150) }
        owner.logout() // the session the request was issued for is gone
        val outcome = slow.await()
        assertTrue(outcome is VoiceSharingMutationResult.Failure)
        assertEquals("epoch-A", state.projection.value?.enrollmentEpoch)
        assertFalse("a signed-out session must not regain consent evidence", state.projection.value?.voiceSharingEnrolled ?: false)
    }
    @Test
    fun `bounded immutable retry resends byte-identical bytes and the same precondition`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection())
        server.enqueue(MockResponse().setResponseCode(503)) // retryable
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-2", disclosure = "v3")),
        )
        assertApplied(operations.enroll(acceptedDisclosureVersion = "v3"))
        server.takeRequest(5, TimeUnit.SECONDS)!! // the GET
        val attempt1 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val attempt2 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body1 = attempt1.body.readUtf8()
        val body2 = attempt2.body.readUtf8()
        assertTrue(attempt1.headers.values("If-Match").isEmpty())
        assertTrue(attempt2.headers.values("If-Match").isEmpty())
        assertEquals("the retry body must be byte-identical: the same desired state", body1, body2)
        assertEquals("and the exact contract body", "{\"enrolled\":true,\"disclosure_version\":\"v3\"}", body1)
    }
    @Test
    fun `exhausted 5xx on the HttpException path is UNAVAILABLE, not an auth rejection`() = runTest {
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
        assertEquals(listOf("id-one", "id-two"), observed.single().requestIds)
    }
    /**
     * THE ORDERING CONTROL (Reviewer + spec on the owner's Pixel test). Arrival order is not issue
     * order: a GET issued BEFORE a withdrawal but delivered AFTER it must not republish consent —
     * neither as the projection nor as the durable choice. The companion case proves a GET issued
     * after the withdrawal still applies, so this cannot pass on a frozen state.
     */
    @Test
    fun `a GET issued before a withdrawal and delivered after it never republishes consent`() = runTest {
        val saved = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence(),
        )
        val ops = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            ownership = owner,
            aurisToken = ownerToken(owner),
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
            savedSharingChoice = saved,
        )
        issueBearer("Bearer token-a")
        // The stale GET is issued first and held at the server.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-stale", disclosure = "v3"))
                .setHeadersDelay(500, TimeUnit.MILLISECONDS),
        )
        val staleGet = async(kotlinx.coroutines.Dispatchers.IO) { ops.refreshProjection() }
        withContext(Dispatchers.IO) { Thread.sleep(150) } // the GET is in flight now
        // The withdrawal is issued afterwards and answers first.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-stale")),
        )
        assertApplied(ops.withdraw())
        assertEquals("PREMISE: the withdrawal is the applied state", WithdrawalState.WITHDRAWN, state.projection.value!!.withdrawalState)
        staleGet.await()
        assertEquals(
            "a pre-withdrawal GET arriving late must not republish enrollment",
            WithdrawalState.WITHDRAWN,
            state.projection.value!!.withdrawalState,
        )
        assertFalse(
            "the durable choice must not be re-enabled by the stale GET",
            saved.read("user@example.com")?.state == au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.ENROLLED,
        )
    }

    /**
     * THE OWNER'S TAP (spec follow-up on the Pixel bug): a refresh issued BEFORE the tap but
     * delivered BEFORE the tap's answer must not throw the tap's own successful answer away. The
     * publication-count fence protects STALE REFRESHES, not fresh mutations: a mutation's own answer
     * carries the account/session fence and wins over any publication that merely landed earlier.
     */
    @Test
    fun `a tap's PUT answer wins over a refresh issued before it and delivered before the answer`() = runTest {
        val saved = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence(),
        )
        val ops = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            ownership = owner,
            aurisToken = ownerToken(owner),
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
            savedSharingChoice = saved,
        )
        issueBearer("Bearer token-a")
        // The refresh is issued first and its answer returns while the tap is in flight.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-stale")),
        )
        val refresh = async(kotlinx.coroutines.Dispatchers.IO) { ops.refreshProjection() }
        // DETERMINISTIC ORDER: the GET has reached the server before the tap is issued, so the
        // stale answer cannot be handed to the PUT by dispatch accident.
        server.takeRequest(5, TimeUnit.SECONDS)!!
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-tap", disclosure = "v3")),
        )
        val outcome = ops.enroll(acceptedDisclosureVersion = "v3")
        refresh.await()
        // (No premise on the refresh's own outcome: whichever apply lands first wins the count race,
        // but the invariant under test is only that the tap's answer survives in the end.)
        assertTrue(
            "the tap's own successful answer must not be discarded by the earlier refresh: $outcome",
            outcome is VoiceSharingMutationResult.Applied,
        )
        assertTrue("the surface state is enrolled", state.projection.value?.voiceSharingEnrolled == true)
        assertEquals("epoch-tap", state.projection.value?.enrollmentEpoch)
        assertEquals(
            "the durable choice holds the tap's epoch",
            "epoch-tap",
            saved.read("user@example.com")?.epoch,
        )
    }

    @Test
    fun `CONTROL - a GET issued after the withdrawal and delivered after it does apply`() = runTest {
        val saved = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence(),
        )
        val ops = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            ownership = owner,
            aurisToken = ownerToken(owner),
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
            savedSharingChoice = saved,
        )
        issueBearer("Bearer token-a")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-stale")),
        )
        assertApplied(ops.withdraw())
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-new", disclosure = "v3")),
        )
        assertTrue("a GET issued AFTER the withdrawal must apply", ops.refreshProjection())
        assertTrue("PREMISE: the newer answer re-enrolls", state.projection.value?.voiceSharingEnrolled == true)
    }

    @Test
    fun `malformed operation response fails closed without applying`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection())
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"schema_version\":2}"))
        assertTrue(operations.withdraw() is VoiceSharingMutationResult.Failure)
        assertEquals(WithdrawalState.NOT_ENROLLED, state.projection.value!!.withdrawalState)
    }
}
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
internal fun ownerToken(owner: CredentialOwnershipOwner): au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
    override suspend fun currentToken(): String? = owner.authoritySnapshot().token?.value
    @Suppress("RemoveRedundantCallsOfConversionMethods")
    override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(owner.authoritySnapshot().token?.value)
}
