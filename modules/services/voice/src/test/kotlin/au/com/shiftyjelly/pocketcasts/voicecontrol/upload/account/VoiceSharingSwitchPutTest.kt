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
        // Read AFTER the call returns: the dispatcher records the request on its own thread as it
        // answers, so reading any earlier can see the answer before the record.
        val put = requests.single()
        val body = bodies.single()
        // Read AFTER the call returns: the dispatcher records the request on its own thread as it
        // Answers, so reading any earlier can see the answer before the record.
        val put = requests.single()
        val body = bodies.single()

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
    fun `an account switch after the read applied refuses the switch instead of issuing it`() = runTest {
        // The read-first step SUSPENDS, and the owner can change while it runs (reviewer 54627e05):
        // A's GET lands and applies, then B signs in. A's choice must not be sent under B — whose
        // projection may legitimately carry a revision — so the switch is refused and NO PUT leaves.
        assertNull("precondition: nothing has been read", state.projection.value)
        state.onRefreshObserved = { diagnostic ->
            if (diagnostic.kind == VoiceSharingRefreshDiagnostic.Kind.APPLIED) {
                // Deterministically AFTER the GET applied, BEFORE the switch is issued.
                owner.switchAccount(to = "other@example.com", token = "fixture-bearer")
            }
        }
        enqueue(okProjection(enrolled = false, revision = "rev-1"))

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertEquals("the superseded intent must not be issued", VoiceSharingMutationResult.Failure, result)
        assertEquals("the read happened, the credit is not transferred by a PUT", listOf("GET"), requests.map { it.method })
    }

    @Test
    fun `a projection cleared during bearer acquisition refuses the switch instead of an unconditional PUT`() = runTest {
        // The revision can disappear between the read and the freeze: a lifecycle clear empties the
        // projection while the bearer is being acquired. frozen() must refuse rather than emit a
        // SWITCH with no If-Match (reviewer 54627e05).
    fun `a stale revision comes back as 409 with the server's current state and changes nothing`() = runTest {
        // Two devices: this one last read rev-1, another turned sharing off meanwhile and the server
        // Is now at rev-5. The stale request must not overwrite that newer choice; it gets the current
        // Projection instead, which is what the UI shows when it re-asks.
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
        // The precondition is REQUIRED: the server refuses a PUT naming no revision,
        // So the client never sends one. With nothing read yet it GETs the state, then switches.
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
        // Another device chose, which is exactly what the precondition exists to prevent.
        // The GET itself is retried (bounded, transport/5xx only); every attempt is a read, and the
        // Switch never proceeds on a fresh read that never landed.
        enqueue(MockResponse().setResponseCode(503), MockResponse().setResponseCode(503))

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertEquals("no revision means no switch", VoiceSharingMutationResult.Failure, result)
        assertTrue(
            "a PUT must never leave without If-Match: ${requests.map { it.method }}",
            requests.none { it.method == "PUT" },
        )
    }

    @Test
    fun `an account switch after the read applied refuses the switch instead of issuing it`() = runTest {
        // The read-first step SUSPENDS, and the owner can change while it runs:
        // A's GET lands and applies, then B signs in. A's choice must not be sent under B — whose
        // Projection may legitimately carry a revision — so the switch is refused and NO PUT leaves.
        assertNull("precondition: nothing has been read", state.projection.value)
        state.onRefreshObserved = { diagnostic ->
            if (diagnostic.kind == VoiceSharingRefreshDiagnostic.Kind.APPLIED) {
                // Deterministically after the GET applied, before the switch is issued.
                owner.switchAccount(to = "other@example.com", token = "fixture-bearer")
            }
        }
        enqueue(okProjection(enrolled = false, revision = "rev-1"))

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertEquals("the superseded intent must not be issued", VoiceSharingMutationResult.Failure, result)
        assertEquals("the read happened, the credit is not transferred by a PUT", listOf("GET"), requests.map { it.method })
    }

    @Test
    fun `a projection cleared during bearer acquisition refuses the switch instead of an unconditional PUT`() = runTest {
        // The revision can disappear between the read and the freeze: a lifecycle clear empties the
        // Projection while the bearer is being acquired. Frozen() must refuse rather than emit a
        // SWITCH with no If-Match.
    fun `an account switch while the request is in flight refuses it instead of relabelling it`() = runTest {
        seedCurrent(enrolled = false, revision = "rev-1")
        // The bearer is acquired inside the owner transaction that freezes the request, and it
        // suspends: this stands in for a lifecycle clear landing in that window.
        val clearingBearer = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
            override suspend fun currentToken(): String? {
                state.clear()
                return "fixture-bearer"
            }

            override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block("fixture-bearer")
        }
        val guarded = VoiceSharingAccountOperations(
        // The bearer is acquired inside the owner transaction that freezes the request, and it
        // Suspends: this stands in for a lifecycle clear landing in that window.
        val clearingBearer = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
            override suspend fun currentToken(): String? {
                state.clear()
                return "fixture-bearer"
            }

            override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block("fixture-bearer")
        }
        val guarded = VoiceSharingAccountOperations(
        enqueue(okProjection(enrolled = true, revision = "rev-2"))
        var switched = false
        operations = VoiceSharingAccountOperations(
            api = api,
            state = state,
            ownership = owner,
            aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
                // The bearer read is the suspension point between capturing the owner and sending,
                // so the account switch is injected here.
                override suspend fun currentToken(): String? {
                    if (!switched) {
                        switched = true
                        owner.login("user@example.org")
                    }
                    return owner.authoritySnapshot().token?.value
                }

                override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(owner.authoritySnapshot().token?.value)
            },
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
        )

        val result = guarded.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertEquals("no revision at freeze time means no request", VoiceSharingMutationResult.Failure, result)
        assertEquals("no PUT may leave without a precondition", emptyList<String>(), requests.map { it.method })
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

        val result = guarded.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertEquals("no revision at freeze time means no request", VoiceSharingMutationResult.Failure, result)
        assertEquals("no PUT may leave without a precondition", emptyList<String>(), requests.map { it.method })
    }

    @Test
    fun `a switch after a later 409 keeps the exact body and never resends without a precondition`() = runTest {
        // The 409 is terminal and its body is the plain projection (the server's new shape): the
        // Client publishes it and stops. No automatic retry means no conditional-less second PUT.
        seedCurrent(enrolled = true, revision = "rev-1")
        enqueue(
            MockResponse().setResponseCode(409)
                .setBody(projectionJson(enrolled = false, revision = "rev-9", withdrawal = "withdrawn")),
        )

        val result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")

        assertTrue("a superseded owner must not issue the switch", result is VoiceSharingMutationResult.Failure)
        assertEquals("no PUT may leave for the wrong account", 0, requests.count { it.method == "PUT" })
    }
}
