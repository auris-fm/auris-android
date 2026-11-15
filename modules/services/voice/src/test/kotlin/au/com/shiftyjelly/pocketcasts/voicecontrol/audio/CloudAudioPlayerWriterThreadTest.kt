package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Audio frames are written to the track on a dedicated thread, so reading the connection never waits
 * on a device write. A blocking AudioTrack.write on a track the platform has stopped mixing can sit
 * for seconds, and when it ran on the receive path nothing else on the turn was read meanwhile.
 *
 * These use the default writer (a real thread), so each one blocks the sink on a latch and checks the
 * caller is NOT held. Every other player test passes [CloudAudioPlayer.CALLER_THREAD] for synchronous
 * writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudAudioPlayerWriterThreadTest {

    private val players = mutableListOf<CloudAudioPlayer>()

    private fun newPlayer(maxQueuedBytes: Int = CloudAudioPlayer.MAX_QUEUED_BYTES): CloudAudioPlayer = CloudAudioPlayer(
        context = RuntimeEnvironment.getApplication(),
        sampleRateHz = 16000,
        maxQueuedBytes = maxQueuedBytes,
    ).also { players += it }

    @After
    fun tearDown() {
        players.forEach { it.release() }
    }

    private fun frame(index: Int) = ByteArray(480) { index.toByte() }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition not reached within ${timeoutMs}ms", condition())
    }

    @Test
    fun `submitting and starting do not wait for a blocked device write`() {
        val player = newPlayer()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val written = CopyOnWriteArrayList<Int>()
        player.writeToSink = { _, bytes, _, count ->
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            written += bytes[0].toInt()
            count
        }

        val startedAt = System.nanoTime()
        player.submitFrame(frame(0))
        player.play()
        (1..5).forEach { player.submitFrame(frame(it)) }
        val callerMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("the first write is blocked on the writer thread", entered.await(2, TimeUnit.SECONDS))
        assertTrue("the caller returned while the write was still blocked (took ${callerMs}ms)", callerMs < 1_000)
        assertTrue("nothing was written yet", written.isEmpty())

        gate.countDown()
        awaitTrue { written.size == 6 }
        assertEquals("frames are written in the order they were submitted", (0..5).toList(), written.toList())
    }

    @Test
    fun `stop does not wait for a blocked write and discards what is queued`() {
        val player = newPlayer()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val written = CopyOnWriteArrayList<Int>()
        player.writeToSink = { _, bytes, _, count ->
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            written += bytes[0].toInt()
            count
        }
        player.submitFrame(frame(0))
        player.play()
        (1..3).forEach { player.submitFrame(frame(it)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        player.stop()
        val stopMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("stop returned while the write was still blocked (took ${stopMs}ms)", stopMs < 1_000)

        gate.countDown()
        awaitTrue { written.isNotEmpty() }
        Thread.sleep(100)
        assertEquals("only the write already in flight completed; the queued frames were discarded", listOf(0), written.toList())
    }

    @Test
    fun `drain and stop writes everything queued before it returns`() {
        val player = newPlayer()
        val written = CopyOnWriteArrayList<Int>()
        player.writeToSink = { _, bytes, _, count ->
            Thread.sleep(20)
            written += bytes[0].toInt()
            count
        }
        player.submitFrame(frame(0))
        player.play()
        (1..4).forEach { player.submitFrame(frame(it)) }

        player.drainAndStop()

        assertEquals("every queued frame was written, in order, before drainAndStop returned", (0..4).toList(), written.toList())
    }

    @Test
    fun `a full queue fails the answer explicitly instead of blocking the caller or dropping silently`() {
        val player = newPlayer(maxQueuedBytes = 1_000)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Long>()
        player.onWriteFailure = { failures += it }
        player.writeToSink = { _, _, _, count ->
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            count
        }
        player.submitFrame(frame(0))
        player.play()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        // The writer holds frame 0; these queue up behind it: 480 + 480 fit under 1000, the third does not.
        (1..4).forEach { player.submitFrame(frame(it)) }
        val callerMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("the caller was not held by the full queue (took ${callerMs}ms)", callerMs < 1_000)
        assertEquals("the overflow is reported exactly once, as a write failure", 1, failures.size)
        gate.countDown()
    }
}
