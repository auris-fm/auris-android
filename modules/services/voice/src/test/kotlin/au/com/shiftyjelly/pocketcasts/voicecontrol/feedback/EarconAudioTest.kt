package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode and rate conversion for the earcon reference copy. A wrong rate here would put
 * time-stretched audio into the shared echo reference — a defect that a passing playback test cannot
 * see, which is why these assert duration and waveform rather than "it didn't throw".
 */
class EarconAudioTest {

    /** WAV is little-endian; write the low bytes first. */
    private fun DataOutputStream.writeLEShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun DataOutputStream.writeLEInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    /** Builds a 16-bit PCM WAV at [rate] containing a [freqHz] tone of [seconds]. */
    private fun wav(seconds: Double, rate: Int, freqHz: Double, channels: Int = 1): ByteArray {
        val frames = (seconds * rate).toInt()
        val data = ByteArrayOutputStream()
        DataOutputStream(data).use { out ->
            for (i in 0 until frames) {
                val v = (0.5 * sin(2 * PI * freqHz * i / rate) * Short.MAX_VALUE).toInt()
                repeat(channels) { out.writeLEShort(v and 0xFFFF) }
            }
        }
        val pcm = data.toByteArray()
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { out ->
            out.writeBytes("RIFF")
            out.writeLEInt(36 + pcm.size)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeLEInt(16)
            out.writeLEShort(1) // PCM
            out.writeLEShort(channels)
            out.writeLEInt(rate)
            out.writeLEInt(rate * channels * 2)
            out.writeLEShort(channels * 2)
            out.writeLEShort(16)
            out.writeBytes("data")
            out.writeLEInt(pcm.size)
        }
        return header.toByteArray() + pcm
    }

    @Test
    fun `decode preserves the file's own rate and frame count`() {
        val clip = EarconAudio.decodeWavToMono(wav(0.4, 44_100, 440.0).inputStream())
        assertEquals(44_100, clip.sampleRateHz)
        assertEquals(17_640, clip.samples.size)
    }

    @Test
    fun `resample to the reference rate preserves duration`() {
        val clip = EarconAudio.decodeWavToMono(wav(0.4, 44_100, 440.0).inputStream())
        val resampled = EarconAudio.resampleMono(clip.samples, clip.sampleRateHz, 16_000)

        val sourceMs = clip.samples.size * 1000 / clip.sampleRateHz
        val resampledMs = resampled.size * 1000 / 16_000
        assertTrue(
            "duration must survive the rate change ($sourceMs ms -> $resampledMs ms)",
            abs(sourceMs - resampledMs) <= 5,
        )
    }

    @Test
    fun `resample preserves the waveform, not just the length`() {
        // A 440 Hz tone resampled 44.1k -> 16k must still be a 440 Hz tone: compare zero-crossings
        // per second before and after, which a time-stretch or a dropped sample run would change.
        val clip = EarconAudio.decodeWavToMono(wav(0.5, 44_100, 440.0).inputStream())
        val resampled = EarconAudio.resampleMono(clip.samples, clip.sampleRateHz, 16_000)

        fun crossings(samples: ShortArray, rate: Int): Double {
            var count = 0
            for (i in 1 until samples.size) {
                if (samples[i - 1] < 0 && samples[i] >= 0) count++
            }
            return count * 1.0 / (samples.size.toDouble() / rate)
        }

        val before = crossings(clip.samples, clip.sampleRateHz)
        val after = crossings(resampled, 16_000)
        assertTrue(
            "the tone's frequency must be preserved ($before Hz -> $after Hz)",
            abs(before - after) < 20,
        )
    }

    @Test
    fun `a stereo clip is downmixed to mono without changing duration`() {
        val clip = EarconAudio.decodeWavToMono(wav(0.3, 44_100, 300.0, channels = 2).inputStream())
        assertEquals(44_100, clip.sampleRateHz)
        assertEquals("stereo frames must fold into one mono sample each", 13_230, clip.samples.size)
    }

    @Test
    fun `the same rate is returned untouched`() {
        val clip = EarconAudio.decodeWavToMono(wav(0.2, 16_000, 500.0).inputStream())
        val same = EarconAudio.resampleMono(clip.samples, 16_000, 16_000)
        assertTrue("no conversion is a no-op", same === clip.samples)
    }

    @Test
    fun `resampling a slice preserves its local duration`() {
        // The player records each ACCEPTED write on the reference timeline. Whatever the slice
        // boundaries are, a converted slice must keep the proportion it had in the clip, or the
        // reference drifts against playback across partial writes.
        val clip = EarconAudio.decodeWavToMono(wav(0.4, 44_100, 440.0).inputStream())
        val slice = clip.samples.copyOfRange(4_410, 4_410 + 8_820)
        val converted = EarconAudio.resampleMono(slice, 44_100, 16_000)

        val sliceMs = slice.size * 1000 / 44_100
        val convertedMs = converted.size * 1000 / 16_000
        assertTrue(
            "a 200ms slice must still be 200ms after conversion ($sliceMs -> $convertedMs)",
            abs(sliceMs - convertedMs) <= 3,
        )
    }
}
