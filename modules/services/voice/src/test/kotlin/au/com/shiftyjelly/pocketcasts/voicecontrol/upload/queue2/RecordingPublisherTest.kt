package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingPublisherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun publisher(queue: FolderQueue) = RecordingPublisher(queue, { "8.20" }, { "12345" })

    private fun sealed(
        wakeRequired: Boolean = true,
        wakeState: SegmentObservation.WakeState = SegmentObservation.WakeState.Detected,
        measurement: SegmentObservation.WakeMeasurement? = SegmentObservation.WakeMeasurement(0.91, 0.55),
        outcome: CollectionOutcome? = CollectionOutcome.Recognized(UUID.randomUUID(), "skip this ad", "en-US"),
        asrModel: ModelRef? = ModelRef.Named("whisper", "base"),
        pcm: ShortArray = ShortArray(1_600) { 7 },
        epoch: String? = "epoch-1",
    ): SealedCapture {
        val id = outcome?.captureId ?: UUID.randomUUID()
        return SealedCapture(
            captureId = id,
            context = CaptureContext(
                captureId = id,
                captureSession = 3,
                routeClass = "ordinary",
                wakeRequired = wakeRequired,
                issuedAuthority = epoch?.let { IssuedAuthority("a@b.c", 1L, 1L, it, "voice-sharing-v1") },
            ),
            pcm = pcm,
            sampleRateHz = 16_000,
            wakeState = wakeState,
            wakeMeasurement = measurement,
            outcome = outcome,
            stageHistory = emptyList(),
            translation = null,
            asrInvocationModel = asrModel,
            routerInvocationModel = null,
            executionOutcome = null,
            allowanceMetadataCap = null,
            cause = SealedCapture.CAUSE_TERMINAL,
        )
    }

    private fun metadata(queue: FolderQueue): JSONObject {
        val rec = queue.recordings().single()
        return JSONObject(String(rec.metadata.readBytes()))
    }

    @Test
    fun `a completed capture is queued as audio plus plain json`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        assertTrue(publisher(q).publish(sealed()))
        assertEquals(1, q.count())
        val json = metadata(q)
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("android", json.getString("platform"))
        // Plain JSON: no digest fields anywhere in the record.
        assertFalse("no digests in a plain record: $json", json.toString().contains("sha256"))
    }

    @Test
    fun `the record carries the real grace state as wake_required`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed(wakeRequired = false))
        assertEquals(
            "wake_required must be the state at segment start, not a guess from the clip",
            false,
            metadata(q).getBoolean("wake_required"),
        )
    }

    @Test
    fun `the measured wake score and threshold and the asr language are recorded`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed())
        val observations = metadata(q).getJSONArray("observations")
        val wake = (0 until observations.length()).map { observations.getJSONObject(it) }
            .single { it.getString("stage") == "wake" }
        assertEquals("completed", wake.getString("state"))
        assertEquals("detected", wake.getString("decision"))
        assertEquals(0.91, wake.getDouble("score"), 1e-9)
        assertEquals(0.55, wake.getDouble("threshold"), 1e-9)

        val asr = (0 until observations.length()).map { observations.getJSONObject(it) }
            .single { it.getString("stage") == "asr" }
        assertEquals("en-US", asr.getString("source_language"))
        assertEquals("skip this ad", asr.getString("text"))
    }

    @Test
    fun `a capture with no wake measurement reports not run rather than inventing a score`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed(measurement = null))
        val observations = metadata(q).getJSONArray("observations")
        val wake = (0 until observations.length()).map { observations.getJSONObject(it) }
            .single { it.getString("stage") == "wake" }
        assertEquals("not_run", wake.getString("state"))
        assertFalse("an unmeasured score must not appear", wake.has("score"))
    }

    @Test
    fun `a segment over the 60 second bound is not kept`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        val tooLong = ShortArray(RecordingPublisher.MAX_SAMPLES + 1)
        assertFalse(publisher(q).publish(sealed(pcm = tooLong)))
        assertEquals(0, q.count())
    }

    @Test
    fun `the queued audio is a wav the server can parse`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed(pcm = ShortArray(800) { 7 }))
        val wav = q.recordings().single().audio.readBytes()
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(wav.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals(RecordingPublisher.WAV_HEADER_BYTES + 1_600, wav.size.toLong())
        // The declared byte count must describe exactly the bytes that travel with it.
        assertEquals(RecordingPublisher.WAV_HEADER_BYTES + 1_600, metadata(q).getJSONObject("audio").getLong("byte_count"))
    }

    @Test
    fun `a capture with no enrollment epoch is dropped rather than sent unattributed`() {
        // enrollment_epoch is a required record field. Dropping is the honest answer; throwing
        // would fail a capture that has already been spoken.
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        assertFalse(publisher(q).publish(sealed(epoch = null)))
        assertEquals(0, q.count())
    }

    @Test
    fun `an empty capture is not queued`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        assertFalse(publisher(q).publish(sealed(pcm = ShortArray(0))))
        assertNull(q.recordings().firstOrNull())
    }
}
