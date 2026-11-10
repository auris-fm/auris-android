package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wake-negative segment is still a recording, and still uploads.
 *
 * The wake decision is metadata about the capture, not a gate on sending it: a user who says
 * something without the wake word has still had their audio recorded, and the spec says a
 * completed speech segment is owed an upload. Gating on it would silently withhold data the record
 * itself says was captured, so this pins the whole path from capture to wire.
 */
class WakeRejectedStillUploadsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun publisher(queue: FolderQueue) = RecordingPublisher(queue, { "8.20" }, { "9452" })

    /** A segment the detector heard and refused: real speech, low score. */
    private fun wakeRejected(): SealedCapture {
        val id = UUID.randomUUID()
        return SealedCapture(
            captureId = id,
            context = CaptureContext(
                captureId = id,
                captureSession = 1,
                routeClass = "ordinary",
                wakeRequired = true,
                issuedAuthority = IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "voice-sharing-v1"),
            ),
            pcm = ShortArray(1_600) { 7 },
            sampleRateHz = 16_000,
            wakeState = SegmentObservation.WakeState.NotDetected,
            wakeMeasurement = SegmentObservation.WakeMeasurement(score = 0.05, threshold = 0.81),
            outcome = CollectionOutcome.NotCollected(id, "rejected_by_wake"),
            stageHistory = listOf(CollectionOutcome.NotCollected(id, "rejected_by_wake")),
            translation = null,
            asrInvocationModel = null,
            routerInvocationModel = null,
            executionOutcome = null,
            allowanceMetadataCap = null,
            cause = SealedCapture.CAUSE_TERMINAL,
        )
    }

    @Test
    fun `a wake-rejected capture is published and reaches the wire`() = runBlocking {
        val queue = FolderQueue(tmp.newFolder("q"), 32, {})

        // It must be queued at all - a filter before the queue would lose it silently.
        assertEquals(true, publisher(queue).publish(wakeRejected()))
        assertEquals("a wake-negative recording must still be queued", 1, queue.count())

        // Everything downstream of the refusal says so, rather than claiming it merely was not
        // invoked: a reader of the record should be able to see that the wake word stopped it.
        val recorded = queue.recordings().single()
        val observations = org.json.JSONObject(recorded.metadata.readText()).getJSONObject("observations")
        for (stage in listOf("asr", "translation", "routing", "execution")) {
            val entry = observations.getJSONObject(stage)
            assertEquals("$stage must not claim it ran", "not_run", entry.getString("state"))
            assertEquals("$stage must carry the refusal reason", "rejected_by_wake", entry.getString("reason"))
        }

        // And the loop must actually send it: the gate is about entitlement, not the wake result.
        val sent = mutableListOf<String>()
        val loop = UploaderLoop(
            queue = queue,
            send = { recording ->
                sent.add(recording.captureId)
                201
            },
            refreshSignIn = {},
            now = { 10_000L },
        )
        val result = loop.runOnce()

        assertEquals("the recording must be offered to the wire", 1, sent.size)
        assertEquals("and accepted", 1, result.accepted)
        assertEquals(0, queue.count())
    }
}
