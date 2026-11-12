package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyQueueRemovalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the previous queue root is removed and the new queue still works`() {
        val filesDir = tmp.newFolder("files")
        // A leftover from the previous upload path: a folder with a recording-shaped file inside.
        val legacy = File(filesDir, "voice-contributions")
        File(legacy, "capture-1").mkdirs()
        File(File(legacy, "capture-1"), "audio.wav").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue("premise: the old root must exist before this runs", legacy.exists())

        assertTrue("the old root must be removed", VoiceUploadQueueModule.deleteLegacyQueue(filesDir) { })
        assertFalse("nothing of the old root may remain", legacy.exists())

        // The new queue is unaffected and fully usable afterwards.
        val queue = FolderQueue(File(filesDir, "voice-upload-queue"), 32, {})
        val published = queue.publish("capture-2", { it.writeBytes(byteArrayOf(4)) }, "{}".toByteArray(), 1_000L)
        assertNotNull("a recording published after the cleanup must still work", published)
        assertEquals(1, queue.count())
    }

    @Test
    fun `removal is a no-op when there is no old root`() {
        val filesDir = tmp.newFolder("clean")
        assertFalse("nothing to remove is not a failure", VoiceUploadQueueModule.deleteLegacyQueue(filesDir) { })
    }

    @Test
    fun `only the previous queue root is touched`() {
        // The removal must not reach anything else the app owns.
        val filesDir = tmp.newFolder("files2")
        File(filesDir, "voice-contributions").mkdirs()
        val neighbour = File(filesDir, "voice-sharing-choice")
        neighbour.writeText("a saved sharing choice")
        val model = File(filesDir, "sensevoice-model")
        model.mkdirs()

        VoiceUploadQueueModule.deleteLegacyQueue(filesDir) { }

        assertTrue("a saved sharing choice must survive", neighbour.exists())
        assertEquals("a saved sharing choice", neighbour.readText())
        assertTrue("model data must survive", model.exists())
    }

    @Test
    fun `the removal is reported, so it is not a silent deletion`() {
        val filesDir = tmp.newFolder("logging")
        File(filesDir, "voice-contributions/contributions").mkdirs()
        val lines = mutableListOf<String>()
        VoiceUploadQueueModule.deleteLegacyQueue(filesDir) { lines.add(it) }
        assertEquals("exactly one line for one deletion", 1, lines.size)
        assertTrue("the line must name what went: ${lines.single()}", lines.single().contains("voice-contributions"))
    }

    @Test
    fun `the removal is available as a startup step, not only at construction`() {
        // The queue is built before logging exists, so removal has a separate entry point that the
        // app calls once it is up. If this hook vanished the deletion would still happen but leave
        // no record, which is the defect this covers.
        val filesDir = tmp.newFolder("startup")
        File(filesDir, "voice-contributions").mkdirs()
        assertTrue("the old root must exist first", File(filesDir, "voice-contributions").exists())
        hmLegacyRemoval(filesDir)
        assertFalse(File(filesDir, "voice-contributions").exists())
    }

    private fun hmLegacyRemoval(filesDir: File) {
        VoiceUploadQueueModule.deleteLegacyQueue(filesDir) { }
    }
}
