package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * THE OFF-TAP LOCAL RECORD CONTROLS (reviewer-required).
 *
 * The saved WITHDRAWN record is now THE local stop. These three controls pin the properties the
 * deleted LocalWithdrawalIntentStore used to provide, now resting on the durable choice record:
 *
 *  a. An off tap writes WITHDRAWN immediately, BEFORE the server answer resolves.
 *  b. That record STANDS when the withdrawal request FAILS and when offline.
 *  c. A stale ENROLLED refresh cannot release it: enrolledChoiceFor stays null.
 */
class OffTapLocalRecordControlTest {

    private val account = "user@example.com"

    private fun store() = SavedSharingChoiceStore(InMemoryChoicePersistence())

    private fun controller(
        store: SavedSharingChoiceStore,
        withdrawAnswer: CompletableDeferred<VoiceSharingMutationResult>,
        withdrawInvoked: CompletableDeferred<Unit>,
    ): EnrollmentController = EnrollmentController(
        enrollOperation = { _, _ -> VoiceSharingMutationResult.Failure },
        withdrawOperation = { _ ->
            withdrawInvoked.complete(Unit)
            withdrawAnswer.await()
        },
        deleteOperation = { false },
        clearLocalPending = {},
        recordLocalWithdrawal = { store.recordWithdrawn(account) },
        ownerSession = { 1L },
    )

    @Test
    fun `an off tap writes the WITHDRAWN record before the server answer resolves`() = runBlocking {
        val store = store()
        assertTrue("PREMISE: enrolled with provenance", store.recordEnrolled(account, "epoch-1", EnrollmentDisclosure.VERSION))
        val withdrawInvoked = CompletableDeferred<Unit>()
        val withdrawAnswer = CompletableDeferred<VoiceSharingMutationResult>()
        val controller = controller(store, withdrawAnswer, withdrawInvoked)

        val outcome = async { controller.withdraw() }
        withdrawInvoked.await()

        // The server answer is STILL pending: the durable record must already be the local stop.
        assertEquals(
            "the WITHDRAWN record is written at tap time, before the answer",
            SharingChoiceState.WITHDRAWN,
            store.read(account)?.state,
        )
        assertNull("and admission refuses on the same record", store.enrolledChoiceFor(account))

        withdrawAnswer.complete(
            VoiceSharingMutationResult.Applied(
                VoiceSharingAccountState().parseProjection(
                    "{\"schema_version\":1,\"plan\":\"free\",\"voice_sharing_enrolled\":false," +
                        "\"enrollment_epoch\":null,\"accepted_disclosure_version\":null," +
                        "\"withdrawal_state\":\"withdrawn\"}",
                )!!,
            ),
        )
        assertTrue(outcome.await() is EnrollmentOutcome.Applied)
        assertNull("the stop still refuses admission after the answer", store.enrolledChoiceFor(account))
    }

    @Test
    fun `a failed withdrawal leaves the WITHDRAWN record standing`() = runBlocking {
        val store = store()
        assertTrue("PREMISE: enrolled with provenance", store.recordEnrolled(account, "epoch-1", EnrollmentDisclosure.VERSION))
        val withdrawInvoked = CompletableDeferred<Unit>()
        val withdrawAnswer = CompletableDeferred<VoiceSharingMutationResult>()
        val controller = controller(store, withdrawAnswer, withdrawInvoked)

        val outcome = async { controller.withdraw() }
        withdrawInvoked.await()
        withdrawAnswer.complete(VoiceSharingMutationResult.Failure)

        assertTrue(outcome.await() is EnrollmentOutcome.Failed)
        assertEquals(
            "a failed server withdrawal must not release the local stop",
            SharingChoiceState.WITHDRAWN,
            store.read(account)?.state,
        )
        assertNull("admission stays refused", store.enrolledChoiceFor(account))
    }

    @Test
    fun `an offline withdrawal leaves the WITHDRAWN record standing`() = runBlocking {
        val store = store()
        assertTrue("PREMISE: enrolled with provenance", store.recordEnrolled(account, "epoch-1", EnrollmentDisclosure.VERSION))
        // Offline is the same failure the operations layer reports: no network answer at all.
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> VoiceSharingMutationResult.Failure },
            withdrawOperation = { _ -> VoiceSharingMutationResult.Failure },
            deleteOperation = { false },
            clearLocalPending = {},
            recordLocalWithdrawal = { store.recordWithdrawn(account) },
            ownerSession = { 1L },
        )

        val outcome = controller.withdraw()

        assertTrue(outcome is EnrollmentOutcome.Failed)
        assertEquals(
            "offline must not release the local stop",
            SharingChoiceState.WITHDRAWN,
            store.read(account)?.state,
        )
        assertNull("admission stays refused", store.enrolledChoiceFor(account))
    }

    @Test
    fun `a stale enrolled refresh cannot turn admission back on`() = runBlocking {
        val store = store()
        assertTrue("PREMISE: enrolled with provenance", store.recordEnrolled(account, "epoch-1", EnrollmentDisclosure.VERSION))
        val withdrawInvoked = CompletableDeferred<Unit>()
        val withdrawAnswer = CompletableDeferred<VoiceSharingMutationResult>()
        val controller = controller(store, withdrawAnswer, withdrawInvoked)

        val outcome = async { controller.withdraw() }
        withdrawInvoked.await()
        assertEquals("PREMISE: the off request is still pending, the stop is already written", SharingChoiceState.WITHDRAWN, store.read(account)?.state)

        // A stale projection/enrolled answer arrives through the operations refresh path while the
        // off request is still pending. It must be discarded: a GET never releases a WITHDRAWN stop.
        val state = VoiceSharingAccountState()
        val ops = VoiceSharingAccountOperations(
            api = FakeVoiceSharingApi(
                "{\"schema_version\":1,\"plan\":\"free\",\"voice_sharing_enrolled\":true," +
                    "\"enrollment_epoch\":\"epoch-stale\",\"accepted_disclosure_version\":\"${EnrollmentDisclosure.VERSION}\"," +
                    "\"withdrawal_state\":\"active\"}",
            ),
            state = state,
            currentAccountName = { account },
            aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
                override suspend fun currentToken(): String? = "auris-token"
                override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block("auris-token")
            },
            onCredentialInvalid = {},
            savedSharingChoice = store,
        )
        assertFalse("the stale enrolled refresh must be discarded", ops.refreshProjection())
        assertEquals(
            "the durable local stop stands",
            SharingChoiceState.WITHDRAWN,
            store.read(account)?.state,
        )
        assertNull("the decision refuses admission", store.enrolledChoiceFor(account))

        withdrawAnswer.complete(VoiceSharingMutationResult.Failure)
        assertTrue(outcome.await() is EnrollmentOutcome.Failed)
        assertNull("and it stays refused after the failed answer", store.enrolledChoiceFor(account))
    }

    /** A real Retrofit-shaped API that serves one fixed projection without a server. */
    private class FakeVoiceSharingApi(private val projectionBody: String) : VoiceSharingAccountApi {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        override suspend fun getVoiceSharing(bearer: String?): Response<okhttp3.ResponseBody> =
            Response.success(200, projectionBody.toResponseBody(jsonMediaType))

        override suspend fun putVoiceSharing(bearer: String?, body: okhttp3.RequestBody): Response<okhttp3.ResponseBody> =
            Response.success(200, projectionBody.toResponseBody(jsonMediaType))

        override suspend fun deleteData(bearer: String?): Response<okhttp3.ResponseBody> =
            Response.success(204, "".toResponseBody(jsonMediaType))
    }
}
