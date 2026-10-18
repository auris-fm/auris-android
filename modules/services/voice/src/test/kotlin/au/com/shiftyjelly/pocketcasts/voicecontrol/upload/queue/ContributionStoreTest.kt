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
        assertTrue(store.admit(16, 100))
        assertNotNull(store.prepare(id, "epoch-1", chunks(16), meta(id)))
        // Record cap reached: deny BEFORE any copy; no new files appear.
        assertFalse(store.admit(16, 100))
        assertNull(store.prepare(UUID.randomUUID().toString(), "epoch-1", chunks(16), meta("x")))
        // Clip byte cap: 31s of 16k mono exceeds max_clip_bytes -> deny.
        assertFalse(store.admit(16_000L * 31, 100))
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
