package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * THE INTEGRATED TAP-ORDERING CONTROL (Reviewer on spec's out-of-order case, at `c2ce670cc7`).
 * `EnrollmentController.withdraw()` deliberately does NOT queue behind an in-flight enrollment —
 * the user's stop must never wait — so two PUTs CAN be in flight at once. The fence's supersession
 * must therefore reach the operations' apply: a superseded enrollment's answer must not publish the
 * projection or persist the durable choice AFTER the withdrawal landed.
 *
 * Sequence: enroll PUT parked at the server → withdrawal issued and fully applied (its answer
 * applied, durable choice WITHDRAWN) → the parked enroll answer is released. The enrollment was
 * superseded the moment the withdrawal was issued, so its answer must be discarded: the projection
 * and the durable record stay withdrawn and capture stays off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntegratedTapOrderingTest {

    private lateinit var server: MockWebServer
    private lateinit var owner: TestOwner
    private lateinit var state: VoiceSharingAccountState
    private lateinit var operations: VoiceSharingAccountOperations
    private lateinit var controller: EnrollmentController
    private lateinit var saved: SavedSharingChoiceStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        owner = TestOwner()
        state = VoiceSharingAccountState()
        saved = SavedSharingChoiceStore(InMemoryChoicePersistence())
        operations = VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
            state = state,
            ownership = owner,
            aurisToken = ownerToken(owner),
            onCredentialInvalid = { },
            onAurisCredentialRefused = { },
            savedSharingChoice = saved,
        )
        controller = EnrollmentController(
            enrollOperation = { version, mayApply -> operations.enroll(version, mayApply) },
            withdrawOperation = { mayApply -> operations.withdraw(mayApply) },
            deleteOperation = { false },
            clearLocalPending = { },
            ownerSession = { owner.sessionGeneration() },
        )
        owner.issueToken("token-a")
        // Seed the projection with a withdrawn state so the enroll tap is a real off→on.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-1"),
            ),
        )
        runBlocking { operations.refreshProjection() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a superseded enrollment's answer cannot republish consent after a withdrawal applied`() = runBlocking {
        // The enroll PUT is parked: the withdrawal will be issued and complete while it is in flight.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-tap", disclosure = "v3"))
                .setHeadersDelay(600, TimeUnit.MILLISECONDS),
        )
        val enroll = async(Dispatchers.IO) { controller.enroll(EnrollmentDisclosure.VERSION) }

        withTimeout(5_000) { server.takeRequest() } // the enroll PUT reached the server
        Thread.sleep(150) // the enroll PUT is parked in flight

        // The withdrawal runs and applies NOW, while the enroll answer is still parked.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-tap"),
            ),
        )
        val outcome = controller.withdraw()
        assertTrue("PREMISE: the withdrawal applied", outcome is EnrollmentOutcome.Applied)
        val account = owner.authoritySnapshot().accountName!!
        assertEquals(
            "PREMISE: the durable choice is withdrawn",
            SharingChoiceState.WITHDRAWN,
            saved.read(account)?.state,
        )
        assertEquals(
            "PREMISE: the projection is withdrawn",
            WithdrawalState.WITHDRAWN,
            state.projection.value!!.withdrawalState,
        )

        enroll.await() // the superseded enrollment's answer now arrives

        assertEquals(
            "a superseded enrollment's answer must not republish enrollment in the projection",
            WithdrawalState.WITHDRAWN,
            state.projection.value!!.withdrawalState,
        )
        assertFalse(
            "and it must not re-enable the durable choice",
            saved.read(account)?.state == SharingChoiceState.ENROLLED,
        )
    }

    private fun projectionJson(
        withdrawal: String,
        enrolled: Boolean,
        epoch: String?,
        disclosure: String? = null,
    ): String = buildString {
        append("{\"schema_version\":1,")
        append("\"plan\":\"free\",")
        append("\"voice_sharing_enrolled\":$enrolled,")
        append("\"enrollment_epoch\":${epoch?.let { "\"$it\"" } ?: "null"},")
        append("\"accepted_disclosure_version\":${disclosure?.let { "\"$it\"" } ?: "null"},")
        append("\"withdrawal_state\":\"$withdrawal\"}")
    }
}
