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
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSharingSwitchPutTest {

    private lateinit var server: MockWebServer
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
        val api = VoiceSharingAccountApi.create(
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
            ownership = owner,
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
        revision: String,
        epoch: String? = if (enrolled) "epoch-1" else null,
        disclosure: String? = if (enrolled) "voice-sharing-v1" else null,
        withdrawal: String = if (enrolled) "active" else "not_enrolled",
    ): String = StringBuilder().apply {
        append("{")
        append("\"schema_version\":1,")
        append("\"membership_revision\":\"$revision\",")
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

    private fun okProjection(enrolled: Boolean, revision: String) = MockResponse()
        .setResponseCode(200)
        .setBody(projectionJson(enrolled = enrolled, revision = revision))

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
        // Read AFTER the call returns: the dispatcher records the request on its own thread as it
        // answers, so reading any earlier can see the answer before the record.
        val put = requests.single()
        val body = bodies.single()

        assertTrue("a 200 with the new projection is an applied change", result is VoiceSharingMutationResult.Applied)
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/account/voice-sharing", put.path)
        assertTrue("desired state must be on the wire: $body", body.contains("\"enrolled\":true"))
        assertTrue("the accepted disclosure must be recorded: $body", body.contains("voice-sharing-v1"))
        assertEquals(
            "the PUT body is EXACT: enrolled + disclosure_version and nothing else (spec e3cfc8ae)",
            "{\"enrolled\":true,\"disclosure_version\":\"voice-sharing-v1\"}",
            body,
        )
        assertTrue("the last-read revision must be sent as If-Match: ${put.headers}", put.headers.values("If-Match").contains("rev-1"))
    }

    @Test
    fun `turning sharing off is the same PUT with the desired state false`() = runTest {
        seedCurrent(enrolled = true, revision = "rev-2")
        enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(enrolled = false, revision = "rev-3", withdrawal = "withdrawn")),
        )

        val result = operations.setVoiceSharing(enrolled = false, acceptedDisclosureVersion = null)

        assertTrue("a 200 with the withdrawn projection is an applied change", result is VoiceSharingMutationResult.Applied)
        val put = requests.single()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/account/voice-sharing", put.path)
        val body = bodies.single()
        assertTrue("desired state must be on the wire: $body", body.contains("\"enrolled\":false"))
        assertTrue("no disclosure is claimed when turning sharing off: $body", !body.contains("disclosure"))
        assertEquals("the OFF body is exactly the desired state", "{\"enrolled\":false}", body)
        assertTrue("the last-read revision must be sent as If-Match: ${put.headers}", put.headers.values("If-Match").contains("rev-2"))
    }

    @Test
    fun `repeating the same desired state sends the same request and is applied again`() = runTest {
        // The spec's idempotency claim: a second "on" changes nothing server-side, which is what makes
        // a plain resend after a network failure safe. The client's job is only to send it again,
        // unchanged.
        seedCurrent(enrolled = true, revision = "rev-2")
        enqueue(okProjection(enrolled = true, revision = "rev-2"))

        val first = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")
        assertTrue(first is VoiceSharingMutationResult.Applied)

        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        val second = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")
        assertTrue(second is VoiceSharingMutationResult.Applied)

        assertEquals("both calls must reach the server", 2, requests.size)
        assertEquals("the repeat is BYTE-IDENTICAL — the body carries no per-call id: $bodies", bodies.toList()[0], bodies.toList()[1])
    }

    @Test
    fun `network failure keeps the user's choice and a resend lands the same desired state`() = runTest {
        // A failed switch is NOT a silent success: the caller learns the answer failed, keeps the
        // user's choice locally, and resends. The server sees the same desired state either way.
        seedCurrent(enrolled = false, revision = "rev-1")
        enqueue(MockResponse().setResponseCode(503))
        val failed = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")
        assertEquals("an exhausted 5xx is a failure, never an applied change", VoiceSharingMutationResult.Failure, failed)
        assertEquals("the last known state is untouched by a failure", "rev-1", state.projection.value?.membershipRevision)

        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        val resent = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")
        assertTrue("the resend of the user's choice must apply", resent is VoiceSharingMutationResult.Applied)
        assertEquals("both attempts carry the SAME desired state", bodies.toList()[0], bodies.toList()[1])
    }

    @Test
    fun `a stale revision comes back as 409 with the server's current state and changes nothing`() = runTest {
        // Two devices: this one last read rev-1, another turned sharing off meanwhile and the server
        // is now at rev-5. The stale request must not overwrite that newer choice; it gets the current
        // projection instead, which is what the UI shows when it re-asks.
        seedCurrent(enrolled = true, revision = "rev-1")
        enqueue(
            MockResponse().setResponseCode(409)
                .setBody(projectionJson(enrolled = false, revision = "rev-5", withdrawal = "withdrawn")),
        )

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        val conflict = result as? VoiceSharingMutationResult.MembershipConflict
        assertTrue("a stale revision must surface as a conflict, not an applied change", conflict != null)
        assertEquals("rev-5", conflict!!.currentProjection.membershipRevision)
        assertEquals(
            "the server's newer state is what the app now shows",
            "rev-5",
            state.projection.value?.membershipRevision,
        )
        assertEquals("exactly one attempt: a conflict is terminal", 1, requests.size)
    }

    @Test
    fun `with no revision held the client reads first and the PUT still carries If-Match`() = runTest {
        // The precondition is REQUIRED (spec e3cfc8ae): the server refuses a PUT naming no revision,
        // so the client never sends one. With nothing read yet it GETs the state, then switches.
        assertNull("precondition: nothing has been read", state.projection.value)
        enqueue(
            okProjection(enrolled = false, revision = "rev-1"),
            okProjection(enrolled = true, revision = "rev-2"),
        )

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertTrue("the switch after the read must apply", result is VoiceSharingMutationResult.Applied)
        assertEquals("GET then PUT: exactly two requests", listOf("GET", "PUT"), requests.map { it.method })
        val put = requests.last()
        assertEquals(
            "the revision just read is the precondition: ${put.headers}",
            listOf("rev-1"),
            put.headers.values("If-Match"),
        )
        assertEquals("the GET sends no body", "", bodies.first())
    }

    @Test
    fun `a failed first read refuses the switch instead of sending a PUT without a precondition`() = runTest {
        // No revision and no way to learn one: refuse. An unconditional PUT would override whatever
        // another device chose, which is exactly what the precondition exists to prevent.
        // The GET itself is retried (bounded, transport/5xx only); every attempt is a read, and the
        // switch never proceeds on a fresh read that never landed.
        enqueue(MockResponse().setResponseCode(503), MockResponse().setResponseCode(503))

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertEquals("no revision means no switch", VoiceSharingMutationResult.Failure, result)
        assertTrue(
            "a PUT must never leave without If-Match: ${requests.map { it.method }}",
            requests.none { it.method == "PUT" },
        )
    }

    @Test
    fun `a switch after a later 409 keeps the exact body and never resends without a precondition`() = runTest {
        // The 409 is terminal and its body is the plain projection (the server's new shape): the
        // client publishes it and stops. No automatic retry means no conditional-less second PUT.
        seedCurrent(enrolled = true, revision = "rev-1")
        enqueue(
            MockResponse().setResponseCode(409)
                .setBody(projectionJson(enrolled = false, revision = "rev-9", withdrawal = "withdrawn")),
        )

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertTrue(result is VoiceSharingMutationResult.MembershipConflict)
        assertEquals("exactly one attempt", listOf("PUT"), requests.map { it.method })
        assertEquals("rev-9", state.projection.value?.membershipRevision)
        assertEquals(
            "the retried/resent body is the exact desired state",
            "{\"enrolled\":true,\"disclosure_version\":\"voice-sharing-v1\"}",
            bodies.single(),
        )
    }
}
