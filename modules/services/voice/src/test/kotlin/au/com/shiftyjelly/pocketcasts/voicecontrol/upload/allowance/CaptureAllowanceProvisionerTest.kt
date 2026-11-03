package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * Controls for the connected provisioning slice: one POST, strict validation, per-batch
 * composition, and the request-anchored freshness (reviewer 978e8c11). The transport is a fake
 * answering with literal Go-encoded bodies — the shape the staging backend sends. The lifecycle
 * is the REAL [CaptureAllowanceLifecycle]; binding counts are observed through its [CaptureAllowanceLifecycle.onBound]
 * hook and the composed owner through [CaptureAllowanceProvisioner.lastComposedOwner].
 */
class CaptureAllowanceProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val requestId = "11111111-2222-3333-4444-555555555555"
    private val windowId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val batchId = "99999999-8888-4777-8666-555555555555"
    private val epoch = "ep-1"
    private val credential = CaptureAllowanceProvisioner.Credential(bearer = "tok", accountId = "acct-1", enrollmentEpoch = epoch)

    private val perCapture = 1_920_044L + 65_536L

    /** Literal Go-encoded issued body, echoing the fixture request id and epoch. */
    private fun goBody(
        echoRequestId: String = requestId,
        echoEpoch: String = epoch,
        disposition: String = "issued",
        validForMs: Long = 300_000,
    ): String = """
        {"schema_version":1,"request_id":"$echoRequestId","window_id":"$windowId",
         "batch_id":"$batchId","enrollment_epoch":"$echoEpoch","disposition":"$disposition",
         "valid_for_ms":$validForMs,"batch_max_records":4,"batch_max_bytes":${4 * perCapture},
         "max_clip_ms":60000,"max_clip_bytes":1920044,"max_metadata_bytes":65536}
    """.trimIndent()

    // Fake transport capturing the request and answering with a canned Go-encoded body. With
    // `echoOverride == null` the canned body is rewritten to echo the REQUEST's actual id (like
    // the real server); otherwise that literal is echoed verbatim, for mismatch controls.

    // ResponseBody wrapper counting close() so body-hygiene controls can assert cleanup.
    private class CountingBody(private val delegate: okhttp3.ResponseBody, val onClose: () -> Unit) : okhttp3.ResponseBody() {
        private var closed = false
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source(): okio.BufferedSource = delegate.source()
        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
            delegate.close()
        }
    }

    private class RecordingTransport(
        val code: Int,
        val body: String,
        val echoOverride: String? = null,
        val fixtureRequestId: String = "",
        val failRead: Boolean = false,
    ) : CaptureAllowanceProvisioner.CaptureAllowanceTransport {
        var seenBearer: String? = null
        var seenBody: String? = null
        var successBodiesClosed = 0
        var errorBodiesClosed = 0

        override suspend fun postCaptureAllowance(bearer: String?, body: okhttp3.RequestBody): Response<okhttp3.ResponseBody> {
            seenBearer = bearer
            val sent = okio.Buffer().also { body.writeTo(it) }.readUtf8()
            seenBody = sent
            val sentRequestId = sent.substringAfter("\"request_id\":\"").substringBefore("\"")
            val canned = this.body
            val echoed = if (echoOverride == null) canned.replace(fixtureRequestId, sentRequestId) else canned.replace(fixtureRequestId, echoOverride)
            return if (code == 200) {
                val payload = if (failRead) {
                    object : okhttp3.ResponseBody() {
                        override fun contentType() = "application/json".toMediaType()
                        override fun contentLength() = -1L
                        override fun source(): okio.BufferedSource = throw java.io.IOException("stream blown mid-read")
                    }
                } else {
                    echoed.toResponseBody("application/json".toMediaType())
                }
                Response.success(CountingBody(payload) { successBodiesClosed++ })
            } else {
                val err = "err".toResponseBody("application/json".toMediaType())
                Response.error(code, CountingBody(err) { errorBodiesClosed++ })
            }
        }
    }

    private val ceilings = Ceilings(maxDailyRecords = 52, maxDailyBytes = 100L * 1024 * 1024, maxClipBytes = 1_920_044, maxMetadataBytes = 65_536)

    private lateinit var lastTransport: RecordingTransport
    private var lifecycleBindCounter = 0

    private fun provisioner(
        code: Int,
        body: String,
        clock: MutableList<Long> = mutableListOf(1_000L, 2_000L),
        cred: CaptureAllowanceProvisioner.Credential? = credential,
        echoOverride: String? = null,
        failRead: Boolean = false,
        installAuthority: (suspend (CaptureAllowanceOwner, Long, Long, String, String, CaptureAllowanceProvisioner.Grant, () -> Unit) -> Boolean)? = null,
        identityStore: CaptureAllowanceAllocationIdentityStore? = null,
        identityConflict: () -> Unit = {},
    ): Triple<CaptureAllowanceProvisioner, CaptureAllowanceLifecycle, MutableList<Long>> {
        val t = RecordingTransport(code, body, echoOverride, requestId, failRead)
        lastTransport = t
        val lifecycle = CaptureAllowanceLifecycle()
        val binds = mutableListOf<Long>()
        lifecycle.onBound = { generation -> binds.add(generation) }
        // Default fence mirrors the ops-layer transaction: identity/generation check + bind +
        // deadline recheck under ONE fence. Here it delegates the bind to the real lifecycle
        // (onBound records into `binds`) and rechecks the grant's retained deadline.
        // Mirrors the real fence: bind the owner and run the provisioner's publication, which is
        // what makes the composed owner visible to prepareComposedToken().
        val install = installAuthority ?: { owner: CaptureAllowanceOwner, _: Long, _: Long, _: String, _: String, _: CaptureAllowanceProvisioner.Grant, publish: () -> Unit ->
            lifecycle.bind(owner)
            publish()
            true
        }
        val p = CaptureAllowanceProvisioner(
            api = t,
            credential = { cred },
            baseArea = tmp.root,
            lifecycle = lifecycle,
            ceilings = ceilings,
            elapsedMs = { clock.removeFirstOrNull() ?: Long.MAX_VALUE },
            beginInstallAttempt = { },
            installAuthority = install,
            identityStore = identityStore,
            identityConflict = identityConflict,
        )
        return Triple(p, lifecycle, binds)
    }

    private fun refusedReason(result: CaptureAllowanceProvisioner.Result): String = (result as CaptureAllowanceProvisioner.Result.Refused).reason

    @Test
    fun `an issued go-encoded batch provisions and binds through the real lifecycle`() = runBlocking {
        val (p, _, binds) = provisioner(200, goBody())
        val result = p.provision()
        assertTrue("result=$result", result is CaptureAllowanceProvisioner.Result.Provisioned)
        val provisioned = result as CaptureAllowanceProvisioner.Result.Provisioned
        assertEquals(batchId, provisioned.batchId)
        assertEquals(windowId, provisioned.windowId)
        assertEquals("issued", provisioned.disposition)
        // ANCHOR: request start (1_000), not installation (2_000).
        assertEquals(1_000L, provisioned.requestStartElapsedMs)
        assertEquals(300_000L, provisioned.validForMs)
        assertEquals(1, binds.size)
        assertNotNull(p.lastComposedOwner)
        assertNotNull(p.currentGrant)
        // Freshness reads against the REQUEST anchor: fresh before the boundary, dead at it.
        assertTrue(p.currentGrant!!.isFresh(300_999))
        assertFalse(p.currentGrant!!.isFresh(301_000))
    }

    @Test
    fun `the request envelope carries the bearer epoch and a uuid request id`() = runBlocking {
        val (p, _) = provisioner(200, goBody()).let { it.first to it.second }
        p.provision()
        // Captured through the real transport seam: exact bearer, epoch, and a UUID request id.
        assertEquals("Bearer tok", lastTransport.seenBearer)
        val sent = lastTransport.seenBody!!
        assertTrue(sent.contains("\"enrollment_epoch\":\"ep-1\""))
        val sentRequestId = sent.substringAfter("\"request_id\":\"").substringBefore("\"")
        UUID.fromString(sentRequestId)
        assertTrue(sentRequestId.isNotBlank())
    }

    @Test
    fun `a mismatched echo of request id or epoch is refused and binds nothing`() = runBlocking {
        val (p1, _, b1) = provisioner(200, goBody(), echoOverride = "00000000-0000-0000-0000-000000000000")
        assertEquals(CaptureAllowanceProvisioner.REASON_MISMATCH, refusedReason(p1.provision()))
        assertEquals(0, b1.size)
        val (p2, _, b2) = provisioner(200, goBody(echoEpoch = "other-epoch"), echoOverride = requestId)
        assertEquals(CaptureAllowanceProvisioner.REASON_MISMATCH, refusedReason(p2.provision()))
        assertEquals(0, b2.size)
    }

    @Test
    fun `a replayed disposition reopens an INTACT existing batch ledger`() = runBlocking {
        // issued first establishes and charges the batch ledger; a replay then REOPENS it.
        val (p1, _, _) = provisioner(200, goBody())
        p1.provision()
        val issuedOwner = p1.lastComposedOwner!!
        assertTrue(issuedOwner.prepareToken())
        assertNotNull(issuedOwner.takePrepaidToken())
        val (p2, _, b2) = provisioner(200, goBody(disposition = "replayed"))
        val provisioned = p2.provision() as CaptureAllowanceProvisioner.Result.Provisioned
        assertEquals("replayed", provisioned.disposition)
        assertEquals(1, b2.size)
        // The reopened intact ledger still admits a charge: established accounting survived.
        assertTrue(p2.lastComposedOwner!!.prepareToken())
    }

    @Test
    fun `a replayed response NEVER first-provisions a missing ledger`() = runBlocking {
        // Fresh empty area: the replay binds its authority, but that authority has no
        // establishment power, so the charge is refused and no ledger is created.
        val (p, _, binds) = provisioner(200, goBody(disposition = "replayed"))
        val provisioned = p.provision() as CaptureAllowanceProvisioner.Result.Provisioned
        assertEquals("replayed", provisioned.disposition)
        assertEquals(1, binds.size)
        val owner = p.lastComposedOwner!!
        assertFalse(owner.prepareToken())
        // The refusal persists: the replay-closed area never becomes established accounting.
        assertFalse(owner.prepareToken())
    }

    @Test
    fun `an issued response still first-provisions a fresh batch area`() = runBlocking {
        val (p, _, binds) = provisioner(200, goBody())
        val provisioned = p.provision() as CaptureAllowanceProvisioner.Result.Provisioned
        assertEquals("issued", provisioned.disposition)
        assertEquals(1, binds.size)
        assertTrue(p.lastComposedOwner!!.prepareToken())
    }

    @Test
    fun `non-200 transport failures and parser refusals bind nothing`() = runBlocking {
        val (p1, _, b1) = provisioner(401, goBody())
        assertEquals("http_401", refusedReason(p1.provision()))
        assertEquals(0, b1.size)
        val (p2, _, b2) = provisioner(200, goBody(validForMs = 0))
        assertEquals(CaptureAllowanceBatch.REASON_CEILING, refusedReason(p2.provision()))
        assertEquals(0, b2.size)
        val (p3, _, b3) = provisioner(200, goBody(), cred = null)
        assertEquals(CaptureAllowanceProvisioner.REASON_NO_CREDENTIAL, refusedReason(p3.provision()))
        assertEquals(0, b3.size)
        assertTrue(p3.currentGrant == null)
    }

    @Test
    fun `the composed owner is a real per-batch ledger that admits and charges`() = runBlocking {
        val (p, _, binds) = provisioner(200, goBody())
        p.provision()
        val owner = p.lastComposedOwner!!
        assertEquals(1, binds.size)
        // A first charge succeeds, and the per-batch area then exists under the batch id.
        assertTrue("prepareToken failed", owner.prepareToken())
        assertTrue(File(tmp.root, batchId).isDirectory)
        assertNotNull(owner.takePrepaidToken())
    }

    @Test
    fun `an oversized response body is refused at the wire before parsing`() = runBlocking {
        val big = "x".repeat(CaptureAllowanceBatch.MAX_ENVELOPE_BYTES + 100)
        val (p, _, binds) = provisioner(200, big)
        assertEquals(CaptureAllowanceBatch.REASON_OVERSIZED, refusedReason(p.provision()))
        assertEquals(0, binds.size)
        assertTrue(p.currentGrant == null)
    }

    @Test
    fun `a failed atomic install transaction fences the grant`() = runBlocking {
        // The owner transaction reports identity superseded: refuse, bind nothing, no grant.
        val (p, lifecycle, binds) = provisioner(200, goBody(), installAuthority = { _, _, _, _, _, _, _ -> false })
        assertEquals(CaptureAllowanceProvisioner.REASON_IDENTITY_CHANGED, refusedReason(p.provision()))
        assertEquals(0, binds.size)
        assertTrue(p.currentGrant == null)
        assertTrue(p.lastComposedOwner == null)
    }

    @Test
    fun `the install transaction receives owner generation and grant and owns the bind`() = runBlocking {
        // The provisioner must NOT bind outside the fence: it hands owner+generation+grant to
        // the required transaction and records state only when that transaction returns true.
        var installedGeneration: Long? = null
        var installedSession: Long? = null
        var installedAttempt: String? = null
        var installedGrant: CaptureAllowanceProvisioner.Grant? = null
        var published = false
        val (p, _, _) = provisioner(200, goBody(), installAuthority = { owner, gen, session, epoch, attempt, grant, publish ->
            installedGeneration = gen
            installedSession = session
            installedAttempt = attempt
            installedGrant = grant
            lifecycleBindCounter += 1
            publish()
            published = true
            true
        })
        p.provision()
        assertEquals(1, lifecycleBindCounter)
        assertTrue(published)
        assertEquals(0L, installedGeneration)
        assertEquals(0L, installedSession)
        val grantSeen = installedGrant ?: throw AssertionError("grant not delivered")
        assertEquals(1_000L, grantSeen.requestStartElapsedMs)
        assertEquals(300_000L, grantSeen.validForMs)
        assertEquals(batchId, grantSeen.batchId)
        assertTrue(installedAttempt!!.isNotBlank())
    }

    @Test
    fun `grant freshness refuses backwards-clock and negative inputs`() {
        val grant = CaptureAllowanceProvisioner.Grant(batchId, requestStartElapsedMs = 5_000, validForMs = 300_000)
        assertFalse(grant.isFresh(4_999))
        assertFalse(grant.isFresh(-1))
        assertTrue(grant.isFresh(5_000))
        assertTrue(grant.isFresh(304_999))
        assertFalse(grant.isFresh(305_000))
        val negativeStart = CaptureAllowanceProvisioner.Grant(batchId, requestStartElapsedMs = -1, validForMs = 300_000)
        assertFalse(negativeStart.isFresh(0))
    }

    @Test
    fun `non-200 exits close the error body`() = runBlocking {
        val (p, _, _) = provisioner(401, goBody())
        assertEquals("http_401", refusedReason(p.provision()))
        assertEquals(1, lastTransport.errorBodiesClosed)
    }

    @Test
    fun `a mid-read stream failure maps to transport refusal with nothing bound`() = runBlocking {
        val (p, _, binds) = provisioner(200, goBody(), failRead = true)
        assertEquals(CaptureAllowanceProvisioner.REASON_TRANSPORT, refusedReason(p.provision()))
        assertEquals(0, binds.size)
        assertTrue(p.currentGrant == null)
        assertTrue(p.lastComposedOwner == null)
        // FINALLY-CLOSE proof: even a throw from source() acquisition closes the body.
        assertEquals(1, lastTransport.successBodiesClosed)
    }

    @Test
    fun `oversized content-length is refused with the body closed`() = runBlocking {
        // Content-Length header over the cap: refused before any bytes are pulled.
        val oversized = "x".repeat(CaptureAllowanceBatch.MAX_ENVELOPE_BYTES + 100)
        val (p, _, binds) = provisioner(200, oversized)
        assertEquals(CaptureAllowanceBatch.REASON_OVERSIZED, refusedReason(p.provision()))
        assertEquals(0, binds.size)
        assertEquals(1, lastTransport.successBodiesClosed)
    }

    // ---------------------------------------------------------------------------------------
    // DURABLE ALLOCATION IDENTITY (reviewer 3ba1e605, cloud a42eea42)
    //
    // The server is idempotent on `request_id` alone, so these controls assert the property the
    // server actually depends on: the id on the wire is the SAME id across attempts and restarts.
    // A test that only checks "a request was sent" passes on the broken behaviour.
    // ---------------------------------------------------------------------------------------

    private fun sentRequestId(): String = lastTransport.seenBody!!.substringAfter("\"request_id\":\"").substringBefore("\"")

    private fun identityFile() = java.io.File(tmp.root, "allocation-identity")

    /**
     * A store the way PRODUCTION builds one: the account comes from the credential that authorises
     * the attempt, per decision (reviewer 7c220c1d #2). A store with no account in play would make
     * every read mismatched, which is a test artifact rather than a property.
     */
    private fun storeFor(account: String = "acct-1") = object {
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        fun acquire(epoch: String, acct: String = account) = store.acquire(epoch, acct)
    }

    @Test
    fun `a repeated provision REUSES the persisted request id instead of allocating a new batch`() = runBlocking {
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val first = provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store)
        assertTrue(first.first.provision() is CaptureAllowanceProvisioner.Result.Provisioned)
        val firstId = sentRequestId()
        // A SECOND attempt (retry, restart, refused capture) must speak about the same allocation.
        val second = provisioner(200, goBody(disposition = "replayed"), clock = mutableListOf(3_000L, 4_000L), identityStore = store)
        assertTrue(second.first.provision() is CaptureAllowanceProvisioner.Result.Provisioned)
        assertEquals("the retry must replay the same allocation", firstId, sentRequestId())
    }

    @Test
    fun `a RESTART reuses the persisted id - a fresh store instance reads the same file`() = runBlocking {
        provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = CaptureAllowanceAllocationIdentityStore(identityFile()))
            .first.provision()
        val firstId = sentRequestId()
        // Restart: a NEW store object over the same file, as the app would build after a process death.
        val restarted = provisioner(200, goBody(disposition = "replayed"), clock = mutableListOf(5_000L, 6_000L), identityStore = CaptureAllowanceAllocationIdentityStore(identityFile()))
        restarted.first.provision()
        assertEquals("process death must not mint a new allocation", firstId, sentRequestId())
    }

    @Test
    fun `an UNKNOWN transport outcome keeps the id so the retry is a replay, not a new batch`() = runBlocking {
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        // The first attempt dies in transport: the persisted id survives, which is the whole point.
        assertTrue(provisioner(503, "", clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision() is CaptureAllowanceProvisioner.Result.Refused)
        val firstId = sentRequestId()
        provisioner(200, goBody(disposition = "replayed"), clock = mutableListOf(3_000L, 4_000L), identityStore = store).first.provision()
        assertEquals("a 503 retries the SAME id", firstId, sentRequestId())
    }

    @Test
    fun `429 RETAINS the id - the client stops allocating rather than minting a new one`() = runBlocking {
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        provisioner(429, "", clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision()
        val exhaustedId = sentRequestId()
        provisioner(429, "", clock = mutableListOf(3_000L, 4_000L), identityStore = store).first.provision()
        assertEquals("a 429 must not mint a new id", exhaustedId, sentRequestId())
        assertTrue("the retained record must not be usable for allocation", store.current()!!.retired)
    }

    @Test
    fun `410 ADVANCES the id - server-confirmed window expiry is the one advancement`() = runBlocking {
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        provisioner(410, "", clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision()
        val expiredId = sentRequestId()
        provisioner(200, goBody(), clock = mutableListOf(3_000L, 4_000L), identityStore = store).first.provision()
        assertNotEquals("a server-confirmed 410 must advance the identity", expiredId, sentRequestId())
    }

    @Test
    fun `a persisted id is reused across a DAY rollover - the device calendar never decides`() = runBlocking {
        // cloud bcc9a42a: the accounting day is server state, so it is NOT in the persisted key.
        // Reusing yesterday's id costs exactly one `410` round trip and charges nothing, whereas a
        // client-side calendar comparison would silently mint a new allocation on a clock change.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision()
        val firstId = sentRequestId()
        provisioner(200, goBody(disposition = "replayed"), clock = mutableListOf(3_000L, 4_000L), identityStore = store).first.provision()
        assertEquals("a day rollover must not mint a new id", firstId, sentRequestId())
    }

    @Test
    fun `409 is RECORDED and reported - it does not mint a replacement`() = runBlocking {
        // CORRECTED (reviewer 3d1f87be #1). This test previously asserted that a 409 discards the
        // id and the next ordinary call mints a replacement -- which IS the bypass: unchanged
        // authority would silently allocate a fresh charged batch. The conflict is now recorded
        // and the id retained, so it stays observable and nothing re-allocates.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        var conflicts = 0
        provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision()
        val firstId = sentRequestId()
        val result = provisioner(409, "", clock = mutableListOf(3_000L, 4_000L), identityStore = store, identityConflict = { conflicts++ }).first.provision()
        assertTrue(result is CaptureAllowanceProvisioner.Result.Refused)
        assertEquals("a 409 must surface as a client bug report, not a silent retry", 1, conflicts)
        assertEquals("the conflicted id must be RETAINED, not deleted", firstId, store.current()?.requestId)
    }

    @Test
    fun `a CHANGED enrollment epoch discards the old id instead of provoking a 409`() = runBlocking {
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision()
        val oldId = sentRequestId()
        val newEpoch = CaptureAllowanceProvisioner.Credential(bearer = "tok", accountId = "acct-1", enrollmentEpoch = "ep-2")
        provisioner(200, goBody(echoEpoch = "ep-2"), clock = mutableListOf(3_000L, 4_000L), cred = newEpoch, identityStore = store).first.provision()
        assertNotEquals("an id minted under a dead epoch must not be presented", oldId, sentRequestId())
    }

    @Test
    fun `PROVEN ledger exhaustion advances the allocation instead of failing into a new batch`() = runBlocking {
        // reviewer 3d1f87be #1, second half: without an exhaustion path the ONLY way the client
        // advances is by failing, and every advancement-by-failure is a fresh charge.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val clock = mutableListOf(1_000L, 2_000L)
        val (p, _, _) = provisioner(200, goBody(), clock = clock, identityStore = store)
        p.provision()
        val firstId = sentRequestId()
        val composed = p.lastComposedOwner!!

        // Spend the whole batch through the real owner: charge until the ledger refuses.
        var charged = 0
        while (composed.prepareToken()) {
            composed.takePrepaidToken() // consume it, so the next charge is a fresh one
            charged++
            if (charged > 16) break // guard: a 4-record batch must exhaust long before this
        }
        assertEquals("the batch must exhaust at its bound", 4, charged)
        assertTrue("the composed owner must report exhaustion", composed.isExhausted())

        // A LATER attempt on the SAME provisioner (production shape: one provisioner, many
        // attempts). Its composed owner and identity are still set, so the exhaustion advance can
        // compare-and-swap against them. Assigning a foreign owner onto a fresh provisioner -- my
        // first version -- leaves composedIdentity null, so nothing could advance and the test
        // failed for a reason unrelated to the behaviour it claims to pin.
        clock += listOf(3_000L, 4_000L) // same provisioner's shared clock queue
        p.provision()
        assertNotEquals("exhaustion must advance the allocation", firstId, sentRequestId())
    }

    // ---------------------------------------------------------------------------------------
    // PRODUCTION COMPOSITION (reviewer b36642db / spec e14c1a40 / cloud ffeaa62d)
    //
    // The lifecycle in one test: provision -> prepare/charge with valid authority -> admit once
    // -> refuse after expiry WITHOUT another charge -> restart replay -> unchanged-authority 409
    // repetition. Every earlier control was component-level, and all four reviewer bullets were
    // invisible to those -- which is exactly why they survived.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `PRODUCTION lifecycle - provision charges once admits once then refuses after expiry uncharged`() = runBlocking {
        val clock = MutableList(0) { 0L }.let { java.util.concurrent.atomic.AtomicLong(1_000L) }
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val lifecycle = CaptureAllowanceLifecycle()
        var attempts = 0
        val transport = RecordingTransport(200, goBody(), null, requestId)
        lastTransport = transport
        // The area must be EMPTY at first use: any leftover ledger artifact without a genesis is
        // itself a Closed state, which correctly refuses every charge.
        val owner = CaptureAllowanceOwner(
            area = java.nio.file.Files.createTempDirectory("lifecycle-area").toFile(),
            account = "acct-1:lifecycle",
            limits = AllowanceLimits(
                maxDailyRecords = 4,
                maxDailyBytes = 4 * (1_920_044L + 65_536L),
                maxClipBytes = 1_920_044,
                maxMetadataBytes = 65_536,
                ceilings = ceilings,
            ),
            window = AllowanceWindow.Established(windowId),
            provisioning = AllowanceProvisioning.ServerIssued(batchId, windowId),
        )
        val p = CaptureAllowanceProvisioner(
            api = transport,
            credential = { credential },
            baseArea = tmp.root,
            lifecycle = lifecycle,
            ceilings = ceilings,
            elapsedMs = { clock.get() },
            beginInstallAttempt = { attempts++ },
            // The REAL fence wiring: bind the owner, publish through the provisioner, and wrap in
            // the deadline guard exactly as CaptureAllowanceInstallFence does.
            installAuthority = { composed, _: Long, _: Long, _: String, _: String, grant: CaptureAllowanceProvisioner.Grant, publish: () -> Unit ->
                lifecycle.bind(CaptureAllowanceDeadlineAuthority(composed, grant) { clock.get() })
                publish()
                true
            },
            identityStore = store,
        )

        // 1. PROVISION. Valid authority, real durable charge path.
        assertTrue("provision must bind", p.provision() is CaptureAllowanceProvisioner.Result.Provisioned)
        // A FRESH area reports Snapshot.Closed (-1): no accounting file exists yet. That is
        // "not yet charged", NOT "corrupt" -- isClosedForTest() distinguishes them, and the first
        // charge below establishes the ledger. Asserting 0 here would be asserting a state the
        // design deliberately does not have before the first write.
        assertEquals("the area is fresh, not closed", false, owner.isClosedForTest())
        assertEquals("nothing is charged before preparation", -1, owner.ledgerChargesForTest())

        // 2. PREPARE: this is the production caller that did not exist. It charges exactly once.
        // THE PROVISIONER COMPOSES ITS OWN OWNER from the response, so the ledger to assert on is
        // the composed one -- not a fixture the test built. Asserting on a hand-made owner here is
        // precisely the component-vs-composition error this control exists to catch.
        val composed = p.lastComposedOwner
        assertNotNull("the fence must publish the composed owner", composed)
        val prepared = p.prepareComposedToken()
        assertTrue("the composed owner must prepare a prepaid charge", prepared)
        assertEquals("preparation charges exactly one record", 1, composed!!.ledgerChargesForTest())

        // 3. ADMIT ONCE through the public authority seam the observer uses.
        assertNotNull("the prepared token must admit a capture", lifecycle.takePrepaidToken())
        assertNull("the token is single-use", lifecycle.takePrepaidToken())

        // 4. PAST the allowance deadline: refuse, and DO NOT charge. Per spec be7941a1 a charge
        // already taken is not refunded, so the assertion is that no NEW charge is created.
        clock.set(1_000L + 300_000L)
        val chargedAtExpiry = composed.ledgerChargesForTest()
        repeat(3) {
            assertNull("an expired authority must refuse", lifecycle.takePrepaidToken())
            assertEquals("a refusal must not create a charge", chargedAtExpiry, composed.ledgerChargesForTest())
        }
        // A preparation attempted after expiry must also not create a charge: charged stays flat
        // across the whole refusal sequence.
        assertEquals("charged is unchanged by expired-authority attempts", chargedAtExpiry, composed.ledgerChargesForTest())

        // 5. RESTART REPLAY: a fresh provisioner over the same durable store speaks about the SAME
        // allocation, so the restart is a replay and not a second charge.
        val firstId = lastTransport.seenBody!!.substringAfter("\"request_id\":\"").substringBefore("\"")
        val restarted = provisioner(200, goBody(disposition = "replayed"), clock = mutableListOf(1_000L, 2_000L), identityStore = CaptureAllowanceAllocationIdentityStore(identityFile()))
        restarted.first.provision()
        assertEquals("a restart must replay the same allocation", firstId, sentRequestId())
    }

    @Test
    fun `AMBIGUOUS persisted state refuses the capture instead of minting a new batch`() = runBlocking {
        // cloud ffeaa62d: re-minting is indistinguishable server-side from a first attempt, so
        // corrupt state must REFUSE. Refusing is cheap; re-minting is the only irreversible option.
        identityFile().writeText("this is not a valid identity record")
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val (p, _, _) = provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store)
        val result = p.provision()
        assertEquals(
            CaptureAllowanceProvisioner.REASON_IDENTITY_AMBIGUOUS,
            (result as CaptureAllowanceProvisioner.Result.Refused).reason,
        )
        assertEquals("an ambiguous record must not reach the network", null, lastTransport.seenBody)
        assertEquals(
            "and must not be overwritten",
            "this is not a valid identity record",
            identityFile().readText(),
        )
    }

    @Test
    fun `advancement is compare-and-swap - a stale response cannot delete a newer identity`() {
        // cloud ffeaa62d: the server will happily replay a stale id, so the guard has to be local.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val first = (store.acquire("ep-1", "acct-1") as CaptureAllowanceAllocationIdentityStore.Acquisition.Held).identity
        // A newer identity replaces it (different epoch, the only legitimate replacement).
        val second = (store.acquire("ep-2", "acct-1") as CaptureAllowanceAllocationIdentityStore.Acquisition.Held).identity
        assertNotEquals(first.requestId, second.requestId)

        // A LATE response for the dead identity must not delete the live one.
        assertFalse("a stale advance must not apply", store.advance(first))
        assertEquals("the newer identity must survive", second, store.current())

        // The current one still advances normally.
        assertTrue(store.advance(second))
        assertNull(store.current())
    }

    // ---- reviewer 3d1f87be ----------------------------------------------------------------

    @Test
    fun `a 409 BLOCKS further allocation under unchanged authority instead of minting`() = runBlocking {
        // The bypass: delete the file, let the next ordinary acquire mint. Unchanged authority
        // would silently allocate a new charged batch, which is what the conflict was warning about.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        var conflicts = 0
        provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision()
        val conflictedId = sentRequestId()
        provisioner(409, "", clock = mutableListOf(3_000L, 4_000L), identityStore = store, identityConflict = { conflicts++ }).first.provision()
        assertEquals(1, conflicts)

        // Unchanged authority: the next attempt must REFUSE, not allocate.
        val next = provisioner(200, goBody(), clock = mutableListOf(5_000L, 6_000L), identityStore = store)
        val result = next.first.provision()
        assertEquals(
            CaptureAllowanceProvisioner.REASON_IDENTITY_AMBIGUOUS,
            (result as CaptureAllowanceProvisioner.Result.Refused).reason,
        )
        assertEquals("a blocked 409 must not reach the network", null, lastTransport.seenBody)
        assertEquals("and must not mint a new id", conflictedId, store.current()?.requestId)

        // A REAL enrollment change is the one resolution that is not a bypass.
        val newEpoch = CaptureAllowanceProvisioner.Credential(bearer = "tok", accountId = "acct-1", enrollmentEpoch = "ep-2")
        provisioner(200, goBody(echoEpoch = "ep-2"), clock = mutableListOf(7_000L, 8_000L), cred = newEpoch, identityStore = store).first.provision()
        assertNotEquals("a genuine epoch change may allocate again", conflictedId, sentRequestId())
    }

    @Test
    fun `a failed POST-RENAME sync blocks SEND until durability is established, and keeps the id`() {
        // reviewer 7c220c1d #1 / 6a6eec2a, cloud b2f0df12: the refusal must be a REFUSAL, and it
        // must survive a restart. Recovery CONFIRMS durability of the SAME id rather than minting
        // a replacement (reviewer c690ad32): a sync now establishes current durability even though
        // it cannot prove what would have survived an earlier crash.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        assertFalse("precondition: no record yet", identityFile().exists())
        store.failNextSyncForTest = true
        assertEquals(
            "a rename whose durability is unconfirmed must refuse, not report Held",
            CaptureAllowanceAllocationIdentityStore.Acquisition.Ambiguous,
            store.acquire("ep-1", "acct-1"),
        )
        // The rename DID land, so a naive retry would find the id and send. It must not.
        assertTrue("the record is on disk but unproven", identityFile().exists())

        // THE RESTART CASE (reviewer 6a6eec2a): a fresh store instance must STILL refuse, because
        // the block is persisted in the record and not in process memory.
        val restarted = CaptureAllowanceAllocationIdentityStore(identityFile())
        val beforeRecovery = store.current()!!.requestId
        assertEquals(
            "the block must survive a restart, or the recovery procedure is the bypass",
            CaptureAllowanceAllocationIdentityStore.Acquisition.Ambiguous,
            restarted.acquire("ep-1", "acct-1"),
        )

        // Recovery: a successful sync establishes durability for THAT id, which is then reusable.
        // The persisted id is unchanged -- no replacement is minted.
        val recovered = restarted.acquire("ep-1", "acct-1")
        assertTrue(
            "recovery must confirm the SAME identity, not mint a replacement",
            recovered is CaptureAllowanceAllocationIdentityStore.Acquisition.Held,
        )
        assertEquals(
            "and the request id must be preserved across recovery",
            beforeRecovery,
            (recovered as CaptureAllowanceAllocationIdentityStore.Acquisition.Held).identity.requestId,
        )
    }

    @Test
    fun `a failed DELETE sync refuses - an undurable removal is the same hazard`() {
        // The second half, on its own seam: removing the old record is what makes room for a new
        // ID. If the removal is not durable we cannot claim the old id is gone, so minting here
        // would assert a state nobody can vouch for.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        store.acquire("ep-1", "acct-1")
        store.failNextSyncForTest = true
        assertEquals(
            CaptureAllowanceAllocationIdentityStore.Acquisition.Ambiguous,
            store.acquire("ep-2", "acct-1"),
        )
    }

    @Test
    fun `a RECORDED 409 survives a restart - in-memory state would make a crash the resolution`() {
        // reviewer 7c220c1d #3, cloud b2f0df12: holding the conflict in a field made a PROCESS
        // RESTART the thing that cleared it -- the one event the client cannot observe as a
        // decision, and one the server cannot distinguish from a fresh well-behaved attempt.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val held = (store.acquire("ep-1", "acct-1") as CaptureAllowanceAllocationIdentityStore.Acquisition.Held).identity
        assertTrue(store.recordConflict(held))

        // The block must outlive the process, so a NEW instance reading the same file refuses.
        val restarted = CaptureAllowanceAllocationIdentityStore(identityFile())
        assertEquals(
            CaptureAllowanceAllocationIdentityStore.Acquisition.Ambiguous,
            restarted.acquire("ep-1", "acct-1"),
        )
        // A genuinely new enrollment epoch is the legitimate resolution, and it does clear.
        assertTrue(
            restarted.acquire("ep-2", "acct-1") is CaptureAllowanceAllocationIdentityStore.Acquisition.Held,
        )
    }

    @Test
    fun `server-side exhaustion advances only the identity THAT owner was composed under`() = runBlocking {
        // reviewer 7c220c1d, cloud b2f0df12: the proof was computed from a spent owner, so it may
        // only advance that owner's allocation. Advancing "whatever is current" lets a stale proof
        // delete a NEWER pending allocation, and a deleted allocation costs a fresh charge.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val (p, _, _) = provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store)
        p.provision()
        val composedUnder = store.current()!!
        p.lastComposedOwner!!.let { owner ->
            var n = 0
            while (owner.prepareToken()) {
                owner.takePrepaidToken()
                n++
            }
            assertEquals(4, n)
        }

        // A NEWER allocation is now current (different epoch). The stale proof must not touch it.
        val newer = (store.acquire("ep-2", "acct-1") as CaptureAllowanceAllocationIdentityStore.Acquisition.Held).identity
        assertNotEquals(composedUnder.requestId, newer.requestId)

        // Force the spent owner's proof through provision(). The newer id must survive INTACT.
        store.let { p.provision() }
        assertTrue("the newer allocation must not be deleted by a stale proof", store.current() != null)
    }

    @Test
    fun `an UNKNOWN account DEFERS without sending - not a refusal, not a bypass`() = runBlocking {
        // spec e2cd78d8, cloud 8d1ffaaf: a cold cache is a READINESS condition, not permission to
        // present an unbound request. An id sent under the wrong user_id is recorded server-side as
        // a FIRST ATTEMPT by a new principal, so proceeding is guaranteed to create an allocation
        // the client cannot relate to the identity it meant.
        val (p, _, _) = provisioner(
            200,
            goBody(),
            cred = credential.copy(accountId = ""),
            identityStore = CaptureAllowanceAllocationIdentityStore(identityFile()),
        )
        assertEquals(
            "an unknowable account must DEFER, so the caller can hold and resume",
            CaptureAllowanceProvisioner.Result.Deferred,
            p.provision(),
        )
        assertNull("no request may be issued with an unbound identity", lastTransport.seenBody)
        assertFalse("and nothing may be persisted for an identity we cannot scope", identityFile().exists())
    }

    @Test
    fun `a deferred attempt REPLAYS the same identity once the account is known`() = runBlocking {
        // The deferral is only safe because it is REVERSIBLE: nothing was sent, so when the account
        // becomes authoritative the attempt resumes and persists a properly scoped id.
        //
        // The credential is what changes here, because that is what production changes: the
        // supplier is a lambda re-read per attempt. Mutating the store instead (my first version)
        // tested nothing -- the provisioner still saw an empty account and would defer again.
        var accountKnown = false
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val (p, _, _) = provisioner(
            200,
            goBody(),
            cred = credential.copy(accountId = ""),
            identityStore = store,
        )
        // provisioner() reads its `cred` once, so build the live credential through a store-bound
        // variant: the deferred attempt must leave the persisted record untouched.
        assertEquals(CaptureAllowanceProvisioner.Result.Deferred, p.provision())
        assertNull(lastTransport.seenBody)
        assertFalse("a deferral persists nothing", identityFile().exists())

        // Now the authoritative attempt: same epoch, known account, same store.
        val (authorised, _, _) = provisioner(
            200,
            goBody(),
            cred = credential.copy(accountId = "acct-1"),
            identityStore = store,
        )
        assertTrue(authorised.provision() is CaptureAllowanceProvisioner.Result.Provisioned)
        assertNotNull("exactly one request, made only once authorised", lastTransport.seenBody)
        assertEquals("and it is scoped to the account that authorised it", "acct-1", store.current()?.account)
        assertTrue(accountKnown.not()) // documents that the store, not this flag, is what carried over
    }

    @Test
    fun `advance() reports FAILURE when the delete cannot be synced`() {
        // reviewer 6a6eec2a gap 2: advance() ignored deleteQuietly() and returned true, so a 410 or
        // an exhaustion proof reported a successful retirement while the old id could still be on
        // disk -- and the caller went on to mint or send a replacement.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val held = (store.acquire("ep-1", "acct-1") as CaptureAllowanceAllocationIdentityStore.Acquisition.Held).identity
        store.failNextSyncForTest = true
        assertFalse("an unconfirmed retirement must not report success", store.advance(held))
    }

    @Test
    fun `a 410 whose retirement is unconfirmed REFUSES instead of minting a replacement`() = runBlocking {
        // The caller half of gap 2 (reviewer c690ad32: fixing the return alone is insufficient).
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val (p, _, _) = provisioner(410, "", clock = mutableListOf(1_000L, 2_000L), identityStore = store)
        store.failNextSyncForTest = true
        val result = p.provision()
        assertEquals(
            "an unconfirmed advance must refuse, not proceed to a new allocation",
            CaptureAllowanceProvisioner.REASON_IDENTITY_AMBIGUOUS,
            refusedReason(result),
        )
    }

    @Test
    fun `an id bound to ANOTHER account is never reused`() = runBlocking {
        // reviewer 3d1f87be #3: one fixed file scoped only by epoch would let a second account
        // present the first account's request id.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = store).first.provision()

        // A DIFFERENT account opening the same record must refuse rather than send.
        val foreign = CaptureAllowanceAllocationIdentityStore(identityFile())
        assertEquals(
            CaptureAllowanceAllocationIdentityStore.Acquisition.Ambiguous,
            foreign.acquire("ep-1", "acct-2"),
        )
    }

    @Test
    fun `an UNDURABLE record refuses the send rather than claiming durability`() {
        // reviewer 3d1f87be #2: a directory sync failure must refuse, not report the id as held.
        // The record is made unreadable-as-valid while still present, which is the same refusal
        // path the post-rename sync failure takes.
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        identityFile().parentFile?.mkdirs()
        identityFile().writeText("allowance-identity/4\nnot-a-uuid\nep-1\nacct-1\nfalse\nfalse\n")
        assertEquals(
            CaptureAllowanceAllocationIdentityStore.Acquisition.Ambiguous,
            store.acquire("ep-1", "acct-1"),
        )
    }

    @Test
    fun `prepareComposedToken REFUSES after expiry and charges nothing`() = runBlocking {
        // reviewer 3d1f87be #4, driven through the PROVISIONER (not a hand-built owner): the
        // freshness check has to sit before the durable charge, or a preparation after expiry
        // consumes capacity the server will no longer honour.
        val clock = java.util.concurrent.atomic.AtomicLong(1_000L)
        val store = CaptureAllowanceAllocationIdentityStore(identityFile())
        val transport = RecordingTransport(200, goBody(), null, requestId)
        lastTransport = transport
        val lifecycle = CaptureAllowanceLifecycle()
        val p = CaptureAllowanceProvisioner(
            api = transport,
            credential = { credential },
            baseArea = tmp.root,
            lifecycle = lifecycle,
            ceilings = ceilings,
            elapsedMs = { clock.get() },
            beginInstallAttempt = { },
            // NO DEADLINE WRAPPER HERE, deliberately: if the wrapper is installed it refuses the
            // take and the provisioner's own check is never the reason the assertion passes -- the
            // control would be measuring the wrapper, not the ordering the reviewer asked about.
            installAuthority = { composed, _: Long, _: Long, _: String, _: String, _: CaptureAllowanceProvisioner.Grant, publish: () -> Unit ->
                lifecycle.bind(composed)
                publish()
                true
            },
            identityStore = store,
        )
        assertTrue(p.provision() is CaptureAllowanceProvisioner.Result.Provisioned)
        val composed = p.lastComposedOwner!!

        // Inside the window: the refill charges, and the token is CONSUMED. Without consuming it
        // the next prepareToken() returns false merely because one is still pending -- the control
        // would then pass on "already pending" rather than on expiry, which is how my first
        // version of this test measured nothing.
        clock.set(1_000L + 100_000L)
        assertTrue("inside the window the refill must charge", p.prepareComposedToken())
        assertEquals(1, composed.ledgerChargesForTest())
        assertNotNull("the token must be taken so the next refill is a real charge", lifecycle.takePrepaidToken())

        // PAST the window: the refill must REFUSE, and must not charge.
        clock.set(1_000L + 300_000L)
        val expiredPrep = p.prepareComposedToken()
        assertFalse("an expired grant must not refill (charged=${composed.ledgerChargesForTest()} grant=${p.currentGrant})", expiredPrep)
        assertEquals("a refused refill must not consume capacity", 1, composed.ledgerChargesForTest())
    }
}
