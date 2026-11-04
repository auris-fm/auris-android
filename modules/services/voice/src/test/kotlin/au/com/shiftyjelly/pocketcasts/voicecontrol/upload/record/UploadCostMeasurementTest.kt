package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.sharedtest.JvmTask
import au.com.shiftyjelly.pocketcasts.utils.config.FirebaseConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionUploadTransport
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.OwnedAudio
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigInfo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * SPEC BOUNDARY 10 (voice-recording-uploads.md): "Measured client policy refresh/background
 * transfer and upload/storage costs are reported with environment and limits."
 *
 * These controls MEASURE costs through the real production serializers/transport/provider and
 * print the numbers (JUnit captures system-out) for the evidence receipt. They are not merely
 * correctness tests: each asserts a COMPILED ceiling so a regression that inflates a cost fails
 * here. Environment and limits are stated in the receipt; the JVM host cannot measure device
 * radio latency or acoustic acceptance — those are marked UNVERIFIED there, per @spec.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadCostMeasurementTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    // --- Transfer cost: real canonical record + real multipart envelope over a real socket. ---

    private fun referenceRecord(captureId: String, wavBytes: Int, retainedSamples: Int): RecordNode.Obj = RecordNode.Obj(
        mapOf(
            "schema_version" to RecordNode.ofInt(1),
            "capture_id" to RecordNode.Str(captureId),
            "enrollment_epoch" to RecordNode.Str("epoch-1"),
            "platform" to RecordNode.Str("android"),
            "app_version" to RecordNode.Str("8.9"),
            "build" to RecordNode.Str("123"),
            "capture_session_id" to RecordNode.Str(UUID.randomUUID().toString()),
            "models" to RecordNode.Arr(emptyList()),
            "observations" to RecordNode.Arr(emptyList()),
            "audio" to RecordNode.Obj(
                mapOf(
                    "sha256" to RecordNode.Str("0".repeat(64)),
                    "byte_count" to RecordNode.ofInt(wavBytes.toLong()),
                    "encoding" to RecordNode.Str("pcm_s16le"),
                    "sample_rate_hz" to RecordNode.ofInt(16000),
                    "channels" to RecordNode.ofInt(1),
                    "retained_samples" to RecordNode.ofInt(retainedSamples.toLong()),
                    "original_format" to RecordNode.Str("pcm_s16le"),
                    "conversion_version" to RecordNode.Str("v1"),
                    "truncated" to RecordNode.Bool(false),
                    "dropped_spans" to RecordNode.Arr(emptyList()),
                ),
            ),
        ),
    )

    /** A real WAV: 44-byte canonical header + s16le mono 16 kHz PCM for `seconds`. */
    private fun realWav(seconds: Int, root: File, name: String = "recording.wav"): Pair<File, Int> {
        val samples = seconds * 16_000
        val pcm = ByteArray(samples * 2)
        java.util.Random(7).nextBytes(pcm) // incompressible content, like speech
        val header = ByteArray(44)
        fun put(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        put(4, 36 + pcm.size)
        "WAVEfmt ".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        put(16, 16)
        header[20] = 1
        header[22] = 1
        put(24, 16_000)
        put(28, 32_000)
        header[34] = 16
        "data".forEachIndexed { i, c -> header[36 + i] = c.code.toByte() }
        put(40, pcm.size)
        val file = File(root, name)
        file.writeBytes(header + pcm)
        return file to (44 + pcm.size)
    }

    @Test
    fun `measured upload transfer cost - canonical record and multipart envelope bytes`() {
        val (wav, wavBytes) = realWav(seconds = 10, root = tmp.newFolder("transfer"))
        val id = UUID.randomUUID().toString()
        val record = referenceRecord(id, wavBytes = wavBytes, retainedSamples = (wavBytes - 44) / 2)
        val recordBytes = CanonicalJson.canonicalBytes(record).size

        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        // Shutdown in finally: an assertion failure must also release the socket (reviewer note).
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody("{}"))
            val transport = ContributionUploadTransport(
                endpoint = server.url("/api/v1/voice/contributions").toString(),
                authorization = { "Bearer receipt-token" },
            )
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(wav.readBytes()).joinToString("") { "%02x".format(it) }
            val owned = OwnedAudio(java.io.FileInputStream(wav), wav.length(), sha)
            val uploadResult = owned.use { kotlinx.coroutines.runBlocking { transport.upload(id, record, it) } }
            assertTrue("PREMISE: the measured upload is accepted", uploadResult is ContributionUploadTransport.Response.Accepted)

            val recorded = server.takeRequest()
            val body = recorded.body.readByteArray()
            val overhead = body.size - wavBytes
            // REPORT (asserted ceiling: envelope+metadata must stay under 16 KiB over the audio):
            println("B10-TRANSFER: wav_bytes=$wavBytes record_json_bytes=$recordBytes multipart_body_bytes=${body.size} envelope_overhead_bytes=$overhead")
            assertTrue("envelope overhead must stay under 16 KiB (measured $overhead)", overhead < 16 * 1024)
        } finally {
            server.shutdown()
        }
    }

    // --- Storage cost: the durable per-segment footprint the store retains. ---

    @Test
    fun `measured storage cost - wav plus canonical record per retained segment`() {
        val dir = tmp.newFolder("store")
        val n = 10
        repeat(n) { i ->
            val (wav, wavBytes) = realWav(seconds = 5, root = dir, name = "segment-$i.wav")
            val record = referenceRecord(UUID.randomUUID().toString(), wavBytes = wavBytes, retainedSamples = (wavBytes - 44) / 2)
            File(dir, "segment-$i.json").writeBytes(CanonicalJson.canonicalBytes(record))
        }
        // Measure the directory's ACTUAL footprint (files on disk), not an in-memory sum.
        val files = dir.listFiles().orEmpty()
        assertEquals("each segment is exactly two durable artifacts", n * 2, files.size)
        val total = files.sumOf { it.length() }
        val perSegment = total / n
        // REPORT (asserted ceiling: audio-dominated; record JSON ~0.3%).
        println(
            "B10-STORAGE: segments=$n files=${files.size} measured_directory_bytes=$total per_segment_bytes=$perSegment " +
                "(5s 16kHz mono s16le wav + canonical record json; the durable STORE's own index/lease overhead is NOT exercised here - UNVERIFIED)",
        )
        // Ceiling: the audio payload itself (5 s = 160,044 B) plus a 16 KiB allowance for the
        // canonical record and any sidecar — the durable footprint must be audio-dominated.
        val ceiling = (5 * 16_000 * 2 + 44) + 16 * 1024
        assertTrue("per-segment durable footprint must stay under $ceiling (measured $perSegment)", perSegment < ceiling)
    }
}
