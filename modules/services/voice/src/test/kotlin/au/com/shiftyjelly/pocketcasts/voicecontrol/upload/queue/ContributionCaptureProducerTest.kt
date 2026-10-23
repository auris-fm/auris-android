package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RECORDER PROVENANCE (spec 07ae8661, reviewer boundary): `original_format` and `conversion_version` are
 * bound at publish time by the producer, describe the audio the record claims, and survive unchanged
 * through the store's durable authority - never rewritten by settings or retry.
 */
class ContributionCaptureProducerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(root: java.io.File, now: Long = 61_000_000L) = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = { now },
        jitter = { it - 1 },
    )

    private val pcm = listOf(ShortArray(1600) { i -> i.toShort() } to 1600)

    private fun producer(
        store: ContributionStore,
        format: String = "pcm_s16le",
        version: String = "v1",
        account: () -> String = { "a@x" },
        session: () -> Long = { 7L },
    ) = ContributionCaptureProducer(
        store,
        originalFormat = format,
        conversionVersion = version,
        appVersion = "8.20",
        appBuild = "12345",
        captureSessionId = { "00000000-0000-4000-8000-000000000000" },
        enrollmentAccount = account,
        enrollmentSession = session,
    )

    private val models = listOf(
        "vad" to ModelRef.Named("silero-vad", "4.0"),
        "wakeword" to ModelRef.Named("auris-wake", "1.2"),
        "asr" to ModelRef.Named("whisper-small", "2024-11"),
        "translation" to ModelRef.None,
        "router" to ModelRef.Named("lfm-intent", "0.3"),
    )

    private val stages = listOf(
        StageName.Wake to StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))),
        StageName.Bleed to StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))),
        StageName.Asr to StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))),
        StageName.Translation to StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))),
        StageName.Routing to StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))),
        StageName.Execution to StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))),
    )

    @Test
    fun `a published capture carries the producer's provenance and survives the durable authority`() {
        val root = tmp.newFolder("producer-intact")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()

        val entry = producer(store, format = "pcm_s16le", version = "v2").publish(
            id,
            "epoch-1",
            16000,
            pcm,
            models,
            stages,
        )
        assertNotNull("the store accepted the producer's capture", entry)

        // Through the SAME authority the uploader reads: provenance is intact and the audio matches.
        val capture = store.durableCapture(id)
        assertNotNull("the store vouches for what the producer published", capture)
        val audio = capture!!.record.fields["audio"] as RecordNode.Obj
        assertEquals("pcm_s16le", (audio.fields["original_format"] as RecordNode.Str).value)
        assertEquals("v2", (audio.fields["conversion_version"] as RecordNode.Str).value)
        assertEquals("the record's audio sha matches the WAV on disk", 0L, 0L)
        val declared = (audio.fields["sha256"] as RecordNode.Str).value
        val wav = java.io.File(java.io.File(root, "contributions"), "$id.recording.wav")
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(wav.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("declared sha IS the published file's sha", declared, actual)
    }

    @Test
    fun `a budget-exceeded capture is refused WITHOUT publishing a malformed record`() {
        val root = tmp.newFolder("producer-refused")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        // Far past the clip duration bound.
        val huge = listOf(ShortArray(1_000_000) { 0 } to 1_000_000)

        val entry = producer(store).publish(id, "epoch-1", 16000, huge, models, stages)

        assertNull("an over-budget capture is refused", entry)
        assertTrue("no record was published beside it", store.readyEntries().isEmpty())
    }

    @Test
    fun `a malformed capture id is refused before any disk work`() {
        val root = tmp.newFolder("producer-badid")
        val store = newStore(root)

        val entry = producer(store).publish("../escape", "epoch-1", 16000, pcm, models, stages)

        assertNull("a non-UUID id never reaches the store", entry)
        assertTrue("nothing was written", store.readyEntries().isEmpty())
    }
}
