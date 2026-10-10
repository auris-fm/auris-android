package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EarconPlayerTest {
    private lateinit var player: EarconPlayer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        player = EarconPlayer(context)
    }

    @Test
    fun `play does not throw for any EarconId`() {
        EarconId.entries.forEach { id ->
            try {
                player.play(id)
            } catch (e: Exception) {
                fail("Unexpected exception: $e")
            }
        }
    }

    @Test
    fun `play returns false for missing assets`() {
        // When assets are not present, play should return false, not crash
        val result = player.play(EarconId.SUCCESS)
        assertFalse(result)
    }

    @Test
    fun `release disposes resources`() {
        player.release()
        // Subsequent play should be no-op, not crash
        try {
            player.play(EarconId.SUCCESS)
        } catch (e: Exception) {
            fail("Unexpected exception: $e")
        }
    }

    @Test
    fun `playback keeps the clip's rate while only the reference is normalized`() {
        // @spec's rate policy: playback keeps original fidelity, and only the reference copy is
        // normalized to the correlator's rate. The two halves live in EarconClip, and nothing pinned
        // that they stay separate — a change that resampled the clip in place would downgrade what the
        // user hears to satisfy the correlator, which is the outcome the policy forbids.
        val clipRate = 44_100
        val clip = EarconAudio.decodeWavToMono(monoWav(rate = clipRate, seconds = 0.25, hz = 440.0).inputStream())

        assertEquals("the decoded clip must keep its own rate", clipRate, clip.sampleRateHz)
        assertEquals(
            "and the samples must be the clip's own, not a rate-converted copy",
            (clipRate * 0.25).toInt(),
            clip.samples.size,
        )

        // The reference side of the same clip: normalized, and a different length by construction.
        val forReference = EarconAudio.resampleRange(
            samples = clip.samples,
            fromRate = clip.sampleRateHz,
            toRate = 16_000,
            startSample = 0,
            count = clip.samples.size,
        )
        val sourceMs = clip.samples.size * 1000 / clipRate
        val referenceMs = forReference.size * 1000 / 16_000
        assertTrue(
            "the reference must be converted to 16 kHz (size=${forReference.size})",
            forReference.size != clip.samples.size,
        )
        assertTrue(
            "and must describe the same instant of sound ($sourceMs ms vs $referenceMs ms)",
            kotlin.math.abs(sourceMs - referenceMs) <= 5,
        )
    }

    @Test
    fun `a clip already at the reference rate is not resampled away`() {
        // The boundary case: when the clip IS at 16 kHz the reference conversion must be a no-op in
        // length, so a change that always converted cannot silently drop audio at this rate.
        val clip = EarconAudio.decodeWavToMono(monoWav(rate = 16_000, seconds = 0.25, hz = 440.0).inputStream())
        val forReference = EarconAudio.resampleRange(
            samples = clip.samples,
            fromRate = clip.sampleRateHz,
            toRate = 16_000,
            startSample = 0,
            count = clip.samples.size,
        )
        assertEquals("no rate change, no length change", clip.samples.size, forReference.size)
    }

    /** A minimal 16-bit mono PCM WAV, so the decoder is driven by a real container. */
    private fun monoWav(rate: Int, seconds: Double, hz: Double): ByteArray {
        val frames = (rate * seconds).toInt()
        val data = java.nio.ByteBuffer.allocate(frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (kotlin.math.sin(2.0 * Math.PI * hz * i / rate) * 12_000).toInt().toShort()
            data.putShort(v)
        }
        val pcm = data.array()
        val out = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray())
        out.putInt(36 + pcm.size)
        out.put("WAVE".toByteArray())
        out.put("fmt ".toByteArray())
        out.putInt(16)
        out.putShort(1)
        out.putShort(1)
        out.putInt(rate)
        out.putInt(rate * 2)
        out.putShort(2)
        out.putShort(16)
        out.put("data".toByteArray())
        out.putInt(pcm.size)
        out.put(pcm)
        return out.array()
    }
}
