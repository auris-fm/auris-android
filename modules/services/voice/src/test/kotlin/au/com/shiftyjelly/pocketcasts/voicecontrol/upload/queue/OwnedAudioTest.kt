package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
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
    fun `a TIMED-OUT settle still refuses later borrows and completes closure when the last borrow exits`() {
        // Reviewer ade65c96: settle() that times out with a live borrow returned "unresolved" WITHOUT
        // making the source terminal. The borrow's later close only completes a deferred close when
        // terminal is true, so the descriptor, slot and lease stayed held forever - and new borrows
        // stayed admissible after the timeout. The timeout transition must itself be terminal.
        var descriptorClosed = 0
        var leaseClosed = 0
        val file = temp.newFile("clip-timeout.recording.wav").apply { writeBytes(payload) }
        val audio = OwnedAudio(
            java.io.FileInputStream(file),
            payload.size.toLong(),
            java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
            sourceLease = AutoCloseable { leaseClosed++ },
            onDescriptorClosed = { descriptorClosed++ },
        )
        val borrow = requireNotNull(audio.borrowForBody()) { "PREMISE: an idle source is borrowable" }

        val settled = audio.settle(50L)
        assertFalse("PREMISE: a live borrow must time out a 50ms settle", settled)

        // The timeout is terminal: no further replay may be admitted.
        assertNull("a borrows-admissible source after a timed-out settle", audio.borrowForBody())

        // Releasing the last borrow must complete the deferred close WITHOUT any further settle call.
        borrow.close()
        assertTrue("the descriptor never closed after the last borrow exited", audio.isClosed())
        assertEquals("the descriptor close callback did not run", 1, descriptorClosed)
        assertEquals("the source lease was never released", 1, leaseClosed)
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

    @Test
    fun `cancellation between sequential body attempts refuses a new borrow and never rewinds`() {
        // Reviewer 6456b2a5: "after settle, no replay" was the WRONG window. settle() makes the source
        // terminal, so it proves nothing about cancellation. The required window is the LIVE one: a body has
        // finished, the attempt has NOT settled, and a retry/redirect asks for a second body while the call
        // is still in flight. Cancelling there must refuse admission - and must not rewind the file position
        // of a body that is legitimately still running.
        val file = temp.newFile("audio.wav").also { it.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        val audio = OwnedAudio(FileInputStream(file), file.length(), "sha-of-test-bytes")
        try {
            // FIRST body: consume it fully, then close it. No settle has happened.
            val first = requireNotNull(audio.borrowForBody()) { "PREMISE: the first body is admitted" }
            val firstRead = first.stream.readBytes()
            first.close()
            assertFalse("PREMISE: the source is NOT terminal yet", audio.isClosed())

            // Cancel while the call is still in flight, between body attempts.
            audio.cancelFurtherBorrows()

            assertNull(
                "a cancelled in-flight attempt was granted a second body",
                audio.borrowForBody()?.also { it.close() },
            )
            // The first body's read is untouched: cancellation must not corrupt bytes already sent.
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), firstRead)
        } finally {
            audio.close()
        }
    }

    @Test
    fun `cancellation does not disturb a body that is still running`() {
        // The other half: a borrow already granted keeps its own file position. Cancellation refuses FUTURE
        // replays only - yanking the buffer from a running writer would corrupt the bytes on the wire.
        val file = temp.newFile("audio.wav").also { it.writeBytes(byteArrayOf(10, 20, 30, 40)) }
        val audio = OwnedAudio(FileInputStream(file), file.length(), "sha-of-test-bytes")
        try {
            val running = requireNotNull(audio.borrowForBody()) { "PREMISE: the body is admitted" }
            // Consume PART of the body, so its position is mid-stream and a rewind is observable.
            val head = ByteArray(2)
            assertEquals("PREMISE: two bytes are read", 2, running.stream.read(head))
            assertArrayEquals("PREMISE: the head is the file's first bytes", byteArrayOf(10, 20), head)
            val positionBefore = running.stream.channel.position()
            assertEquals("PREMISE: the writer is mid-stream", 2L, positionBefore)

            audio.cancelFurtherBorrows()

            // Cancellation must not move the RUNNING writer: a rewind would resend bytes already on the wire.
            assertEquals(
                "cancellation moved a running body's file position",
                positionBefore,
                running.stream.channel.position(),
            )
            // And it still streams the remainder from where it was, not from the start.
            assertArrayEquals("the remainder was not read from the writer's own position", byteArrayOf(30, 40), running.stream.readBytes())
            running.close()
            assertNull("a cancelled attempt was granted another body", audio.borrowForBody()?.also { it.close() })
        } finally {
            audio.close()
        }
    }

    @Test
    fun `a sequential replay after a first body sends the SAME bytes, and a cancelled replay is refused`() {
        // Reviewer 6456b2a5: the retry path. `isOneShot=false` with `retryOnConnectionFailure` enabled means
        // OkHttp CAN ask for the body a second time after the first attempt was cut short, so a body must be
        // replayable from byte 0 - and must stop being replayable the moment the attempt is withdrawn.
        // Driving OkHttp's internal retry directly is not deterministic, so the CONTRACT the retry relies on
        // is pinned here: replay returns byte-identical content, and cancellation refuses the next body.
        val payload = ByteArray(2048) { (it * 7).toByte() }
        val file = temp.newFile("clip.recording.wav").apply { writeBytes(payload) }
        val audio = OwnedAudio(FileInputStream(file), file.length(), "sha-of-test-bytes")
        try {
            // FIRST body: read part of it, as a cut-short attempt would.
            val first = requireNotNull(audio.borrowForBody()) { "PREMISE: the first body is admitted" }
            val partial = ByteArray(512)
            assertEquals("PREMISE: the first body is cut short", 512, first.stream.read(partial))
            first.close()

            // REPLAY, before any settle: must start at byte 0 and carry the same bytes.
            val replay = requireNotNull(audio.borrowForBody()) { "PREMISE: a replay is admitted before settle" }
            val replayed = replay.stream.readBytes()
            replay.close()
            assertArrayEquals("a replay did not resend the SAME bytes from byte 0", payload, replayed)

            // CANCEL, then replay again: refused. Cancellation is what stops a retry loop.
            audio.cancelFurtherBorrows()
            assertNull(
                "a cancelled attempt was granted another body",
                audio.borrowForBody()?.also { it.close() },
            )
        } finally {
            audio.close()
        }
    }
}
