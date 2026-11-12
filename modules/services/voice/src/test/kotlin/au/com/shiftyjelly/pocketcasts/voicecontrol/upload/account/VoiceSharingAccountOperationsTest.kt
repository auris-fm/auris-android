package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit

/** Minimal account owner for the operations suite: an account name, a session token and a bearer. */
internal class TestOwner(
    @Volatile var accountName: String? = "user@example.com",
) {
    private val lock = Any()
    private var session = 1L
    @Volatile var token: AccessToken? = null
    val invalidations = java.util.concurrent.atomic.AtomicInteger(0)

    fun sessionGeneration(): Long = synchronized(lock) { session }

    fun issueToken(value: String) = synchronized(lock) {
        token = AccessToken(value)
    }

    fun switchAccount(to: String, token: String) = synchronized(lock) {
        accountName = to
        this.token = AccessToken(token)
        session += 1
    }

    fun logout() = synchronized(lock) {
        accountName = null
        token = null
        session += 1
    }

}

internal fun ownerToken(owner: TestOwner): au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
    override suspend fun currentToken(): String? = owner.token?.value

    override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(owner.token?.value)
}

class VoiceSharingAccountOperationsTest {

    private lateinit var server: MockWebServer
    private lateinit var state: VoiceSharingAccountState
    private lateinit var operations: VoiceSharingAccountOperations
    private lateinit var owner: TestOwner

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        state = VoiceSharingAccountState()
        owner = TestOwner()
        owner.issueToken("fixture-bearer")
        operations = VoiceSharingAccountOperations(
            api = api,
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = ownerToken(owner),
            onCredentialInvalid = { owner.invalidations.incrementAndGet() },
        )

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
        owner.logout()
        assertFalse(operations.deleteData())
    }

    @Test
    fun `a bearer acquired for A is never rebound to B, even without any suspension mid-request`() = runTest {
        enqueueProjection(epoch = "epoch-A")
        assertTrue(operations.refreshProjection())
        val ownerA = owner
        val aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
            var switchedOnce = false
            override suspend fun currentToken(): String? {
                val value = ownerA.token?.value
                if (!switchedOnce) {
                    switchedOnce = true
                    ownerA.switchAccount(to = "other@example.com", token = "token-B")
                }
                return value // A's bearer, handed over just before B became the owner
            }

            override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(ownerA.token?.value)
        }
        val switched = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = aurisToken,
            onCredentialInvalid = {},
            onAurisCredentialRefused = {},
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-B", disclosure = "v3")))
        val outcome = switched.enroll(acceptedDisclosureVersion = "v3")
        assertEquals("PREMISE: B owns the account now", "other@example.com", owner.accountName)
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
        assertNotEquals("PREMISE: a different account owns the session now", "user@example.com", owner.accountName)
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
        val ops = VoiceSharingAccountOperations(
            api = throwing,
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = ownerToken(owner),
            onCredentialInvalid = { owner.invalidations.incrementAndGet() },
        )
        issueBearer("token-a")
        state.onRefreshObserved = { observed += it }
        assertFalse(ops.refreshProjection())
        assertEquals("the transport really threw, twice", 2, throwing.calls)
        assertTrue("a 5xx must never invalidate credentials", owner.invalidations.get() == 0)
        assertEquals("token-a", owner.token?.value)
        assertEquals(503, observed.single().status) // the REAL status, not null
        assertTrue("must read as unavailable", observed.single().detail.contains("UNAVAILABLE"))
        assertFalse("must not read as an auth rejection", observed.single().detail.contains("401"))
    }

    @Test
    fun `server correlation ids are captured per HTTP attempt and bounded`() = runTest {
        issueBearer("token-a")
        server.enqueue(MockResponse().setResponseCode(503).setHeader("X-Request-Id", "id-one"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()).setHeader("X-Request-Id", "id-two"))
        val observed = mutableListOf<VoiceSharingRefreshDiagnostic>()
        state.onRefreshObserved = { observed += it }
        assertTrue(operations.refreshProjection())
        assertEquals(listOf("id-one", "id-two"), observed.single().requestIds)
    }

    @Test
    fun `a GET issued before a withdrawal and delivered after it never republishes consent`() = runTest {
        val saved = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence(),
        )
        val ops = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = ownerToken(owner),
            onCredentialInvalid = {},
            onAurisCredentialRefused = {},
            savedSharingChoice = saved,
        )
        issueBearer("token-a")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-stale", disclosure = "v3"))
                .setHeadersDelay(500, TimeUnit.MILLISECONDS),
        )
        val staleGet = async(Dispatchers.IO) { ops.refreshProjection() }
        withContext(Dispatchers.IO) { Thread.sleep(150) } // the GET is in flight now
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

    @Test
    fun `a tap's PUT answer wins over a refresh issued before it and delivered before the answer`() = runTest {
        val saved = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence(),
        )
        val ops = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = ownerToken(owner),
            onCredentialInvalid = {},
            onAurisCredentialRefused = {},
            savedSharingChoice = saved,
        )
        issueBearer("token-a")

        val getLatch = CompletableDeferred<Unit>()
        val putLatch = CompletableDeferred<Unit>()
        val staleBody = projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-stale")
        val tapBody = projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-tap", disclosure = "v3")
        val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                requests.add(request.method!!)
                return when (request.method) {
                    "GET" -> {
                        runBlocking { getLatch.await() }
                        MockResponse().setResponseCode(200).setBody(staleBody)
                    }

                    "PUT" -> {
                        runBlocking { putLatch.await() }
                        MockResponse().setResponseCode(200).setBody(tapBody)
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val refresh = async(Dispatchers.IO) { ops.refreshProjection() }
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 5_000
            while (requests.size < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
        val enroll = async(Dispatchers.IO) { ops.enroll(acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 5_000
            while (requests.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
        assertTrue("PREMISE: the tap's PUT is issued while the refresh reply is still parked", requests.toList() == listOf("GET", "PUT"))

        getLatch.complete(Unit)
        refresh.await()
        assertEquals(
            "PREMISE: the older refresh applied",
            "epoch-stale",
            state.projection.value?.enrollmentEpoch,
        )

        putLatch.complete(Unit)
        val outcome = enroll.await()
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
    fun `CONTROL - a GET issued after the withdrawal cannot release the durable local stop`() = runTest {
        val saved = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence(),
        )
        val ops = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = ownerToken(owner),
            onCredentialInvalid = {},
            onAurisCredentialRefused = {},
            savedSharingChoice = saved,
        )
        issueBearer("token-a")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-stale")),
        )
        assertApplied(ops.withdraw())
        assertEquals(
            "PREMISE: the withdrawal is the durable local stop",
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.WITHDRAWN,
            saved.read("user@example.com")?.state,
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-new", disclosure = "v3")),
        )
        assertFalse("a GET must never release the durable local stop into enrollment", ops.refreshProjection())
        assertEquals(
            "the local stop stands until an explicit new opt-in",
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.WITHDRAWN,
            saved.read("user@example.com")?.state,
        )
        assertFalse("and the projection must not claim enrollment", state.projection.value?.voiceSharingEnrolled ?: false)
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
