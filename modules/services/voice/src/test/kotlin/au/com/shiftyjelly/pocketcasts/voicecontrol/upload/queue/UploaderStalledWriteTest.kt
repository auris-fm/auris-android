package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A write that never completes must not hold the uploader.
 *
 * The server here accepts the connection and then reads nothing, so the client's write blocks in
 * the socket indefinitely. A coroutine timeout cannot release that: the blocking write never
 * returns to be checked. Only cancelling the call itself closes the connection underneath it, which
 * is what this pins.
 */
class UploaderStalledWriteTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun recording(): FolderQueue.Recording {
        val dir = folder.newFolder("capture")
        val metadata = File(dir, "metadata.json").apply { writeText("""{"a":1}""") }
        // Just under the size cap the sender enforces, so the request is actually attempted: an
        // oversized payload returns null before any byte is written, and the test would pass
        // without testing the write at all.
        val audio = File(dir, "audio.wav").apply {
            writeBytes(ByteArray(PlainRecordLimits.MAX_AUDIO_BYTES - 1))
        }
        return FolderQueue.Recording(
            captureId = "stalled",
            folder = dir,
            metadata = metadata,
            audio = audio,
            publishedAtMs = 0L,
        )
    }

    @Test
    fun `a stalled write is released by cancelling the call`() {
        val server = ServerSocket(0)
        val readNothing = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val reader = thread(isDaemon = true) {
            runCatching {
                // Accept the connection and then make no progress at all.
                server.accept().use {
                    connected.countDown()
                    readNothing.await(30, TimeUnit.SECONDS)
                }
            }
        }

        // Deliberately NO call timeout: if the write is released here, cancel is the only thing
        // that can have released it. A timeout would mask a missing cancel.
        val client = OkHttpClient.Builder().build()
        val uploader = RecordingUploader(
            endpoint = "http://127.0.0.1:${server.localPort}/voice-recordings",
            client = client,
            authorization = { "token" },
        )

        val released = CountDownLatch(1)
        val returnedNull = AtomicBoolean(false)
        thread(isDaemon = true) {
            val answer = runBlocking { uploader.send(recording()) }
            returnedNull.set(answer == null)
            released.countDown()
        }

        // The request must have reached the socket before cancelling can mean anything. Without
        // this, an early return upstream would make the test pass on a call that never started.
        assertTrue(
            "the request must actually be in flight, or this test proves nothing",
            connected.await(10, TimeUnit.SECONDS),
        )
        Thread.sleep(300)
        uploader.cancelInFlight()

        assertTrue(
            "cancelling must release a write that is blocked in the socket",
            released.await(10, TimeUnit.SECONDS),
        )
        assertTrue("a cancelled call reports no answer, never a status", returnedNull.get())

        readNothing.countDown()
        server.close()
        reader.join(2_000)
    }

    @Test
    fun `cancelling with nothing in flight does not fail`() {
        val uploader = RecordingUploader(
            endpoint = "http://127.0.0.1:1/voice-recordings",
            client = OkHttpClient(),
            authorization = { "token" },
        )
        uploader.cancelInFlight()
        assertNull(runBlocking { uploader.send(recording()) })
    }
}
