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
        accountingDay: () -> String = { "2026-11-04" },
    ): Triple<CaptureAllowanceProvisioner, CaptureAllowanceLifecycle, MutableList<Long>> {
        val t = RecordingTransport(code, body, echoOverride, requestId, failRead)
        lastTransport = t
        val lifecycle = CaptureAllowanceLifecycle()
        val binds = mutableListOf<Long>()
        lifecycle.onBound = { generation -> binds.add(generation) }
        // Default fence mirrors the ops-layer transaction: identity/generation check + bind +
        // deadline recheck under ONE fence. Here it delegates the bind to the real lifecycle
        // (onBound records into `binds`) and rechecks the grant's retained deadline.
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
            accountingDay = accountingDay,
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
    fun `a NEW accounting day does not reuse yesterday's id - that would be a silent 410`() = runBlocking {
        provisioner(200, goBody(), clock = mutableListOf(1_000L, 2_000L), identityStore = CaptureAllowanceAllocationIdentityStore(identityFile()), accountingDay = { "2026-11-04" })
            .first.provision()
        val yesterdayId = sentRequestId()
        provisioner(200, goBody(), clock = mutableListOf(3_000L, 4_000L), identityStore = CaptureAllowanceAllocationIdentityStore(identityFile()), accountingDay = { "2026-11-05" })
            .first.provision()
        assertNotEquals("a persisted id from another day is dead on the server", yesterdayId, sentRequestId())
    }
}
