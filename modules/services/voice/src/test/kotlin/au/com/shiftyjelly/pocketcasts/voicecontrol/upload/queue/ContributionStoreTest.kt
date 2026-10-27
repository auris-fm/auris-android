package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordValidator
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionStoreTest {

    private val idB = "bbbbbbbb-1111-4111-8111-111111111101"
    private val idC = "cccccccc-1111-4111-8111-111111111102"
    private val idD = "dddddddd-1111-4111-8111-111111111103"
    private val idE = "eeeeeeee-1111-4111-8111-111111111104"

    private val firstName = "first-attempt"
    private val secondName = "second-attempt"

    private companion object {
        const val ID_A = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val SUCC = "11111111-2222-3333-4444-555555555555"
    }

    private fun tempRoot(): File = Files.createTempDirectory("contrib").toFile()

    /**
     * A REAL canonical record for the WAV that [chunks] produces: recovery now validates the metadata
     * through the canonical parser and binds it to the actual audio bytes, so a stub JSON object is
     * no longer a valid entry. Byte count and audio digest are computed from the exact WAV layout
     * ([WavWriter]) the store writes.
     */
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
        // A record that satisfies the pinned WIRE contract: all five model keys and all six
        // stages present, declared absences explicit.
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

    /** A legal enumerated reason for each stage, for declared-absence fixtures. */
    private fun stageReasonFor(stage: StageName): String = when (stage) {
        StageName.Wake -> "no_wake_detected"
        StageName.Bleed -> "no_playback_reference"
        StageName.Asr -> "local_only_action"
        StageName.Translation -> "no_text"
        StageName.Routing -> "no_candidates"
        StageName.Execution -> "client_refused"
    }

    /** The exact WAV bytes the store writes for [sampleCount] samples (16 kHz mono pcm_s16le). */
    private fun audioBlockFor(sampleCount: Int): AudioBlock {
        val audio = wavBytes(sampleCount)
        return AudioBlock(
            sha256 = sha256HexForTest(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = sampleCount.toLong(),
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
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
    fun `ready marker is the commit point - interrupted preparation never becomes ready`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        // Simulate a kill MID-PREPARATION of a second entry: only .part debris exists.
        val id2 = UUID.randomUUID().toString()
        File(root, "contributions").mkdirs()
        File(root, "contributions/$id2.wav.part").writeBytes(ByteArray(10))
        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val recovered = ContributionStore(root, QueueLimits.defaults()).recover()
        assertEquals(listOf(id), recovered.map { it.captureId })
        assertFalse(File(root, "contributions/$id2.wav.part").exists())
    }

    @Test
    fun `admission denies on record and byte caps without touching disk`() {
        val root = tempRoot()
        val limits = QueueLimits.defaults().copy(maxQueueRecords = 1)
        val store = ContributionStore(root, limits)
        val id = UUID.randomUUID().toString()
        store.admit(16, 100)!!.let {
            assertTrue(it.isHeld())
            it.discard()
        }
        assertNotNull(store.prepare(id, "epoch-1", chunks(16), meta(id), "a@x", 7L))
        // Record cap reached: deny BEFORE any copy; no new files appear.
        assertNull(store.admit(16, 100))
        assertNull(store.prepare(UUID.randomUUID().toString(), "epoch-1", chunks(16), meta(UUID.randomUUID().toString()), "a@x", 7L))
    }

    @Test
    fun `duration cap denies an over-length clip on a fresh store`() {
        // Fresh store, ALL spec defaults (maxQueueRecords=200, maxQueueBytes=64MiB) so no other bound
        // can short-circuit first. 31 s at 16 kHz mono = 992,144 bytes INCLUDING header, which is well
        // UNDER max_clip_bytes (2 MiB); only max_clip_ms = 30_000 makes it inadmissible. Enforcing this
        // bound is required: without it a device could queue an arbitrarily long recording whose byte
        // size still slips under the cap.
        val store = ContributionStore(tempRoot(), QueueLimits.defaults())
        assertNull(store.admit(16_000L * 31, 100))
        // Boundary stays admissible: exactly 30 s of audio is within max_clip_ms.
        assertNotNull(store.admit(16_000L * 30, 100))
    }

    @Test
    fun `attempts persist across restart and expiry removes only expired entries`() {
        var now = 1_000_000L
        val root = tempRoot()
        val limits = QueueLimits.defaults().copy(maxQueueAgeSeconds = 100)
        val store = ContributionStore(root, limits, clock = { now })
        val idA = UUID.randomUUID().toString()
        val idB = UUID.randomUUID().toString()
        assertNotNull(store.prepare(idA, "epoch-1", chunks(8), meta(idA), "a@x", 7L))
        now += 150_000 // A (created at t=1_000_000) is now 150s old > max_queue_age_s=100
        assertNotNull(store.prepare(idB, "epoch-1", chunks(8), meta(idB), "a@x", 7L))
        store.recordAttempt(store.fence.currentGeneration(), idA)
        // RESTART: fresh store recovers durable state including attempt counts.
        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val store2 = ContributionStore(root, limits, clock = { now })
        val recovered = store2.recover()
        assertEquals(2, recovered.size)
        assertEquals(1, recovered.first { it.captureId == idA }.attempts)
        val expired = store2.expireOlderThan()
        assertEquals(listOf(idA), expired)
        assertEquals(1, store2.readyEntries().size)
        assertEquals(idB, store2.readyEntries().single().captureId)
        assertEquals(1L, store2.terminalCounter(ContributionStore.TerminalReason.QUEUE_AGE_EXPIRED))
    }

    @Test
    fun `full revocation purges everything while partial loss keeps ready entries`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        // In-progress debris from another capture.
        val id2 = UUID.randomUUID().toString()
        File(root, "contributions/$id2.wav.part").writeBytes(ByteArray(4))
        // Listening closure alone: partial goes, ready stays.
        store.discardCurrentPartials()
        assertEquals(1, store.readyEntries().size)
        assertFalse(File(root, "contributions/$id2.wav.part").exists())
        // Account change / withdrawal / disable / lease loss: EVERYTHING clears immediately.
        store.purgeQueue("account-change")
        assertEquals(0, store.readyEntries().size)
        val dir = File(root, "contributions")
        assertTrue(dir.list()?.none { it.startsWith(id) } ?: true)
        assertEquals(listOf("account-change"), store.purgeReasons)
    }

    @Test
    fun `recovery validates complete entries and drops corrupt ones with content-free counter`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val good = UUID.randomUUID().toString()
        val bad = UUID.randomUUID().toString()
        assertNotNull(store.prepare(good, "epoch-1", chunks(8), meta(good), "a@x", 7L))
        assertNotNull(store.prepare(bad, "epoch-1", chunks(8), meta(bad), "a@x", 7L))
        // Corrupt the bad entry's WAV AFTER it was marked ready (truncated body vs header).
        val wav = File(root, "contributions/$bad.recording.wav")
        wav.writeBytes(wav.readBytes().copyOf(wav.readBytes().size - 2))
        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val store2 = ContributionStore(root, QueueLimits.defaults())
        val recovered = store2.recover()
        assertEquals(listOf(good), recovered.map { it.captureId })
        assertEquals(1L, store2.terminalCounter(ContributionStore.TerminalReason.INVALID_ON_RECOVERY))
        // Corrupt entry files are gone — nothing invalid can be admitted to upload.
        assertFalse(File(root, "contributions/$bad.ready").exists())
    }

    @Test
    fun `wav bytes include header and match declared byte count exactly`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        store.prepare(id, "epoch-1", chunks(8, 4), meta(id), "a@x", 7L)
        val entry = store.readyEntries().single()
        assertEquals(WavWriter.fileBytes(12), entry.audioBytes)
        assertEquals(44 + 24, entry.audioBytes)
    }

    @Test
    fun `fence blocks late callbacks after revocation and successor account`() {
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val captured = fence.currentGeneration()
        var ran = false
        assertNotNull(
            fence.guard(captured) {
                ran = true
                Unit
            },
        )
        assertTrue(ran)
        // Revocation (account change OR withdrawal OR disable OR lease loss) bumps generation.
        fence.revoke()
        assertNull(fence.guard(captured) { error("must not run") })
        assertFalse(fence.isValid(captured))
        // Successor session works on the NEW generation.
        val next = fence.currentGeneration()
        assertNotNull(fence.guard(next) { "ok" })
    }

    @Test
    fun `interrupted preparation is never visible as ready`() {
        // The failure path must not leave a provisional index entry behind: only durable, marker-
        // committed entries may appear in readyEntries(). Refusing on an over-length clip writes no
        // files and publishes nothing.
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNull(store.prepare(id, "epoch-1", chunks(16_000 * 45), meta(id), "a@x", 7L)) // 45 s > max_clip_ms
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })
        val dir = File(root, "contributions")
        assertTrue(dir.list()?.none { it.startsWith(id) } ?: true)
    }

    @Test
    fun `concurrent prepares cannot both consume one remaining slot`() {
        // ONE record of headroom, many threads racing to claim it. Admission reserves count+bytes
        // atomically, so at most one entry may exist afterwards; without a reservation two threads
        // can both pass the check and publish, silently exceeding max_queue_records.
        val root = tempRoot()
        val limits = QueueLimits.defaults().copy(maxQueueRecords = 1)
        val store = ContributionStore(root, limits)
        val ids = (1..8).map { UUID.randomUUID().toString() }
        val results = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(ids.size)
        ids.forEach { id ->
            Thread {
                start.await()
                if (store.prepare(id, "epoch-1", chunks(16), meta(id), "a@x", 7L) != null) results.add(id)
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("expected at least one admission", results.isNotEmpty())
        assertTrue(
            "over-admitted ${results.size} entries into a 1-record queue: ${results.size}",
            store.readyEntries().size <= 1,
        )
    }

    @Test
    fun `late callback after purge cannot recreate an entry`() {
        // Purge removes everything; a subsequent attempt-bookkeeping call for the removed capture must
        // not bring the entry back. Uses only the current public API so the behavior is observable now.
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(16), meta(id), "a@x", 7L))
        store.purgeQueue("account-change")
        assertEquals(0, store.readyEntries().size)
        assertNull("attempt bookkeeping must not resurrect a purged entry", store.recordAttempt(store.fence.currentGeneration(), id))
        assertEquals(0, store.readyEntries().size)
        assertFalse(File(root, "contributions/$id.ready").exists())
    }

    // ---- HELD (deterministic) controls: a hook fires WHILE the store is mid-preparation, so the
    // interleaving is forced rather than raced. These replace sequential-after-purge proxies.

    @Test
    fun `HELD revocation during actual WAV write releases the reservation and keeps successor files`() {
        // The hook runs at the moment the .wav.part write happens: purge lands DURING the real write.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        var hooked = false
        // The successor runs on the NEW generation after a revoke, which is exactly what an account
        // change does: old owner purged, new owner opens its own store instance on the same directory.
        lateinit var theStore: ContributionStore
        lateinit var successor: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 2),
            ownership = ownership,
            fence = fence,
            hooks = StoreHooks(onWavPartWrite = { _ ->
                hooked = true
                fence.revoke() // revocation lands DURING the stale owner's actual WAV write
                theStore.purgeQueue("account-change")
                // DIRECTORY OWNERSHIP (spec c8f766fb): a successor CANNOT publish while this owner still
                // holds the directory - the old fixture published into a live owner's namespace, which is
                // the concurrency the contract removes. The purge above happens first (unchanged), then the
                // owner releases, then the successor publishes. Every survival assertion below is kept.
                // The close is DEFERRED here (reviewer 52ee7e91): this hook is inside the guarded write,
                // so the directory is released only when the write exits. The successor publishes below,
                // after the stale preparation returns - a successor cannot publish into a live owner.
                theStore.close()
            }),
        )
        val store = theStore
        val staleGen = fence.currentGeneration()
        assertNull(store.prepareAt(staleGen, store.admitForTest(8, meta(ID_A).size), ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L))
        assertTrue("hook never fired - control did not exercise the actual write", hooked)
        // The write has exited, so the deferred release has happened and the successor can take over.
        successor = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        successor.prepare(SUCC, "epoch-B", chunks(8), meta(SUCC), "a@x", 7L)
        // The successor's COMMITTED files must survive the stale preparation's cleanup untouched: purge is
        // scoped to ids this owner held, never a blanket directory sweep.
        assertTrue("successor WAV deleted by stale purge", File(root, "contributions/$SUCC.recording.wav").isFile)
        assertTrue("successor READY marker deleted by stale purge", File(root, "contributions/$SUCC.ready").isFile)
        // And the purged owner keeps its own index empty.
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })
        assertTrue(File(root, "contributions/$SUCC.recording.wav").isFile)
        assertTrue(File(root, "contributions/$SUCC.ready").isFile)
        // And the stale preparation must not leave its own debris behind.
        assertFalse(File(root, "contributions/$ID_A.ready").exists())
        // The stale reservation was released with the owner: a CLOSED owner refuses admission outright
        // (spec c8f766fb - no unguarded fallback), so the capacity check moves to the live successor.
        assertNull("a closed owner must not admit", store.admit(8, 100))
        assertNotNull("a closed owner reports why it is unusable", store.unavailableReason())
        assertNotNull("the live owner still has its capacity", successor.admit(8, 100))
    }

    @Test
    fun `every non-publication path releases its own reservation`() {
        // Direct, deterministic coverage of finding 1: each refusal/failure path must give back EXACTLY
        // its own slot+bytes. No purge involved, so nothing can mask a leak.
        val store = ContributionStore(tempRoot(), QueueLimits.defaults().copy(maxQueueRecords = 2))
        // (a) refused before writing: over-length clip
        assertNull(store.admit(16_000L * 45, 100))
        assertEquals("refused admission must hold no budget", 0, store.reservedRecordsForTest())
        // (b) admitted then explicitly discarded
        val held = store.admit(8, 100)
        assertNotNull("admission should succeed here", held)
        assertTrue(held!!.isHeld())
        assertEquals(1, store.reservedRecordsForTest())
        held.discard()
        assertFalse(held.isHeld())
        assertEquals("discard must return the budget", 0, store.reservedRecordsForTest())
        // (c) prepare() that reaches the commit point adopts the reservation (not double counted)
        assertNotNull(store.prepare(ID_A, "epoch-1", chunks(8), meta(ID_A), "a@x", 7L))
        assertEquals(1, store.readyEntries().size)
        assertEquals("published entry leaves no live reservation", 0, store.reservedRecordsForTest())
        // (d) duplicate prepare for an owned capture is refused WITHOUT consuming a second slot
        assertNull(store.prepare(ID_A, "epoch-1", chunks(8), meta(ID_A), "a@x", 7L))
        assertEquals(0, store.reservedRecordsForTest())
        // Budget still has exactly one record free: prove it by filling it, then seeing denial.
        assertNotNull(store.prepare(SUCC, "epoch-2", chunks(8), meta(SUCC), "a@x", 7L))
        assertEquals(2, store.readyEntries().size)
        assertNull(store.admit(8, 100))
    }

    @Test
    fun `HELD stale publication alone releases its reservation without any purge`() {
        // The leak path is the fenced-off publication inside writeAndPublish. A purge would clear all
        // reservations and mask the bug, so this control revokes the generation WITHOUT purging: only the
        // stale preparation's own handle can be holding budget. If its release is missing, the last
        // admission in a 1-record queue is refused by phantom load.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        var hooked = false
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 1),
            ownership = ownership,
            fence = fence,
            hooks = StoreHooks(beforePublication = { _ ->
                hooked = true
                fence.revoke() // stale at the publication instant; deliberately NO purgeQueue() call
            }),
        )
        val store = theStore
        val gen = fence.currentGeneration()
        assertNull(store.prepareAt(gen, store.admitForTest(8, meta(ID_A).size), ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L))
        assertTrue("hook never fired - control did not reach the publication point", hooked)
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })
        assertEquals("stale publication leaked its reservation", 0, store.reservedRecordsForTest())
        assertNotNull("one-record queue still occupied by a phantom reservation", store.admit(8, 100))
    }

    @Test
    fun `HELD stale publication releases its reservation so the one free slot stays usable`() {
        // No successor commits anything, so the ONLY thing that can occupy the single slot is this
        // preparation's own reservation. If the fenced-off path failed to release it, admit() would
        // find the queue full. Deterministic: the hook fires inside the real write, not after it.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        var hooked = false
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 1),
            ownership = ownership,
            fence = fence,
            hooks = StoreHooks(onWavPartWrite = { gen ->
                hooked = true
                fence.revoke() // makes our captured generation stale while we are mid-write
                theStore.purgeQueue("account-change")
            }),
        )
        val store = theStore
        assertNull(store.prepareAt(fence.currentGeneration(), store.admitForTest(8, meta(ID_A).size), ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L))
        assertTrue("hook never fired - control did not exercise the actual write", hooked)
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })
        assertNotNull("stale preparation leaked its reservation into a 1-slot queue", store.admit(8, 100))
    }

    @Test
    fun `HELD late attempt write and terminal drop are refused after revocation`() {
        // Full attempt-write fencing: a network callback that captured the OLD generation must not be able
        // to advance retry state, write the attempts sidecar, or delete an entry once a revocation landed.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        var hooked = false
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults(),
            ownership = ownership,
            fence = fence,
            hooks = StoreHooks(beforePublication = { _ ->
                hooked = true
                fence.revoke()
                theStore.purgeQueue("account-change")
            }),
        )
        val store = theStore
        // Revocation lands at the publication instant, so this preparation publishes nothing and the
        // generation captured by an in-flight callback is now stale.
        val inflightGen = fence.currentGeneration()
        assertNull(store.prepareAt(inflightGen, store.admitForTest(8, meta(ID_A).size), ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L))
        assertTrue("hook never fired - control did not reach the publication point", hooked)
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })

        // A late callback carrying that stale generation must be refused on every mutating surface.
        // The entry below belongs to THIS (now revoked) owner's index, so only the fence can stop it.
        // DIRECTORY OWNERSHIP (spec c8f766fb): the store below takes over only after the first releases;
        // the stale callback the assertions deliver is unchanged.
        store.close()
        val victim = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        assertNotNull(victim.prepare(ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L))
        assertEquals(1, victim.readyEntries().size)
        assertNull("stale recordAttempt must be refused", victim.recordAttempt(inflightGen, ID_A))
        assertFalse(
            "stale dropEntry must be refused",
            victim.dropEntry(inflightGen, ID_A, ContributionStore.TerminalReason.SERVER_TERMINAL),
        )
        assertEquals("stale callback must leave the attempt count alone", 0, victim.readyEntries().single().attempts)
        assertTrue("stale callback must not delete the entry's files", File(victim.dirForTest(), "$ID_A.recording.wav").isFile)
    }

    @Test
    fun `late attempt write cannot touch a successor entry under its own generation`() {
        // The dangerous shape: revocation + successor commits the SAME capture id, then the old owner's
        // callback arrives carrying the old generation. It must neither bump nor delete the successor's entry.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val oldStore = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        assertNotNull(oldStore.prepare(ID_A, "epoch-old", chunks(8), meta(ID_A), "a@x", 7L))
        val staleGen = fence.currentGeneration()
        fence.revoke()
        oldStore.purgeQueue("account-change")

        // DIRECTORY OWNERSHIP (spec c8f766fb): the successor takes the directory only after the old owner
        // releases it. The stale callback below is still delivered to the OLD instance exactly as before -
        // that is what this control is about; only the simultaneous-writable-store setup is removed.
        oldStore.close()
        val newStore = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        assertNotNull(newStore.prepare(ID_A, "epoch-new", chunks(8), meta(ID_A), "a@x", 7L))
        val succAttempts = newStore.recordAttempt(newStore.fence.currentGeneration(), ID_A)
        assertEquals(1, succAttempts)

        // Stale callback from the previous owner.
        assertNull(oldStore.recordAttempt(staleGen, ID_A))
        assertFalse(oldStore.dropEntry(staleGen, ID_A, ContributionStore.TerminalReason.SERVER_TERMINAL))

        // Successor's entry and its durable attempt count are untouched.
        val live = newStore.readyEntries().single()
        assertEquals("successor enrollment epoch changed by stale callback", "epoch-new", live.enrollmentEpoch)
        assertEquals("successor attempt count changed by stale callback", 1, live.attempts)
        assertEquals(
            "successor attempts sidecar changed by stale callback",
            "1",
            File(newStore.dirForTest(), "$ID_A.attempts").readText(),
        )
    }

    @Test
    fun `discarded reused foreign and undersized handles cannot publish`() {
        // Reviewer ee26880c finding 2: the transferred handle must be validated as THIS store's, still HELD,
        // sized for the ACTUAL chunks+metadata, and not already settled. Each refusal has one isolated reason.
        val store = ContributionStore(tempRoot(), QueueLimits.defaults())
        // (a) already discarded
        val discarded = store.admit(8, meta(ID_A).size)!!
        discarded.discard()
        assertNull(
            "discarded handle must not publish",
            store.prepareAt(store.fence.currentGeneration(), discarded, ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L),
        )
        assertEquals(0, store.readyEntries().size)
        // (b) reused after a successful publication
        val used = store.admit(8, meta(idB).size)!!
        assertNotNull(store.prepareAt(store.fence.currentGeneration(), used, idB, "epoch-A", chunks(8), meta(idB), "a@x", 7L))
        assertEquals(1, store.readyEntries().size)
        assertNull(
            "adopted handle must not publish again",
            store.prepareAt(store.fence.currentGeneration(), used, idC, "epoch-A", chunks(8), meta(idC), "a@x", 7L),
        )
        assertEquals("reuse must not add an entry", 1, store.readyEntries().size)
        // (c) foreign: created by a DIFFERENT store's reservation registry
        val other = ContributionStore(tempRoot(), QueueLimits.defaults())
        val foreign = other.admit(8, meta(idD).size)!!
        assertNull(
            "foreign handle must not publish into this store",
            store.prepareAt(store.fence.currentGeneration(), foreign, idD, "epoch-A", chunks(8), meta(idD), "a@x", 7L),
        )
        assertFalse("foreign handle must be released, not left held", foreign.isHeld())
        // (d) undersized: reserved for 8 samples but asked to write 16
        val small = store.admit(8, meta(idE).size)!!
        assertNull(
            "undersized handle must not publish a larger clip",
            store.prepareAt(store.fence.currentGeneration(), small, idE, "epoch-A", chunks(16), meta(idE), "a@x", 7L),
        )
        assertFalse("refused handle must not stay held", small.isHeld())
        // Only the legitimate B entry exists; none of the four refusals created state.
        assertEquals(listOf(idB), store.readyEntries().map { it.captureId })
    }

    @Test
    fun `concurrent reuse of one handle cannot share its staging`() {
        // Reviewer ee26880c finding 2, concurrency half: two attempts racing with the SAME handle must produce
        // exactly one writer; the transfer is single-winner under the shared monitor.
        val store = ContributionStore(tempRoot(), QueueLimits.defaults())
        val gen = store.fence.currentGeneration()
        val handle = store.admit(8, meta(ID_A).size)!!
        val gate = java.util.concurrent.CountDownLatch(1)
        val results = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val threads = (1..2).map { _ ->
            Thread {
                gate.await()
                val e = runCatching {
                    store.prepareAt(gen, handle, ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L)
                }.fold({ it?.captureId }, { t -> "THROWN:${t::class.simpleName}" })
                results += e
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join(20_000) }
        assertEquals("both attempts must report", 2, results.size)
        val published = results.count { it != null && !it.startsWith("THROWN") }
        assertEquals("exactly one attempt may claim the handle", 1, published)
        assertFalse("loser must not leave the handle held", handle.isHeld())
        assertEquals("at most one entry for one capture id", 1, store.readyEntries().size)
        assertTrue("no thread may fail unexpectedly: $results", results.none { it?.startsWith("THROWN") == true })
    }

    @Test
    fun `stale generation refuses before any staging claim`() {
        // Findings 1+2 combined: a handle offered under a revoked generation is refused WITHOUT claiming
        // staging or writing bytes, and settles the caller's reservation.
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val store = ContributionStore(tempRoot(), QueueLimits.defaults(), ownership = ownership, fence = fence)
        val gen = fence.currentGeneration()
        val handle = store.admit(8, meta(ID_A).size)!!
        fence.revoke()
        assertNull(store.prepareAt(gen, handle, ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L))
        assertFalse("stale attempt must settle its handle", handle.isHeld())
        val files = store.dirForTest().listFiles()?.toList() ?: emptyList()
        assertEquals(
            "stale attempt must leave no staging behind",
            emptyList<String>(),
            files.filter { it.name.endsWith(".part") }.map { it.name },
        )
        assertTrue("stale attempt must not create the final wav", !File(store.dirForTest(), "$ID_A.recording.wav").exists())
    }

    @Test
    fun `same-id successor survives a revoked owner's late cleanup`() {
        // Finding 4: revocation+cleanup is ONE transaction on the shared monitor, and cleanup is scoped to ids
        // this owner actually held, so a successor that claims the same capture id keeps its files.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val oldOwner = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        assertNotNull(oldOwner.prepare(ID_A, "epoch-old", chunks(8), meta(ID_A), "a@x", 7L))
        fence.revoke()
        oldOwner.revokeAndPurge("account-change")
        // DIRECTORY OWNERSHIP (spec c8f766fb): the old owner RELEASES the directory before the successor
        // takes over. Previously both were live at once, which is the concurrency this contract removes;
        // what the assertions below still prove is unchanged - a successor's identical-id files survive the
        // old owner's LATE purge, because purge is scoped to ids that owner actually held.
        oldOwner.close()
        val successor = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        assertNotNull(successor.prepare(ID_A, "epoch-new", chunks(8), meta(ID_A, "epoch-new"), "a@x", 7L))
        // The closed owner refuses every mutation, so its late purge cannot reach the directory at all -
        // a strictly stronger outcome than the old "scoped so it does not delete" one.
        oldOwner.revokeAndPurge("late-second-purge")
        assertNotNull(
            "a closed owner must refuse the late purge outright",
            oldOwner.unavailableReason(),
        )
        assertTrue("successor WAV deleted by a revoked owner's late cleanup", File(successor.dirForTest(), "$ID_A.recording.wav").isFile)
        assertTrue("successor READY deleted by a revoked owner's late cleanup", File(successor.dirForTest(), "$ID_A.ready").isFile)
        assertEquals(listOf(ID_A), successor.readyEntries().map { it.captureId })
        assertEquals("epoch-new", successor.readyEntries().single().enrollmentEpoch)
    }

    @Test
    fun `attempt bump cannot be attributed across a concurrent revocation`() {
        // Reviewer correction: the ORIGINAL control reused one entry across 20 rounds and asserted a
        // refused call left the sidecar at 0. That is wrong — an earlier round legitimately increments
        // it, so the assertion could fail with no production defect (the "flake" I reported). The
        // corrected invariant compares each refused call against ITS OWN pre-call baseline, and the
        // pre/post revocation ordering is pinned with a barrier instead of a busy revoker loop.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val store = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        val dir = store.dirForTest()
        repeat(20) { round ->
            // A FRESH entry per round: no cross-round carry-over can poison the baseline.
            val id = UUID.randomUUID().toString()
            assertNotNull(store.prepare(id, "epoch-A", chunks(8), meta(id), "a@x", 7L))
            val sidecar = File(dir, "$id.attempts")
            fun sidecarValue(): Int? = sidecar.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

            // --- Phase 1: a LIVE generation bump must be attributed and durable. ---
            val gen = fence.currentGeneration()
            val before = sidecarValue() ?: 0
            val reported = store.recordAttempt(gen, id)
            if (reported != null) {
                assertEquals("round $round: reported value must advance the baseline by one", before + 1, reported)
                assertEquals("round $round: durable sidecar disagrees with the reported bump", reported, sidecarValue())
            }

            // --- Phase 2: pin the revocation BEFORE the call, then assert against the pre-call baseline. ---
            fence.revoke()
            val baseline = sidecarValue()
            val afterRevoke = store.recordAttempt(gen, id)
            assertNull("round $round: a stale-generation bump was reported as successful", afterRevoke)
            assertEquals(
                "round $round: refused call WROTE the sidecar past its pre-call baseline",
                baseline,
                sidecarValue(),
            )
        }
    }

    @Test
    fun `generation cannot be consulted or changed outside the shared ownership transaction`() {
        // Reviewer ee26880c finding 1: reading or bumping the generation requires the shared monitor; a raw
        // fence reference cannot mutate state outside a transaction.
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val gen = fence.currentGeneration()
        val error = runCatching { ownership.validateOutsideLockForTest(gen) }.exceptionOrNull()
        assertNotNull("validation outside the shared monitor must be refused", error)
        assertTrue("expected IllegalStateException, got ${error?.let { it::class.simpleName }}", error is IllegalStateException)
        ownership.transaction<Unit> {
            assertTrue(isValid(gen))
            revoke()
            assertFalse("stale generation must be refused after revoke in the same transaction", isValid(gen))
        }
        assertEquals(gen + 1, fence.currentGeneration())
        assertEquals(gen + 2, fence.revoke())
        assertEquals(gen + 2, fence.currentGeneration())
    }

    @Test
    fun `public prepare consumes a pre-retention admission without re-admitting`() {
        // Reviewer 195845cb finding 5: the PUBLIC entry point must take the ORIGINAL validated handle.
        // A one-slot queue plus one pre-PCM admission must publish successfully; if prepare admitted a
        // second time, the slot it already occupies would make its own admission fail.
        val store = ContributionStore(tempRoot(), QueueLimits.defaults().copy(maxQueueRecords = 1))
        val admission = store.admit(8, meta(ID_A).size)
        assertNotNull(admission)
        // The caller (retention sink) reserved before copying PCM; the worker now does the disk work.
        val entry = store.prepare(admission!!, ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L)
        assertNotNull("pre-retention admission was not consumed by public prepare", entry)
        assertEquals(1, store.readyEntries().size)
        assertFalse("consumed admission must not stay held", admission.isHeld())
        assertEquals("published entry leaves no live reservation", 0, store.reservedRecordsForTest())
        // And the slot is genuinely occupied by the durable entry, not by a leaked reservation.
        assertNull(store.admit(8, 100))
    }

    @Test
    fun `early-return invalid size releases the transferred admission`() {
        // Finding 4: an invalid audioBytes / over-cap return must not leak the caller's reservation.
        val store = ContributionStore(tempRoot(), QueueLimits.defaults())
        val admission = store.admit(8, meta(ID_A).size)!!
        // Invalid per-chunk range makes sampleCount refuse AFTER the handle was handed over.
        val refused = store.prepareAt(store.fence.currentGeneration(), admission, ID_A, "epoch-A", listOf(ShortArray(4) to 9), meta(ID_A), "a@x", 7L)
        assertNull(refused)
        assertFalse("invalid-size early return leaked the admission", admission.isHeld())
        assertEquals(0, store.reservedRecordsForTest())
    }

    @Test
    fun `ownership paths take the store lock before the fence on every path`() {
        // Lock-order contract (reviewer 195845cb finding 1): publication, attempt writes, terminal drops and
        // revocation must ALL acquire the store lock first and consult the fence second. Mixing the orders is
        // what deadlocked: one thread holding the fence waiting for the store, another holding the store
        // waiting for the fence. This reads the committed source and fails if any ownership site reverts to
        // wrapping a store-lock block inside fence.guard, or calls the fence outside the store lock.
        val relative = "modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/" +
            "voicecontrol/upload/queue/ContributionStore.kt"
        val cwd = System.getProperty("user.dir") ?: error("no working directory reported")
        var probe: java.io.File? = java.io.File(cwd)
        var found: java.io.File? = null
        while (probe != null && found == null) {
            val candidate = java.io.File(probe, relative)
            if (candidate.isFile) found = candidate
            probe = probe.parentFile
        }
        val file = found ?: error("could not locate $relative from $cwd")
        val srcLines = file.readLines().map { it.substringBefore("//") }
        val src = srcLines.joinToString("\n")
        val ownershipSites = listOf(
            "val published: Entry? = synchronized(lock) {",
            "val result: Int? = synchronized(lock) {",
            "val removed = synchronized(lock) {",
        )
        ownershipSites.forEach { site ->
            assertTrue(
                "ownership site missing (expected `$site` on a single line, comments stripped)",
                srcLines.any { it.trim().startsWith(site.substringBefore(" {").trim()) && it.contains("synchronized(lock) {") },
            )
        }
        assertFalse(
            "fence.guard must not wrap any store-lock ownership block",
            Regex("fence\\.guard\\([^)]*\\)\\s*\\{\\s*synchronized\\(lock\\)").containsMatchIn(src),
        )
        val guardCalls = Regex("fence\\.guard\\(").findAll(src).count()
        assertEquals("no ownership path may take the fence first; found $guardCalls fence.guard call sites", 0, guardCalls)
        val ownershipChecks = Regex("ownership\\.isValidUnderLock\\(").findAll(src).count()
        assertTrue(
            "every ownership path should validate inside the shared monitor (found $ownershipChecks)",
            ownershipChecks >= 4,
        )
        assertFalse(
            "the store must not keep its own separate monitor away from the generation",
            Regex("private val lock[^\\n]*= *Any\\(\\)").containsMatchIn(src),
        )
        assertTrue(
            "revocation and cleanup must be one transaction on the shared monitor",
            srcLines.any { it.contains("fun revokeAndPurge(reason: String)") } &&
                Regex("ownership\\.revokeUnderLock\\(\\)").containsMatchIn(src),
        )
    }

    @Test
    fun `ownership paths cannot deadlock between publication and purge`() {
        // Finding 1: publication once took fence -> store while purge took store -> fence, so two threads
        // could each hold one lock and wait forever. All ownership paths now use store -> fence. This runs
        // preparation/purge against each other under a hard timeout: a deadlock shows up as failure, not a hang.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val store = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val publishers = (1..4).map { i ->
            Thread {
                try {
                    while (!stop.get()) {
                        val id = String.format("aaaaaaaa-0000-4000-8000-%012d", i * 1000 + 1)
                        store.prepare(id, "epoch-$i", chunks(8), meta(id), "a@x", 7L)
                    }
                } catch (t: Throwable) {
                    errors += "${t::class.simpleName}: ${t.message}"
                }
            }
        }
        val purger = Thread {
            try {
                var n = 0
                while (!stop.get() && n < 60) {
                    Thread.sleep(2)
                    store.purgeQueue("race-$n")
                    n++
                }
            } catch (t: Throwable) {
                errors += "${t::class.simpleName}: ${t.message}"
            }
        }
        val all = publishers + listOf(purger)
        all.forEach {
            it.isDaemon = true
            it.start()
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (all.any { it.isAlive } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        stop.set(true)
        all.forEach { it.join(5_000) }
        val stuck = all.filter { it.isAlive }
        assertTrue("lock-order deadlock between publication and purge; still running: ${stuck.map { it.name }}", stuck.isEmpty())
        assertTrue("unexpected errors during lock race: $errors", errors.isEmpty())
    }

    @Test
    fun `same-generation duplicate attempts do not share staging files`() {
        // Deterministic staging-collision control (reviewer 32d8012c follow-up): both attempts run in the
        // SAME generation for the SAME capture id, and the first is HELD INSIDE its staging write until the
        // second has finished writing. If staging were keyed by generation+captureId, both would open one
        // path and the first writer would observe the sibling's byte count in its own file. Unique attempt
        // identity keeps two files with their own sizes.
        val root = tempRoot()
        val probeDir = File(root, "contributions")
        val gate = java.util.concurrent.CountDownLatch(1)
        val holdFirstOpen = java.util.concurrent.Semaphore(0)
        val sightings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val firstWriterFlag = java.util.concurrent.atomic.AtomicBoolean(true)
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults(),
            hooks = StoreHooks(onWavPartWrite = { _ ->
                val partsNow = probeDir.listFiles()?.filter { it.name.endsWith(".part") }
                    ?.map { "${it.name}=${it.length()}B" }?.sorted()?.joinToString(" ; ") ?: ""
                sightings += "${Thread.currentThread().name} -> $partsNow"
                if (firstWriterFlag.compareAndSet(true, false)) {
                    gate.countDown()
                    holdFirstOpen.acquire()
                }
            }),
        )
        val store = theStore
        val gen = store.fence.currentGeneration()
        val large = chunks(4096) // 8236 B payload
        val small = chunks(8) //   60 B payload
        var failure: Throwable? = null
        val publishedCount = java.util.concurrent.atomic.AtomicInteger()
        fun run(samples: Int, chunks: List<Pair<ShortArray, Int>>) {
            try {
                val handle = store.admitForTest(samples.toLong(), meta(ID_A).size)
                if (store.prepareAt(gen, handle, ID_A, "epoch-A", chunks, meta(ID_A), "a@x", 7L) != null) publishedCount.incrementAndGet()
            } catch (t: Throwable) {
                failure = t
            }
        }

        val first = Thread({ run(4096, large) }, firstName)
        first.start()
        assertTrue("first attempt never entered its staging write", gate.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val second = Thread({ run(8, small) }, secondName)
        second.start()
        second.join(20_000) // second completes fully while the first is still held inside its write
        holdFirstOpen.release()
        first.join(20_000)
        failure?.let { throw it }

        assertEquals("both attempts must have been observed mid-write", 2, sightings.size)
        assertTrue("at most one attempt may publish a capture id", publishedCount.get() <= 1)

        val secondSighting = sightings.first { it.startsWith(secondName) }
        val firstSighting = sightings.first { it.startsWith(firstName) }
        // The second attempt sees TWO live staging files: its own (60B) and the first one's partial (8xxx B).
        assertTrue(
            "second attempt should see the first attempt's separate staging file: $secondSighting",
            secondSighting.contains("=60B"),
        )
        // Sharing one path would make the first writer's OWN file read the sibling's 60 B size.
        val firstSawOnlySiblingBytes = firstSighting.contains("=60B") && !Regex("""=\d{4,}B""").containsMatchIn(firstSighting)
        assertFalse(
            "same-generation attempts shared one staging path; first writer saw truncated bytes: $sightings",
            firstSawOnlySiblingBytes,
        )
        val distinctStagingNames = sightings.flatMap { line ->
            line.substringAfter("-> ").split(" ; ").filter { it.isNotEmpty() }.map { it.substringBefore("=") }
        }.distinct()
        assertEquals(
            "each attempt must own a distinct staging path; saw $distinctStagingNames",
            2,
            distinctStagingNames.size,
        )
    }

    @Test
    fun `HELD revocation during late callback publication cannot delete successor files`() {
        // Hook fires just before publication: the generation is already stale at that instant, so the
        // stale path must abandon WITHOUT touching any file for the shared capture id.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults(),
            ownership = ownership,
            fence = fence,
            hooks = StoreHooks(beforePublication = { _ -> theStore.purgeQueue("account-change") }),
        )
        val store = theStore
        val gen = fence.currentGeneration()
        assertNull(store.prepareAt(gen, store.admitForTest(8, meta(ID_A).size), ID_A, "epoch-A", chunks(8), meta(ID_A), "a@x", 7L))
        // Purge cleared everything; nothing from the fenced-off preparation may have been republished.
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })
        assertFalse(File(root, "contributions/$ID_A.ready").exists())
    }

    @Test
    fun `duplicate prepare for an owned capture does not clobber its immutable ready files`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val first = store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L)
        assertNotNull(first)
        val wavBefore = File(root, "contributions/$id.recording.wav").readBytes().toList()
        val second = store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L)
        assertNull("re-preparing an already-owned capture must be refused, not overwrite ready files", second)
        assertEquals(wavBefore, File(root, "contributions/$id.recording.wav").readBytes().toList())
        assertEquals(1, store.readyEntries().count { it.captureId == id })
    }

    @Test
    fun `single slot queue admits one prepare without double charging`() {
        // prepare() must CONSUME the Admission it validated, not re-admit and demand a second slot.
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults().copy(maxQueueRecords = 1))
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        assertEquals(1, store.readyEntries().size)
    }

    @Test
    fun `retry preserves exact identity - metadata digest stable across reload`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val bytes = meta(id)
        val created = store.prepare(id, "epoch-1", chunks(8), bytes, "a@x", 7L)!!
        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val reloaded = ContributionStore(root, QueueLimits.defaults()).recover().single()
        assertEquals(created.recordSha256, reloaded.recordSha256)
        assertEquals(id, reloaded.captureId)
        assertEquals("epoch-1", reloaded.enrollmentEpoch)
    }
// --- Account-change invalidation (task #73 recovery hardening, spec plan Task 5) ---

    @Test
    fun `account change invalidates the whole queue - entries never survive an identity switch`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val dir = File(root, "contributions")
        // Two ready entries prepared under account A's enrollment epoch.
        val idA = UUID.randomUUID().toString()
        val idB = UUID.randomUUID().toString()
        assertNotNull(store.prepare(idA, "epoch-A", chunks(64), meta(idA, "epoch-A"), "a@x", 7L))
        assertNotNull(store.prepare(idB, "epoch-A", chunks(64), meta(idB, "epoch-A"), "a@x", 7L))
        // A foreign debris entry under a foreign name must also go in a full revocation.
        File(dir, "not-a-uuid.wav").writeBytes(ByteArray(4))

        // Account switch: full revocation, content-free reason.
        store.purgeQueue("account_change")

        // Nothing survives in memory or on disk under this owner's namespace.
        assertTrue(store.readyEntries().isEmpty())
        for (id in listOf(idA, idB)) {
            for (suffix in listOf(".recording.wav", ".metadata.json", ".ready", ".attempts", ".created", ".sha256")) {
                assertFalse("residual $id$suffix after account change", File(dir, "$id$suffix").exists())
            }
        }
        // A later recover() must not resurrect purged entries (recovered latch). Each restart below takes
        // the directory only after the previous owner RELEASES it (spec c8f766fb: one live owner).
        store.close()
        val restarted = ContributionStore(root, QueueLimits.defaults())
        assertTrue(restarted.recover().isEmpty())

        // A NEW store instance on the same directory models the controller restart after the switch:
        // still nothing. Then a fresh admission under account B works normally.
        restarted.close()
        val storeB = ContributionStore(root, QueueLimits.defaults())
        assertTrue(storeB.recover().isEmpty())
        val idC = UUID.randomUUID().toString()
        assertNotNull(storeB.prepare(idC, "epoch-B", chunks(64), meta(idC, "epoch-B"), "a@x", 7L))
        assertEquals(listOf(idC), storeB.readyEntries().map { it.captureId })
    }

    // --- Recovery hardening: bounded reads / digest+size binding / final debris (finding 4) ---

    @Test
    fun `recovery refuses an oversized metadata file without reading it whole`() {
        val root = tempRoot()
        // The bound must FIT a conforming record (all six stages + five model keys), otherwise
        // prepare() would refuse before the oversized case ever arises.
        val limits = QueueLimits.defaults().copy(maxMetadataBytes = 4096)
        val store = ContributionStore(root, limits)
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(64), meta(id), "a@x", 7L))
        // Inflate the metadata far past the bound: recovery must refuse it, not allocate it.
        val oversized = ByteArray(1 * 1024 * 1024) { ' '.code.toByte() }
        File(root, "contributions/$id.metadata.json").writeBytes(oversized)
        // Record the digest of the oversized bytes so the DIGEST binding cannot be what refuses
        // it: the ONLY remaining reason to refuse is the size bound itself.
        File(root, "contributions/$id.sha256").writeText(sha256HexForTest(oversized))
        File(root, "contributions/$id.ready").writeBytes(ByteArray(0))
        val recovered = ContributionStore(root, limits).recover()
        assertTrue("oversized metadata admitted on recovery", recovered.isEmpty())
    }

    @Test
    fun `digesting metadata is bound to the exact retained metadata bytes`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val prepared = store.prepare(id, "epoch-1", chunks(64), meta(id), "a@x", 7L)
        assertNotNull(prepared)
        // The recorded digest must equal the digest of the bytes actually on disk, and must change
        // when the metadata content changes (identity is bound to content, not to a size or a name).
        val onDisk = File(root, "contributions/$id.metadata.json").readBytes()
        assertEquals(sha256HexForTest(onDisk), prepared!!.recordSha256)
        val other = UUID.randomUUID().toString()
        val preparedOther = store.prepare(other, "epoch-1", chunks(64), meta(other, "epoch-2"), "a@x", 7L)
        assertNotNull(preparedOther)
        assertNotEquals(preparedOther!!.recordSha256, prepared.recordSha256)
        // Tamper the retained metadata AFTER preparation (the .ready marker and the recorded digest
        // stay untouched). Recovery must refuse the entry: identity is bound to the exact bytes.
        val tampered = UUID.randomUUID().toString()
        assertNotNull(store.prepare(tampered, "epoch-1", chunks(64), meta(tampered), "a@x", 7L))
        File(root, "contributions/$tampered.metadata.json").writeBytes(meta(tampered, "epoch-evil"))
        assertTrue(
            "tampered metadata admitted on recovery",
            ContributionStore(root, QueueLimits.defaults()).recover().none { it.captureId == tampered },
        )
    }

    @Test
    fun `unmarked final debris is swept when it has no ready marker`() {
        val root = tempRoot()
        val dir = File(root, "contributions").apply { mkdirs() }
        val orphanWav = UUID.randomUUID().toString()
        File(dir, "$orphanWav.recording.wav").writeBytes(ByteArray(64))
        File(dir, "$orphanWav.metadata.json").writeBytes(meta(orphanWav))
        val foreign = "garbage-name.recording.wav"
        File(dir, foreign).writeBytes(ByteArray(8))
        assertTrue(ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
        assertFalse("unmarked final wav survived recovery", File(dir, "$orphanWav.recording.wav").exists())
        assertFalse("unmarked final meta survived recovery", File(dir, "$orphanWav.metadata.json").exists())
        assertFalse("foreign-named debris survived recovery", File(dir, foreign).exists())
    }

    // --- Reviewer round: mandatory digest identity, bounded sidecars, canonical record/WAV authority ---

    @Test
    fun `a missing digest sidecar refuses the entry - no legacy fallback`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        // Delete the digest sidecar AND change the metadata: without a mandatory digest the changed
        // bytes would be accepted as their own identity.
        File(root, "contributions/$id.sha256").delete()
        File(root, "contributions/$id.metadata.json").writeBytes(meta(id, "epoch-evil"))
        assertTrue("entry with no recorded digest was admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    @Test
    fun `malformed sidecars refuse the entry instead of defaulting attempts or renewing age`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        // Malformed attempts: must refuse, not default to 0 and hand back a spent retry budget.
        val id1 = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id1, "epoch-1", chunks(8), meta(id1), "a@x", 7L))
        File(root, "contributions/$id1.attempts").writeText("not-a-number")
        assertTrue("malformed attempts admitted", ContributionStore(root, QueueLimits.defaults()).recover().none { it.captureId == id1 })
        // Malformed created-at: must refuse, not fall back to mtime and silently renew queue age.
        val id2 = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id2, "epoch-1", chunks(8), meta(id2), "a@x", 7L))
        File(root, "contributions/$id2.created").writeText("bogus")
        assertTrue("malformed created-at admitted", ContributionStore(root, QueueLimits.defaults()).recover().none { it.captureId == id2 })
        // Oversized attempts sidecar: bounded read refuses without allocating it.
        val id3 = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id3, "epoch-1", chunks(8), meta(id3), "a@x", 7L))
        File(root, "contributions/$id3.attempts").writeBytes(ByteArray(1024) { '9'.code.toByte() })
        assertTrue("oversized attempts sidecar admitted", ContributionStore(root, QueueLimits.defaults()).recover().none { it.captureId == id3 })
    }

    @Test
    fun `audio tampering is refused - the record binds the actual WAV bytes`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val wav = File(root, "contributions/$id.recording.wav")
        // Flip ONE PCM sample byte deep in the body, leaving the 44-byte header (and the declared
        // byte_count) untouched: a header sniff cannot detect this, the audio digest must.
        val bytes = wav.readBytes()
        val target = WavWriter.HEADER_BYTES + 3
        bytes[target] = (bytes[target].toInt() xor 0x01).toByte()
        wav.writeBytes(bytes)
        assertTrue("tampered audio admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    @Test
    fun `record pointing at a different capture is refused`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        // Replace the metadata with a valid record for a DIFFERENT capture id, and update the digest
        // sidecar so the digest check passes: only the capture-id binding can refuse this.
        val other = UUID.randomUUID().toString()
        val foreign = meta(other)
        File(root, "contributions/$id.metadata.json").writeBytes(foreign)
        File(root, "contributions/$id.sha256").writeText(sha256HexForTest(foreign))
        assertTrue("record for another capture admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    @Test
    fun `record declaring a wrong byte_count or sample format is refused`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val valid = meta(id).toString(Charsets.UTF_8)
        // Wrong byte_count in the record vs the actual file length, digest updated to match.
        val wrongBytes = valid.replace("\"byte_count\"", "\"byte_count_x\"").replace("\"retained_samples\"", "\"retained_samples_x\"")
        val bad = wrongBytes.replace("\"byte_count_x\":8", "\"byte_count_x\":9")
        val badBytes = bad.toByteArray(Charsets.UTF_8)
        File(root, "contributions/$id.metadata.json").writeBytes(badBytes)
        File(root, "contributions/$id.sha256").writeText(sha256HexForTest(badBytes))
        assertTrue("record with a wrong byte_count admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    // --- Reviewer round: schema authority, exact integers, WAV structure ---

    /** Replace the retained metadata with [text] and keep the digest sidecar consistent. */
    private fun tamperMetadata(root: File, id: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        File(root, "contributions/$id.metadata.json").writeBytes(bytes)
        File(root, "contributions/$id.sha256").writeText(sha256HexForTest(bytes))
    }

    @Test
    fun `a fractional byte_count is refused rather than truncated to an integer`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val valid = meta(id).toString(Charsets.UTF_8)
        // 60 == actual file length; 60.9 would truncate to 60 under Double.toLong().
        val tampered = valid.replace("\"byte_count\":60", "\"byte_count\":60.9")
        assertNotEquals("fixture did not contain the expected byte_count", valid, tampered)
        tamperMetadata(root, id, tampered)
        assertTrue("fractional byte_count was truncated and admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    @Test
    fun `an empty enrollment_epoch is refused`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val valid = meta(id).toString(Charsets.UTF_8)
        val tampered = valid.replace("\"epoch-1\"", "\"\"")
        assertNotEquals("fixture did not contain the expected epoch", valid, tampered)
        tamperMetadata(root, id, tampered)
        assertTrue("empty epoch admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    @Test
    fun `unknown fields at root and inside stages are refused`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        // Unknown ROOT field.
        val id1 = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id1, "epoch-1", chunks(8), meta(id1), "a@x", 7L))
        val valid1 = meta(id1).toString(Charsets.UTF_8)
        tamperMetadata(root, id1, valid1.replaceFirst("{", "{\"surprise_field\":1,"))
        assertTrue("unknown root field admitted", ContributionStore(root, QueueLimits.defaults()).recover().none { it.captureId == id1 })
        // Unknown STAGE field (nested): only the stage allowlist can refuse this.
        val id2 = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id2, "epoch-1", chunks(8), meta(id2), "a@x", 7L))
        val valid2 = meta(id2).toString(Charsets.UTF_8)
        val withObservation = valid2.replace("\"observations\":{", "\"observations\":{\"wake\":{\"state\":\"completed\",\"not_allowed_here\":1},")
        assertNotEquals("fixture lacked observations", valid2, withObservation)
        tamperMetadata(root, id2, withObservation)
        assertTrue("unknown stage field admitted", ContributionStore(root, QueueLimits.defaults()).recover().none { it.captureId == id2 })
    }

    @Test
    fun `the record validator is the single schema authority - direct stage allowlist refusal`() {
        // Store-level tests can mask a missing stage allowlist (the store also compares capture_id
        // and audio identity). This asserts the VALIDATOR alone, on a record built legally then
        // given one unknown stage field, so only the allowlist can refuse it.
        val builder = ContributionRecordBuilder(UUID.randomUUID().toString(), "epoch-1", "7")
            .model("vad", ModelRef.None)
            .model("wakeword", ModelRef.None)
            .model("asr", ModelRef.None)
            .model("translation", ModelRef.None)
            .model("router", ModelRef.None)
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("rejected"))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("x"))))
            .stage(StageName.Bleed, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_playback_reference"))))
            .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
            .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
            .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("client_refused"))))
        val record = builder.build("android", "8.20", "test", audioBlockFor(8))
        assertTrue(
            "a legitimately built record must validate",
            RecordValidator.validate(record) is RecordValidator.Result.Ok,
        )
        // Inject an unknown field into an existing stage: allowlist-only refusal.
        val observations = record.fields["observations"] as RecordNode.Obj
        val wake = observations.fields["wake"] as RecordNode.Obj
        val poisonedWake = RecordNode.Obj(wake.fields + ("smuggled" to RecordNode.Str("value")))
        val poisoned = RecordNode.Obj(record.fields + ("observations" to RecordNode.Obj(observations.fields + ("wake" to poisonedWake))))
        assertTrue(
            "unknown stage field accepted without the allowlist",
            RecordValidator.validate(poisoned) is RecordValidator.Result.Rejected,
        )
    }

    @Test
    fun `the WAV validator refuses headers whose derived fields disagree with the format`() {
        // Prompt for this control: a digest authenticates malformed bytes as faithfully as valid
        // ones, so each structural field must be checked in its own right. Each case below is a
        // header that is internally CONSISTENT in length but describes a file this writer cannot
        // produce, or that a decoder may read differently from us.
        fun header(
            dataBytes: Long = 20,
            byteRateDrift: Long = 0,
            blockAlign: Int = 2,
            riffSizeDrift: Long = 0,
            byteRateFromBlockAlign: Boolean = true,
        ): ByteArray {
            val header = ByteArray(WavWriter.HEADER_BYTES)
            val out = java.io.ByteArrayOutputStream()
            WavWriter.writeHeader(out, (dataBytes / 2).toInt())
            out.toByteArray().copyInto(header)
            fun put32(at: Int, v: Long) {
                header[at] = (v and 0xFF).toByte()
                header[at + 1] = ((v shr 8) and 0xFF).toByte()
                header[at + 2] = ((v shr 16) and 0xFF).toByte()
                header[at + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun put16(at: Int, v: Int) {
                header[at] = (v and 0xFF).toByte()
                header[at + 1] = ((v shr 8) and 0xFF).toByte()
            }
            put32(4, WavWriter.HEADER_BYTES + dataBytes + riffSizeDrift - 8)
            put32(40, dataBytes)
            // When byteRateFromBlockAlign is true the byte rate FOLLOWS the (possibly wrong) block
            // alignment, so block alignment is the only field that can disagree.
            put32(28, if (byteRateFromBlockAlign) 16_000L * blockAlign + byteRateDrift else 16_000L * 2 + byteRateDrift)
            put16(32, blockAlign)
            return header
        }
        val fileBytes = WavWriter.HEADER_BYTES + 20L
        assertNotNull("baseline header rejected", WavHeaderValidator.validate(header(), fileBytes))
        assertNull("odd data length accepted", WavHeaderValidator.validate(header(dataBytes = 21), WavWriter.HEADER_BYTES + 21L))
        assertNull("wrong byte rate accepted", WavHeaderValidator.validate(header(byteRateDrift = 2), fileBytes))
        assertNull("wrong block alignment accepted", WavHeaderValidator.validate(header(blockAlign = 4), fileBytes))
        // Byte rate consistent with the wrong block alignment: ONLY the block-align rule can refuse.
        assertNull(
            "wrong block alignment with a consistent byte rate accepted",
            WavHeaderValidator.validate(header(blockAlign = 4), fileBytes),
        )
        // Four-byte block alignment also changes the implied sample width; keep the data even.
        assertNull(
            "block alignment 4 with even data accepted",
            WavHeaderValidator.validate(header(dataBytes = 20, blockAlign = 4), fileBytes),
        )
        assertNull("inconsistent RIFF size accepted", WavHeaderValidator.validate(header(riffSizeDrift = 2), fileBytes))
    }

    @Test
    fun `a malformed WAV header is refused even though its digest matches`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val wav = File(root, "contributions/$id.recording.wav")
        val bytes = wav.readBytes()
        // Corrupt the fmt chunk LENGTH (offset 16) and re-record the audio digest so the digest
        // check passes: only the structural validator can refuse this file.
        bytes[16] = 20
        wav.writeBytes(bytes)
        val newSha = sha256HexForTest(bytes)
        val valid = meta(id).toString(Charsets.UTF_8)
        val oldSha = sha256HexForTest(meta(id).let { wavBytes(8) })
        tamperMetadata(root, id, valid.replace(oldSha, newSha))
        assertTrue("malformed fmt chunk length admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    @Test
    fun `a WAV with an odd data length is refused`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id), "a@x", 7L))
        val wav = File(root, "contributions/$id.recording.wav")
        val bytes = wav.readBytes().copyOf(wav.readBytes().size - 1) // truncated mid-sample
        wav.writeBytes(bytes)
        val newSha = sha256HexForTest(bytes)
        val oldSha = sha256HexForTest(wavBytes(8))
        tamperMetadata(root, id, meta(id).toString(Charsets.UTF_8).replace(oldSha, newSha))
        assertTrue("odd-length data admitted", ContributionStore(root, QueueLimits.defaults()).recover().isEmpty())
    }

    @Test
    fun `a closed owner refuses every stale mutation instead of touching the successor`() {
        // SPEC c8f766fb asks for proof of which stale operations survive their fences. MEASURED here:
        // none of them do. close() invalidates the handle BEFORE the OS lock is dropped, so a delayed
        // callback into the old instance refuses on every mutating surface, and the successor's
        // identical-id entry keeps its files and its readiness. No persisted owner token is needed for
        // this case, which is why none was added.
        val root = tempRoot()
        val ownership = QueueOwnership()
        val fence = QueueFence(ownership)
        val old = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        assertNotNull(old.prepare(ID_A, "epoch-old", chunks(8), meta(ID_A), "a@x", 7L))
        val staleGen = fence.currentGeneration()
        fence.revoke()
        old.revokeAndPurge("account-change")
        old.close()

        val successor = ContributionStore(root, QueueLimits.defaults(), ownership = ownership, fence = fence)
        assertNotNull("PREMISE: the successor publishes the same id", successor.prepare(ID_A, "epoch-new", chunks(8), meta(ID_A), "a@x", 7L))
        val wavBefore = File(root, "contributions/$ID_A.recording.wav").readBytes()

        // Every stale surface the old instance can still be called on.
        assertNull("stale recordAttempt", old.recordAttempt(staleGen, ID_A))
        assertFalse("stale dropEntry", old.dropEntry(staleGen, ID_A, ContributionStore.TerminalReason.SERVER_TERMINAL))
        assertNull("stale admit", old.admit(8, meta(ID_A).size))
        assertEquals("stale recover must return nothing", emptyList<ContributionStore.Entry>(), old.recover())
        val refusalsBefore = old.guardedRefusalsForTest()
        old.revokeAndPurge("late-second-purge")
        assertNotNull("the closed owner reports why", old.unavailableReason())
        // The stale purge must not be a silent no-op either. Counting the DELTA around this call is what
        // makes it observable: "the files are still there" would also hold if the purge ran and found an
        // empty index, which is exactly how a missing guard hides.
        assertEquals(
            "the closed owner's purge must be REFUSED, not silently applied",
            refusalsBefore + 1,
            old.guardedRefusalsForTest(),
        )

        // The successor's entry is untouched by any of it.
        assertArrayEquals("successor audio rewritten by a stale operation", wavBefore, File(root, "contributions/$ID_A.recording.wav").readBytes())
        assertEquals(listOf(ID_A), successor.readyEntries().map { it.captureId })
        assertEquals("epoch-new", successor.readyEntries().single().enrollmentEpoch)
    }

    @Test
    fun `close drains an operation already past the guard and refuses a later one`() {
        // REVIEWER 7db01cd9 finding 1: invalidating on close fences NEW entrants but not an operation
        // already running, which could resume after a successor owns the directory. This parks a real
        // writer inside the store, closes, and proves the close WAITS for it.
        val root = tempRoot()
        val parked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val store = ContributionStore(
            root,
            QueueLimits.defaults(),
            hooks = StoreHooks(onWavPartWrite = { _ ->
                entered.countDown()
                parked.countDown()
                release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            }),
        )
        val writer = Thread {
            runCatching { store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L) }
        }
        writer.start()
        assertTrue("PREMISE: the writer reached the guarded write", parked.await(30, java.util.concurrent.TimeUnit.SECONDS))

        val closedAt = java.util.concurrent.CountDownLatch(1)
        val closer = Thread {
            store.close()
            closedAt.countDown()
        }
        closer.start()
        // close() must NOT complete while an operation is inside the guarded section.
        assertFalse(
            "close returned while a guarded operation was still running",
            closedAt.await(400, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        release.countDown()
        assertTrue("close completes once the writer drains", closedAt.await(30, java.util.concurrent.TimeUnit.SECONDS))
        writer.join()

        // And after the drain, the directory is genuinely free for a successor.
        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("a successor acquires the drained directory", successor.isAvailable())
        assertNotNull("the successor can publish", successor.prepare(java.util.UUID.randomUUID().toString(), "epoch-epoch", chunks(8), meta(java.util.UUID.randomUUID().toString()), "a@x", 7L))
    }

    @Test
    fun `a closed handle cannot reacquire the directory and lock out a successor`() {
        // REVIEWER 7db01cd9 finding 3: close() cleared lock/reason, so acquire() would happily take the OS
        // lock again while isHeld() still reported false - holding the directory and denying every owner.
        val root = tempRoot()
        val abandoned = ContributionStore(root, QueueLimits.defaults())
        assertTrue("PREMISE: it starts as the owner", abandoned.isAvailable())
        abandoned.close()

        // A late callback must be refused, not silently re-acquire.
        assertNull("closed handle refuses admission", abandoned.admit(8, meta(ID_A).size))
        assertEquals("and says why", "closed", abandoned.unavailableReason())

        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("the successor takes the directory; nothing was re-acquired", successor.isAvailable())
        assertEquals(null, successor.unavailableReason())
        assertNotNull(successor.prepare(java.util.UUID.randomUUID().toString(), "epoch-epoch", chunks(8), meta(java.util.UUID.randomUUID().toString()), "a@x", 7L))
    }

    @Test
    fun `a self-close does not release the directory to a successor until the writer exits`() {
        // REVIEWER 52ee7e91: a self-close must NOT release the OS lock while its own writer is still
        // running, or a successor acquires and the old writer resumes against it. The release is DEFERRED
        // to the last lease leaving, not skipped.
        val root = tempRoot()
        val successorAcquired = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val probed = java.util.concurrent.CountDownLatch(1)
        lateinit var store: ContributionStore
        store = ContributionStore(
            root,
            QueueLimits.defaults(),
            hooks = StoreHooks(onWavPartWrite = { _ ->
                store.close()
                // Still inside the guarded write: the directory must NOT be handed over yet.
                successorAcquired.set(ContributionStore(root, QueueLimits.defaults()).isAvailable())
                probed.countDown()
            }),
        )
        // The write itself is abandoned because the store closed mid-flight; that is not the assertion.
        store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L)
        assertTrue("the hook ran", probed.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(
            "a successor acquired the directory while the old writer was still inside its guarded section",
            false,
            successorAcquired.get(),
        )

        // AFTER the writer exits the deferred release must actually happen, or the directory is wedged.
        val afterExit = ContributionStore(root, QueueLimits.defaults())
        assertTrue(
            "the deferred release must complete once the last lease leaves; got ${afterExit.unavailableReason()}",
            afterExit.isAvailable(),
        )
        assertNotNull(
            "and a successor can publish",
            afterExit.prepare(java.util.UUID.randomUUID().toString(), "epoch-epoch", chunks(8), meta(java.util.UUID.randomUUID().toString()), "a@x", 7L)
                ?: error("successor publication refused"),
        )
    }

    @Test
    fun `a nested fenced call keeps the outer operation in flight so self-close still defers`() {
        // REVIEWER 52ee7e91: nesting must be a DEPTH, not a flag. If an inner enter/leave cleared the
        // thread's depth, a self-close inside the outer operation would treat itself as untouched and
        // release the directory while the outer writer is still running.
        val root = tempRoot()
        val successorAcquired = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val probed = java.util.concurrent.CountDownLatch(1)
        lateinit var store: ContributionStore
        store = ContributionStore(
            root,
            QueueLimits.defaults(),
            hooks = StoreHooks(onWavPartWrite = { _ ->
                // An INNER fenced operation completes and leaves its lease while the outer one continues.
                store.expireOlderThan()
                store.close()
                successorAcquired.set(ContributionStore(root, QueueLimits.defaults()).isAvailable())
                probed.countDown()
            }),
        )
        // Bounded: a broken nesting model deadlocks here, and a hang is a worse signal than a failure.
        val writer = Thread { runCatching { store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L) } }
        writer.isDaemon = true
        writer.start()
        assertTrue("the hook ran (a false here means nesting deadlocked)", probed.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(
            "a successor acquired the directory after an inner lease left but the outer writer was still running",
            false,
            successorAcquired.get(),
        )
    }

    @Test
    fun `an external close must not deadlock a leased operation that needs the lifecycle monitor`() {
        // REVIEWER 487d6967's CORRECTED interleaving, and the one that really blocks: an ACTIVE leased
        // operation calls back into the ownership object and needs the lifecycle monitor, while an external
        // close() holds that monitor waiting for the same operation's lease. inFlight can never reach zero,
        // and acquire() cannot return, so neither thread can finish.
        //
        // A polling drain does NOT save this case, which is why the fix is to stop holding the monitor.
        val ownership = FileDirectoryOwnership(ownedDir("reentrant-close"))
        ownership.acquire()
        assertTrue("PREMISE: the owner holds the directory", ownership.isHeld())

        val insideLease = java.util.concurrent.CountDownLatch(1)
        val closeStarted = java.util.concurrent.CountDownLatch(1)
        val opDone = java.util.concurrent.CountDownLatch(1)
        val operation = Thread {
            if (ownership.enter()) {
                try {
                    insideLease.countDown()
                    // Wait until the external close is definitely inside close().
                    closeStarted.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    // A nested lifecycle call that needs the SAME monitor close() may be holding.
                    ownership.acquire()
                } finally {
                    ownership.leave()
                    opDone.countDown()
                }
            }
        }
        operation.isDaemon = true
        operation.start()
        assertTrue("PREMISE: the operation holds a lease", insideLease.await(30, java.util.concurrent.TimeUnit.SECONDS))

        val closer = Thread {
            // Signal BEFORE close() returns: the operation is waiting for close to be IN PROGRESS, and
            // closing the latch afterwards made this fixture wait on itself.
            closeStarted.countDown()
            ownership.close()
        }
        closer.isDaemon = true
        closer.start()

        assertTrue(
            "the leased operation finished (a false here means close() held the monitor it needed)",
            opDone.await(15, java.util.concurrent.TimeUnit.SECONDS),
        )
    }

    private fun ownedDir(name: String): File = tempRoot().resolve(name).also { it.mkdirs() }

    @Test
    fun `a deferred self-close followed by an external close does not deadlock the last writer`() {
        // REVIEWER 7f1bfeb0: synchronized close() held the lifecycle monitor while awaiting inFlight==0;
        // the last leave() then called synchronized releaseLock() and blocked on that same monitor.
        val root = tempRoot()
        val writerInside = java.util.concurrent.CountDownLatch(1)
        val releaseWriter = java.util.concurrent.CountDownLatch(1)
        lateinit var store: ContributionStore
        store = ContributionStore(
            root,
            QueueLimits.defaults(),
            hooks = StoreHooks(onWavPartWrite = { _ ->
                // DEFERRED SELF-CLOSE: our own lease is active, so the release must be deferred.
                store.close()
                writerInside.countDown()
                releaseWriter.await(30, java.util.concurrent.TimeUnit.SECONDS)
            }),
        )
        val writer = Thread { runCatching { store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L) } }
        writer.isDaemon = true
        writer.start()
        assertTrue("PREMISE: the writer is inside the guarded section", writerInside.await(30, java.util.concurrent.TimeUnit.SECONDS))

        // EXTERNAL CLOSE from another thread, while the deferred self-close is still pending.
        val externalDone = java.util.concurrent.CountDownLatch(1)
        val external = Thread {
            store.close()
            externalDone.countDown()
        }
        external.isDaemon = true
        external.start()

        // Let the writer exit: this is the last leave(), which must release without blocking on a monitor.
        releaseWriter.countDown()
        assertTrue(
            "the writer exited (a false here means the last leave() deadlocked)",
            awaitThread(writer, 15_000),
        )
        assertTrue(
            "the external close completed (a false here means close() deadlocked on the drain)",
            awaitLatch(externalDone, 15_000),
        )

        // And the directory is genuinely free afterwards.
        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("a successor acquires after both closes settle; got ${successor.unavailableReason()}", successor.isAvailable())
    }

    private fun awaitThread(thread: Thread, timeoutMs: Long): Boolean {
        thread.join(timeoutMs)
        return !thread.isAlive
    }

    private fun awaitLatch(latch: java.util.concurrent.CountDownLatch, timeoutMs: Long): Boolean = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    @Test
    fun `a parked purge blocks close until it finishes and the successor then owns the directory`() {
        // REVIEWER 72a35e19: revokeAndPurge() checked ownership but never entered a lease, so it could pass
        // the check, pause, and finish deleting after close() released the directory to a successor.
        val root = tempRoot()
        val parked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = ContributionStore(
            root,
            QueueLimits.defaults(),
            hooks = StoreHooks(onPurgeOrRecoverMutation = { what ->
                if (what == "purge") {
                    parked.countDown()
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                }
            }),
        )
        assertNotNull(store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L))

        val purge = Thread { store.revokeAndPurge("policy-disable") }
        purge.isDaemon = true
        purge.start()
        assertTrue("PREMISE: the purge is parked inside its mutation", parked.await(30, java.util.concurrent.TimeUnit.SECONDS))

        // close() from another thread MUST wait: the purge still holds the lease.
        val closeReturned = java.util.concurrent.CountDownLatch(1)
        val closer = Thread {
            store.close()
            closeReturned.countDown()
        }
        closer.isDaemon = true
        closer.start()
        assertFalse(
            "close() returned while the purge was still mutating - it did not drain the purge",
            closeReturned.await(1, java.util.concurrent.TimeUnit.SECONDS),
        )

        release.countDown()
        assertTrue("the purge finished", awaitThread(purge, 30_000))
        assertTrue("and close() then completed", closeReturned.await(15, java.util.concurrent.TimeUnit.SECONDS))

        // The purge really did its work, and only then did the successor take over.
        assertFalse("the purged capture must be gone", File(root, "contributions/$ID_A.recording.wav").isFile)
        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("the successor acquires; got ${successor.unavailableReason()}", successor.isAvailable())
    }

    @Test
    fun `a parked recovery blocks close until it finishes and the successor then owns the directory`() {
        val root = tempRoot()
        val parked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = ContributionStore(
            root,
            QueueLimits.defaults(),
            hooks = StoreHooks(onPurgeOrRecoverMutation = { what ->
                if (what == "recover") {
                    parked.countDown()
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                }
            }),
        )
        val recoverer = Thread { store.recover() }
        recoverer.isDaemon = true
        recoverer.start()
        assertTrue("PREMISE: recovery is parked inside its mutation", parked.await(30, java.util.concurrent.TimeUnit.SECONDS))

        val closeReturned = java.util.concurrent.CountDownLatch(1)
        val closer = Thread {
            store.close()
            closeReturned.countDown()
        }
        closer.isDaemon = true
        closer.start()
        assertFalse(
            "close() returned while recovery was still mutating - it did not drain recovery",
            closeReturned.await(1, java.util.concurrent.TimeUnit.SECONDS),
        )

        release.countDown()
        assertTrue("recovery finished", awaitThread(recoverer, 30_000))
        assertTrue("and close() then completed", closeReturned.await(15, java.util.concurrent.TimeUnit.SECONDS))
        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("the successor acquires; got ${successor.unavailableReason()}", successor.isAvailable())
    }

    @Test
    fun `a closed owner cannot release an admitted attempt out of the successor's directory`() {
        // Found by the entry-point audit reviewer e09f4cfc asked for: releaseForAdmittedAttempt() deletes
        // via deleteLocked() with NO ownership check and NO lease, so a stale ACK callback on a closed
        // owner can delete a successor's files. Every other mutating surface refuses; this one did not.
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val token = store.admitForTest(8, meta(ID_A).size).let { admission ->
            store.prepareAt(
                store.fence.currentGeneration(),
                admission,
                ID_A,
                "epoch-epoch",
                chunks(8),
                meta(ID_A),
                "a@x",
                7L,
            )
            val picked = store.pickupAttempt(store.fence.currentGeneration(), ID_A)
            store.admittedAttemptForTest(ID_A) ?: error("no admitted attempt; pickup=$picked")
        }
        assertTrue("PREMISE: the attempt exists", File(root, "contributions/$ID_A.recording.wav").isFile)

        store.close()
        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("the successor owns the directory", successor.isAvailable())

        // The stale ack callback hits the CLOSED owner with a valid-looking token.
        val released = store.releaseForAdmittedAttempt(token)

        assertFalse("a closed owner released an admitted attempt", released)
        assertTrue(
            "the successor's file was deleted by the closed owner's late release",
            File(root, "contributions/$ID_A.recording.wav").isFile,
        )
    }

    @Test
    fun `durableCapture refuses once the owner is closed even though the bytes remain`() {
        // Reviewer c5a00f02: durableCapture() reads and HASHES the WAV, metadata and .digest sidecar, so it
        // is filesystem work, not an in-memory read. It must sit inside the lease like any other surface that
        // touches the directory: a closed owner must not validate bytes out of a successor's namespace.
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        assertNotNull(store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L))
        assertNotNull("PREMISE: the mounted owner can validate its own bytes", store.durableCapture(ID_A))

        store.close()
        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("the successor owns the directory", successor.isAvailable())

        assertNull(
            "a closed owner vouchsafed bytes inside a successor's directory",
            store.durableCapture(ID_A),
        )
        // The successor serves from its OWN recovered index; a fresh store has not recovered, which is the
        // separately-tracked startup-discovery gap, so this control asserts nothing about it.
    }

    @Test
    fun `the transport reads the bytes the store validated even after the path is replaced`() {
        // CLOSES the gap reviewer 54d0f185 demonstrated (spec ee31a3cc). The same real sequence as before -
        // validate, let the directory change hands, publish different bytes at the same path - but now the
        // transport reads the OWNED descriptor instead of re-opening the pathname. What it sends is what the
        // store hashed, because it is the same file description.
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        assertNotNull(store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L))
        val capture = requireNotNull(store.durableCapture(ID_A)) { "PREMISE: the store vouches for its bytes" }
        val owned = requireNotNull(capture.owned) { "PREMISE: the store hands down an owned source" }
        val audioNode = capture.record.fields.getValue("audio") as au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj
        val validatedSha = (audioNode.fields.getValue("sha256") as au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str).value
        assertEquals("PREMISE: the on-disk bytes match the record", validatedSha, sha256Of(capture.audio))
        assertEquals("PREMISE: the descriptor hashed its own bytes", validatedSha, owned.audioSha256)

        // The directory changes hands and the new owner publishes DIFFERENT bytes at the same path.
        store.close()
        val successor = ContributionStore(root, QueueLimits.defaults())
        assertTrue("the successor owns the directory", successor.isAvailable())
        val replaced = java.util.UUID.randomUUID().toString()
        assertNotNull(successor.prepare(replaced, "epoch-epoch", chunks(40), meta(replaced), "a@x", 7L))
        val replacementBytes = File(root, "contributions/$replaced.recording.wav").readBytes()
        // MODEL THE REAL MECHANISM: production publishes by writing <id>.wav.part and RENAMING it into
        // place (claimFinalNames), never by rewriting a final name in place. A rename swaps the inode, so
        // the path now resolves to different bytes while an already-open descriptor keeps the old inode.
        // An in-place writeBytes() would truncate the SAME inode and an open descriptor would correctly
        // follow it - that models something production never does and cannot demonstrate this gap.
        val staged = File(root, "contributions/$ID_A.replacement.staged")
        staged.writeBytes(replacementBytes)
        assertTrue("PREMISE: the replacement was published by rename", staged.renameTo(capture.audio))
        assertNotEquals("PREMISE: the replacement differs", validatedSha, sha256Of(capture.audio))

        // Re-opening the path now yields the replacement - this is what the old code sent.
        assertEquals(
            "PREMISE: the pathname now serves different bytes",
            sha256HexOf(replacementBytes),
            sha256Of(capture.audio),
        )

        // The owned source still serves the validated bytes, which is what the transport now reads.
        val borrow = requireNotNull(owned.borrowForBody()) { "PREMISE: the source is borrowable" }
        val transmitted = try {
            sha256HexOf(borrow.stream.readBytes())
        } finally {
            borrow.close()
            owned.close()
        }
        assertEquals("the transport sent bytes the store never validated", validatedSha, transmitted)
    }

    @Test
    fun `a capture that cannot open its descriptor is refused rather than served by path`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        assertNotNull(store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L))
        // Remove the audio: validation cannot open a descriptor, so no capture may be served.
        assertTrue(File(root, "contributions/$ID_A.recording.wav").delete())
        assertNull("the store served a capture it could not bind to bytes", store.durableCapture(ID_A))
    }

    @Test
    fun `a refused capture closes the descriptor instead of leaking it`() {
        // Every refusal path after the open must close the descriptor. A corrupt owner sidecar is the
        // cheapest reachable refusal that happens AFTER the open.
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        assertNotNull(store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L))
        val capture = requireNotNull(store.durableCapture(ID_A)) { "PREMISE: the store vouches once" }
        requireNotNull(capture.owned).close()

        // Corrupt the sidecar: the epoch no longer matches, so the next attempt refuses AFTER opening.
        store.close()
        val sidecar = File(root, "contributions/$ID_A.owner")
        assertTrue("PREMISE: the sidecar exists", sidecar.isFile)
        sidecar.writeText("""{"capture_id":"$ID_A","account":"a@x","session":7,"epoch":"WRONG"}""")
        val fresh = ContributionStore(root, QueueLimits.defaults())
        assertNull("a mismatched sidecar must refuse the capture", fresh.durableCapture(ID_A))
        // If the refusal leaked, repeated refusals exhaust the process's descriptors. Prove it does not by
        // refusing many times and still being able to open a file.
        repeat(64) { assertNull(fresh.durableCapture(ID_A)) }
        val probe = File(root, "contributions/$ID_A.recording.wav").inputStream()
        probe.close()
    }

    @Test
    fun `a capture whose descriptor is closed cannot be replayed`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        assertNotNull(store.prepare(ID_A, "epoch-epoch", chunks(8), meta(ID_A), "a@x", 7L))
        val capture = requireNotNull(store.durableCapture(ID_A))
        val owned = requireNotNull(capture.owned)
        owned.close()
        assertNull("a settled source admitted another body read", owned.borrowForBody())
        store.close()
    }

    private fun sha256HexOf(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Of(file: File): String = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
