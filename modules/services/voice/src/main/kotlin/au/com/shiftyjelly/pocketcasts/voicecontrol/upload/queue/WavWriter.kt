package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.OutputStream

// Bounded WAV finalization for contribution audio (spec: immutable WAV, byte_count includes the
// header, pcm_s16le 16 kHz mono). Streams chunk-by-chunk: peak extra memory is one chunk copy of
// the caller's current frame, never a second full buffer.
object WavWriter {

    const val HEADER_BYTES = 44

    class WavException(message: String) : Exception(message)

    /** Total file bytes for [samples] PCM samples including the 44-byte canonical header. */
    fun fileBytes(samples: Long): Long = HEADER_BYTES + samples * 2

    fun writeHeader(out: OutputStream, sampleCount: Int, sampleRateHz: Int = 16000, channels: Int = 1) {
        if (sampleRateHz != 16000 || channels != 1) throw WavException("only 16kHz mono pcm_s16le is specced")
        val dataBytes = sampleCount.toLong() * 2
        val riffSize = 36 + dataBytes
        val b = out
        fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII).also { require(it.size == s.length) }
        fun u32(v: Long) {
            b.write((v and 0xFF).toInt())
            b.write(((v shr 8) and 0xFF).toInt())
            b.write(((v shr 16) and 0xFF).toInt())
            b.write(((v shr 24) and 0xFF).toInt())
        }
        fun u16(v: Int) {
            b.write(v and 0xFF)
            b.write((v shr 8) and 0xFF)
        }
        ascii("RIFF").let(b::write)
        u32(riffSize)
        ascii("WAVE").let(b::write)
        ascii("fmt ").let(b::write)
        u32(16) // PCM fmt chunk size
        u16(1) // audio format = PCM
        u16(channels)
        u32(sampleRateHz.toLong())
        u32(sampleRateHz.toLong() * channels * 2) // byte rate
        u16(channels * 2) // block align
        u16(16) // bits per sample
        ascii("data").let(b::write)
        u32(dataBytes)
    }

    /** Writes little-endian PCM from a valid RANGE of [samples] (capacity may exceed range). */
    fun writePcm(out: OutputStream, samples: ShortArray, validLength: Int = samples.size) {
        if (validLength < 0 || validLength > samples.size) throw WavException("invalid range $validLength of ${samples.size}")
        val buf = ByteArray(validLength * 2)
        var i = 0
        var o = 0
        while (i < validLength) {
            val v = samples[i].toInt()
            buf[o++] = (v and 0xFF).toByte()
            buf[o++] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        out.write(buf)
    }
}
