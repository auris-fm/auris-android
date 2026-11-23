package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import timber.log.Timber

@JsonClass(generateAdapter = true)
data class ReferenceFingerprint(
    val format: String,
    @Json(name = "total_duration") val totalDuration: Double,
    @Json(name = "checkpoint_interval") val checkpointInterval: Int,
    @Json(name = "checkpoint_duration") val checkpointDuration: Int,
    @Json(name = "timestamp_quantum") val timestampQuantum: Int,
    val checkpoints: List<List<Any>>,
) {
    data class LibraryCheckpoint(
        val timestampSeconds: Float,
        val hashes: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LibraryCheckpoint) return false
            return timestampSeconds == other.timestampSeconds && hashes.contentEquals(other.hashes)
        }

        override fun hashCode(): Int {
            return 31 * timestampSeconds.hashCode() + hashes.contentHashCode()
        }
    }

    val checkpointDurationSeconds: Float get() = checkpointDuration.toFloat()

    @Json(ignore = true)
    internal val decodedCheckpoints by lazy { decodeCheckpoints() }

    fun libraryCheckpoints(): List<LibraryCheckpoint> = decodedCheckpoints

    private fun decodeCheckpoints(): List<LibraryCheckpoint> {
        var accumulated = 0
        return checkpoints.mapNotNull { checkpoint ->
            if (checkpoint.size < 2) return@mapNotNull null
            val delta = (checkpoint[0] as? Number)?.toInt() ?: return@mapNotNull null
            val data = checkpoint[1] as? String ?: return@mapNotNull null

            accumulated += delta

            val payload = try {
                Base64.Default.decode(data)
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            if (payload.size % 4 != 0) return@mapNotNull null

            val count = payload.size / 4
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val hashes = IntArray(count) { buffer.getInt() }

            val timestamp = accumulated.toFloat() * timestampQuantum.toFloat()
            LibraryCheckpoint(timestampSeconds = timestamp, hashes = hashes)
        }
    }

    companion object {
        /**
         * The Auris endpoint's format: the inherited library's values, deltas the client accumulates.
         */
        const val AURIS_FORMAT = "fingerprint-compact-v3"

        /**
         * The transcript host's format. That host is not ours to migrate, and its references hold
         * the same library's values, so transcript sync keeps accepting them.
         */
        const val TRANSCRIPT_FORMAT = "fingerprint-compact-v2"

        private val moshi = Moshi.Builder().build()
        private val adapter = moshi.adapter(ReferenceFingerprint::class.java)

        /**
         * Parses compact reference bytes of any format. Which formats are usable is the CALL SITE's
         * decision, because the two sources serve different ones: check [format] against the
         * constant for the source the bytes came from.
         */
        fun decode(data: ByteArray): ReferenceFingerprint? {
            return try {
                adapter.fromJson(String(data, Charsets.UTF_8))
            } catch (e: Exception) {
                Timber.w(e, "ReferenceFingerprint: failed to decode JSON")
                null
            }
        }

        /** Decodes bytes that must come from [expectedFormat]; any other format is absent. */
        fun decode(data: ByteArray, expectedFormat: String): ReferenceFingerprint? {
            val fingerprint = decode(data) ?: return null
            if (fingerprint.format != expectedFormat) {
                Timber.w("ReferenceFingerprint: format '${fingerprint.format}', expected '$expectedFormat'")
                return null
            }
            return fingerprint
        }
    }
}
