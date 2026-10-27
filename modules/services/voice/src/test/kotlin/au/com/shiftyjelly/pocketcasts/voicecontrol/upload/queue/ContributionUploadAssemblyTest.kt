package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE UNIFIED PRODUCTION PATH: caller -> real HTTP -> token-scoped settlement.
 *
 * Every earlier control satisfied the caller's seams by hand, which proves the caller and proves the
 * transport, but never that they compose. This control wires [ContributionUploadAssembly] - the object
 * the product uses - to a REAL multipart POST against a local HTTP server, and asserts what happened in
 * the QUEUE: released, retained, or left alone. Settlement, not classification, is the observable.
 */
class ContributionUploadAssemblyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private var port = 0
    private val requests = AtomicInteger(0)
    private var responseStatus = 200
    private var responseBody = ""
    private var onRequest: (() -> Unit)? = null
    private var lastBody: ByteArray = ByteArray(0)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/contributions") { exchange: HttpExchange ->
            requests.incrementAndGet()
            lastBody = exchange.requestBody.readBytes() // drain so the client's POST completes
            onRequest?.invoke()
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    private fun record(captureId: String, audioSha: String, audioBytes: Long): RecordNode.Obj {
        val block = AudioBlock(
            sha256 = audioSha,
            byteCount = audioBytes,
            retainedSamples = 1600,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        return ContributionRecordBuilder(captureId, "epoch-1", "42")
            .model("vad", ModelRef.Named("silero-vad", "4.0"))
            .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
            .model("asr", ModelRef.Named("whisper-small", "2024-11"))
            .model("translation", ModelRef.None)
            .model("router", ModelRef.Named("lfm-intent", "0.3"))
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
            .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))))
            .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
            .stage(StageName.Routing, StageEntry(StageState.Completed, mapOf("predicted_intent" to RecordNode.Str("skip_ad"))))
            .stage(StageName.Execution, StageEntry(StageState.Completed, mapOf("outcome" to RecordNode.Str("success"))))
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = block)
    }

    /** A capture on disk plus the record that describes it, exactly as the product would supply one. */
    private class Fixture(val id: String, val file: File)

    private fun fixture(store: ContributionStore, label: String): Fixture {
        val id = UUID.randomUUID().toString()
        // The recorded digest must be the digest of the file that is ACTUALLY sent, so the capture the
        // store publishes is the one the record describes.
        val sample = wavBytes(1600)
        val meta = CanonicalJson.canonicalBytes(record(id, sha256Hex(sample), sample.size.toLong()))
        val admission = store.admitForTest(1600, meta.size)
        val generation = store.fence.currentGeneration()
        check(store.prepareAt(generation, admission, id, "epoch-1", listOf(ShortArray(1600) { i -> i.toShort() } to 1600), meta, "a@x", 7L) != null) {
            "the capture was not admitted into the queue ($label)"
        }
        // The DURABLE file the store published - not a test-side copy standing in for it. The store
        // stages under a generation prefix, so the published name is discovered rather than assumed.
        val durable = store.dirForTest().walkTopDown().firstOrNull { it.isFile && it.name.endsWith("$id.recording.wav") }
        check(durable != null) {
            "the store published no capture file for $id ($label); have: " +
                store.dirForTest().walkTopDown().filter { it.isFile }.map { it.name }.toList()
        }
        return Fixture(id, durable)
    }

    private var storeRoot: File = File(".")

    private fun newStore(now: () -> Long): ContributionStore = ContributionStore(
        tmp.newFolder("store-${UUID.randomUUID()}").also { storeRoot = it },
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = now,
        jitter = { it - 1 },
    )

    private fun assembly(
        store: ContributionStore,
        now: () -> Long,
        captures: MutableMap<String, ContributionUploadAssembly.Capture>,
        accepted: MutableList<String> = mutableListOf(),
    ) = ContributionUploadAssembly(
        store = store,
        transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:$port/v1/contributions",
            authorization = { "Bearer test-token" },
        ),
        captureFor = { captures[it] },
        clock = now,
        policyAllows = { true },
        networkAvailable = { true },
        onAccepted = { _, captureId -> accepted += captureId },
    )

    private fun receiptFor(store: ContributionStore, id: String, status: String = "accepted"): String {
        val digest = store.recordedDigest(id) ?: error("no recorded digest for $id")
        return """{"schema_version":1,"capture_id":"$id","record_sha256":"$digest","status":"$status",""" +
            """"receipt_id":"rcpt-1","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"2026-11-21T00:00:00Z"}"""
    }

    @Test
    fun `the attempt settles the capture's descriptor on both an acceptance and a refusal`() {
        // Reviewer 9a47123e: captureFor built a Capture and dropped the owned descriptor, leaking one per
        // successful capture. This drives the REAL seam and observes the descriptor's own fate.
        for ((label, status) in listOf("accepted" to 201, "refused" to 500)) {
            var now = 30_000_000L
            val store = newStore { now }
            val fixture = fixture(store, "assembly-settle-$label")
            val accepted = mutableListOf<String>()
            responseStatus = status
            responseBody = if (status == 201) receiptFor(store, fixture.id, status = "accepted") else "{}"

            val capture = store.durableCapture(fixture.id)
            val owned = requireNotNull(capture?.owned) { "PREMISE: the store hands down an owned source" }
            val handed = mutableMapOf(
                fixture.id to ContributionUploadAssembly.Capture(
                    capture.record,
                    capture.audio,
                    capture.enrollmentEpoch,
                    capture.enrollmentAccount,
                    capture.enrollmentSession,
                    owned,
                ),
            )
            assembly(store, { now }, handed, accepted).poll()

            assertTrue(
                "the $label attempt left the descriptor open",
                runCatching { owned.borrowForBody() ?: throw java.io.IOException("closed") }.isFailure,
            )
        }
    }

    // The fixture supplies the owned source the transport now requires, exactly as the store does in
    // production; without it the attempt correctly refuses before any request leaves.
    private fun capturesFor(store: ContributionStore, fixture: Fixture): MutableMap<String, ContributionUploadAssembly.Capture> = mutableMapOf(
        fixture.id to ContributionUploadAssembly.Capture(
            record(fixture.id, sha256Hex(fixture.file.readBytes()), fixture.file.length()),
            fixture.file,
            owned = OwnedAudio(java.io.FileInputStream(fixture.file), fixture.file.length(), sha256Hex(fixture.file.readBytes())),
        ),
    )

    @Test
    fun `a validated receipt over real HTTP releases the entry and reports the acceptance`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-accept")
        val accepted = mutableListOf<String>()
        // The contract's pair for a FIRST acceptance: 201 with status `accepted`.
        responseStatus = 201
        responseBody = receiptFor(store, fixture.id, status = "accepted")

        val result = assembly(store, { now }, capturesFor(store, fixture), accepted).poll()

        assertEquals("one request left the device", 1, requests.get())
        assertEquals("the entry is settled", 0, store.readyEntries().count { it.captureId == fixture.id })
        assertEquals("the acceptance was reported", listOf(fixture.id), accepted)
        assertEquals("attempted", 1, result.attempted)
    }

    @Test
    fun `a 2xx with a receipt for DIFFERENT bytes retains the entry and reports no acceptance`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-forged-digest")
        val accepted = mutableListOf<String>()
        val forged = receiptFor(store, fixture.id).replace(
            store.recordedDigest(fixture.id)!!,
            "0".repeat(64),
        )
        responseStatus = 201 // a valid pair, so the digest is the ONLY defect under test
        responseBody = forged

        assembly(store, { now }, capturesFor(store, fixture), accepted).poll()

        assertEquals("a forged receipt must not delete audio", 1, store.readyEntries().count { it.captureId == fixture.id })
        assertTrue("no acceptance is reported", accepted.isEmpty())
    }

    @Test
    fun `a 200 claiming accepted is a mismatched pair and retains`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-pair-200-accepted")
        val accepted = mutableListOf<String>()
        responseStatus = 200
        responseBody = receiptFor(store, fixture.id, status = "accepted")

        assembly(store, { now }, capturesFor(store, fixture), accepted).poll()

        assertEquals("200/accepted is not the contract's pair; the capture stays", 1, store.readyEntries().count { it.captureId == fixture.id })
        assertTrue("no acceptance is reported for a mismatched pair", accepted.isEmpty())
    }

    @Test
    fun `a 201 claiming duplicate is a mismatched pair and retains`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-pair-201-duplicate")
        val accepted = mutableListOf<String>()
        responseStatus = 201
        responseBody = receiptFor(store, fixture.id, status = "duplicate")

        assembly(store, { now }, capturesFor(store, fixture), accepted).poll()

        assertEquals("201/duplicate is not the contract's pair; the capture stays", 1, store.readyEntries().count { it.captureId == fixture.id })
        assertTrue("no acceptance is reported for a mismatched pair", accepted.isEmpty())
    }

    @Test
    fun `a 200 claiming duplicate IS an acceptance and releases`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-pair-200-duplicate")
        val accepted = mutableListOf<String>()
        responseStatus = 200
        responseBody = receiptFor(store, fixture.id, status = "duplicate")

        val result = assembly(store, { now }, capturesFor(store, fixture), accepted).poll()

        assertEquals("the duplicate pair is a real acceptance", 0, store.readyEntries().count { it.captureId == fixture.id })
        assertEquals("the acceptance is reported", listOf(fixture.id), accepted)
        assertEquals("attempted", 1, result.attempted)
    }

    @Test
    fun `a terminal refusal over real HTTP settles as a terminal disposal, not a retry`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-terminal")
        responseStatus = 400
        responseBody = """{"schema_version":1,"code":"invalid_request"}"""

        assembly(store, { now }, capturesFor(store, fixture)).poll()

        assertEquals("a terminal refusal is disposed", 0, store.readyEntries().count { it.captureId == fixture.id })
    }

    @Test
    fun `a revocation that lands DURING the request settles as retained, never released`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-revoked-midflight")
        val accepted = mutableListOf<String>()
        responseStatus = 200
        responseBody = receiptFor(store, fixture.id)
        // Revoke while the request is in flight: the response arrives for an owner that no longer exists.
        onRequest = { store.fence.revoke() }

        assembly(store, { now }, capturesFor(store, fixture), accepted).poll()

        assertEquals("the request did leave", 1, requests.get())
        assertTrue("a post-revocation acceptance reports nothing", accepted.isEmpty())
        assertEquals("the entry is still held", 1, store.readyEntries().count { it.captureId == fixture.id })
    }

    @Test
    fun `a capture that is no longer on disk is retained and sends nothing`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-missing")
        responseStatus = 200
        responseBody = receiptFor(store, fixture.id)

        val result = assembly(store, { now }, mutableMapOf()).poll()

        assertEquals("no request is made for a capture we cannot send", 0, requests.get())
        assertEquals("the entry is retained, not released", 1, store.readyEntries().count { it.captureId == fixture.id })
        // `attempted` counts settled attempts, and a retained attempt IS settled - so 1 here. What must
        // NOT happen is an acceptance: nothing was released and nothing was reported as accepted.
        assertEquals("the attempt was settled as retained", 1, result.attempted)
        assertTrue("nothing was released", store.readyEntries().any { it.captureId == fixture.id })
    }

    @Test
    fun `an ambiguous transport outcome retains the entry`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-ambiguous")
        responseStatus = 500
        responseBody = "not json at all"

        assembly(store, { now }, capturesFor(store, fixture)).poll()

        assertEquals("an unknown outcome never deletes", 1, store.readyEntries().count { it.captureId == fixture.id })
    }

    @Test
    fun `the DUPLICATE pair is an acceptance - 200 with status duplicate releases`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-bytes")
        responseStatus = 200
        responseBody = receiptFor(store, fixture.id, status = "duplicate")

        assembly(store, { now }, capturesFor(store, fixture)).poll()

        assertFalse("a duplicate pair is a validated receipt", store.readyEntries().any { it.captureId == fixture.id })
    }

    @Test
    fun `the audio that reaches the server is the DURABLE capture, byte for byte`() {
        var now = 30_000_000L
        val store = newStore { now }
        val fixture = fixture(store, "assembly-wire-bytes-wire")
        responseStatus = 201
        responseBody = receiptFor(store, fixture.id)
        // Capture the request body the server actually received.
        var received: ByteArray? = null
        onRequest = { received = lastBody }
        // Read the DURABLE capture BEFORE the poll: a validated acceptance deletes it, which is the
        // correct outcome and would otherwise make this comparison impossible.
        val durable = fixture.file.readBytes()
        assertTrue("the durable queue capture is non-empty", durable.isNotEmpty())

        assembly(store, { now }, capturesFor(store, fixture)).poll()

        // A validated acceptance deletes the durable capture: the assertion that it is gone is itself
        // evidence the release was real, and it is why the bytes were read above.
        assertFalse("a validated acceptance deletes the durable capture", fixture.file.exists())
        val body = received ?: error("the server received no body")
        assertTrue(
            "the multipart body must carry the durable capture's exact bytes",
            body.toString(Charsets.ISO_8859_1).contains(durable.toString(Charsets.ISO_8859_1)),
        )
    }
}
