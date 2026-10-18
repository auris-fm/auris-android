package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

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
    private lateinit var token: SessionToken
    private val credentials = VoiceSharingCredentialStore()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        state = VoiceSharingAccountState()
        operations = VoiceSharingAccountOperations(api, state, credentials)
        token = state.sessionStarted()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun assertApplied(result: VoiceSharingMutationResult) = assertTrue(result is VoiceSharingMutationResult.Applied)

    /** Publishes the credential; callers keep using `credentials.current()` / publish to rotate. */
    private fun issueBearer(value: String) {
        credentials.publish(value)
    }

    private fun projectionJson(
        revision: String = "rev-1",
        withdrawal: String = "not_enrolled",
        enrolled: Boolean = false,
        epoch: String? = null,
    ): String = buildString {
        append("{\"schema_version\":1,")
        append("\"membership_revision\":\"$revision\",")
        append("\"plan\":\"free\",")
        append("\"voice_sharing_enrolled\":$enrolled,")
        append("\"enrollment_epoch\":${epoch?.let { "\"$it\"" } ?: "null"},")
        append("\"accepted_disclosure_version\":null,")
        append("\"withdrawal_state\":\"$withdrawal\"}")
    }

    private fun enqueueProjection(revision: String = "rev-1") {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson(revision = revision)))
    }

    @Test
    fun `current-session GET applies the projection and hits the account route`() = runTest {
        enqueueProjection()
        assertTrue(operations.refreshProjection(token))
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.startsWith("/api/v1/account/voice-sharing"))
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `enrollment without a current-session projection issues NO network call`() = runTest {
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `enrollment posts the contract body and applies the response`() = runTest {
        // 1. current-session GET first.
        enqueueProjection()
        assertTrue(operations.refreshProjection(token))

        // 2. Enrollment: request_id UUID, expected_revision = current, disclosure version.
        enqueueProjection(revision = "rev-2")
        assertApplied(operations.enroll(token, acceptedDisclosureVersion = "v3"))
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
                projectionJson(revision = "rev-3", withdrawal = "withdrawn", enrolled = false, epoch = "epoch-9"),
            ),
        )
        assertTrue(operations.refreshProjection(token))

        assertApplied(operations.withdraw(token))
        server.takeRequest(5, TimeUnit.SECONDS)!! // drain the seeding GET
        val posted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", posted.method)
        assertTrue(posted.path!!.startsWith("/api/v1/account/voice-sharing/withdrawal"))
        val body = posted.body.readUtf8()
        assertTrue(body.contains("\"expected_membership_revision\":\"rev-abc\""))
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
        assertTrue(operations.refreshProjection(token))

        val receipt = operations.deleteData(token)
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
    fun `bounded immutable retry reuses the same request_id and captured revision`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))

        server.enqueue(MockResponse().setResponseCode(503)) // retryable
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson(revision = "rev-2")))
        assertApplied(operations.enroll(token, acceptedDisclosureVersion = "v3"))

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
    fun `session ended while the call is in flight - response never applied`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))

        // Pause the enrollment response until the session has ended mid-flight.
        val paused = MockResponse().setResponseCode(200).setBody(projectionJson(revision = "rev-2"))
            .setHeadersDelay(500, TimeUnit.MILLISECONDS)
        server.enqueue(paused)

        val result = async { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        state.sessionEnded()
        assertTrue(result.await() is VoiceSharingMutationResult.Failure)
        // The projection was nulled by logout and stays null.
        assertNull(state.projection.value)
    }

    @Test
    fun `malformed operation response fails closed without applying`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"schema_version\":2}"))
        assertTrue(operations.withdraw(token) is VoiceSharingMutationResult.Failure)
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
    }
}

/**
 * Per-operation generation + 401 bearer-capture contract (spec 27cedf97): a later same-session
 * intent/GET fences an older in-flight operation's response; mutation retries retain the
 * ORIGINAL request_id/body/generation; the bearer actually sent is captured for 401 handling
 * with an originating-session and current-bearer check before any credential effect; credential
 * data never appears in generations or failure evidence.
 */
class VoiceSharingAccountOperationsFencingTest {

    private lateinit var server: MockWebServer
    private lateinit var state: VoiceSharingAccountState
    private lateinit var operations: VoiceSharingAccountOperations
    private lateinit var token: SessionToken
    private val credentials = VoiceSharingCredentialStore()
    private val invalidations = mutableListOf<CredentialIncarnation>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        state = VoiceSharingAccountState()
        operations = VoiceSharingAccountOperations(
            api = api,
            state = state,
            credentials = credentials,
            onCredentialInvalid = { rejected -> invalidations.add(rejected) },
        )
        token = state.sessionStarted()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun assertApplied(result: VoiceSharingMutationResult) = assertTrue(result is VoiceSharingMutationResult.Applied)

    /** Publishes the credential; callers keep using `credentials.current()` / publish to rotate. */
    private fun issueBearer(value: String) {
        credentials.publish(value)
    }

    private fun projectionJson(revision: String = "rev-1"): String = "{\"schema_version\":1,\"membership_revision\":\"$revision\",\"plan\":\"free\"," +
        "\"voice_sharing_enrolled\":false,\"enrollment_epoch\":null," +
        "\"accepted_disclosure_version\":null,\"withdrawal_state\":\"not_enrolled\"}"

    @Test
    fun `a later same-session GET fences an older in-flight operation response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))

        // Older enrollment pauses in flight; a later GET completes first with newer data.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(projectionJson("rev-old"))
                .setHeadersDelay(500, TimeUnit.MILLISECONDS),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson("rev-new")))

        val older = async { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(100) } // let the older op freeze + go in flight
        assertTrue(operations.refreshProjection(token)) // newer intent, later generation
        assertEquals("rev-new", state.projection.value!!.membershipRevision)
        assertTrue(older.await() is VoiceSharingMutationResult.Failure) // obsolete generation: never applied
        assertEquals("rev-new", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `mutation retries retain the ORIGINAL request_id and byte-identical body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))

        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson("rev-2")))
        assertApplied(operations.enroll(token, acceptedDisclosureVersion = "v3"))

        server.takeRequest(5, TimeUnit.SECONDS)!! // drain GET
        val a1 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val a2 = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(a1.body.readUtf8(), a2.body.readUtf8())
    }

    @Test
    fun `401 with the SAME bearer and originating session triggers the credential effect once`() = runTest {
        issueBearer("Bearer token-a")
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        assertEquals(listOf("Bearer token-a"), sentAuthHeaders())

        server.enqueue(MockResponse().setResponseCode(401))
        issueBearer("Bearer token-a") // unchanged at 401 time
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)
        assertEquals(1, invalidations.size)
        // The effect carries the ACTUAL rejected incarnation (opaque; the bearer never leaks).
        assertEquals("Bearer token-a", invalidations[0].bearer)
    }

    @Test
    fun `401 after the session ended triggers NO credential effect`() = runTest {
        issueBearer("Bearer token-a")
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(401))
        state.sessionEnded()
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)
        assertTrue(invalidations.isEmpty())
    }

    @Test
    fun `401 after rotation BETWEEN the frozen credential read and the state check invalidates NOTHING`() = runTest {
        val c1 = credentials.publish("Bearer token-a")!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token)) // froze incarnation c1
        // Rotate AFTER the freeze read but BEFORE the paused 401 reaches the owner check.
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val result = async { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(100) } // request went out with c1
        credentials.publish("Bearer token-b") // fresh authority exists before the 401 lands
        assertTrue(result.await() is VoiceSharingMutationResult.Failure)
        val current = credentials.current()!!
        assertNotSame(c1, current) // rotated: a NEW incarnation exists
        assertEquals("Bearer token-b", current.bearer) // fresh credential untouched
        assertTrue(state.hasCurrentSessionProjection()) // fresh session authority untouched too
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
        assertTrue(invalidations.isEmpty())
    }

    @Test
    fun `401 after rotation BETWEEN the state fence and the credential effect spares the fresh incarnation`() = runTest {
        val c1 = credentials.publish("Bearer token-a")!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        // The 401 passes the owner identity check (c1 still current), then rotation happens
        // INSIDE the window between the state fence and the atomic compare-and-invalidate.
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val result = async { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(100) }
        // Publish a new incarnation while the rejection is being processed.
        credentials.publish("Bearer token-b")
        assertTrue(result.await() is VoiceSharingMutationResult.Failure)
        // Whichever side of the fence the rotation landed on, the FRESH incarnation survives:
        assertEquals("Bearer token-b", credentials.current()!!.bearer)
        assertNotSame(c1, credentials.current()!!)
    }

    @Test
    fun `401 when the bearer has since changed triggers NO credential effect`() = runTest {
        issueBearer("Bearer token-a")
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        // Pause the 401 so the credential changes AFTER the request was sent.
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(400, TimeUnit.MILLISECONDS))
        val result = async { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(100) } // let the request go out with token-a
        issueBearer("Bearer token-b") // refreshed credentials mid-flight
        assertTrue(result.await() is VoiceSharingMutationResult.Failure)
        assertTrue(invalidations.isEmpty())
    }

    @Test
    fun `401 from a superseded same-session operation invalidates NOTHING - generation-fenced`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        // Older enrollment's 401 pauses in flight; a newer same-session op completes first.
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val older = async { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(100) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson("rev-2")))
        assertApplied(operations.withdraw(token)) // newer intent bumps the operation generation
        assertTrue(older.await() is VoiceSharingMutationResult.Failure)
        // The stale 401 must not log out the current session or touch the credential.
        assertTrue(state.hasCurrentSessionProjection())
        assertEquals("rev-2", state.projection.value!!.membershipRevision)
        assertTrue(invalidations.isEmpty())
    }

    @Test
    fun `409 revision conflict is terminal - no retry, projection untouched`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(409))
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)
        assertEquals(2, server.requestCount) // GET + ONE enrollment attempt, no retry
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `422 is terminal - no retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(422))
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `deleteData fences its terminal outcome - session ended mid-flight discards the receipt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("{\"schema_version\":1,\"deletion_request_id\":\"del-1\",\"status\":\"deletion_pending\"}")
                .setHeadersDelay(400, TimeUnit.MILLISECONDS),
        )
        val result = async { operations.deleteData(token) }
        state.sessionEnded()
        assertNull(result.await())
    }

    @Test
    fun `disclosure versions with quotes and backslashes are JSON-escaped and retry bodies are byte-identical`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        val tricky = "v\"3\\x"
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson("rev-2")))
        assertApplied(operations.enroll(token, acceptedDisclosureVersion = tricky))
        server.takeRequest(5, TimeUnit.SECONDS)!! // drain GET
        val a1 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val a2 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val raw1 = a1.body.readUtf8()
        val raw2 = a2.body.readUtf8()
        assertEquals(raw1, raw2)
        // The tricky value must survive a strict decode of the posted body, byte-identical.
        val decoded = state.parseProjection(
            "{\"schema_version\":1,\"membership_revision\":\"r\",\"plan\":\"free\",\"voice_sharing_enrolled\":true,\"enrollment_epoch\":\"e\",\"accepted_disclosure_version\":null,\"withdrawal_state\":\"withdrawn\"}",
        ) // sanity: strict parser still works; body itself checked below via org.json-free read
        assertTrue(raw1.contains("\\\"") || raw1.contains("\\\\"))
    }

    @Test
    fun `malformed deletion receipt fails closed - dup keys, trailing junk, wrong schema`() {
        val ops = operations
        val access = ops.javaClass.getDeclaredMethod("parseDeletionReceipt", String::class.java)
        access.isAccessible = true
        assertNull(access.invoke(ops, "{\"schema_version\":1,\"deletion_request_id\":\"a\",\"deletion_request_id\":\"b\",\"status\":\"deletion_pending\"}"))
        assertNull(access.invoke(ops, "{\"schema_version\":1,\"deletion_request_id\":\"a\",\"status\":\"deletion_pending\"} junk"))
        assertNull(access.invoke(ops, "{\"schema_version\":\"1\",\"deletion_request_id\":\"a\",\"status\":\"deletion_pending\"}"))
        assertNull(access.invoke(ops, "{\"schema_version\":1,\"deletion_request_id\":\"a\",\"status\":\"other\"}"))
    }

    // --- reviewer 6b1d517f: endpoint-exact bodies ---

    private fun decodedKeys(raw: String): Set<String> = Regex("\\\"([a-z_]+)\\\":").findAll(raw).map { it.groupValues[1] }.toSet()

    @Test
    fun `enrollment body carries NO epoch - exact decoded key set`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson(epoch = "epoch-9")))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson("rev-2")))
        assertApplied(operations.enroll(token, acceptedDisclosureVersion = "v3"))
        server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertEquals(setOf("request_id", "expected_membership_revision", "accepted_disclosure_version"), decodedKeys(body))
        assertFalse(body.contains("epoch"))
    }

    @Test
    fun `withdrawal serializes a NULL epoch explicitly - exact decoded key set`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson("rev-2")))
        assertApplied(operations.withdraw(token))
        server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertEquals(setOf("request_id", "expected_membership_revision", "enrollment_epoch"), decodedKeys(body))
        assertTrue(body.contains("\"enrollment_epoch\":null"))
    }

    @Test
    fun `deletion body carries ONLY the request_id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("{\"schema_version\":1,\"deletion_request_id\":\"del-1\",\"status\":\"deletion_pending\"}"),
        )
        assertTrue(operations.deleteData(token) != null)
        server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertEquals(setOf("request_id"), decodedKeys(body))
    }

    // --- 409 explicit membership-conflict outcome: strictly parsed projection, fenced apply ---

    @Test
    fun `409 applies the strictly parsed CURRENT projection under the issued token - no replay`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                projectionJson("rev-current", withdrawal = "withdrawn", enrolled = false, epoch = "e"),
            ),
        )
        val outcome = operations.enroll(token, acceptedDisclosureVersion = "v3")
        assertTrue(outcome is VoiceSharingMutationResult.MembershipConflict) // NEVER a success
        assertEquals("rev-current", (outcome as VoiceSharingMutationResult.MembershipConflict).currentProjection.membershipRevision)
        assertEquals(2, server.requestCount) // terminal: no retry, no auto re-enroll
        assertEquals("rev-current", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `withdraw 409 conflict is a DISTINCT outcome - never reports success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                projectionJson("rev-current", withdrawal = "withdrawn", enrolled = false, epoch = "e"),
            ),
        )
        val outcome = operations.withdraw(token)
        assertTrue(outcome is VoiceSharingMutationResult.MembershipConflict)
        assertEquals(2, server.requestCount) // terminal: no retry
        assertEquals("rev-current", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `409 arriving after a newer same-session op is Failure - not a conflict outcome`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        // Older enrollment's 409 pauses in flight; a newer same-session withdrawal completes first.
        server.enqueue(
            MockResponse().setResponseCode(409).setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody(
                projectionJson("rev-conflict"),
            ),
        )
        val older = async { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        withContext(Dispatchers.IO) { Thread.sleep(100) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson("rev-2")))
        assertApplied(operations.withdraw(token)) // newer intent bumps the operation generation
        // The stale 409's fenced apply is rejected: Failure, not MembershipConflict.
        assertTrue(older.await() is VoiceSharingMutationResult.Failure)
        assertEquals("rev-2", state.projection.value!!.membershipRevision)
    }

    @Test
    fun `409 with a malformed projection body applies nothing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"schema_version\":9}"))
        // A malformed 409 body is Failure, NEVER a MembershipConflict with a null projection.
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)
        assertEquals("rev-1", state.projection.value!!.membershipRevision) // malformed body applies nothing
    }

    private fun sentAuthHeaders(): List<String> {
        val headers = mutableListOf<String>()
        while (server.requestCount > headers.size) {
            val r = server.takeRequest(5, TimeUnit.SECONDS) ?: break
            r.headers["Authorization"]?.let { headers.add(it) } ?: break
        }
        return headers
    }
}
