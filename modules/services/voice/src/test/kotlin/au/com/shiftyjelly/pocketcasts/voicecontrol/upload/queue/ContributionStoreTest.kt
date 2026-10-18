package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionStoreTest {

    private val firstName = "first-attempt"
    private val secondName = "second-attempt"


    private companion object {
        const val ID_A = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val SUCC = "11111111-2222-3333-4444-555555555555"
    }

    private fun tempRoot(): File = Files.createTempDirectory("contrib").toFile()

    private fun meta(captureId: String, epoch: String = "epoch-1"): ByteArray = ("""{"capture_id":"$captureId","enrollment_epoch": "$epoch"}""").toByteArray()

    private fun chunks(vararg sizes: Int) = sizes.map { ShortArray(it) { i -> i.toShort() } to it }

    @Test
    fun `ready marker is the commit point - interrupted preparation never becomes ready`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id)))
        // Simulate a kill MID-PREPARATION of a second entry: only .part debris exists.
        val id2 = UUID.randomUUID().toString()
        File(root, "contributions").mkdirs()
        File(root, "contributions/$id2.wav.part").writeBytes(ByteArray(10))
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
        assertNotNull(store.prepare(id, "epoch-1", chunks(16), meta(id)))
        // Record cap reached: deny BEFORE any copy; no new files appear.
        assertNull(store.admit(16, 100))
        assertNull(store.prepare(UUID.randomUUID().toString(), "epoch-1", chunks(16), meta("x")))
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
        assertNotNull(store.prepare(idA, "epoch-1", chunks(8), meta(idA)))
        now += 150_000 // A (created at t=1_000_000) is now 150s old > max_queue_age_s=100
        assertNotNull(store.prepare(idB, "epoch-1", chunks(8), meta(idB)))
        store.recordAttempt(idA)
        // RESTART: fresh store recovers durable state including attempt counts.
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
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id)))
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
        assertNotNull(store.prepare(good, "epoch-1", chunks(8), meta(good)))
        assertNotNull(store.prepare(bad, "epoch-1", chunks(8), meta(bad)))
        // Corrupt the bad entry's WAV AFTER it was marked ready (truncated body vs header).
        val wav = File(root, "contributions/$bad.recording.wav")
        wav.writeBytes(wav.readBytes().copyOf(wav.readBytes().size - 2))
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
        store.prepare(id, "epoch-1", chunks(8, 4), meta(id))
        val entry = store.readyEntries().single()
        assertEquals(WavWriter.fileBytes(12), entry.audioBytes)
        assertEquals(44 + 24, entry.audioBytes)
    }

    @Test
    fun `fence blocks late callbacks after revocation and successor account`() {
        val fence = QueueFence()
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
        assertNull(store.prepare(id, "epoch-1", chunks(16_000 * 45), meta(id))) // 45 s > max_clip_ms
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
                if (store.prepare(id, "epoch-1", chunks(16), meta(id)) != null) results.add(id)
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
        assertNotNull(store.prepare(id, "epoch-1", chunks(16), meta(id)))
        store.purgeQueue("account-change")
        assertEquals(0, store.readyEntries().size)
        assertNull("attempt bookkeeping must not resurrect a purged entry", store.recordAttempt(id))
        assertEquals(0, store.readyEntries().size)
        assertFalse(File(root, "contributions/$id.ready").exists())
    }

    // ---- HELD (deterministic) controls: a hook fires WHILE the store is mid-preparation, so the
    // interleaving is forced rather than raced. These replace sequential-after-purge proxies.

    @Test
    fun `HELD revocation during actual WAV write releases the reservation and keeps successor files`() {
        // The hook runs at the moment the .wav.part write happens: purge lands DURING the real write.
        val root = tempRoot()
        val fence = QueueFence()
        var hooked = false
        // The successor runs on the NEW generation after a revoke, which is exactly what an account
        // change does: old owner purged, new owner opens its own store instance on the same directory.
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 2),
            fence = fence,
            hooks = StoreHooks(onWavPartWrite = { _ ->
                hooked = true
                fence.revoke() // revocation lands DURING the stale owner's actual WAV write
                ContributionStore(root, QueueLimits.defaults(), fence = fence)
                    .prepare(SUCC, "epoch-B", chunks(8), meta(SUCC))
                theStore.purgeQueue("account-change")
            }),
        )
        val store = theStore
        val staleGen = fence.currentGeneration()
        assertNull(store.prepareAt(staleGen, ID_A, "epoch-A", chunks(8), meta(ID_A)))
        assertTrue("hook never fired - control did not exercise the actual write", hooked)
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
        // One-slot queue: the stale reservation was released, so a fresh admission fits again.
        assertNotNull(store.admit(8, 100))
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
        assertNotNull(store.prepare(ID_A, "epoch-1", chunks(8), meta(ID_A)))
        assertEquals(1, store.readyEntries().size)
        assertEquals("published entry leaves no live reservation", 0, store.reservedRecordsForTest())
        // (d) duplicate prepare for an owned capture is refused WITHOUT consuming a second slot
        assertNull(store.prepare(ID_A, "epoch-1", chunks(8), meta(ID_A)))
        assertEquals(0, store.reservedRecordsForTest())
        // Budget still has exactly one record free: prove it by filling it, then seeing denial.
        assertNotNull(store.prepare(SUCC, "epoch-2", chunks(8), meta(SUCC)))
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
        val fence = QueueFence()
        var hooked = false
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 1),
            fence = fence,
            hooks = StoreHooks(beforePublication = { _ ->
                hooked = true
                fence.revoke() // stale at the publication instant; deliberately NO purgeQueue() call
            }),
        )
        val store = theStore
        val gen = fence.currentGeneration()
        assertNull(store.prepareAt(gen, ID_A, "epoch-A", chunks(8), meta(ID_A)))
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
        val fence = QueueFence()
        var hooked = false
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 1),
            fence = fence,
            hooks = StoreHooks(onWavPartWrite = { gen ->
                hooked = true
                fence.revoke() // makes our captured generation stale while we are mid-write
                theStore.purgeQueue("account-change")
            }),
        )
        val store = theStore
        assertNull(store.prepareAt(fence.currentGeneration().let { it }, ID_A, "epoch-A", chunks(8), meta(ID_A)))
        assertTrue("hook never fired - control did not exercise the actual write", hooked)
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })
        assertNotNull("stale preparation leaked its reservation into a 1-slot queue", store.admit(8, 100))
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
            root, QueueLimits.defaults(),
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
        val small = chunks(8)    //   60 B payload
        var failure: Throwable? = null
        val publishedCount = java.util.concurrent.atomic.AtomicInteger()
        fun run(chunks: List<Pair<ShortArray, Int>>) {
            try {
                if (store.prepareAt(gen, ID_A, "epoch-A", chunks, meta(ID_A)) != null) publishedCount.incrementAndGet()
            } catch (t: Throwable) {
                failure = t
            }
        }

        val first = Thread({ run(large) }, firstName)
        first.start()
        assertTrue("first attempt never entered its staging write", gate.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val second = Thread({ run(small) }, secondName)
        second.start()
        second.join(20_000)  // second completes fully while the first is still held inside its write
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
            "each attempt must own a distinct staging path; saw $distinctStagingNames", 2, distinctStagingNames.size,
        )
    }

    @Test
    fun `HELD revocation during late callback publication cannot delete successor files`() {
        // Hook fires just before publication: the generation is already stale at that instant, so the
        // stale path must abandon WITHOUT touching any file for the shared capture id.
        val root = tempRoot()
        val fence = QueueFence()
        lateinit var theStore: ContributionStore
        theStore = ContributionStore(
            root,
            QueueLimits.defaults(),
            fence = fence,
            hooks = StoreHooks(beforePublication = { _ -> theStore.purgeQueue("account-change") }),
        )
        val store = theStore
        val gen = fence.currentGeneration()
        assertNull(store.prepareAt(gen, ID_A, "epoch-A", chunks(8), meta(ID_A)))
        // Purge cleared everything; nothing from the fenced-off preparation may have been republished.
        assertEquals(emptyList<String>(), store.readyEntries().map { it.captureId })
        assertFalse(File(root, "contributions/$ID_A.ready").exists())
    }

    @Test
    fun `duplicate prepare for an owned capture does not clobber its immutable ready files`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val first = store.prepare(id, "epoch-1", chunks(8), meta(id))
        assertNotNull(first)
        val wavBefore = File(root, "contributions/$id.recording.wav").readBytes().toList()
        val second = store.prepare(id, "epoch-1", chunks(8), meta(id))
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
        assertNotNull(store.prepare(id, "epoch-1", chunks(8), meta(id)))
        assertEquals(1, store.readyEntries().size)
    }

    @Test
    fun `retry preserves exact identity - metadata digest stable across reload`() {
        val root = tempRoot()
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val bytes = meta(id)
        val created = store.prepare(id, "epoch-1", chunks(8), bytes)!!
        val reloaded = ContributionStore(root, QueueLimits.defaults()).recover().single()
        assertEquals(created.recordSha256, reloaded.recordSha256)
        assertEquals(id, reloaded.captureId)
        assertEquals("epoch-1", reloaded.enrollmentEpoch)
    }
}
