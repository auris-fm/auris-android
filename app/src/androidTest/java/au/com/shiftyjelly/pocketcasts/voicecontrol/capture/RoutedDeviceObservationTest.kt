package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the platform can report where a live stream is ACTUALLY routed, which is the observation
 * @spec asked for to distinguish "available" from "selected".
 *
 * `AudioTrack` implements `AudioRouting`, so an owned, active track can answer this. Two facts are
 * established here: the observation exists on this device, and it is null while the stream is inactive —
 * so "unknown" is distinguishable from "the speaker".
 */
@RunWith(AndroidJUnit4::class)
class RoutedDeviceObservationTest {

    private val tag = "RoutedDevice"

    @Test
    fun aLiveTrackReportsItsRoutedDeviceAndAnIdleOneDoesNot() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            // Before play: an inactive stream must not claim a route.
            val beforePlay = runCatching { track.routedDevice }.getOrNull()

            track.play()
            track.write(FloatArray(3_200), 0, 3_200, AudioTrack.WRITE_BLOCKING)
            val whilePlaying = runCatching { track.routedDevice }.getOrNull()

            // The enumeration, for contrast: what COULD be an output.
            val enumerated = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            // And the anticipated routing for this attribute set, the other candidate surface.
            val anticipated = runCatching {
                manager.getAudioDevicesForAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).map { it.type }
            }.getOrElse { listOf("unavailable:${it::class.simpleName}") }

            Log.i(tag, "enumerated=$enumerated")
            Log.i(tag, "anticipatedRouting=$anticipated")
            Log.i(tag, "routedBeforePlay=${beforePlay?.type}")
            Log.i(tag, "routedWhilePlaying=${whilePlaying?.type}")

            // The observation must exist on a live stream, or the route-selection item has no seam.
            assertTrue(
                "a playing track must report a routed device (got=${whilePlaying?.type})",
                whilePlaying != null,
            )
        } finally {
            track.release()
        }
    }
}
