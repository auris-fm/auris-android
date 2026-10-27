package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ASSEMBLED LIVE CANCELLATION (reviewer 99b3947d / ba490eee, spec 05f52f98).
 *
 * The previous controls proved the transport *can* cancel a call, but they cancelled it by hand. That is
 * capability evidence, not composition: `LaunchLease.bindAbort` had no production caller at all, so a real
 * store revocation reached a flag that a blocked writer never reads.
 *
 * This control revokes the STORE LEASE - the actual authorization authority - and follows the production
 * chain: store revocation -> lease abort -> `Call.cancel()` -> blocked writer terminates. Nothing in the
 * fixture calls transport cancellation directly.
 */
class AssembledLiveCancellationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sockets = mutableListOf<ServerSocket>()
    private val pools = mutableListOf<java.util.concurrent.ExecutorService>()

    @After
    fun closeAll() {
        sockets.forEach { runCatching { it.close() } }
        pools.forEach { it.shutdownNow() }
    }

    /** Accepts and never reads: the request body cannot drain, so the write is genuinely blocked. */
    private fun blackHole(): Int {
        val socket = ServerSocket(0)
        sockets += socket
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        pools += pool
        pool.submit {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // Held open and never read.
                pool.submit { runCatching { client.getInputStream().read() } }
            }
        }
        return socket.localPort
    }

    /**
     * The queue only publishes a VALIDATED record, so the fixture builds the same shape the recorder does
     * for 1600 samples - the capture supplier then re-reads it from disk exactly as production does.
     */
    private fun validRecordBytes(id: String): ByteArray {
        val audio = ByteArray(44 + 1600 * 2)
        val block = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock(
            sha256 = "0".repeat(64),
            byteCount = audio.size.toLong(),
            retainedSamples = 1600,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        return au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson.canonicalBytes(
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
    }

    private fun record(id: String): RecordNode.Obj = RecordNode.Obj(
        mapOf(
            "schema_version" to RecordNode.ofInt(1),
            "capture_id" to RecordNode.Str(id),
            "enrollment_epoch" to RecordNode.Str("epoch-1"),
            "platform" to RecordNode.Str("android"),
            "app_version" to RecordNode.Str("8.9"),
            "build" to RecordNode.Str("123"),
            "capture_session_id" to RecordNode.Str("session-$id"),
            "models" to RecordNode.Arr(emptyList()),
            "observations" to RecordNode.Arr(emptyList()),
            "audio" to RecordNode.Obj(
                mapOf(
                    "sha256" to RecordNode.Str("0".repeat(64)),
                    "byte_count" to RecordNode.ofInt(8L * 1024 * 1024),
                    "encoding" to RecordNode.Str("pcm_s16le"),
                    "sample_rate_hz" to RecordNode.ofInt(16000),
                    "channels" to RecordNode.ofInt(1),
                    "retained_samples" to RecordNode.ofInt(0),
                    "original_format" to RecordNode.Str("pcm_s16le"),
                    "conversion_version" to RecordNode.Str("v1"),
                    "truncated" to RecordNode.Bool(false),
                    "dropped_spans" to RecordNode.Arr(emptyList()),
                ),
            ),
        ),
    )

    /**
     * A capture far larger than any socket buffer, so the write blocks once the peer stops reading, and
     */
    private fun capture(id: String): File = File(tmp.root, "$id.recording.wav").also { file ->
        file.writeBytes(ByteArray(8 * 1024 * 1024) { b -> (b % 251).toByte() })
    }

    @Test
    fun `a STORE revocation cancels the blocked call through the assembled caller, not by hand`() {
        var now = 31_000_000L
        val port = blackHole()
        val id = UUID.randomUUID().toString()
        val storeRef = arrayOfNulls<ContributionStore>(1)
        val store = ContributionStore(
            tmp.newFolder("assembled"),
            QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { it - 1 },
        )
        storeRef[0] = store
        val metadata = validRecordBytes(id)
        val admission = store.admitForTest(1600, metadata.size)
        val generation = store.fence.currentGeneration()
        val pcm = listOf(ShortArray(1600) { i -> i.toShort() } to 1600)
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", pcm, metadata, "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))

        // Progress is signalled by ACTUAL BODY WRITES leaving this process, not by file creation or a
        // sleep: backpressure is established only when chunks were emitted and the peer is not draining.
        val chunksWritten = java.util.concurrent.atomic.AtomicInteger(0)
        val bodyFlowing = CountDownLatch(1)
        val transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:$port/v1/contributions",
            authorization = { "Bearer token" },
            callTimeoutMs = 120_000,
            onBodyProgressForTest = {
                if (chunksWritten.incrementAndGet() >= 8) bodyFlowing.countDown()
            },
        )
        val assembly = ContributionUploadAssembly(
            store = store,
            transport = transport,
            captureFor = { captureId ->
                val file = capture(captureId)
                ContributionUploadAssembly.Capture(record(captureId), file, owned = ownedOf(file))
            },
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
        )

        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        pools += pool
        val poll = pool.submit<ContributionRetryCaller.PollResult> { assembly.poll() }

        // The writer has EMITTED chunks into a peer that never reads: the write is genuinely blocked.
        assertTrue("body bytes were actually written", bodyFlowing.await(15, TimeUnit.SECONDS))
        val revokedAt = System.nanoTime()
        // THE ONLY cancellation trigger: revoke the lease authority in the STORE.
        store.revokeAndCancelLaunches("epoch changed")
        val result = poll.get(30, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - revokedAt) / 1_000_000

        assertTrue(
            "the store revocation reached the blocked Call (writer returned in ${elapsedMs}ms)",
            elapsedMs < 15_000,
        )
        assertEquals("the revocation is attributed to the epoch", 1, result.cancelledEpoch)
        assertEquals("nothing was settled as a completed attempt", 0, result.attempted)
        assertEquals("and this transport holds no call afterwards", 0, transport.inFlightForTest())
        assertTrue("no call remains registered", transport.registeredCallForTest() == null)
    }

    @Test
    fun `a binder that throws leaks neither the handle nor the permit`() {
        val port = blackHole()
        val id = UUID.randomUUID().toString()
        val transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:$port/v1/contributions",
            authorization = { "Bearer token" },
            callTimeoutMs = 20_000,
        )
        val file = File(tmp.root, "$id.recording.wav").also { it.writeBytes(ByteArray(1024) { b -> (b % 251).toByte() }) }

        // The registration callback fails AFTER the handle was registered: cleanup must still run.
        val response = transport.upload(
            id,
            record(id),
            ownedOf(file),
            onRegistered = { throw IllegalStateException("binder failed") },
        )
        assertTrue("a failed binder is an unknown outcome: $response", response is ContributionUploadTransport.Response.Ambiguous)
        assertEquals("no permit leaked", 0, transport.inFlightForTest())
        assertTrue("no handle leaked", transport.registeredCallForTest() == null)
    }

    /** Wraps a fixture file as the owned source the transport now requires; the caller keeps it open. */
    private fun ownedOf(file: File): OwnedAudio = OwnedAudio(
        java.io.FileInputStream(file),
        file.length(),
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
    )
}
