package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pending acquisition is only cancelled on actual device loss. The route stopping to be the
 * candidate is not loss when selection succeeded: the selection callbacks publish
 * [AudioRoute.Headset] while acquisition is still confirming, and cancelling then would abort the
 * attempt that just succeeded and release the routing it acquired.
 */
class AudioRouteTest {
    @Test
    fun `the candidate keeps a pending acquisition alive`() {
        assertTrue(AudioRoute.BluetoothA2dpOnly.keepsPendingAcquisition())
    }

    @Test
    fun `the selected external microphone keeps a pending acquisition alive`() {
        assertTrue(AudioRoute.Headset(hasMicrophone = true).keepsPendingAcquisition())
    }

    @Test
    fun `a headset without a microphone ends the attempt`() {
        assertFalse(AudioRoute.Headset(hasMicrophone = false).keepsPendingAcquisition())
    }

    @Test
    fun `speaker and unknown end the attempt`() {
        assertFalse(AudioRoute.Speaker.keepsPendingAcquisition())
        assertFalse(AudioRoute.Unknown.keepsPendingAcquisition())
    }
}
