package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandExecutorTest {

    @Test
    fun `executes core playback actions`() {
        val controller = FakeVoicePlaybackController()
        val executor = VoiceCommandExecutor(controller)

        assertTrue(executor.execute(IntentType.SKIP_FORWARD))
        assertTrue(executor.execute(IntentType.REWIND))
        assertTrue(executor.execute(IntentType.NEXT_EPISODE))

        assertEquals(1, controller.skipForwardCount)
        assertEquals(1, controller.rewindCount)
        assertEquals(1, controller.nextEpisodeCount)
    }

    @Test
    fun `executes speed actions`() {
        val controller = FakeVoicePlaybackController()
        val executor = VoiceCommandExecutor(controller)

        assertTrue(executor.execute(IntentType.SPEED_UP))
        assertTrue(executor.execute(IntentType.SPEED_DOWN))

        assertEquals(1, controller.speedUpCount)
        assertEquals(1, controller.speedDownCount)
    }

    @Test
    fun `returns false for unsupported and unknown intents`() {
        val executor = VoiceCommandExecutor(FakeVoicePlaybackController())

        assertFalse(executor.execute(IntentType.UNSUPPORTED_ADVANCED))
        assertFalse(executor.execute(IntentType.UNKNOWN))
    }

    private class FakeVoicePlaybackController : VoicePlaybackController {
        var skipForwardCount = 0
        var rewindCount = 0
        var speedUpCount = 0
        var speedDownCount = 0
        var nextEpisodeCount = 0

        override fun skipForward() {
            skipForwardCount++
        }

        override fun rewind() {
            rewindCount++
        }

        override fun speedUp() {
            speedUpCount++
        }

        override fun speedDown() {
            speedDownCount++
        }

        override fun nextEpisode() {
            nextEpisodeCount++
        }
    }
}
