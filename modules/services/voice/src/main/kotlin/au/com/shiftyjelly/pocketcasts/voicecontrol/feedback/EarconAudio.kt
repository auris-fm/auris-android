package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * WAV decode and rate conversion for the bundled earcons.
 *
 * Split out from [EarconPlayer] so the arithmetic is testable without a device or a bundled asset:
 * a wrong rate here would put time-stretched audio into the shared echo reference, which is exactly
 * the kind of defect that is invisible in a passing playback test.
 */
internal object EarconAudio {

    /** A decoded clip at its own (playback) rate. */
    class Clip(
        val samples: ShortArray,
        val sampleRateHz: Int,
    )

    /**
     * Decodes a PCM WAV stream to mono samples at the file's own rate. Handles the format the
     * bundles use (16-bit PCM, mono or stereo); anything else is rejected rather than silently
     * mis-read, since a wrong interpretation would put wrong audio in the reference.
     */
    fun decodeWavToMono(input: InputStream): Clip = DataInputStream(input.buffered()).use { data ->
        require(readAscii(data, 4) == "RIFF") { "not a RIFF file" }
        data.readIntLE() // RIFF chunk size, unused
        require(readAscii(data, 4) == "WAVE") { "not WAVE" }

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcm: ShortArray? = null

        while (true) {
            val chunkId = runCatching { readAscii(data, 4) }.getOrNull() ?: break
            val chunkSize = data.readIntLE()
            when (chunkId) {
                "fmt " -> {
                    val formatTag = data.readShortLE()
                    channels = data.readShortLE()
                    sampleRate = data.readIntLE()
                    data.readIntLE() // byte rate
                    data.readShortLE() // block align
                    bitsPerSample = data.readShortLE()
                    val extra = chunkSize - 16
                    if (extra > 0) data.skipBytes(extra)
                    require(formatTag == 1) { "unsupported WAV format tag $formatTag" }
                    require(bitsPerSample == 16) { "unsupported bit depth $bitsPerSample" }
                }

                "data" -> {
                    val frameCount = chunkSize / (2 * channels.coerceAtLeast(1))
                    val interleaved = ShortArray(frameCount * channels.coerceAtLeast(1))
                    for (i in interleaved.indices) interleaved[i] = data.readShortLE().toShort()
                    pcm = if (channels <= 1) {
                        interleaved
                    } else {
                        ShortArray(frameCount) { f ->
                            var sum = 0
                            for (c in 0 until channels) sum += interleaved[f * channels + c]
                            (sum / channels).toShort()
                        }
                    }
                }

                else -> data.skipBytes(chunkSize)
            }
            if (chunkSize % 2 != 0) data.skipBytes(1) // chunks are word-aligned
            if (pcm != null) break
        }

        val samples = requireNotNull(pcm) { "no data chunk" }
        require(sampleRate > 0) { "no fmt chunk" }
        Clip(samples, sampleRate)
    }

    /** Linear-interpolation resample, used only for the reference copy (never for playback). */
    fun resampleMono(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
        if (samples.isEmpty() || fromRate == toRate) return samples
        val outLength = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLength) {
            val src = i * step
            val idx = src.toInt()
            val frac = src - idx
            val a = samples[idx.coerceAtMost(samples.size - 1)].toInt()
            val b = samples[(idx + 1).coerceAtMost(samples.size - 1)].toInt()
            out[i] = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Converts the source range `[startSample, startSample + count)` to [toRate] using the running
     * mapping rather than resampling the slice on its own.
     *
     * Resampling each slice independently rounds its length, and those roundings accumulate: a few
     * hundred 1024-sample slices at 44.1 kHz drift a few milliseconds against the output. Converting
     * by absolute source position makes the mapping continuous, so slices can be recorded as the sink
     * accepts them without the reference slowly sliding against playback.
     */
    fun resampleRange(
        samples: ShortArray,
        fromRate: Int,
        toRate: Int,
        startSample: Int,
        count: Int,
    ): ShortArray {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
        if (count <= 0) return ShortArray(0)
        val end = (startSample + count).coerceAtMost(samples.size)
        if (fromRate == toRate) return samples.copyOfRange(startSample, end)

        val startOut = (startSample.toLong() * toRate / fromRate).toInt()
        val endOut = (end.toLong() * toRate / fromRate).toInt()
        val outLength = (endOut - startOut).coerceAtLeast(0)
        val out = ShortArray(outLength)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLength) {
            // Position in the source timeline, so the phase does not reset at each slice boundary.
            val src = (startOut + i) * step
            val idx = src.toInt()
            val frac = src - idx
            val a = samples[idx.coerceAtMost(samples.size - 1)].toInt()
            val b = samples[(idx + 1).coerceAtMost(samples.size - 1)].toInt()
            out[i] = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun readAscii(data: DataInputStream, length: Int): String {
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun DataInputStream.readShortLE(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        return (b1 shl 8) or b0
    }

    private fun DataInputStream.readIntLE(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }
}
