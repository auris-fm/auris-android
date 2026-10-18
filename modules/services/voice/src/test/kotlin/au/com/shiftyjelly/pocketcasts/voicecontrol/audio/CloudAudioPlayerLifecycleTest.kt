package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudAudioPlayerLifecycleTest {
    @Test
    fun `a same codec second turn records new answer audio and starts a fresh playback clock`() = runTest {
        var nowMs = 0L
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap()).apply { monotonicNowMs = { nowMs } }
        val player = player(recorder)
        try {
            player.setCodec("pcm@16k")
            player.submitFrame(pcm(8_000))
            player.play()
            assertTrue("the instrument must observe the first turn", recorder.snapshot().any { it > 0f })
            player.readPlaybackHeadFrames = { player.trackFramesForTest() }
            assertEquals(CloudAudioPlayer.Completion.Consumed, player.drainAndAwaitCompletion())

            nowMs = 5_000L // The first turn's legitimate acoustic tail has expired.
            player.setCodec("pcm@16k")
            player.submitFrame(pcm(-8_000))
            player.play()

            assertTrue("a reused player must record the second answer", recorder.snapshot().any { it < 0f })
            assertEquals("a stopped AudioTrack's playback head restarts at zero", 1_600L, player.trackFramesForTest())
        } finally {
            player.stop()
            player.release()
        }
    }

    @Test
    fun `a continuing answer renews reference admission after route retirement`() {
        var nowMs = 0L
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap()).apply { monotonicNowMs = { nowMs } }
        val player = player(recorder)
        try {
            player.setCodec("pcm@16k")
            player.submitFrame(pcm(8_000))
            player.play()
            assertTrue("the first write must reach the recorder", recorder.snapshot().any { it > 0f })

            recorder.retire()
            nowMs = 5_000L
            player.submitFrame(pcm(-8_000))

            assertTrue("future live answer writes need a new admission", recorder.snapshot().any { it < 0f })
        } finally {
            player.stop()
            player.release()
        }
    }

    @Test
    fun `pause and resume preserve the current answer clock and reference`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val player = player(recorder)
        try {
            player.setCodec("pcm@16k")
            player.submitFrame(pcm(8_000))
            player.play()
            player.pause()
            player.submitFrame(pcm(-8_000))
            player.play()

            assertEquals(3_200L, player.trackFramesForTest())
            assertTrue(recorder.snapshot().any { it > 0f })
            assertTrue(recorder.snapshot().any { it < 0f })
        } finally {
            player.stop()
            player.release()
        }
    }

    @Test
    fun `permanent release stops the live playback and withdraws its route`() {
        val observer = RoutedOutputObserver()
        val player = CloudAudioPlayer(RuntimeEnvironment.getApplication(), routedOutputObserver = observer)
        player.writeToSink = { _, _, _, count -> count }
        player.submitFrame(pcm(8_000))
        player.play()
        assertTrue(observer.holdsAClaim())

        player.release()

        assertFalse("release must execute stop before making the player terminal", player.isPlaying)
        assertFalse("released output cannot keep a routed observation", observer.holdsAClaim())
    }

    @Test
    fun `a write held across a route reset cannot use the continuing owner's renewed admission`() {
        heldWriteCannotBecomeSuccessor(startNewTurn = false)
    }

    @Test
    fun `a write held across stop cannot affect a new turn on the same player`() {
        heldWriteCannotBecomeSuccessor(startNewTurn = true)
    }

    private fun heldWriteCannotBecomeSuccessor(startNewTurn: Boolean) {
        var nowMs = 0L
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap()).apply { monotonicNowMs = { nowMs } }
        val player = player(recorder)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val calls = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        var held: Thread? = null
        try {
            player.setCodec("pcm@16k")
            player.submitFrame(pcm(8_000))
            player.play()
            assertTrue(recorder.snapshot().any { it > 0f })
            player.writeToSink = { _, _, _, count ->
                if (calls.getAndIncrement() == 0) {
                    entered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS)) { "held write was not released" }
                }
                count
            }
            held = thread(name = "held-cloud-audio-write") {
                try {
                    player.submitFrame(pcm(-8_000))
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            assertTrue("the production write must reach the sink", entered.await(5, TimeUnit.SECONDS))

            if (startNewTurn) player.stop() else recorder.retire()
            nowMs = 5_000L
            player.submitFrame(pcm(16_000))
            if (startNewTurn) player.play()
            assertTrue("the new owner must really record", recorder.snapshot().any { it > 0.4f })

            unblock.countDown()
            held.join(5_000)
            assertFalse("the held writer must finish", held.isAlive)
            failure.get()?.let { throw it }
            assertFalse("old accepted bytes cannot take the new owner's ticket", recorder.snapshot().any { it < 0f })
            if (startNewTurn) assertEquals(1_600L, player.trackFramesForTest())
        } finally {
            unblock.countDown()
            held?.join(5_000)
            player.stop()
            player.release()
        }
    }

    private fun player(recorder: PlaybackBufferRecorder) = CloudAudioPlayer(
        RuntimeEnvironment.getApplication(),
        playbackBufferRecorder = recorder,
    ).apply {
        writeToSink = { _, _, _, count -> count }
    }

    private fun pcm(sample: Int) = ByteArray(3_200).apply {
        for (index in indices step 2) {
            this[index] = sample.toByte()
            this[index + 1] = (sample shr 8).toByte()
        }
    }
}
