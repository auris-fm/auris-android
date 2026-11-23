package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import timber.log.Timber

/** Which Auris reference contributed entries to a saved mapping, as recorded by the writer. */
data class AurisReferenceIdentity(
    val format: String?,
    val payloadHash: String,
)

object FingerprintMappingCache {

    data class LoadResult(
        val entries: List<FingerprintTimingManager.TimeMappingEntry>,
        val referenceDuration: Double,
    )

    /**
     * The Auris reference a mapping's entries were built against. Absent when no Auris reference was
     * in use, which is a mapping that can never carry Auris-derived anchors.
     */
    @JsonClass(generateAdapter = true)
    internal data class CachedAurisIdentity(
        val format: String?,
        val payloadHash: String,
    )

    @JsonClass(generateAdapter = true)
    internal data class CachedMapping(
        val schemaVersion: Int,
        val referenceHash: String,
        val referenceByteSize: Long,
        val referenceMTime: Long,
        val audioByteSize: Long,
        val audioMTime: Long,
        val audioContentHash: String,
        val referenceDuration: Double,
        /**
         * Which reference format produced these entries. Absent on a cache written before this was
         * recorded, which is unknown provenance rather than a supported one.
         */
        val referenceFormat: String? = null,
        /**
         * The Auris reference in use when this mapping was saved, if any. Its format and content
         * hash, because the Auris reference has no other on-disk identity to compare against: the
         * byte guards above describe the TRANSCRIPT reference only.
         */
        val auris: CachedAurisIdentity? = null,
        val entries: List<CachedEntry>,
    )

    @JsonClass(generateAdapter = true)
    internal data class CachedEntry(
        @Json(name = "p") val playbackTime: Double,
        @Json(name = "r") val referenceTime: Double,
        @Json(name = "s") val score: Float,
    )

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(CachedMapping::class.java)

    /**
     * [aurisIdentity] is the Auris reference the caller holds now, or null when none is in use. It
     * is the only record of the Auris reference anywhere on disk, so a mapping that recorded one
     * must match it exactly — format and content.
     */
    fun load(
        audioFilePath: String,
        referenceFilePath: String,
        referenceData: ByteArray,
        aurisIdentity: AurisReferenceIdentity? = null,
    ): LoadResult? {
        val path = mappingPath(audioFilePath)
        val file = File(path)
        if (!file.exists()) return null

        val cached = try {
            adapter.fromJson(file.readText())
        } catch (e: Exception) {
            Timber.w("FingerprintMappingCache: failed to decode cache at $path — discarding")
            return null
        } ?: return null

        if (cached.schemaVersion != FingerprintConstants.MAPPING_CACHE_SCHEMA_VERSION) {
            Timber.d("FingerprintMappingCache: schema version mismatch at $path — discarding")
            return null
        }

        // The byte guards below cannot tell WHICH FORMAT produced these reference times. A mapping
        // built from values this build no longer computes would resume alignment on a timeline it
        // cannot reproduce, so the format is checked first and an unrecorded one counts as unknown.
        // It must be the format of the reference the caller is holding: a transcript-derived mapping
        // stays reusable, while one built from an Auris reference in another format is not.
        val expectedFormat = ReferenceFingerprint.decode(referenceData)?.format
        if (expectedFormat == null || cached.referenceFormat != expectedFormat) {
            Timber.d("FingerprintMappingCache: reference format '${cached.referenceFormat}' at $path — discarding")
            return null
        }

        // The Auris reference has no file of its own, so its format and hash are the only identity a
        // mapping can be checked against. A reference regenerated in place keeps its format and its
        // size, and would otherwise resume alignment on a timeline this build cannot reproduce.
        if (cached.auris != null && cached.auris.format != aurisIdentity?.format) {
            Timber.d("FingerprintMappingCache: Auris reference format changed at $path — discarding")
            return null
        }
        if (cached.auris != null && cached.auris.payloadHash != aurisIdentity?.payloadHash) {
            Timber.d("FingerprintMappingCache: Auris reference content changed at $path — discarding")
            return null
        }

        val refFile = File(referenceFilePath)
        if (!refFile.exists() || refFile.length() != cached.referenceByteSize || refFile.lastModified() != cached.referenceMTime) {
            Timber.d("FingerprintMappingCache: reference file changed at $referenceFilePath — discarding cache")
            return null
        }

        if (cached.referenceHash != sha256(referenceData)) {
            Timber.d("FingerprintMappingCache: reference hash mismatch at $path — discarding")
            return null
        }

        val audioFile = File(audioFilePath)
        if (!audioFile.exists() || audioFile.length() != cached.audioByteSize || audioFile.lastModified() != cached.audioMTime) {
            Timber.d("FingerprintMappingCache: audio file changed at $audioFilePath — discarding cache")
            return null
        }

        if (contentSampleHash(audioFilePath) != cached.audioContentHash) {
            Timber.d("FingerprintMappingCache: audio content hash mismatch at $audioFilePath — discarding cache")
            return null
        }

        val lastEntry = cached.entries.lastOrNull() ?: return null
        if (cached.referenceDuration <= 0 || lastEntry.referenceTime / cached.referenceDuration < FingerprintConstants.FULL_COVERAGE_THRESHOLD) {
            Timber.d("FingerprintMappingCache: partial coverage at $path — ignoring")
            return null
        }

        Timber.d("FingerprintMappingCache: loaded ${cached.entries.size} cached mappings from $path")
        return LoadResult(
            entries = cached.entries.map {
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = it.playbackTime,
                    referenceTime = it.referenceTime,
                    score = it.score,
                )
            },
            referenceDuration = cached.referenceDuration,
        )
    }

    fun save(
        entries: List<FingerprintTimingManager.TimeMappingEntry>,
        audioFilePath: String,
        referenceFilePath: String,
        referenceData: ByteArray,
        referenceDuration: Double,
        aurisIdentity: AurisReferenceIdentity? = null,
    ) {
        val lastEntry = entries.lastOrNull() ?: return
        if (referenceDuration <= 0 || lastEntry.referenceTime / referenceDuration < FingerprintConstants.FULL_COVERAGE_THRESHOLD) {
            return
        }

        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            Timber.w("FingerprintMappingCache: cannot stat audio at $audioFilePath — skipping save")
            return
        }

        val refFile = File(referenceFilePath)
        if (!refFile.exists()) {
            Timber.w("FingerprintMappingCache: cannot stat reference at $referenceFilePath — skipping save")
            return
        }

        val audioContentHash = contentSampleHash(audioFilePath)
        if (audioContentHash == null) {
            Timber.w("FingerprintMappingCache: cannot hash audio content at $audioFilePath — skipping save")
            return
        }

        val cached = CachedMapping(
            schemaVersion = FingerprintConstants.MAPPING_CACHE_SCHEMA_VERSION,
            referenceHash = sha256(referenceData),
            referenceByteSize = refFile.length(),
            referenceMTime = refFile.lastModified(),
            audioByteSize = audioFile.length(),
            audioMTime = audioFile.lastModified(),
            audioContentHash = audioContentHash,
            referenceDuration = referenceDuration,
            referenceFormat = ReferenceFingerprint.decode(referenceData)?.format,
            auris = aurisIdentity?.let { CachedAurisIdentity(it.format, it.payloadHash) },
            entries = entries.map { CachedEntry(it.playbackTime, it.referenceTime, it.score) },
        )

        val path = mappingPath(audioFilePath)
        val tmpFile = File("$path.tmp")
        try {
            tmpFile.writeText(adapter.toJson(cached))
            if (!tmpFile.renameTo(File(path))) {
                Timber.w("FingerprintMappingCache: atomic rename failed for $path, falling back to direct write")
                File(path).writeText(adapter.toJson(cached))
                tmpFile.delete()
            }
            Timber.d("FingerprintMappingCache: saved ${entries.size} mappings to $path")
        } catch (e: Exception) {
            Timber.w(e, "FingerprintMappingCache: failed to save to $path")
            tmpFile.delete()
        }
    }

    fun mappingPath(audioFilePath: String): String {
        val dotIndex = audioFilePath.lastIndexOf('.')
        val base = if (dotIndex > 0) audioFilePath.substring(0, dotIndex) else audioFilePath
        return "$base.map.fp.json"
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private const val CONTENT_SAMPLE_SIZE = 65_536

    private fun contentSampleHash(filePath: String): String? {
        return try {
            FileInputStream(filePath).use { stream ->
                val buffer = ByteArray(CONTENT_SAMPLE_SIZE)
                val bytesRead = stream.read(buffer)
                if (bytesRead <= 0) return null
                sha256(buffer.copyOf(bytesRead))
            }
        } catch (e: Exception) {
            null
        }
    }
}
