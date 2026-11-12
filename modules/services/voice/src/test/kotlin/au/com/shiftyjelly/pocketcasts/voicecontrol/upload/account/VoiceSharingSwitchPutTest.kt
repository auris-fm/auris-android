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
/**
 * The sharing switch is ONE desired-state operation (spec: `PUT /api/v1/account/voice-sharing`).
 *
 * What this suite holds:
 *  - turning it on and off are the SAME request shape, differing only in the desired state and the
 *    disclosure version that is required when turning it on;
 *  - repeating a state is a no-op on the wire contract (the idempotency claim the spec makes), so a
 *    retry after a network failure can simply resend what the user asked for;
 *  - the last-read `membership_revision` travels as `If-Match`, and a stale one comes back as 409
 *    with the server's current state rather than overwriting a newer choice.
 * - turning it on and off are the SAME request shape, differing only in the desired state and the
 * disclosure version that is required when turning it on;
 * - repeating a state is a no-op on the wire contract (the idempotency claim the spec makes), so a
 * retry after a network failure can simply resend what the user asked for;
 * - the last-read `membership_revision` travels as `If-Match`, and a stale one comes back as 409
 * with the server's current state rather than overwriting a newer choice.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSharingSwitchPutTest {

    private lateinit var server: MockWebServer
    private lateinit var api: VoiceSharingAccountApi
    private lateinit var state: VoiceSharingAccountState
    private lateinit var owner: TestOwner
    private lateinit var operations: VoiceSharingAccountOperations
    /** The requests the control asserts on, recorded by the dispatcher as it answers. */
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    /** The exact bytes of those requests, in the same order. */
    private val bodies = ConcurrentLinkedQueue<String>()

    @Before
    fun setUp() {
        // Robolectric does not build a fresh instance per test, so every control is reset here. The
        // QUEUE matters most: a leftover answer would be spent by the next test's seeding read.
        requests.clear()
        bodies.clear()
        queue.clear()
        server = MockWebServer()
        server.start()
        api = VoiceSharingAccountApi.create(
            retrofit2.Retrofit.Builder().baseUrl(server.url("/")),
            okhttp3.OkHttpClient(),
        )

        // Answers come from this control's own queue so a test says what each request gets; the
        // production queue is never swapped underneath a running call.
        state = VoiceSharingAccountState()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Recorded before the answer is handed back, so a control that drains after the call
                // has returned always sees it; the body is read here for the same reason.
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
    /** Answers the NEXT requests with the given bodies. */
    private fun enqueue(vararg responses: MockResponse) = responses.forEach { answer(it) }
    /** Answers the next request with [response]: the dispatcher's own queue, in order. */
    private fun answer(response: MockResponse) = queue.add(response)
    /** Stands in for the server's queue so a control can decide what each request gets. */
    private val queue = ConcurrentLinkedQueue<MockResponse>()

    private fun okProjection(enrolled: Boolean, revision: String = "unused") = MockResponse()
        .setResponseCode(200)
        .setBody(projectionJson(enrolled = enrolled))

    private suspend fun seedCurrent(enrolled: Boolean, revision: String) {
        answer(okProjection(enrolled, revision))
        assertTrue("the current state must be readable", operations.refreshProjection())
        // The seeding GET is answered above; its request is not what these controls are about.
        requests.clear()
        bodies.clear()
    }

    @Test
    fun `turning sharing on is one PUT carrying the desired state and the disclosure version`() = runTest {
        seedCurrent(enrolled = false, revision = "rev-1")
        enqueue(okProjection(enrolled = true, revision = "rev-2"))

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertTrue("the switch must apply", result is VoiceSharingMutationResult.Applied)
        // Read AFTER the call returns: the dispatcher records the request on its own thread as it
        // answers, so reading any earlier can see the answer before the record.
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
        // Read AFTER the call returns: the dispatcher records the request on its own thread as it
        // Answers, so reading any earlier can see the answer before the record.
        val put = requests.single()
        assertEquals("PUT", put.method)
        assertNull("a withdrawal carries no precondition either", put.headers["If-Match"])
        assertTrue("the desired state is false: ${bodies.single()}", bodies.single().contains("\"enrolled\":false"))
    }

    @Test
    fun `repeating the same desired state sends the same request and is applied again`() = runTest {
        // The spec's idempotency claim: a second "on" changes nothing server-side, which is what makes
        // a plain resend after a network failure safe. The client's job is only to send it again,
        // unchanged.
        // Theidempotency claim: a second "on" changes nothing server-side, which is what makes
        // A plain resend after a network failure safe. The client's job is only to send it again,
        // Unchanged.
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
        // A failed switch is NOT a silent success: the caller learns the answer failed, keeps the
        // user's choice locally, and resends. The server sees the same desired state either way.
        // A failed switch is NOT a silent success: the caller learns the answer failed, keeps the
        // User's choice locally, and resends. The server sees the same desired state either way.
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
        // The revision can disappear between the read and the freeze: a lifecycle clear empties the
        // Projection while the bearer is being acquired. Frozen() must refuse rather than emit a
        // SWITCH with no If-Match.
    fun `an account switch while the request is in flight refuses it instead of relabelling it`() = runTest {
        seedCurrent(enrolled = false, revision = "rev-1")
        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        var switched = false
        operations = VoiceSharingAccountOperations(
            api = api,
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
                // The bearer read is the suspension point between capturing the owner and sending,
                // so the account switch is injected here.
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
