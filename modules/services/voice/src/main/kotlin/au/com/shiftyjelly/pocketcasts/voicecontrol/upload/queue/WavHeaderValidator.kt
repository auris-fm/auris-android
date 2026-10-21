package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Structural validation of a retained WAV file's header.
 *
 * Why this exists separately from the audio digest: a digest proves the bytes are the bytes that
 * were recorded, NOT that they are a well-formed PCM WAV. A malformed header (wrong fmt chunk
 * length, inconsistent RIFF size, bad block alignment) hashes just as cleanly as a valid one, so
 * the header must be checked on its own terms.
 *
 * The layout checked is the canonical one [WavWriter] emits: 44-byte header, PCM (format 1),
 * 16-bit samples, 16 kHz, mono. Anything else is refused rather than reinterpreted.
 */
object WavHeaderValidator {

    /** Header of a validated file: the body length and sample count implied by the header. */
    data class Valid(val fileBytes: Long, val dataBytes: Long, val sampleCount: Long)

    fun validate(header: ByteArray, actualFileBytes: Long): Valid? {
        if (header.size < WavWriter.HEADER_BYTES) return null
        if (actualFileBytes < WavWriter.HEADER_BYTES) return null
        // Chunk identifiers: RIFF<size>WAVE, then fmt<16>, then data<size>.
        if (ascii(header, 0, 4) != "RIFF") return null
        if (ascii(header, 8, 4) != "WAVE") return null
        if (ascii(header, 12, 4) != "fmt ") return null
        if (ascii(header, 36, 4) != "data") return null
        val riffSize = le32(header, 4)
        val fmtSize = le32(header, 16)
        val audioFormat = le16(header, 20)
        val channels = le16(header, 22)
        val sampleRate = le32(header, 24)
        val byteRate = le32(header, 28)
        val blockAlign = le16(header, 32)
        val bitsPerSample = le16(header, 34)
        val dataBytes = le32(header, 40)

        // Canonical format: PCM, mono, 16 kHz, 16-bit.
        if (fmtSize != 16L) return null
        if (audioFormat != 1) return null
        if (channels != 1) return null
        if (sampleRate != 16_000L) return null
        if (bitsPerSample != 16) return null
        // Derived fields must AGREE with the format, not merely be plausible: a byte rate or block
        // alignment inconsistent with rate/channels/bits describes a file this writer cannot have
        // produced and a decoder may read differently from us.
        if (blockAlign != channels * (bitsPerSample / 8)) return null
        if (byteRate != sampleRate * blockAlign) return null
        // RIFF size covers everything after the first 8 bytes.
        if (riffSize + 8 != actualFileBytes) return null
        if (WavWriter.HEADER_BYTES.toLong() + dataBytes != actualFileBytes) return null
        // Whole samples only: an odd data length means a truncated sample.
        if (dataBytes % 2L != 0L) return null
        return Valid(fileBytes = actualFileBytes, dataBytes = dataBytes, sampleCount = dataBytes / 2)
    }

    private fun ascii(b: ByteArray, at: Int, len: Int): String = String(b, at, len, Charsets.US_ASCII)

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Long = (b[at].toLong() and 0xFF) or
        ((b[at + 1].toLong() and 0xFF) shl 8) or
        ((b[at + 2].toLong() and 0xFF) shl 16) or
        ((b[at + 3].toLong() and 0xFF) shl 24)
}
