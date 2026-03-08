package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningModeResolverTest {
    private val resolver = ListeningModeResolver()

    @Test
    fun `returns continuous mode for active playback`() {
        assertEquals(ListeningMode.CONTINUOUS, resolver.resolve(isPlaybackActive = true))
    }

    @Test
    fun `returns wake word mode for inactive playback`() {
        assertEquals(ListeningMode.WAKE_WORD, resolver.resolve(isPlaybackActive = false))
    }
}
