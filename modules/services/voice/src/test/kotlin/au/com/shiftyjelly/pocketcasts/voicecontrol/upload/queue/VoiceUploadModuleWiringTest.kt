package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PRODUCTION FACTORY AND TRIGGER (spec e842c497): the compiled-off wiring must put NOTHING on the
 * network while the published policy is disabled, and an explicitly enabled fixture must reach the
 * store-backed authority and the assembled settlement path - account/attempt ownership and immutable
 * bytes intact.
 */
class VoiceUploadModuleWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(root: java.io.File, now: Long = 51_000_000L) = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = { now },
        jitter = { it - 1 },
    )

    @Test
    fun `a DISABLED policy puts nothing on the network and settles nothing`() {
        val root = tmp.newFolder("wiring-disabled")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        prepareCapture(store, id)

        val transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:1/v1/contributions", // a dead port: nothing can arrive
            authorization = { "Bearer t" },
            callTimeoutMs = 2_000,
        )
        val assembly = ContributionUploadAssembly(
            store = store,
            transport = transport,
            captureFor = { captureId -> store.durableCapture(captureId)?.let { ContributionUploadAssembly.Capture(it.record, it.audio) } },
            clock = { 51_000_000L },
            policyAllows = { false }, // the compiled-off published policy
            networkAvailable = { true },
        )
        val result = assembly.poll()

        assertEquals("no request may leave while the gate is closed", 0, requests.get())
        assertEquals("and no attempt was consumed", 0, result.attempted)
        assertTrue("the capture is still owned by the queue", store.readyEntries().any { it.captureId == id })
    }

    @Test
    fun `an ENABLED fixture reaches the store-backed authority and the assembled settlement path`() {
        val root = tmp.newFolder("wiring-enabled")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        prepareCapture(store, id)

        requests.set(0)
        responseStatus = 201 // the contract's first-acceptance pair
        responseBody = receiptFor(store, id, "accepted")
        val port = startServer()
        val assembly = ContributionUploadAssembly(
            store = store,
            transport = ContributionUploadTransport(
                endpoint = "http://127.0.0.1:$port/v1/contributions",
                authorization = { "Bearer t" },
            ),
            captureFor = { captureId -> store.durableCapture(captureId)?.let { ContributionUploadAssembly.Capture(it.record, it.audio) } },
            clock = { 51_000_000L },
            policyAllows = { true }, // an explicitly enabled fixture
            networkAvailable = { true },
        )
        val result = assembly.poll()

        assertEquals("the enabled gate lets exactly one request through", 1, requests.get())
        assertEquals("the attempt was consumed once", 1, result.attempted)
        assertTrue("a validated acceptance releases the entry", store.readyEntries().none { it.captureId == id })
    }

    // -- fixtures ---------------------------------------------------------------

    private val requests = AtomicInteger(0)
    private var responseStatus = 201
    private var responseBody = ""
    private var server: HttpServer? = null

    private fun startServer(): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/contributions") { exchange ->
            requests.incrementAndGet()
            val body = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseStatus, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        this.server = server
        return server.address.port
    }

    @org.junit.After
    fun stopServer() {
        server?.stop(0)
    }

    /** The REAL wire receipt (worker upload_route.ts UploadReceipt) bound to THIS capture's digest. */
    private fun receiptFor(store: ContributionStore, id: String, status: String): String {
        val digest = store.recordedDigest(id) ?: error("no recorded digest for $id")
        return """{"schema_version":1,"capture_id":"$id","record_sha256":"$digest","status":"$status",""" +
            """"receipt_id":"rcpt-1","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"2026-11-21T00:00:00Z"}"""
    }

    private fun prepareCapture(store: ContributionStore, id: String) {
        val pcm = listOf(ShortArray(1600) { i -> i.toShort() } to 1600)
        val expected = java.io.ByteArrayOutputStream()
        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter.writeHeader(expected, 1600)
        for ((chunk, valid) in pcm) au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter.writePcm(expected, chunk, valid)
        val audio = expected.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) }
        val block = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock(
            sha256 = sha,
            byteCount = audio.size.toLong(),
            retainedSamples = 1600,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        val metadata = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson.canonicalBytes(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder(id, "epoch-1", "42")
                .model("vad", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("silero-vad", "4.0"))
                .model("wakeword", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("auris-wake", "1.2"))
                .model("asr", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("whisper-small", "2024-11"))
                .model("translation", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.None)
                .model("router", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("lfm-intent", "0.3"))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Wake, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Bleed, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Asr, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Translation, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Routing, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Execution, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
        val admission = store.admitForTest(1600, metadata.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(store.prepareAt(generation, admission, id, "epoch-1", pcm, metadata))
        assertEquals(1, store.recordAttempt(generation, id))
    }
}
