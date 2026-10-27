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
    fun `a second borrow is refused so it cannot rewind the running reader`() {
        // Reviewer 9436742d finding 1: all borrows share ONE file position, so admitting a second reader
        // would seek the descriptor away from the first and corrupt both bodies.
        val audio = ownedAudio()
        val first = requireNotNull(audio.borrowForBody())
        val readSoFar = first.stream.read(ByteArray(100))
        assertEquals("PREMISE: the first reader advanced", 100, readSoFar)

        assertNull("a second borrow was admitted while a body writer was active", audio.borrowForBody())

        // And the first reader's position is untouched: it continues from where it was.
        assertEquals("the active reader's position was moved", payload[100].toInt() and 0xFF, first.stream.read())
        first.close()
        audio.close()
    }

    @Test
    fun `close does not yank the descriptor from a parked body writer`() {
        // Reviewer 9436742d finding 2: close() closed immediately even with an active borrow.
        val audio = ownedAudio()
        val borrow = requireNotNull(audio.borrowForBody())
        val read = java.util.concurrent.atomic.AtomicInteger(-1)
        val parked = CountDownLatch(1)
        val reader = Thread {
            parked.await(10, TimeUnit.SECONDS)
            read.set(if (borrow.isOpen()) borrow.stream.read() else -1)
        }
        reader.isDaemon = true
        reader.start()

        audio.close()
        assertFalse("close() closed the descriptor under a live reader", audio.isClosed())
        parked.countDown()
        reader.join(10_000)
        assertEquals("the parked reader lost its stream", payload[0].toInt() and 0xFF, read.get())

        borrow.close()
        // The terminal transition observed the drain; the descriptor is closed exactly once.
        assertTrue("the descriptor was never closed after the writer exited", audio.isClosed())
    }

    @Test
    fun `settle reports unresolved ownership on timeout and resolution once the writer exits`() {
        val audio = ownedAudio()
        val borrow = requireNotNull(audio.borrowForBody())
        assertFalse(
            "settle claimed completion while a body writer was still running",
            audio.settle(50),
        )
        assertFalse("the descriptor closed while the writer was live", audio.isClosed())

        borrow.close()
        assertTrue("settlement was never observed", audio.awaitSettlement(5_000))
        assertTrue(audio.isClosed())
    }

    @Test
    fun `admission races settlement without admitting a borrow after the terminal transition`() {
        val audio = ownedAudio()
        val admittedAfterTerminal = java.util.concurrent.atomic.AtomicInteger(0)
        val start = CountDownLatch(1)
        val racers = (1..8).map {
            Thread {
                start.await(10, TimeUnit.SECONDS)
                audio.borrowForBody()?.let { borrow ->
                    admittedAfterTerminal.incrementAndGet()
                    borrow.close()
                }
            }.also { t ->
                t.isDaemon = true
                t.start()
            }
        }
        start.countDown()
        audio.close()
        racers.forEach { it.join(10_000) }
        // Whatever the interleaving, once close() has taken the terminal transition no NEW borrow is granted
        // afterwards; a borrow already admitted before it must have been drained by close()'s own path.
        assertTrue("a borrow survived the terminal transition", audio.awaitSettlement(5_000))
        assertTrue("the descriptor was never closed", audio.isClosed())
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
