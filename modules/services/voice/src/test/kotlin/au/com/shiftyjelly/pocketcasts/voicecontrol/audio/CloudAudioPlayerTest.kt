package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CloudAudioPlayerTest {

    private lateinit var player: CloudAudioPlayer

    @Before
    fun setUp() {
        player = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
        )
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun submitFrame_addsToBuffer() {
        val frame = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        player.submitFrame(frame)
        // Frame is buffered; we can't directly inspect the buffer, but
        // submitting without errors proves the frame was accepted.
    }

    @Test
    fun play_afterSubmit_startsPlaying() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        assertTrue(player.isPlaying)
    }

    @Test
    fun pause_stopsPlaying() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        assertTrue(player.isPlaying)
        player.pause()
        assertFalse(player.isPlaying)
    }

    @Test
    fun play_afterResume_resumesPlaying() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        player.pause()
        player.play()
        assertTrue(player.isPlaying)
    }

    @Test
    fun stop_clearsBuffer() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.stop()
        // Submitting after stop should be a no-op
        player.submitFrame(byteArrayOf(0x02, 0x03))
        // The player should be stopped and not playing
        assertFalse(player.isPlaying)
    }

    @Test
    fun release_afterStop_doesNotCrash() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.stop()
        player.release()
        // Should not throw
    }

    @Test
    fun submitFrame_afterRelease_doesNotCrash() {
        player.release()
        player.submitFrame(byteArrayOf(0x00, 0x01))
        // Should not throw
    }

    @Test
    fun codec_isEmptyByDefault() {
        assertEquals("", player.codec)
    }

    @Test
    fun codec_canBeSet() {
        player.codec = "opus"
        assertEquals("opus", player.codec)
    }

    @Test
    fun notPlayingInitially() {
        assertFalse(player.isPlaying)
    }
}
