package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Transport-boundary controls for ack resolution (spec 7c5b344d): lost/ambiguous responses, stale
 * completion after epoch/account change, and retry of the same immutable capture. Local ownership is
 * removed ONLY on validated acceptance or confirmed terminal disposal under a STILL-VALID generation.
 */
class ContributionAckResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun meta(captureId: String): ByteArray {
        val audio = wavBytes(1600)
        val block = AudioBlock(
            sha256 = sha256Hex(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = 1600,
            originalFormat = "oboe_float32",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        return CanonicalJson.canonicalBytes(
            ContributionRecordBuilder(captureId, "epoch-1", "42")
                .model("vad", ModelRef.Named("silero-vad", "4.0"))
                .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
                .model("asr", ModelRef.Named("whisper-small", "2024-11"))
                .model("translation", ModelRef.None)
                .model("router", ModelRef.Named("lfm-intent", "0.3"))
                .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
                .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
                .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))))
                .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
    }

    private fun newStore(root: File, clock: () -> Long): ContributionStore = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = clock,
        jitter = { bound -> bound - 1 },
    )

    private fun prepared(store: ContributionStore): Pair<String, Long> {
        val id = UUID.randomUUID().toString()
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", listOf(ShortArray(1600) { i -> i.toShort() } to 1600), meta(id)) != null)
        return id to generation
    }

    private fun wavOf(store: ContributionStore, id: String): File = File(store.dirForTest(), "$id.recording.wav")

    @Test
    fun `a LOST or AMBIGUOUS response retains ownership and re-arms the boundary`() {
        var now = 15_000_000L
        val root = tmp.newFolder("ack-ambiguous")
        val store = newStore(root) { now }
        val (id, generation) = prepared(store)
        assertEquals(1, store.recordAttempt(generation, id))
        val wavBefore = sha256Hex(wavOf(store, id).readBytes())

        val resolver = ContributionAckResolver(store) { captureId, _ -> captureId.isNotEmpty() }
        val resolution = resolver.resolve(generation, id, ContributionAckResolver.Ack.AMBIGUOUS)
        assertTrue(resolution is ContributionAckResolver.Resolution.Retained)
        assertTrue((resolution as ContributionAckResolver.Resolution.Retained).retryAtMs != null)
        assertTrue("ambiguous: entry RETAINED", store.readyEntries().any { it.captureId == id })
        assertEquals("ambiguous: capture bytes untouched", wavBefore, sha256Hex(wavOf(store, id).readBytes()))
        assertTrue("ambiguous: not retry-eligible until the boundary elapses", store.retryEligible(now + 1).isEmpty())
        assertTrue(store.retryEligible(now + 3_999L).isNotEmpty())
    }

    @Test
    fun `the RETRY of an ambiguous capture re-posts the SAME immutable bytes`() {
        var now = 15_500_000L
        val root = tmp.newFolder("ack-retry-immutable")
        val store = newStore(root) { now }
        val (id, generation) = prepared(store)
        assertEquals(1, store.recordAttempt(generation, id))
        val bytesBefore = wavOf(store, id).readBytes()
        val digestBefore = sha256Hex(bytesBefore)

        val resolver = ContributionAckResolver(store) { _, _ -> true }
        resolver.resolve(generation, id, ContributionAckResolver.Ack.AMBIGUOUS)
        now += 10_000L

        // The next attempt reads the same immutable capture: identical bytes, identical digest.
        val bytesOnRetry = wavOf(store, id).readBytes()
        assertEquals("retry re-posts the identical capture", digestBefore, sha256Hex(bytesOnRetry))
        assertTrue(bytesBefore.contentEquals(bytesOnRetry))
    }

    @Test
    fun `VALIDATED acceptance releases local ownership - unvalidated acceptance does not`() {
        var now = 16_000_000L
        val root = tmp.newFolder("ack-accept")
        val store = newStore(root) { now }
        val (id, generation) = prepared(store)
        assertEquals(1, store.recordAttempt(generation, id))

        // Unvalidated ACCEPTED (wrong payload): treated as ambiguous, ownership retained.
        val strict = ContributionAckResolver(store) { _, payload -> payload != null && payload.size == 4 }
        val unvalidated = strict.resolve(generation, id, ContributionAckResolver.Ack.ACCEPTED, payload = "no".toByteArray())
        assertTrue(unvalidated is ContributionAckResolver.Resolution.UnvalidatedAccept)
        assertTrue("unvalidated accept retains the entry", store.readyEntries().any { it.captureId == id })

        // Validated ACCEPTED: ownership released under the still-valid generation.
        val validated = strict.resolve(generation, id, ContributionAckResolver.Ack.ACCEPTED, payload = "abcd".toByteArray())
        assertEquals(ContributionAckResolver.Resolution.Released, validated)
        assertTrue("accepted: entry gone from the queue", store.readyEntries().none { it.captureId == id })
        assertFalse(wavOf(store, id).exists())
        assertEquals(1L, store.terminalCounter(ContributionStore.TerminalReason.UPLOAD_ACCEPTED))
    }

    @Test
    fun `a STALE completion after epoch or account change changes NOTHING locally`() {
        var now = 16_500_000L
        val root = tmp.newFolder("ack-stale")
        val store = newStore(root) { now }
        val (id, generation) = prepared(store)
        assertEquals(1, store.recordAttempt(generation, id))
        val wavBefore = sha256Hex(wavOf(store, id).readBytes())

        // An epoch bump (enrollment/account change) invalidates the attempt's generation while the
        // response is in flight - WITHOUT a purge, so the local queue must survive untouched.
        store.fence.revoke()
        val resolver = ContributionAckResolver(store) { _, _ -> true }
        assertEquals(
            "stale ACCEPTED must not release (or delete) anything",
            ContributionAckResolver.Resolution.StaleEpoch,
            resolver.resolve(generation, id, ContributionAckResolver.Ack.ACCEPTED),
        )
        assertEquals(
            "stale TERMINAL_DISPOSAL must not release anything either",
            ContributionAckResolver.Resolution.StaleEpoch,
            resolver.resolve(generation, id, ContributionAckResolver.Ack.TERMINAL_DISPOSAL),
        )
        assertEquals("stale AMBIGUOUS must not re-arm anything", ContributionAckResolver.Resolution.StaleEpoch, resolver.resolve(generation, id, ContributionAckResolver.Ack.AMBIGUOUS))
        assertEquals("capture bytes intact", wavBefore, sha256Hex(wavOf(store, id).readBytes()))
        assertEquals(0L, store.terminalCounter(ContributionStore.TerminalReason.UPLOAD_ACCEPTED))
    }

    @Test
    fun `confirmed TERMINAL disposal releases ownership and a racing revoke retains it`() {
        var now = 17_000_000L
        val root = tmp.newFolder("ack-terminal")
        val store = newStore(root) { now }
        val (id, generation) = prepared(store)
        assertEquals(1, store.recordAttempt(generation, id))

        val resolver = ContributionAckResolver(store) { _, _ -> true }
        assertEquals(
            ContributionAckResolver.Resolution.Released,
            resolver.resolve(generation, id, ContributionAckResolver.Ack.TERMINAL_DISPOSAL),
        )
        assertTrue(store.readyEntries().none { it.captureId == id })
        assertEquals(1L, store.terminalCounter(ContributionStore.TerminalReason.SERVER_TERMINAL))

        // Second capture, but the epoch changes before the terminal response lands: the stale ack must
        // NOT be the thing that removes it - the entry (and its bytes) remain owned and served.
        val (id2, generation2) = prepared(store)
        assertEquals(1, store.recordAttempt(generation2, id2))
        store.fence.revoke()
        assertEquals(
            ContributionAckResolver.Resolution.StaleEpoch,
            resolver.resolve(generation2, id2, ContributionAckResolver.Ack.TERMINAL_DISPOSAL),
        )
        assertTrue("stale terminal ack retains the entry", store.readyEntries().any { it.captureId == id2 })
        assertTrue(wavOf(store, id2).exists())
    }
}
