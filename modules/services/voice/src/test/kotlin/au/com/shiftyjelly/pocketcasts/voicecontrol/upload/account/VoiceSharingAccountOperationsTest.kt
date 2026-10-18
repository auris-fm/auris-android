package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        state = VoiceSharingAccountState()
        operations = VoiceSharingAccountOperations(api, state)
        token = state.sessionStarted()
    }

    @After
    fun tearDown() {
        server.shutdown()
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
        assertFalse(operations.enroll(token, acceptedDisclosureVersion = "v3"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `enrollment posts the contract body and applies the response`() = runTest {
        // 1. current-session GET first.
        enqueueProjection()
        assertTrue(operations.refreshProjection(token))

        // 2. Enrollment: request_id UUID, expected_revision = current, disclosure version.
        enqueueProjection(revision = "rev-2")
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3"))
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

        assertTrue(operations.withdraw(token))
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
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3"))

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
        assertFalse(result.await())
        // The projection was nulled by logout and stays null.
        assertNull(state.projection.value)
    }

    @Test
    fun `malformed operation response fails closed without applying`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"schema_version\":2}"))
        assertFalse(operations.withdraw(token))
        assertEquals("rev-1", state.projection.value!!.membershipRevision)
    }
}
