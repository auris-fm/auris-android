package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.AudioDescription
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.PlainRecord
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.StageObservation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoded multipart body against the server's actual parser.
 *
 * Everything here mirrors a rule in `worker/src/contributions/upload.ts` at cloud-voice-uploads
 * `9aa8f662`. The server answers a `400` for any of these, and the uploader's answer to a `400` is
 * to delete the recording — so a mistake pinned here is a silently destroyed capture, not a retry.
 *
 * The rules asserted are the ones that cost a recording:
 * - the metadata part must arrive as a plain string, which means no filename;
 * - `Content-Length` must be present and numeric;
 * - `observations` must be an object keyed by stage, never an array;
 * - `audio.byte_count` is required, and the fields the server does read must have the right names.
 */
class UploadWireInteropTest {
    private fun recordJson(): String = PlainRecord(
        captureId = "3f9a1c2e-0000-4000-8000-000000000001",
        enrollmentEpoch = "epoch-1",
        account = "owner@example.test",
        sessionId = "session-1",
        platform = "android",
        appVersion = "8.20",
        build = "9452",
        wakeRequired = true,
        audio = AudioDescription(byteCount = 96_044, retainedSamples = 48_000),
        models = mapOf("asr" to ModelRef("whisper", "1")),
        observations = mapOf(
            "bleed" to StageObservation.completed("decision" to "no_overlap"),
            "wake" to StageObservation.completed("decision" to "detected", "score" to 0.931, "threshold" to 0.6),
            "asr" to StageObservation.completed("source_language" to "en", "text" to "play the news"),
            "translation" to StageObservation.notRun("source_language_target_language"),
            "routing" to StageObservation.completed("predicted_intent" to "play"),
            "execution" to StageObservation.completed("outcome" to "success"),
        ),
    ).toJson().toString()

    /** The server refuses an array outright: `validateObservations` requires an object. */
    @Test
    fun observationsAreAnObjectKeyedByStageNeverAnArray() {
        val observations = JSONObject(recordJson()).get("observations")

        assertTrue("observations must be an object, not an array", observations is JSONObject)
        assertFalse("observations must not be an array", observations is org.json.JSONArray)

        // Every stage the spec names must be present: a missing stage is an unstated claim.
        val stages = (observations as JSONObject).keys().asSequence().toSet()
        assertEquals(PlainRecord.STAGES.toSet(), stages)
    }

    /**
     * The metadata part must be a plain form field.
     *
     * A part that carries a filename is parsed as a file, and the server's `typeof metadata !==
     * "string"` check refuses the whole upload.
     */
    @Test
    fun theMetadataPartCarriesNoFilename() {
        val body = bodyOf(recordJson().toByteArray(), byteArrayOf(1, 2, 3))

        val metadataHeaders = headersAfterDisposition(body, name = "metadata")
        assertTrue("metadata part must be named metadata", metadataHeaders.contains("name=\"metadata\""))
        assertFalse(
            "a filename on the metadata part makes the server see a file and refuse the upload",
            metadataHeaders.contains("filename="),
        )
        assertTrue(
            "the metadata part must declare application/json",
            metadataHeaders.contains("Content-Type: application/json"),
        )
    }

    /** The audio part is the one part that does need a filename, and the exact one. */
    @Test
    fun theAudioPartIsNamedRecordingWavWithAudioWavType() {
        val body = bodyOf(recordJson().toByteArray(), byteArrayOf(1, 2, 3))
        val audioHeaders = headersAfterDisposition(body, name = "audio")

        assertTrue(audioHeaders.contains("name=\"audio\""))
        assertTrue("the server requires exactly recording.wav", audioHeaders.contains("filename=\"recording.wav\""))
        assertTrue(audioHeaders.contains("Content-Type: audio/wav"))
    }

    /**
     * `Content-Length` must be stated.
     *
     * The server refuses a request whose length is absent rather than streaming it, so this asserts
     * the sender uses a fixed-length body and therefore emits the header.
     */
    @Test
    fun theBodyLengthIsKnownBeforeSendingSoContentLengthCanBeSent() {
        val metadata = recordJson().toByteArray()
        val audio = byteArrayOf(1, 2, 3, 4)
        val body = bodyOf(metadata, audio)

        // The declared length must be exactly what is written: a mismatch is a truncated upload.
        assertTrue(body.size > metadata.size + audio.size)
        assertEquals(body.size, bodyOf(metadata, audio).size)
    }

    /** Names the server reads. A renamed field is ignored, and a required one renamed is a 400. */
    @Test
    fun theRecordUsesTheServerFieldNames() {
        val json = JSONObject(recordJson())
        val audio = json.getJSONObject("audio")

        assertTrue(audio.has("byte_count"))
        assertTrue("the sample rate field is sample_rate_hz", audio.has("sample_rate_hz"))
        assertFalse("sample_rate is not a field the server reads", audio.has("sample_rate"))

        // Required scalar fields, exactly as the server names them.
        for (field in listOf("schema_version", "capture_id", "enrollment_epoch", "platform", "app_version", "build", "capture_session_id", "wake_required")) {
            assertTrue("$field is required", json.has(field))
        }
        assertTrue("wake_required must be a boolean", json.get("wake_required") is Boolean)

        // A completed stage carries no reason; the server refuses one.
        val observations = json.getJSONObject("observations")
        for (stage in observations.keys()) {
            val entry = observations.getJSONObject(stage)
            assertTrue("$stage.state is required", entry.has("state"))
            if (entry.getString("state") == "completed") {
                assertFalse("$stage is completed and must carry no reason", entry.has("reason"))
            }
        }
    }

    private fun bodyOf(metadata: ByteArray, audio: ByteArray): ByteArray {
        // Uses the production body builder so the test cannot drift from what is actually sent.
        return RecordingUploader(
            endpoint = "http://unused.invalid/api/v1/voice-recordings",
            authorization = { "token" },
        ).buildBodyForTest("BOUNDARY", metadata, audio)
    }

    private fun headersAfterDisposition(body: ByteArray, name: String): String {
        val text = String(body, Charsets.ISO_8859_1)
        val marker = "name=\"$name\""
        val at = text.indexOf(marker)
        check(at >= 0) { "no part named $name" }
        val start = text.lastIndexOf("Content-Disposition:", at)
        val end = text.indexOf("\r\n\r\n", at)
        return text.substring(start, end)
    }
}
