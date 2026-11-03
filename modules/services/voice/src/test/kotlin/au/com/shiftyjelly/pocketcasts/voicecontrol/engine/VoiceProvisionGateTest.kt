package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceAllocationIdentityStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceLifecycle
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceProvisioner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * reviewer 6a6eec2a gap 3, driven through the REAL gate the engine calls.
 *
 * The gate was re-armed only in the else branch, after it had already been consumed. So at the
 * first exhausted or expired utterance after a successful provision, this attempt skipped the
 * server round trip and the user's capture was refused; only the FOLLOWING utterance could refresh.
 * That is exactly the batch boundary the feature exists to cross, and every existing control tested
 * captures inside one batch, which is why it survived.
 */
class VoiceProvisionGateTest {

    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    private val batchBody = """
        {"schema_version":1,"request_id":"%s","window_id":"22222222-2222-4222-8222-222222222222",
         "batch_id":"11111111-1111-4111-8111-111111111111","enrollment_epoch":"ep-1",
         "disposition":"issued","valid_for_ms":300000,"batch_max_records":4,
         "batch_max_bytes":7942320,"max_clip_ms":60000,"max_clip_bytes":1920044,
         "max_metadata_bytes":65536}
    """.trimIndent()

    /** Counts real round trips through the provisioner's own transport seam. */
    private class CountingApi(private val body: (String) -> String) : CaptureAllowanceProvisioner.CaptureAllowanceTransport {
        var calls = 0
            private set

        override suspend fun postCaptureAllowance(
            bearer: String?,
            body: okhttp3.RequestBody,
        ): Response<okhttp3.ResponseBody> {
            calls++
            val sent = okio.Buffer().also { body.writeTo(it) }.readUtf8()
            val id = sent.substringAfter("\"request_id\":\"").substringBefore("\"")
            return Response.success(this.body(id).toResponseBody())
        }
    }

    private val lifecycleRef = CaptureAllowanceLifecycle()

    private fun provisioner(
        api: CountingApi,
        credential: () -> CaptureAllowanceProvisioner.Credential? = {
            CaptureAllowanceProvisioner.Credential(
                bearer = "tok",
                accountId = "acct-1",
                enrollmentEpoch = "ep-1",
            )
        },
        readinessProvider: () -> Boolean = { true },
        clock: () -> Long,
    ): CaptureAllowanceProvisioner = CaptureAllowanceProvisioner(
        api = api,
        credential = credential,
        readinessProvider = readinessProvider,
        baseArea = tmp.root,
        lifecycle = lifecycleRef,
        ceilings = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.Ceilings(
            maxDailyRecords = 52,
            maxDailyBytes = 100L * 1024 * 1024,
            maxClipBytes = 1_920_044L,
            maxMetadataBytes = 65_536,
        ),
        elapsedMs = clock,
        beginInstallAttempt = { },
        // Mirrors the production fence's bind+publish, which is what makes the composed owner
        // visible to prepareComposedToken().
        installAuthority = { owner, _, _, _, _, _, publish ->
            lifecycleRef.bind(owner)
            publish()
            true
        },
        identityStore = CaptureAllowanceAllocationIdentityStore(File(tmp.root, "identity")),
    )

    @Test
    fun `an EXHAUSTED owner may provision on the SAME utterance that finds it spent`() = runBlocking {
        var now = 1_000L
        val api = CountingApi { id -> batchBody.replace("%s", id) }
        val p = provisioner(api) { now }
        // A completed provision: the gate is consumed, the batch is live and spendable.
        val seed = p.provision()
        assertEquals("setup: provision must succeed, got $seed", true, seed is CaptureAllowanceProvisioner.Result.Provisioned)
        p.prepareComposedToken()
        val firstCalls = api.calls

        // SPEND the batch through the real owner, then close the gate as a refusal would.
        val owner = p.lastComposedOwner!!
        // The prepare above already charged one record and left a token PENDING. Consume it first,
        // or the loop measures "already pending" instead of draining the ledger.
        var drained = if (owner.takePrepaidToken() != null) 1 else 0
        while (owner.prepareToken()) {
            owner.takePrepaidToken()
            drained++
        }
        val gate = AtomicBoolean(false)

        assertEquals(
            "setup: drained=$drained charges=${owner.ledgerChargesForTest()} ownerExhausted=${owner.isExhausted()}",
            true,
            p.composedOwnerExhausted(),
        )
        assertEquals("setup: and not expired", false, p.composedOwnerExpired())
        // THE BOUNDARY: this is the first utterance after the batch is spent. Re-arming must happen
        // from the proven state BEFORE the decision, or this attempt is silently refused.
        VoiceProvisionGate.decide(gate, p)

        assertEquals(
            "the utterance that finds the batch spent must reach the server, not be refused",
            firstCalls + 1,
            api.calls,
        )
    }

    @Test
    fun `an ordinary refusal does not re-arm - one ask, not a spin`() = runBlocking {
        var now = 1_000L
        val api = CountingApi { id -> batchBody.replace("%s", id) }
        val p = provisioner(api) { now }
        // A FAILED provision leaves an owner that is neither spent nor expired.
        p.provision()
        val gate = AtomicBoolean(false)

        VoiceProvisionGate.decide(gate, p)
        VoiceProvisionGate.decide(gate, p)

        assertEquals(
            "a closed gate against a live owner must not re-ask on every utterance",
            1,
            api.calls,
        )
    }

    @Test
    fun `an EXPIRED grant re-arms, because expiry is what the server resolves`() = runBlocking {
        var now = 1_000L
        val api = CountingApi { id -> batchBody.replace("%s", id) }
        val p = provisioner(api) { now }
        p.provision()
        val grant = p.currentGrant!!
        now = grant.requestStartElapsedMs + grant.validForMs + 1 // provably expired
        val gate = AtomicBoolean(false)

        VoiceProvisionGate.decide(gate, p)

        assertEquals("expiry must route to the server", 2, api.calls)
    }

    @Test
    fun `an armed gate provisions once and stays closed`() = runBlocking {
        var now = 1_000L
        val api = CountingApi { id -> batchBody.replace("%s", id) }
        val p = provisioner(api) { now }
        val gate = AtomicBoolean(true)

        VoiceProvisionGate.decide(gate, p)
        VoiceProvisionGate.decide(gate, p)

        assertEquals(1, api.calls)
        assertEquals(false, gate.get())
    }

    @Test
    fun `SIGNED OUT at first utterance, SIGNED IN later - the session resumes`() = runBlocking {
        // reviewer d4cff2e5 #3, spec 1a340261: a signed-out session has NO composed owner, so there
        // is no spent/expired state to re-arm from. The gate closed on the no-credential refusal
        // and nothing re-opened it, so signing in during this engine session could never resume
        // provisioning -- the user would have to restart the app to be helped.
        var signedIn = false
        val api = CountingApi { id -> batchBody.replace("%s", id) }
        val p = provisioner(
            api,
            credential = {
                if (signedIn) {
                    CaptureAllowanceProvisioner.Credential("tok", "acct-1", "ep-1")
                } else {
                    null
                }
            },
            // Sign-in is a change of the PASSIVE state; a probe that never moves would model a user
            // who is signed out and stays that way, not the transition under test.
            readinessProvider = { signedIn },
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)

        VoiceProvisionGate.decide(gate, p)
        assertEquals("signed out: nothing may be sent", 0, api.calls)

        signedIn = true
        // THE RESUMPTION: readiness resolved, which must re-arm the gate for THIS session.
        VoiceProvisionGate.decide(gate, p)
        assertEquals("signing in must resume provisioning without a restart", 1, api.calls)
    }

    @Test
    fun `an ACCOUNT-not-yet-known deferral resumes too, and re-arms only once`() = runBlocking {
        // The Deferred branch had no resumption wiring at all. Both readiness states share one
        // mechanism; consuming the transition keeps it event-driven rather than per-utterance.
        var accountKnown = false
        val api = CountingApi { id -> batchBody.replace("%s", id) }
        val p = provisioner(
            api,
            credential = {
                CaptureAllowanceProvisioner.Credential(
                    bearer = "tok",
                    accountId = if (accountKnown) "acct-1" else "",
                    enrollmentEpoch = "ep-1",
                )
            },
            readinessProvider = { accountKnown },
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)

        assertEquals(
            "an unknown account must defer without sending",
            CaptureAllowanceProvisioner.Result.Deferred,
            VoiceProvisionGate.decide(gate, p),
        )
        assertEquals(0, api.calls)

        accountKnown = true
        VoiceProvisionGate.decide(gate, p)
        assertEquals("the resolved account must resume provisioning", 1, api.calls)
        // Third utterance: the transition was consumed, so no further re-arm.
        VoiceProvisionGate.decide(gate, p)
        assertEquals("readiness resolves ONCE, it is not a standing licence to ask", 1, api.calls)
    }

    @Test
    fun `UNRESOLVED then UNRESOLVED again re-arms NOTHING, so readiness is an event not a retry`() = runBlocking {
        // reviewer f77aaa69 #1: a flag set by failure and cleared by consumption is a retry
        // counter wearing the name of an event (cloud 610bec98). The discriminating observable
        // is the RE-ARM SIGNAL ITSELF, asked directly.
        //
        // Two earlier versions of this control were hollow and I only found out by running them
        // against the regression: decide() consumes the gate via compareAndSet, so gate.get()
        // afterwards is false whether or not it re-armed; and with no credential, provision()
        // returns before sending, so send counts cannot see a retry either.
        val p = provisioner(
            CountingApi { id -> batchBody.replace("%s", id) },
            credential = { null }, // signed out, and stays signed out
            readinessProvider = { false },
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)
        VoiceProvisionGate.decide(gate, p) // establishes NOT_READY as the acted-on state

        repeat(4) { i ->
            assertFalse(
                "readiness UNCHANGED must not signal a re-arm (utterance ${i + 1})",
                p.consumeReadinessTransition(),
            )
        }
    }

    @Test
    fun `repeated unresolved decisions do NOT repeat credential ACQUISITION`() = runBlocking {
        // reviewer 70d0801b: the gate check called readinessNow(), which called the full
        // credential() supplier -- and in production that starts with
        // aurisToken.currentToken(), which can exchange or refresh OVER HTTP. So a CLOSED gate
        // performed credential acquisition on every refused utterance while unresolved. My
        // previous control asserted only on the returned Boolean and never counted supplier calls,
        // which is exactly the scope the finding names.
        var acquisitions = 0
        var peeks = 0
        val p = provisioner(
            CountingApi { id -> batchBody.replace("%s", id) },
            credential = {
                acquisitions++
                null // signed out
            },
            readinessProvider = {
                peeks++
                false // side-effect-free: answers "no" without acquiring anything
            },
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)

        VoiceProvisionGate.decide(gate, p)
        val acquisitionsAfterFirst = acquisitions

        repeat(4) { VoiceProvisionGate.decide(gate, p) }

        // The peek is what a closed gate may consult; it is cheap and side-effect free, so many
        // calls are fine. Acquisition is the expensive, network-reaching act and must not repeat.
        assertTrue("readiness must be consulted through the side-effect-free provider", peeks >= 4)
        assertEquals(
            "a closed gate must not perform credential acquisition on every utterance",
            acquisitionsAfterFirst,
            acquisitions,
        )
    }

    @Test
    fun `cached bearer with NO epoch must not re-arm into a free repeated refusal`() = runBlocking {
        // reviewer cfcfab6f, cloud 650fcc93: the probe checked only the cached bearer while the
        // supplier also requires an authoritative account and an enrollment epoch. With a bearer
        // present and the epoch missing, the supplier returns null every time and records
        // NOT_READY, while a bearer-only probe keeps answering READY -- re-arm, attempt, acquire,
        // refuse, repeat. Making the probe cheaper must not make it NARROWER; both predicates must
        // evaluate the same conjuncts.
        var epochKnown = false
        var acquisitions = 0
        val p = provisioner(
            CountingApi { id -> batchBody.replace("%s", id) },
            credential = {
                acquisitions++
                null // supplier refuses: no epoch yet
            },
            readinessProvider = { epochKnown },
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)

        VoiceProvisionGate.decide(gate, p)
        val afterFirst = acquisitions
        repeat(4) { VoiceProvisionGate.decide(gate, p) }

        assertEquals(
            "an incomplete prerequisite must not re-arm into repeated acquisition",
            afterFirst,
            acquisitions,
        )

        // The epoch arrives: exactly one attempt is permitted.
        epochKnown = true
        VoiceProvisionGate.decide(gate, p)
        assertEquals("readiness completing must permit one attempt", afterFirst + 1, acquisitions)
    }

    @Test
    fun `a FAILED acquisition under unchanged prerequisites does not re-arm the next utterance`() = runBlocking {
        // spec 9a9faf8f: prerequisites become ready (empty cache), one acquisition is permitted,
        // and it FAILS. The prerequisites have not changed, so the passive state is still READY
        // and the next utterance must NOT re-arm. Recording NOT_READY on acquisition failure
        // re-armed against a probe that still said READY, so one failure kept re-arming and
        // re-failing on every utterance -- a retry loop assembled from an outcome.
        var prerequisitesReady = false
        var attempts = 0
        val p = provisioner(
            CountingApi { id -> batchBody.replace("%s", id) },
            credential = {
                attempts++
                null // the acquisition itself fails
            },
            readinessProvider = { prerequisitesReady },
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)

        VoiceProvisionGate.decide(gate, p) // not ready yet
        val beforeTransition = attempts

        prerequisitesReady = true
        VoiceProvisionGate.decide(gate, p) // permitted: one attempt, which fails
        val afterOneAttempt = attempts
        assertEquals(
            "the transition must permit exactly one attempt",
            beforeTransition + 1,
            afterOneAttempt,
        )

        // Prerequisites UNCHANGED. The failure must not be treated as a new reason to ask.
        repeat(4) { VoiceProvisionGate.decide(gate, p) }
        assertEquals(
            "a failed attempt under unchanged prerequisites must not re-arm",
            afterOneAttempt,
            attempts,
        )
    }

    @Test
    fun `authoritative prerequisites ready while the CACHE is empty permits one attempt`() = runBlocking {
        // spec 57492640's converse: account and epoch are ready but the Auris cache is empty.
        // Readiness must stay PASSIVE -- it may not fetch to populate the cache, and it must not
        // depend on an unrelated caller to fill it. The permitted attempt is what acquires.
        var cached = false
        var acquisitions = 0
        val p = provisioner(
            CountingApi { id -> batchBody.replace("%s", id) },
            credential = {
                acquisitions++
                null // no cached token yet; the attempt is where acquisition would happen
            },
            readinessProvider = { cached }, // passive snapshot only, never fetches
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)

        VoiceProvisionGate.decide(gate, p)
        val afterFirst = acquisitions
        repeat(3) { VoiceProvisionGate.decide(gate, p) }
        assertEquals("an empty cache must not re-arm repeatedly", afterFirst, acquisitions)

        cached = true
        VoiceProvisionGate.decide(gate, p)
        assertEquals(
            "the cache becoming populated must permit exactly one acquisition attempt",
            afterFirst + 1,
            acquisitions,
        )
    }

    @Test
    fun `readiness actually CHANGING is what re-arms`() = runBlocking {
        var signedIn = false
        val api = CountingApi { id -> batchBody.replace("%s", id) }
        val p = provisioner(
            api,
            credential = {
                if (signedIn) CaptureAllowanceProvisioner.Credential("tok", "acct-1", "ep-1") else null
            },
            readinessProvider = { signedIn },
            clock = { 1_000L },
        )
        val gate = AtomicBoolean(true)

        VoiceProvisionGate.decide(gate, p)
        assertEquals(0, api.calls)

        signedIn = true
        VoiceProvisionGate.decide(gate, p)
        assertEquals("the transition is what re-arms", 1, api.calls)

        // And once consumed, a further utterance with readiness UNCHANGED does not ask again.
        VoiceProvisionGate.decide(gate, p)
        assertEquals(1, api.calls)
    }

    @Test
    fun `no provisioner means no decision`() = runBlocking {
        assertNull(VoiceProvisionGate.decide(AtomicBoolean(true), null))
    }
}
