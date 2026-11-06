package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import timber.log.Timber

/**
 * MONITORING (spec 827c5962, Merlin cf448422): a queued recording that DISAPPEARS must leave a line
 * saying so and why. Before this slice the upload path logged nothing at all, so on a device a deletion
 * and a retention looked identical, and an incident could not be diagnosed from the app's own output.
 *
 * These controls pin the three removal reasons that actually delete, plus the upload-start/settle pair.
 * They assert the LINE EXISTS and carries the capture id and reason - not the count, so a future edit that
 * adds context does not break them.
 */
class UploadRemovalLogTest {
    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    private val planted = mutableListOf<Timber.Tree>()

    private class CollectingTree : Timber.Tree() {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += message
        }

        fun has(fragment: String): Boolean = synchronized(lines) { lines.any { it.contains(fragment) } }

        fun describe(): String = synchronized(lines) { lines.joinToString(" | ") }
    }

    private lateinit var tree: CollectingTree

    @Before
    fun plant() {
        tree = CollectingTree()
        Timber.plant(tree)
        planted += tree
    }

    @After
    fun uproot() {
        planted.forEach { Timber.uproot(it) }
        planted.clear()
    }

    private fun tempRoot() = Files.createTempDirectory("removal-log").toFile()

    private fun meta(captureId: String, epoch: String = "epoch-1", sampleCount: Int = 8): ByteArray {
        val audio = wavBytes(sampleCount)
        val block = AudioBlock(
            sha256 = sha256HexForTest(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = sampleCount.toLong(),
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        val builder = ContributionRecordBuilder(captureId, epoch, "7")
            .model("vad", ModelRef.None)
            .model("wakeword", ModelRef.None)
            .model("asr", ModelRef.None)
            .model("translation", ModelRef.None)
            .model("router", ModelRef.None)
        for (stage in StageName.entries) {
            builder.stage(stage, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str(stageReasonFor(stage)))))
        }
        val record = builder.build(platform = "android", appVersion = "8.20", build = "test", audio = block)
        return CanonicalJson.canonicalBytes(record)
    }

    private fun stageReasonFor(stage: StageName): String = when (stage) {
        StageName.Wake -> "no_wake_detected"
        StageName.Bleed -> "no_playback_reference"
        StageName.Asr -> "local_only_action"
        StageName.Translation -> "no_text"
        StageName.Routing -> "no_candidates"
        StageName.Execution -> "client_refused"
    }

    private fun wavBytes(sampleCount: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, sampleCount)
        WavWriter.writePcm(out, ShortArray(sampleCount) { i -> i.toShort() }, sampleCount)
        return out.toByteArray()
    }

    private fun sha256HexForTest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun chunks(vararg sizes: Int) = sizes.map { ShortArray(it) { i -> i.toShort() } to it }

    @Test
    fun `expiry states the reason and the capture id`() {
        var now = 1_000_000L
        val root = tempRoot()
        val limits = QueueLimits.defaults().copy(maxQueueAgeSeconds = 10)
        val store = ContributionStore(root, limits, clock = { now })
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        now += 60_000 // past maxQueueAgeSeconds

        val removed = store.expireOlderThan()
        assertEquals(listOf(id), removed)

        assertTrue(
            "expiry must log the removal with its reason (lines=${tree.describe()})",
            tree.has("voice-upload remove") && tree.has("capture=$id") && tree.has("reason=queue-age-expired"),
        )
    }

    @Test
    fun `a settlement removal states the reason and the capture id`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val token = store.pickupAttemptToken(store.fence.currentGeneration(), id)!!
        tree.lines.clear()

        val resolver = ContributionAckResolver(store) { _, _, _ -> true }
        resolver.resolveForAttempt(token, ContributionAckResolver.Ack.TERMINAL_DISPOSAL, httpStatus = 410)

        assertTrue(
            "a terminal settlement deletes a valid recording, so it MUST log (lines=${tree.describe()})",
            tree.has("voice-upload remove") && tree.has("capture=$id") && tree.has("reason=SERVER_TERMINAL"),
        )
        assertTrue(
            "the settle line must carry the disposition and the real status (lines=${tree.describe()})",
            tree.has("voice-upload settle") && tree.has("ack=TERMINAL_DISPOSAL") && tree.has("http=410") && tree.has("outcome=released"),
        )
    }

    @Test
    fun `recovery deletion of an invalid entry states the reason and the capture id`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val good = UUID.randomUUID().toString()
        val bad = UUID.randomUUID().toString()
        assertNotNull(store.prepare(good, "epoch-1", chunks(8), meta(good), "a@x", 7L))
        assertNotNull(store.prepare(bad, "epoch-1", chunks(8), meta(bad), "a@x", 7L))
        val wav = java.io.File(root, "contributions/$bad.recording.wav")
        wav.writeBytes(wav.readBytes().copyOf(wav.readBytes().size - 2))
        store.close()
        tree.lines.clear()

        ContributionStore(root, QueueLimits.defaults()).recover()

        assertTrue(
            "recovery deletes corrupt entries, so it must log which (lines=${tree.describe()})",
            tree.has("voice-upload remove") && tree.has("capture=$bad") && tree.has("reason=invalid-on-recovery"),
        )
        assertTrue(
            "a VALID entry must not be logged as removed (lines=${tree.describe()})",
            !tree.has("capture=$good") || tree.lines.none { it.contains("capture=$good") && it.contains("remove") },
        )
    }

    @Test
    fun `the log carries no payload or credential`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val token = store.pickupAttemptToken(store.fence.currentGeneration(), id)!!
        tree.lines.clear()

        ContributionAckResolver(store) { _, _, _ -> true }
            .resolveForAttempt(token, ContributionAckResolver.Ack.TERMINAL_DISPOSAL, httpStatus = 410)

        val all = tree.describe()
        assertTrue(
            "monitoring lines must stay content-free (lines=$all)",
            !all.contains("Bearer") && !all.contains("sha256") && !all.contains("\"capture\":"),
        )
    }
}
