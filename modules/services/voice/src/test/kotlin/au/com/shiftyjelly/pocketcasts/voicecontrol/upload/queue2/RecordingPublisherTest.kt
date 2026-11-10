package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance
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
        sampleRateHz: Int = 16_000,
        stageHistory: List<CollectionOutcome> = emptyList(),
        translation: TranslationProvenance? = null,
        executionOutcome: ExecutionOutcome? = null,
        routerModel: ModelRef? = null,
        cause: String = SealedCapture.CAUSE_TERMINAL,
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
            sampleRateHz = sampleRateHz,
            wakeState = wakeState,
            wakeMeasurement = measurement,
            outcome = outcome,
            stageHistory = stageHistory,
            translation = translation,
            asrInvocationModel = asrModel,
            routerInvocationModel = routerModel,
            executionOutcome = executionOutcome,
            allowanceMetadataCap = null,
            cause = cause,
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
        // observations is an OBJECT keyed by stage, which is what the server validates.
        val observations = metadata(q).getJSONObject("observations")
        val wake = observations.getJSONObject("wake")
        assertEquals("completed", wake.getString("state"))
        assertEquals("detected", wake.getString("decision"))
        assertEquals(0.91, wake.getDouble("score"), 1e-9)
        assertEquals(0.55, wake.getDouble("threshold"), 1e-9)

        val asr = observations.getJSONObject("asr")
        assertEquals("en-US", asr.getString("source_language"))
        assertEquals("skip this ad", asr.getString("text"))
    }

    @Test
    fun `a capture with no wake measurement reports not run rather than inventing a score`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed(measurement = null))
        val observations = metadata(q).getJSONObject("observations")
        val wake = observations.getJSONObject("wake")
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

    @Test
    fun `the signal filter is not claimed as a decision it never recorded`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed())
        val bleed = metadata(q).getJSONObject("observations").getJSONObject("bleed")
        // The filter passed the segment, but what it decided was never recorded. Reporting
        // "no_overlap" would invent a fact the engine does not keep.
        assertFalse("a decision that was never observed must not be asserted", bleed.has("decision"))
        assertEquals("unknown", bleed.getString("state"))
    }

    @Test
    fun `a capture refused before recognition reports asr as not run with the recorded reason`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        val refused = CollectionOutcome.NotCollected(UUID.randomUUID(), "rejected_by_wake")
        publisher(q).publish(
            sealed(outcome = refused, asrModel = null, measurement = null, stageHistory = listOf(refused)),
        )
        val observations = metadata(q).getJSONObject("observations")
        val asr = observations.getJSONObject("asr")
        assertEquals("asr never ran, so it cannot be completed", "not_run", asr.getString("state"))
        assertFalse(asr.has("text"))

        // The wake detector did answer, and its answer was no.
        assertEquals("completed", observations.getJSONObject("wake").getString("state"))
        assertEquals("rejected", observations.getJSONObject("wake").getString("decision"))
    }

    @Test
    fun `a filter refusal keeps its recorded overlap decision`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        val refused = CollectionOutcome.NotCollected(UUID.randomUUID(), "bleed")
        publisher(q).publish(sealed(outcome = refused, asrModel = null, measurement = null, stageHistory = listOf(refused)))
        val bleed = metadata(q).getJSONObject("observations").getJSONObject("bleed")
        // The engine records this refusal, so it is a known decision and must survive.
        assertEquals("completed", bleed.getString("state"))
        assertEquals("overlap", bleed.getString("decision"))
    }

    @Test
    fun `each execution outcome uses the spec's state outcome and reason triple`() {
        val cases = listOf(
            Triple(ExecutionOutcome.Failure, "failed", "action_failed"),
            Triple(ExecutionOutcome.Refused, "failed", "client_refused"),
            Triple(ExecutionOutcome.Cancelled, "cancelled", "cancelled_by_user"),
        )
        for ((index, case) in cases.withIndex()) {
            val (outcome, state, reason) = case
            val q = FolderQueue(tmp.newFolder("q$index"), 32, {})
            publisher(q).publish(sealed(executionOutcome = outcome))
            val execution = metadata(q).getJSONObject("observations").getJSONObject("execution")
            assertEquals("state for $outcome", state, execution.getString("state"))
            assertEquals("reason for $outcome", reason, execution.getString("reason"))
            assertEquals("outcome for $outcome", outcome.wire, execution.getString("outcome"))
        }
    }

    @Test
    fun `a successful execution is completed with its outcome and no reason`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed(executionOutcome = ExecutionOutcome.Success))
        val execution = metadata(q).getJSONObject("observations").getJSONObject("execution")
        assertEquals("completed", execution.getString("state"))
        assertEquals("success", execution.getString("outcome"))
        assertFalse("a completed stage states no reason", execution.has("reason"))
    }

    @Test
    fun `an unobserved execution result is unknown and carries no outcome`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(sealed(executionOutcome = ExecutionOutcome.NotObserved))
        val execution = metadata(q).getJSONObject("observations").getJSONObject("execution")
        assertEquals("unknown", execution.getString("state"))
        assertFalse("no result was seen, so no outcome may be reported", execution.has("outcome"))
    }

    @Test
    fun `a translation failure is not reported as equal languages`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        publisher(q).publish(
            sealed(
                translation = TranslationProvenance(
                    model = ModelRef.Named("t", "v"),
                    producedResult = false,
                    targetLanguage = null,
                    failedWith = "translator_unavailable",
                ),
            ),
        )
        val translation = metadata(q).getJSONObject("observations").getJSONObject("translation")
        assertEquals("failed", translation.getString("state"))
        assertEquals("translator_unavailable", translation.getString("reason"))
    }

    @Test
    fun `a router that could not be loaded is recorded as failed, not as never invoked`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        val id = UUID.randomUUID()
        // Recognition completed, then the router could not be loaded: routing did not merely fail
        // to happen, it was attempted and failed. Reporting "not invoked" would tell a reader the
        // pipeline never reached routing, which hides a broken capability behind a benign skip.
        publisher(q).publish(
            sealed(
                outcome = CollectionOutcome.RoutingFailed(id, "router_unavailable"),
                stageHistory = listOf(CollectionOutcome.Recognized(id, "play", "en"), CollectionOutcome.RoutingFailed(id, "router_unavailable")),
            ),
        )
        val routing = metadata(q).getJSONObject("observations").getJSONObject("routing")
        assertEquals("failed", routing.getString("state"))
        assertEquals("router_unavailable", routing.getString("reason"))
    }

    @Test
    fun `a router that was still pending when the wait expired is unknown, not a skip`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        val id = UUID.randomUUID()
        // Recognition completed but routing never reported before the bounded wait sealed the
        // capture. The result is genuinely unknown; claiming "not invoked" would assert the
        // pipeline never reached routing, which this capture does not establish.
        publisher(q).publish(
            sealed(
                outcome = CollectionOutcome.Recognized(id, "play", "en"),
                stageHistory = listOf(CollectionOutcome.Recognized(id, "play", "en")),
                routerModel = null,
                cause = SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED,
            ),
        )
        val routing = metadata(q).getJSONObject("observations").getJSONObject("routing")
        assertEquals("unknown", routing.getString("state"))
        assertEquals("finalized_before_result", routing.getString("reason"))
    }

    @Test
    fun `a capture with no handler wired records why nothing was routed`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        val id = UUID.randomUUID()
        publisher(q).publish(
            sealed(
                outcome = CollectionOutcome.RoutingFailed(id, "no_handler"),
                stageHistory = listOf(CollectionOutcome.Recognized(id, "play", "en"), CollectionOutcome.RoutingFailed(id, "no_handler")),
            ),
        )
        val routing = metadata(q).getJSONObject("observations").getJSONObject("routing")
        assertEquals("failed", routing.getString("state"))
        assertEquals("no_handler", routing.getString("reason"))
    }

    @Test
    fun `a capture at a rate the server refuses is not published`() {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        // The server accepts only 16 kHz. Sending another rate would earn a 400, and the uploader's
        // answer to a 400 is to delete the recording, so it must never be queued.
        assertFalse(publisher(q).publish(sealed(sampleRateHz = 8_000)))
        assertEquals(0, q.count())
    }
}
