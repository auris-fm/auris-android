package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

    /** Every reply is a SPEC: an optional latch that parks it, then the response. FIFO order. */
    private class GatedDispatcher : Dispatcher() {
        private data class Spec(val response: MockResponse, val gate: CountDownLatch?)
        private val specs = LinkedBlockingQueue<Spec>()

        fun enqueue(response: MockResponse, gate: CountDownLatch? = null) {
            specs.add(Spec(response, gate))
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val spec = specs.poll(10, TimeUnit.SECONDS) ?: return MockResponse().setResponseCode(500)
            spec.gate?.await(10, TimeUnit.SECONDS)
            return spec.response
        }
    }

    private val gated get() = server.dispatcher as GatedDispatcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = GatedDispatcher()
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
        gated.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-1"),
            ),
        )
        runBlocking { operations.refreshProjection() }
        // DRAIN AND ASSERT the seed GET (Reviewer on `561d3c5b6e`): it must not be mistaken for a
        // mutation PUT by any later takeRequest.
        val seed = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", seed.method)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a superseded enrollment's answer cannot republish consent after a withdrawal applied`() = runBlocking {
        // The enroll PUT is PARKED AT A LATCH: the withdrawal will be issued and complete while it
        // is in flight. No sleeps — the withdrawal cannot start until the enroll PUT has arrived,
        // and its answer cannot apply until the test releases the gate.
        val enrollGate = CountDownLatch(1)
        gated.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-tap", disclosure = "v3")),
            gate = enrollGate,
        )
        val enroll = async(Dispatchers.IO) { controller.enroll(EnrollmentDisclosure.VERSION) }
        val enrollPut = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", enrollPut.method) // the enroll PUT reached the server and is parked

        // The withdrawal runs and applies NOW, while the enroll answer is still parked.
        gated.enqueue(
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

        enrollGate.countDown() // the superseded enrollment's answer is released
        enroll.await()

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

    /**
     * THE MIRROR (spec's bot finding on `561d3c5b6e`): off then ON, with the late WITHDRAW answer
     * landing after the newer enroll applied. A superseded withdrawal's answer must not republish
     * WITHDRAWN — neither in the projection nor over the newer ENROLLED durable record.
     */
    @Test
    fun `a superseded withdrawal's answer cannot republish withdrawn over a newer enrollment`() = runBlocking {
        // Seed ENROLLED so the withdraw tap is a real on→off.
        gated.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-1", disclosure = "v3"),
            ),
        )
        runBlocking { operations.refreshProjection() }
        val seed = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", seed.method) // drained: the second PUT cannot be confused with the seed

        // The withdraw PUT is PARKED AT A LATCH; the enroll will be issued and complete while it is
        // in flight. The gate — not a sleep — holds the withdraw answer in flight.
        val withdrawGate = CountDownLatch(1)
        gated.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(projectionJson(withdrawal = "withdrawn", enrolled = false, epoch = "epoch-off")),
            gate = withdrawGate,
        )
        val withdraw = async(Dispatchers.IO) { controller.withdraw() }
        val withdrawPut = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", withdrawPut.method) // the withdraw PUT reached the server and is parked

        // The enroll runs and applies NOW, while the withdraw answer is still parked.
        gated.enqueue(
            MockResponse().setResponseCode(200).setBody(
                projectionJson(withdrawal = "active", enrolled = true, epoch = "epoch-on", disclosure = "v3"),
            ),
        )
        val outcome = controller.enroll(EnrollmentDisclosure.VERSION)
        assertTrue("PREMISE: the enrollment applied", outcome is EnrollmentOutcome.Applied)
        val account = owner.authoritySnapshot().accountName!!
        assertEquals(
            "PREMISE: the durable choice is enrolled",
            SharingChoiceState.ENROLLED,
            saved.read(account)?.state,
        )
        assertEquals("epoch-on", saved.read(account)?.epoch)

        withdrawGate.countDown() // the superseded withdrawal's answer is released
        withdraw.await()

        assertEquals(
            "a superseded withdrawal's answer must not republish withdrawn in the projection",
            WithdrawalState.ACTIVE,
            state.projection.value!!.withdrawalState,
        )
        assertEquals(
            "and it must not overwrite the newer ENROLLED durable record",
            SharingChoiceState.ENROLLED,
            saved.read(account)?.state,
        )
        assertEquals("epoch-on", saved.read(account)?.epoch)
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
