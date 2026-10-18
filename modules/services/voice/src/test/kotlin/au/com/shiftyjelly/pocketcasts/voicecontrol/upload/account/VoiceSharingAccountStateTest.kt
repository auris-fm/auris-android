package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2 red control (plan Task 2; contract guidance 6d975ce7): the membership projection is
 * parsed STRICTLY — schema_version must be 1, every field present, any unknown field fails
 * closed — the projection is keyed on ACCOUNT IDENTITY (a change invalidates the projection and
 * in-flight responses of the obsolete session are dropped), and a token refresh on the SAME
 * identity is not a logout. Membership revisions are opaque server strings: the state exposes
 * them only for equality, never ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSharingAccountStateTest {

    private val identity = MutableStateFlow<String?>("account-1")

    private fun state() = VoiceSharingAccountState(identity = identity)

    private fun validJson(
        revision: String = "rev-abc",
        plan: String = "pro",
        enrolled: Boolean = true,
        epoch: String? = "epoch-1",
        disclosure: String? = "v3",
        withdrawal: String = "active",
    ): String = buildString {
        append("{")
        append("\"schema_version\":1,")
        append("\"membership_revision\":\"$revision\",")
        append("\"plan\":\"$plan\",")
        append("\"voice_sharing_enrolled\":$enrolled,")
        append("\"enrollment_epoch\":${epoch?.let { "\"$it\"" } ?: "null"},")
        append("\"accepted_disclosure_version\":${disclosure?.let { "\"$it\"" } ?: "null"},")
        append("\"withdrawal_state\":\"$withdrawal\"")
        append("}")
    }

    @Test
    fun `valid projection body parses into the full projection`() = runTest {
        val projection = state().parseProjection(validJson())
        assertTrue(projection != null)
        assertEquals("rev-abc", projection!!.membershipRevision)
        assertEquals(VoiceSharingPlan.PRO, projection.plan)
        assertTrue(projection.voiceSharingEnrolled)
        assertEquals("epoch-1", projection.enrollmentEpoch)
        assertEquals("v3", projection.acceptedDisclosureVersion)
        assertEquals(WithdrawalState.ACTIVE, projection.withdrawalState)
    }

    @Test
    fun `free plan with null epoch and not_enrolled parses`() = runTest {
        val projection = state().parseProjection(
            validJson(plan = "free", enrolled = false, epoch = null, disclosure = null, withdrawal = "not_enrolled"),
        )
        assertEquals(VoiceSharingPlan.FREE, projection!!.plan)
        assertNull(projection.enrollmentEpoch)
        assertNull(projection.acceptedDisclosureVersion)
        assertEquals(WithdrawalState.NOT_ENROLLED, projection.withdrawalState)
    }

    @Test
    fun `wrong schema version fails closed`() = runTest {
        val json = validJson().replace("\"schema_version\":1", "\"schema_version\":2")
        assertNull(state().parseProjection(json))
    }

    @Test
    fun `unknown field fails closed`() = runTest {
        val json = validJson().dropLast(1) + ",\"plan_details\":{}}"
        assertNull(state().parseProjection(json))
    }

    @Test
    fun `missing field fails closed`() = runTest {
        val json = validJson().replace("\"voice_sharing_enrolled\":true,", "")
        assertNull(state().parseProjection(json))
    }

    @Test
    fun `invalid enum value fails closed`() = runTest {
        assertNull(state().parseProjection(validJson(plan = "ultra")))
        assertNull(state().parseProjection(validJson(withdrawal = "paused")))
    }

    @Test
    fun `withdrawal and deletion stay distinct states`() = runTest {
        assertEquals(
            WithdrawalState.WITHDRAWN,
            state().parseProjection(validJson(withdrawal = "withdrawn"))!!.withdrawalState,
        )
    }

    @Test
    fun `projection is keyed on account identity - identity change invalidates`() = runTest {
        val state = state()
        state.onProjectionFetched(validJson())
        assertEquals("rev-abc", state.projection.first()!!.membershipRevision)

        identity.value = "account-2"
        assertNull(state.projection.first())
        // A response belonging to the obsolete session is dropped, not applied to the new one.
        assertFalse(state.applyResponseForSession("account-1", validJson(revision = "rev-old")))
    }

    @Test
    fun `token refresh on the same identity is not a logout - projection retained`() = runTest {
        val state = state()
        state.onProjectionFetched(validJson())
        state.onTokenRefresh()
        assertEquals("rev-abc", state.projection.first()!!.membershipRevision)
    }

    @Test
    fun `membership revisions are opaque - equality only, exposed as plain strings`() = runTest {
        val state = state()
        state.onProjectionFetched(validJson(revision = "zzz-later-looking"))
        val projection = state.projection.first()!!
        // Revisions never sort: the API exposes equality with the CURRENT revision only.
        assertTrue(state.isCurrentRevision(projection.membershipRevision))
        assertFalse(state.isCurrentRevision("aaa-earlier-looking"))
        assertFalse(state.isCurrentRevision("zzz-later-looking ") != state.isCurrentRevision("zzz-later-looking"))
    }

    @Test
    fun `current-session GET is required before authority`() = runTest {
        val state = state()
        // No GET has happened: no authority to act on a projection.
        assertNull(state.projection.first())
        assertFalse(state.hasCurrentSessionProjection())
        state.onProjectionFetched(validJson())
        assertTrue(state.hasCurrentSessionProjection())
    }
}
