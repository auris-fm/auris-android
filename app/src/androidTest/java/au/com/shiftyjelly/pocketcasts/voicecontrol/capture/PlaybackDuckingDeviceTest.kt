package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device evidence for the routing/ducking and audible-latency rows, which need real audio: the duck is
 * an audio-focus request and the latency is the time real output takes to start.
 *
 * The production sink ducks by requesting transient-may-duck focus and restores by abandoning it, so
 * the observation here is the focus state the platform actually holds, and the time each operation
 * takes on this device. No playback of a podcast is required for the focus half; the latency half
 * measures a real AudioTrack, which is the same primitive the cloud and TTS sinks use.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackDuckingDeviceTest {

    private val tag = "DuckingDevice"

    // The listener overloads are deprecated in favour of AudioFocusRequestCompat, which lives in
    // androidx.media — a dependency this module does not carry. The production sink suppresses for the
    // same reason, so the test drives the same call it does rather than a different one.
    @Suppress("DEPRECATION")
    @Test
    fun duckAndRestoreMoveTheFocusThePlatformActuallyHolds() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // The production duck: transient focus that may duck, the signal PlaybackManager lowers its
        // volume for. A listener is required by this overload, and is where a real duck would surface.
        // The HOST PLAYER's focus listener: it is the prior holder, so the cloud duck is delivered to
        // IT as a duckable loss. Requesting the focus ourselves would make us the holder and we would
        // never receive the loss, which is why the first version of this test logged
        // duckableLossSeen=false — a value that could not have been true.
        var hostSawDuckableLoss = false
        var hostGainedFocus = false
        val hostListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> hostSawDuckableLoss = true
                AudioManager.AUDIOFOCUS_GAIN -> hostGainedFocus = true
                else -> {}
            }
        }
        // A playing host player holds focus; the test stands in for it with the same request a player
        // makes, so the cloud duck below has someone to duck.
        val hostGranted = manager.requestAudioFocus(
            hostListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        )
        assertEquals(
            "precondition: the host player must hold focus before the cloud ducks",
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            hostGranted,
        )

        val listener = AudioManager.OnAudioFocusChangeListener { /* the cloud turn's own listener */ }

        val duckMs = measureTimeMillis {
            val granted = manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
            Log.i(tag, "duck request granted=${granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED}")
            assertEquals(
                "the cloud duck must be granted, or the host player never lowers for the answer",
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                granted,
            )
        }

        // Restore is abandoning that focus, which returns the host to full volume.
        val restoreMs = measureTimeMillis { manager.abandonAudioFocus(listener) }
        // The restored gain is delivered to the host holder, and may arrive on another thread.
        withTimeoutOrNull(2_000) { delay(200) }
        manager.abandonAudioFocus(hostListener)
        Log.i(
            tag,
            "duck=${duckMs}ms restore=${restoreMs}ms hostDuckableLoss=$hostSawDuckableLoss hostGained=$hostGainedFocus",
        )

        // Both operations must complete promptly: a duck that takes long leaves the answer competing with
        // the host player, which is the audible symptom the row is about.
        assertTrue("the duck must be prompt on this device (${duckMs}ms)", duckMs < 1_000)
        assertTrue("and the restore (${restoreMs}ms)", restoreMs < 1_000)
        // The row is that the HOST PLAYER lowers, not merely that the focus was obtainable: assert the
        // signal the host acts on, since the request being granted is only the precondition.
        assertTrue(
            "the host player must receive the duckable loss, or nothing lowers its volume",
            hostSawDuckableLoss,
        )
    }

    @Test
    fun aRealPlaybackSinkStartsOutputPromptly() = runBlocking<Unit> {
        // Audible latency: the time from requesting playback to the output actually running, measured on
        // a real AudioTrack — the primitive the earcon and TTS sinks both write through.
        val sampleRate = 16_000
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
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            val latencyMs = measureTimeMillis {
                track.play()
                // A short silence write so the stream is running, which is when output begins.
                track.write(FloatArray(1_600), 0, 1_600, AudioTrack.WRITE_BLOCKING)
            }
            Log.i(tag, "output start latency=${latencyMs}ms playbackState=${track.playState}")
            assertEquals(
                "the sink must reach a playing state",
                AudioTrack.PLAYSTATE_PLAYING,
                track.playState,
            )
            // A bound generous enough to be a real check but not a tuned target: the row is about a
            // user-audible delay, and a start beyond this would be audible as a gap before the answer.
            assertTrue("output must start promptly on this device (${latencyMs}ms)", latencyMs < 500)
        } finally {
            track.release()
        }
    }
}
