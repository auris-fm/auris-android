package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `drainAndAwaitCompletion waits for frames queued behind a blocked write`() = kotlinx.coroutines.runBlocking {
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
        player.readPlaybackHeadFrames = { Long.MAX_VALUE }
        player.submitFrame(frame(0))
        player.play()
        (1..3).forEach { player.submitFrame(frame(it)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread { Thread.sleep(200); gate.countDown() }.start()

        val completion = player.drainAndAwaitCompletion(timeoutMs = 5_000)

        assertEquals(CloudAudioPlayer.Completion.Consumed, completion)
        assertEquals("every frame queued behind the blocked write was written before completion", (0..3).toList(), written.toList())
    }

    @Test
    fun `drainAndStop reports a stalled writer as not drained instead of dropping the tail silently`() {
        // The suspending path already treats a writer that never goes idle as an unconsumed answer.
        // The blocking path must say the same thing: its caller asked for everything queued to be
        // written, and a timeout means the queued tail was dropped -- logging and returning void
        // would let that read as a clean drain.
        val player = newPlayer()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        player.writeToSink = { _, _, _, count ->
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            count
        }
        player.writerStallTimeoutMs = 200
        player.submitFrame(frame(0))
        player.play()
        player.submitFrame(frame(1))
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        assertFalse("a stalled writer means the queued tail was not drained", player.drainAndStop())
        gate.countDown()
    }

    @Test
    fun `drainAndStop reports a completed drain`() {
        val player = newPlayer()
        player.writeToSink = { _, _, _, count -> count }
        player.submitFrame(frame(0))
        player.play()
        player.submitFrame(frame(1))

        assertTrue("every queued frame was written before drainAndStop returned", player.drainAndStop())
    }

    @Test
    fun `an answer already fully written when the turn ends is reported consumed at once`() = kotlinx.coroutines.runBlocking {
        // What a short answer hits first: by Done the queue is EMPTY, so the writer has nothing left
        // to write and the completion barrier runs with no progress behind it. A wait that only exits
        // on a new write sits out its whole stall window, reports the answer as cut short, and the
        // user hears the error tone over an answer that was delivered perfectly. Completion has to be
        // checked on every poll, not only between windows.
        val player = newPlayer()
        val written = CopyOnWriteArrayList<Int>()
        player.writeToSink = { _, bytes, _, count ->
            written += bytes[0].toInt()
            count
        }
        player.readPlaybackHeadFrames = { Long.MAX_VALUE }
        player.writerStallTimeoutMs = 2_000
        player.submitFrame(frame(0))
        player.play()
        awaitTrue { written.size == 1 }

        val startedAt = System.nanoTime()
        val completion = player.drainAndAwaitCompletion(timeoutMs = 5_000)
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(CloudAudioPlayer.Completion.Consumed, completion)
        assertTrue("the empty queue ended the wait instead of sitting out the window (took ${waitedMs}ms)", waitedMs < 1_000)
    }

    @Test
    fun `an answer already fully written when the turn ends drains at once`() {
        // Same case on the blocking path, which reports false -- and false is what makes the caller
        // treat a finished answer as cut short.
        val player = newPlayer()
        val written = CopyOnWriteArrayList<Int>()
        player.writeToSink = { _, bytes, _, count ->
            written += bytes[0].toInt()
            count
        }
        player.writerStallTimeoutMs = 2_000
        player.submitFrame(frame(0))
        player.play()
        awaitTrue { written.size == 1 }

        val startedAt = System.nanoTime()
        val drained = player.drainAndStop()
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("nothing was queued, so everything submitted was written", drained)
        assertTrue("the empty queue ended the wait instead of sitting out the window (took ${waitedMs}ms)", waitedMs < 1_000)
    }

    @Test
    fun `a long backlog written steadily is drained, not called a stalled writer`() {
        // The wait at the end of a turn exists to notice a writer that has STOPPED, not to cap how
        // long an answer takes to finish on the device. Playback paces the writer: a 30 s answer
        // received in 3 s still has more than the timeout's worth of audio queued at done, and every
        // frame of it is written steadily. Judging that backlog by total elapsed time timed out,
        // stop() discarded the tail, and the user heard an error tone instead of the end of the
        // answer. Progress is the only thing that distinguishes the two.
        val player = newPlayer()
        val written = CopyOnWriteArrayList<Int>()
        player.writeToSink = { _, bytes, _, count ->
            // Well under the stall window per frame, but the whole backlog takes longer than it.
            Thread.sleep(15)
            written += bytes[0].toInt()
            count
        }
        player.writerStallTimeoutMs = 250
        player.submitFrame(frame(0))
        player.play()
        (1..30).forEach { player.submitFrame(frame(it)) }

        assertTrue("a steadily written backlog is a drain, not a stall", player.drainAndStop())
        assertEquals("the whole answer was written, in order", (0..30).toList(), written.toList())
    }

    @Test
    fun `release does not release the track until the write in flight returns`() {
        val player = newPlayer()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        player.writeToSink = { _, _, _, count ->
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            events += "write_returned"
            count
        }
        player.onTrackReleasedForTest = { events += "track_released" }
        player.submitFrame(frame(0))
        player.play()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        player.release()
        Thread.sleep(100)
        assertTrue("the track was released while a write was still inside it: $events", "track_released" !in events)

        gate.countDown()
        awaitTrue { "track_released" in events }
        assertEquals(listOf("write_returned", "track_released"), events.toList())
    }

    @Test
    fun `drainAndAwaitCompletion reports the answer as cut short when the writer is stuck`() = kotlinx.coroutines.runBlocking {
        val player = newPlayer()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        player.writeToSink = { _, _, _, count ->
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            count
        }
        player.readPlaybackHeadFrames = { Long.MAX_VALUE }
        player.writerStallTimeoutMs = 200
        player.submitFrame(frame(0))
        player.play()
        player.submitFrame(frame(1))
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val completion = player.drainAndAwaitCompletion(timeoutMs = 5_000)

        assertEquals("a stalled write truncated the answer; that is not a normal completion", CloudAudioPlayer.Completion.Unconsumed, completion)
        gate.countDown()
    }
}
