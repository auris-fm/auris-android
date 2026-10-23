package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The STORE's durable capture authority (reviewer 0004628b, spec 07ae8661): no weaker parallel reader may
 * serve upload bytes. These controls tamper with published pairs and assert the store refuses to vouch.
 */
class DurableCaptureAuthorityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256HexForTest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newStore(root: File, now: Long = 41_000_000L) = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = { now },
        jitter = { it - 1 },
    )

    private fun prepare(store: ContributionStore, root: File, id: String): Long {
        // The declared audio sha must be over EXACTLY the bytes the store's WavWriter will produce, so
        // the fixture reproduces them with the same writer.
        val pcm = listOf(ShortArray(1600) { i -> i.toShort() } to 1600)
        val expected = java.io.ByteArrayOutputStream()
        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter.writeHeader(expected, 1600)
        for ((chunk, valid) in pcm) au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter.writePcm(expected, chunk, valid)
        val audio = expected.toByteArray()
        val block = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock(
            sha256 = sha256HexForTest(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = 1600,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        val metadata = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson.canonicalBytes(
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
        val admission = store.admitForTest(1600, metadata.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(store.prepareAt(generation, admission, id, "epoch-1", pcm, metadata, "a@x", 7L))
        return generation
    }

    private fun captureDir(root: File): File = File(root, "contributions")

    private val ownerFile: (File, String) -> File = { root, id -> File(captureDir(root), "$id.owner") }

    @Test
    fun `prepare - durableCapture - RESTART round-trip preserves the full admitted identity`() {
        val root = tmp.newFolder("rt")
        val id = UUID.randomUUID().toString()
        val store = newStore(root)
        prepare(store, root, id)
        assertNotNull(store.durableCapture(id))
        // RESTART: a fresh store instance over the same directory; recover() rebuilds the index.
        val reopened = newStore(root)
        assertEquals(listOf(id), reopened.recover().map { it.captureId })
        val recapture = reopened.durableCapture(id)!!
        assertEquals("a@x", recapture.enrollmentAccount)
        assertEquals(7L, recapture.enrollmentSession)
        assertEquals("epoch-1", recapture.enrollmentEpoch)
    }

    @Test
    fun `a CORRUPT owner sidecar refuses to serve - no epoch-only downgrade`() {
        val root = tmp.newFolder("corrupt")
        val id = UUID.randomUUID().toString()
        val store = newStore(root)
        prepare(store, root, id)
        assertNotNull(store.durableCapture(id))
        ownerFile(root, id).writeText("{\"account\":\"a@x\",\"session\"") // truncated/corrupt
        assertNull("corrupt ownership fails CLOSED", store.durableCapture(id))
    }

    @Test
    fun `a REMOVED owner sidecar refuses to serve - removal cannot bypass the account check`() {
        val root = tmp.newFolder("removed")
        val id = UUID.randomUUID().toString()
        val store = newStore(root)
        prepare(store, root, id)
        assertNotNull(store.durableCapture(id))
        assertTrue(ownerFile(root, id).delete())
        assertNull("absent ownership fails CLOSED", store.durableCapture(id))
    }

    @Test
    fun `a TAMPERED owner sidecar (different account) is carried verbatim and mismatches the live identity`() {
        val root = tmp.newFolder("tamper")
        val id = UUID.randomUUID().toString()
        val store = newStore(root)
        prepare(store, root, id)
        // Rewrite the sidecar to a DIFFERENT admitted account: the store serves what is durably
        // vouched (tamper-evident downstream - the upload identity check compares it live), never
        // silently "fixes" it.
        ownerFile(root, id).writeText("{\"account\":\"attacker@x\",\"session\":7,\"epoch\":\"epoch-1\"}")
        val capture = store.durableCapture(id)!!
        assertEquals("attacker@x", capture.enrollmentAccount)
    }

    @Test
    fun `an OVERSIZE owner sidecar refuses to serve`() {
        val root = tmp.newFolder("oversize")
        val id = UUID.randomUUID().toString()
        val store = newStore(root)
        prepare(store, root, id)
        ownerFile(root, id).writeText("{\"account\":\"" + "x".repeat(600) + "\",\"session\":7,\"epoch\":\"epoch-1\"}")
        assertNull("over-cap ownership fails CLOSED", store.durableCapture(id))
    }

    @Test
    fun `an intact published pair is served with its owning epoch`() {
        val root = tmp.newFolder("authority-intact")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        prepare(store, root, id)

        val capture = store.durableCapture(id)
        assertNotNull("an intact pair is servable", capture)
        assertEquals("epoch-1", capture!!.enrollmentEpoch)
    }

    @Test
    fun `a REWRITTEN record is refused even when it is itself valid`() {
        val root = tmp.newFolder("authority-rewritten")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        prepare(store, root, id)
        val meta = File(captureDir(root), "$id.metadata.json")
        // Overwrite with DIFFERENT valid JSON: correct size class, plausible content, wrong bytes.
        meta.writeText("""{"schema_version":1,"note":"substituted"}""")

        assertNull("a swapped-in record is not the bytes the store published", store.durableCapture(id))
    }

    @Test
    fun `an OVERSIZED metadata file is refused, not read`() {
        val root = tmp.newFolder("authority-oversize")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        prepare(store, root, id)
        val meta = File(captureDir(root), "$id.metadata.json")
        // Well past any recorded size: damage, not data.
        meta.writeBytes(ByteArray(4 * 1024 * 1024) { 0x41 })

        assertNull("an oversized record is damage", store.durableCapture(id))
    }

    @Test
    fun `same-length damaged audio is refused by the WAV validation`() {
        val root = tmp.newFolder("authority-audio")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        prepare(store, root, id)
        val wav = File(captureDir(root), "$id.recording.wav")
        // Same length, broken header: the length check alone would have passed this.
        val bytes = wav.readBytes()
        bytes[0] = 'X'.code.toByte()
        wav.writeBytes(bytes)

        assertNull("damaged audio cannot ride a valid record", store.durableCapture(id))
    }

    @Test
    fun `a same-length PAYLOAD byte mutation is refused, not only header damage`() {
        val root = tmp.newFolder("authority-payload")
        val store = newStore(root)
        val id = UUID.randomUUID().toString()
        prepare(store, root, id)
        val wav = File(captureDir(root), "$id.recording.wav")
        // Identical length, identical header, one PCM byte flipped: the header check alone passes this.
        val bytes = wav.readBytes()
        val original = bytes[1000].toInt()
        bytes[1000] = (original xor 0x55).toByte()
        wav.writeBytes(bytes)

        assertNull("mutated payload cannot ride a valid record and header", store.durableCapture(id))
    }

    @Test
    fun `an unknown capture id serves nothing`() {
        val root = tmp.newFolder("authority-unknown")
        val store = newStore(root)
        assertNull(store.durableCapture(UUID.randomUUID().toString()))
    }
}
