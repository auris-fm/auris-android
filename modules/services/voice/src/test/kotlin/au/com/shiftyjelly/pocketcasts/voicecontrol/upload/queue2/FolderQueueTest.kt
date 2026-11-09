package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun queue(max: Int = 32, logs: MutableList<String> = mutableListOf()) = FolderQueue(tmp.newFolder("q"), max, { logs.add(it) })

    private fun publish(q: FolderQueue, id: String, now: Long = 1_000L) = q.publish(id, { file -> file.writeBytes(byteArrayOf(1, 2, 3)) }, """{"capture_id":"$id"}""".toByteArray(), now)

    @Test
    fun `publishing makes both files visible and nothing else`() {
        val q = queue()
        val r = publish(q, "a")
        assertNotNull(r)
        assertEquals(1, q.count())
        assertTrue(File(r!!.folder, FolderQueue.AUDIO).isFile)
        assertTrue(File(r.folder, FolderQueue.METADATA).isFile)
        // The temporary area is empty: the rename published it and left no shadow copy.
        assertEquals(0, File(tmp.root, "q/${FolderQueue.TEMP}").listFiles()?.size ?: 0)
    }

    @Test
    fun `an interrupted write is discarded at startup and never served`() {
        val q = queue()
        publish(q, "good")
        // Simulate a crash mid-write: a folder under the temporary name with one file in it.
        val stale = File(tmp.root, "q/${FolderQueue.TEMP}/tmp-interrupted")
        stale.mkdirs()
        File(stale, FolderQueue.AUDIO).writeBytes(byteArrayOf(9))

        assertEquals(1, q.count())
        q.discardIncompleteWrites()
        assertFalse("the interrupted write must be gone", stale.exists())
        assertEquals("only the published recording remains", 1, q.recordings().size)
    }

    @Test
    fun `an incomplete published folder is deleted rather than served`() {
        // A folder that exists but is missing its audio cannot be uploaded. Serving it would fail
        // forever; the spec deletes it.
        val q = queue()
        val dir = File(tmp.root, "q/${FolderQueue.RECORDINGS}/broken")
        dir.mkdirs()
        File(dir, FolderQueue.METADATA).writeText("{}")
        assertEquals(0, q.recordings().size)
        assertFalse(dir.exists())
    }

    @Test
    fun `a full queue drops the new recording and says so`() {
        val logs = mutableListOf<String>()
        val q = queue(max = 2, logs = logs)
        assertNotNull(publish(q, "a"))
        assertNotNull(publish(q, "b"))
        assertNull("the third must be dropped, not admitted", publish(q, "c"))
        assertEquals(2, q.count())
        assertEquals(1, q.droppedCount)
        assertTrue(
            "a drop must be logged: a silent drop is indistinguishable from a working queue",
            logs.any { it.contains("dropped") },
        )
    }

    @Test
    fun `a recording is invisible until both its files are written`() {
        // The publish is one rename, so the uploader can never observe a half-written recording and
        // send truncated audio. Observed from inside the write: at that moment the queue must not
        // list it.
        val q = queue()
        var visibleDuringWrite = -1
        val published = q.publish(
            "a",
            { file ->
                visibleDuringWrite = q.recordings().size
                file.writeBytes(byteArrayOf(1, 2, 3))
            },
            "{}".toByteArray(),
            1_000L,
        )
        assertNotNull(published)
        assertEquals("nothing may be served before the write completes", 0, visibleDuringWrite)
        assertEquals("and it is served once published", 1, q.recordings().size)
    }

    @Test
    fun `a write that fails part way leaves nothing servable and does not throw`() {
        // A failed write drops the one recording. It must not propagate: throwing here would take
        // down collection.
        val q = queue()
        val published = q.publish(
            "a",
            { file ->
                file.writeBytes(byteArrayOf(1))
                throw java.io.IOException("disk full")
            },
            "{}".toByteArray(),
            1_000L,
        )
        assertNull("a failed write must drop the recording", published)
        assertEquals("nothing is served", 0, q.recordings().size)
        assertEquals(1, q.droppedCount)
        assertEquals("no partial folder is left behind", 0, File(tmp.root, "q/${FolderQueue.TEMP}").listFiles()?.size ?: 0)
    }

    @Test
    fun `recordings come back oldest first`() {
        val q = queue()
        publish(q, "later", now = 5_000)
        publish(q, "earlier", now = 1_000)
        assertEquals(listOf("earlier", "later"), q.recordings().map { it.captureId })
    }

    @Test
    fun `deleting every recording clears the queue`() {
        val q = queue()
        publish(q, "a")
        publish(q, "b")
        q.deleteAll("sharing was turned off")
        assertEquals(0, q.count())
        assertEquals(0, q.recordings().size)
    }

    @Test
    fun `recordings past the retention bound are dropped without sending`() {
        val q = queue()
        // Real epoch times, because the age is measured against a real clock.
        val now = System.currentTimeMillis()
        val eightDaysAgo = now - 8L * 24 * 60 * 60 * 1000
        val oneDayAgo = now - 1L * 24 * 60 * 60 * 1000
        publish(q, "old", now = eightDaysAgo)
        publish(q, "recent", now = oneDayAgo)

        val removed = q.deleteExpired(nowMs = now)
        assertEquals(1, removed)
        assertEquals(listOf("recent"), q.recordings().map { it.captureId })
    }

    @Test
    fun `a recording inside the retention bound is kept`() {
        val q = queue()
        val now = System.currentTimeMillis()
        publish(q, "justInside", now = now - FolderQueue.MAX_AGE_MS + 60_000)
        assertEquals(0, q.deleteExpired(nowMs = now))
        assertEquals(1, q.count())
    }
}
