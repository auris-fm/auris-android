package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins which stream earcons ride, because the failure it prevents was silent and looked like a
 * missing feature: with sonification usage, Do Not Disturb muted every earcon on a device whose
 * ordinary playback was fine, and the app's own logs showed each play attempt reaching the audio
 * system and being refused (`usage:13 muted`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EarconPlayerAttributesTest {

    @Test
    fun `earcons ride the media stream, not a stream Do Not Disturb silences`() {
        val attrs = EarconPlayer.earconAudioAttributes()

        assertEquals(
            "sonification maps to the system stream, which this device family aliases to the ring " +
                "stream, so DND silenced earcons while playback stayed audible",
            AudioAttributes.USAGE_MEDIA,
            attrs.usage,
        )
        assertEquals(
            "the content is a UI sonification; only the stream it rides changed",
            AudioAttributes.CONTENT_TYPE_SONIFICATION,
            attrs.contentType,
        )
    }
}
