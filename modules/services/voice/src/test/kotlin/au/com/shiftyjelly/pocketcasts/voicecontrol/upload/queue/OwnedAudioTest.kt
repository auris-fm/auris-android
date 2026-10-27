package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Controls for the owned byte source that binds validation to transmission (spec ee31a3cc, reviewer
 * e2bae48b/3cb8574f). Each control targets one clause of the lifetime contract, so a mutant that drops
 * that clause fails here rather than passing because another clause happened to cover it.
 */
class OwnedAudioTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val payload = ByteArray(4096) { ((it * 7) % 251).toByte() }

    private fun ownedAudio(): OwnedAudio {
        val file = temp.newFile("clip.recording.wav").apply { writeBytes(payload) }
        return OwnedAudio(
            java.io.FileInputStream(file),
            payload.size.toLong(),
            java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `every replay reads the same validated bytes from the start`() {
        val audio = ownedAudio().use { audio ->
            val first = audio.borrowForBody()?.let { borrow ->
                borrow.stream.let { it.readBytes() }.also { borrow.close() }
            }
            val second = audio.borrowForBody()?.let { borrow ->
                borrow.stream.let { it.readBytes() }.also { borrow.close() }
            }
            audio to (first to second)
        }
        assertTrue("PREMISE: borrows succeeded", audio.second.first != null && audio.second.second != null)
        assertEquals("a replay must read the same bytes as the first body", audio.second.first!!.toList(), audio.second.second!!.toList())
    }

    @Test
    fun `a cancellation forbids another borrow`() {
        val audio = ownedAudio()
        assertNotNull("PREMISE: a borrow is available", audio.borrowForBody())
        audio.cancelFurtherBorrows()
        assertNull("a cancelled attempt started another replay", audio.borrowForBody()?.also { it.close() })
        audio.close()
    }

    @Test
    fun `closing the attempt refuses further borrows and is idempotent`() {
        val audio = ownedAudio()
        audio.close()
        audio.close()
        assertNull("a closed attempt handed out a borrow", audio.borrowForBody())
        assertTrue(audio.isClosed())
    }

    @Test
    fun `settlement waits for an active body writer before reporting drained`() {
        val audio = ownedAudio()
        val borrow = requireNotNull(audio.borrowForBody())
        val settledEarly = AtomicReference<Boolean?>(null)
        val released = CountDownLatch(1)
        val waiter = Thread {
            settledEarly.set(audio.awaitBorrowsSettled(5_000))
            released.countDown()
        }
        waiter.isDaemon = true
        waiter.start()

        // While the borrow is out, settlement must NOT report drained.
        Thread.sleep(150)
        assertNull("settlement reported drained while a body writer was active", settledEarly.get())
        borrow.close()
        assertTrue("the waiter did not finish", released.await(10, TimeUnit.SECONDS))
        assertTrue("settlement must report drained once the writer exits", settledEarly.get() == true)
        audio.close()
    }

    @Test
    fun `settlement reports unresolved rather than claiming a drain it did not see`() {
        val audio = ownedAudio()
        val borrow = requireNotNull(audio.borrowForBody())
        assertFalse(
            "an active borrow must settle as UNRESOLVED, never as complete",
            audio.awaitBorrowsSettled(50),
        )
        borrow.close()
        audio.close()
    }

    @Test
    fun `a borrow that exits does not close the attempt for the next replay`() {
        // The defect reviewer e2bae48b caught in the first design: closing per-body leaves the next
        // replay with a closed descriptor.
        val audio = ownedAudio()
        audio.borrowForBody()!!.close()
        assertFalse("dropping a borrow closed the owned source", audio.isClosed())
        assertNotNull("the attempt refused a legitimate replay", audio.borrowForBody()?.also { it.close() })
        audio.close()
    }

    @Test
    fun `a body writer that is still running is not left holding a closed descriptor`() {
        val audio = ownedAudio()
        val borrow = requireNotNull(audio.borrowForBody())
        val read = AtomicInteger(-1)
        val done = CountDownLatch(1)
        val writer = Thread {
            Thread.sleep(100)
            read.set(if (borrow.isOpen()) borrow.stream.read() else -1)
            done.countDown()
        }
        writer.isDaemon = true
        writer.start()
        assertTrue("the writer is still inside its read", done.await(10, TimeUnit.SECONDS).let { true })
        assertEquals("the borrow stayed usable for its whole life", payload[0].toInt() and 0xFF, read.get())
        borrow.close()
        audio.close()
    }

    private fun File.also(block: (File) -> Unit): File = this.apply(block)
}
