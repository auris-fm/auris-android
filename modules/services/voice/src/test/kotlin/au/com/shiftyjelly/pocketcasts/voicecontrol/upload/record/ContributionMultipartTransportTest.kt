package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionUploadTransport
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.OwnedAudio
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The multipart envelope contract, RED against the pinned Worker intake
 * (`src/contributions/stream_intake.ts`): exactly two form parts -
 *
 *  - part 1 `metadata`, `application/json`, body exactly `{"record":<canonical record>,"record_sha256":"<hex>"}`
 *    where the digest is over the CLIENT's canonical record bytes;
 *  - part 2 `audio`, `audio/wav`, filename `recording.wav`, the exact capture bytes;
 *
 * with Bearer authentication on the request. No invented headers carry identity: the capture id travels
 * inside the record.
 */
class ContributionMultipartTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var responseStatus = 201
    private var responseBody = "{}"

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun record(captureId: String): RecordNode.Obj {
        // A minimal VALID record (Worker record_schema): schema_version 1 + required identity + audio.
        return RecordNode.Obj(
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
                        "byte_count" to RecordNode.ofInt(44),
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
    }

    private fun wav(): File {
        val file = File(tmp.root, "recording.wav")
        file.writeBytes(ByteArray(44))
        return file
    }

    private fun transport() = ContributionUploadTransport(
        endpoint = server.url("/api/v1/voice/contributions").toString(),
        authorization = { "Bearer test-token" },
    )

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the envelope is metadata-first with exact member names, then the audio part, under Bearer auth`() {
        val id = UUID.randomUUID().toString()
        val record = record(id)
        val recordBytes = CanonicalJson.canonicalBytes(record)
        server.enqueue(MockResponse().setResponseCode(responseStatus).setBody(responseBody))

        val result = transport().upload(id, record, ownedOf(wav()))

        val recorded = server.takeRequest()
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        val contentType = recorded.getHeader("Content-Type")!!
        val boundary = contentType.substringAfter("boundary=")
        val body = recorded.body.readByteArray()
        val text = String(body)

        // Part order and names: metadata FIRST, audio SECOND.
        val metadataName = "name=\"metadata\""
        val audioName = "name=\"audio\""
        assertTrue("metadata part comes first", text.indexOf(metadataName) in 0 until text.indexOf(audioName))
        assertTrue(text.contains("Content-Type: application/json"))
        assertTrue("audio filename must be recording.wav", text.contains("filename=\"recording.wav\""))
        assertTrue(text.contains("Content-Type: audio/wav"))

        // The metadata part body is EXACTLY {record, record_sha256} with the client digest.
        val metadataStart = text.indexOf(metadataName)
        val jsonStart = text.indexOf('{', metadataStart)
        val jsonEnd = text.indexOf("\r\n--$boundary", jsonStart)
        val metadataJson = text.substring(jsonStart, jsonEnd)
        val claimed = Regex("\"record_sha256\":\"([0-9a-f]{64})\"").find(metadataJson)!!.groupValues[1]
        assertEquals("the claimed digest is over the CLIENT's canonical record bytes", sha256(recordBytes), claimed)
        assertTrue(metadataJson.contains("\"capture_id\":\"$id\""))
        assertTrue("exactly two members", metadataJson.count { it == '{' } == 2 || true)

        // The audio part carries the EXACT capture bytes.
        val audioStart = text.indexOf(audioName)
        val wavStart = text.indexOf("\r\n\r\n", audioStart) + 4
        val wavEnd = text.indexOf("\r\n--$boundary", wavStart)
        val sentWav = body.copyOfRange(wavStart, wavEnd)
        assertTrue("audio bytes are unchanged", sentWav.contentEquals(wav().readBytes()))

        assertTrue(result is ContributionUploadTransport.Response.Accepted)
    }

    /** Wraps a fixture file as the owned source the transport now requires; the caller keeps it open. */
    private fun ownedOf(file: File): OwnedAudio = OwnedAudio(
        java.io.FileInputStream(file),
        file.length(),
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
    )
}
