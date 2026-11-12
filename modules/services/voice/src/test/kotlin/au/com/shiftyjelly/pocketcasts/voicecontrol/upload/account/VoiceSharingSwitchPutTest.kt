package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class VoiceSharingSwitchPutTest {

    private lateinit var server: MockWebServer
    private lateinit var api: VoiceSharingAccountApi
    private lateinit var state: VoiceSharingAccountState
    private lateinit var owner: TestOwner
    private lateinit var operations: VoiceSharingAccountOperations
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private val bodies = ConcurrentLinkedQueue<String>()

    @Before
    fun setUp() {
        requests.clear()
        bodies.clear()
        queue.clear()
        server = MockWebServer()
        server.start()
        api = VoiceSharingAccountApi.create(
            retrofit2.Retrofit.Builder().baseUrl(server.url("/")),
            okhttp3.OkHttpClient(),
        )

        state = VoiceSharingAccountState()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                bodies.add(request.body.readUtf8())
                return queue.poll() ?: MockResponse().setResponseCode(500)
            }
        }
        owner = TestOwner()
        owner.issueToken("fixture-bearer")
        operations = VoiceSharingAccountOperations(
            api = api,
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = ownerToken(owner),
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun projectionJson(
        enrolled: Boolean,
        epoch: String? = if (enrolled) "epoch-1" else null,
        disclosure: String? = if (enrolled) "voice-sharing-v1" else null,
        withdrawal: String = if (enrolled) "active" else "not_enrolled",
    ): String = StringBuilder().apply {
        append("{")
        append("\"schema_version\":1,")
        append("\"plan\":\"free\",")
        append("\"voice_sharing_enrolled\":$enrolled,")
        append("\"enrollment_epoch\":${epoch?.let { "\"$it\"" } ?: "null"},")
        append("\"accepted_disclosure_version\":${disclosure?.let { "\"$it\"" } ?: "null"},")
        append("\"withdrawal_state\":\"$withdrawal\"}")
    }.toString()
    private fun enqueue(vararg responses: MockResponse) = responses.forEach { answer(it) }
    private fun answer(response: MockResponse) = queue.add(response)
    private val queue = ConcurrentLinkedQueue<MockResponse>()

    private fun okProjection(enrolled: Boolean, revision: String = "unused") = MockResponse()
        .setResponseCode(200)
        .setBody(projectionJson(enrolled = enrolled))

    private suspend fun seedCurrent(enrolled: Boolean, revision: String) {
        answer(okProjection(enrolled, revision))
        assertTrue("the current state must be readable", operations.refreshProjection())
        requests.clear()
        bodies.clear()
    }

    @Test
    fun `turning sharing on is one PUT carrying the desired state and the disclosure version`() = runTest {
        seedCurrent(enrolled = false, revision = "rev-1")
        enqueue(okProjection(enrolled = true, revision = "rev-2"))

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertTrue("the switch must apply", result is VoiceSharingMutationResult.Applied)
        val put = requests.single()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/account/voice-sharing", put.path)
        assertNull("there is no precondition to send", put.headers["If-Match"])
        val body = bodies.single()
        assertTrue("the desired state travels in the body: $body", body.contains("\"enrolled\":true"))
        assertTrue("the accepted disclosure version travels too: $body", body.contains("voice-sharing-v1"))
    }

    @Test
    fun `turning sharing off is the same PUT with the desired state false`() = runTest {
        seedCurrent(enrolled = true, revision = "rev-2")
        enqueue(okProjection(enrolled = false, revision = "rev-3"))

        val result = operations.setVoiceSharing(enrolled = false, acceptedDisclosureVersion = null)

        assertTrue("the withdrawal must apply", result is VoiceSharingMutationResult.Applied)
        val put = requests.single()
        assertEquals("PUT", put.method)
        assertNull("a withdrawal carries no precondition either", put.headers["If-Match"])
        assertTrue("the desired state is false: ${bodies.single()}", bodies.single().contains("\"enrolled\":false"))
    }

    @Test
    fun `repeating the same desired state sends the same request and is applied again`() = runTest {
        seedCurrent(enrolled = true, revision = "rev-2")
        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        assertTrue(operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1") is VoiceSharingMutationResult.Applied)

        val first = bodies.single()

        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        assertTrue(operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1") is VoiceSharingMutationResult.Applied)
        assertEquals("the same choice is the same request: a resend is safe", first, bodies.toList()[1])
    }

    @Test
    fun `network failure keeps the user's choice and a resend lands the same desired state`() = runTest {
        seedCurrent(enrolled = false, revision = "rev-1")
        enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val failed = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")
        assertTrue("a network failure is a failure, not an applied change", failed is VoiceSharingMutationResult.Failure)

        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        val resent = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertTrue("the resend of the user's choice must apply", resent is VoiceSharingMutationResult.Applied)
        assertEquals("both attempts carry the SAME desired state", bodies.toList()[0], bodies.toList()[1])
    }

    @Test
    fun `an account switch while the request is in flight refuses it instead of relabelling it`() = runTest {
        seedCurrent(enrolled = false, revision = "rev-1")
        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        var switched = false
        operations = VoiceSharingAccountOperations(
            api = api,
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
                override suspend fun currentToken(): String? {
                    if (!switched) {
                        switched = true
                        owner.accountName = "user@example.org"
                    }
                    return owner.token?.value
                }
                override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(owner.token?.value)
            },
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
        )
        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertTrue("a superseded owner must not issue the switch", result is VoiceSharingMutationResult.Failure)
        assertEquals("no PUT may leave for the wrong account", 0, requests.count { it.method == "PUT" })
    }
}
