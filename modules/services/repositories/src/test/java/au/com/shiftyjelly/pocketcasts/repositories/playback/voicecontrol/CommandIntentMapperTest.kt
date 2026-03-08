package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandIntentMapperTest {
    private val mapper = CommandIntentMapper()

    @Test
    fun `maps skip forward commands`() {
        assertEquals(IntentType.SKIP_FORWARD, mapper.map("please fast forward"))
        assertEquals(IntentType.SKIP_FORWARD, mapper.map("skip forward 30 seconds"))
    }

    @Test
    fun `maps rewind and speed commands`() {
        assertEquals(IntentType.REWIND, mapper.map("rewind this"))
        assertEquals(IntentType.SPEED_UP, mapper.map("speed up"))
        assertEquals(IntentType.SPEED_DOWN, mapper.map("slow down"))
    }

    @Test
    fun `maps unsupported advanced commands`() {
        assertEquals(IntentType.UNSUPPORTED_ADVANCED, mapper.map("bookmark this spot"))
    }

    @Test
    fun `extracts wake word command when prefixed`() {
        assertEquals("skip forward", mapper.extractWakeWordCommand("Hey Pocket Casts, skip forward"))
    }

    @Test
    fun `does not extract wake word for non wake phrase`() {
        assertNull(mapper.extractWakeWordCommand("skip forward"))
    }
}
