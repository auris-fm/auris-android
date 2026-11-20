package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The stored cloud reference for one episode: payload bytes plus the ETag to revalidate with. */
data class CachedCloudReference(val payload: ByteArray, val etag: String)

/**
 * Persists cloud fingerprint references per episode so a later preparation can revalidate with
 * If-None-Match instead of re-downloading. The fingerprint cloud reference has no other disk
 * representation on Android; this store IS the disk path (delta: fingerprint revalidation).
 *
 * Layout: one raw-bytes file per episode plus a small ETag sidecar. The payload is never
 * buffered through text (a base64 line of a multi-megabyte reference would double peak memory
 * on the main preparation path); it is written and streamed back as bytes.
 */
interface CloudReferenceStore {
    fun load(episodeUuid: String): CachedCloudReference?
    fun save(episodeUuid: String, payload: ByteArray, etag: String?)
}

@Singleton
class FileCloudReferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudReferenceStore {
    private fun dir(): File = File(context.cacheDir, "fingerprint_cloud_refs").apply { mkdirs() }
    private fun payloadFile(episodeUuid: String): File = File(dir(), "$episodeUuid.payload")
    private fun etagFile(episodeUuid: String): File = File(dir(), "$episodeUuid.etag")

    override fun load(episodeUuid: String): CachedCloudReference? {
        val payloadFile = payloadFile(episodeUuid)
        val etagFile = etagFile(episodeUuid)
        if (!payloadFile.exists() || !etagFile.exists()) return null
        return try {
            // Streamed read: the payload goes to the decoder as bytes, never through a text line.
            val payload = payloadFile.inputStream().use { it.readBytes() }
            CachedCloudReference(payload, etagFile.readText())
        } catch (e: Exception) {
            payloadFile.delete()
            etagFile.delete()
            null
        }
    }

    override fun save(episodeUuid: String, payload: ByteArray, etag: String?) {
        if (etag == null) return // Without an ETag there is nothing to revalidate with later.
        try {
            // Payload first, etag last: a crash between the two leaves no etag sidecar, and
            // load() then discards the half-written entry instead of trusting a truncated payload.
            payloadFile(episodeUuid).writeBytes(payload)
            etagFile(episodeUuid).writeText(etag)
        } catch (e: Exception) {
            // Cache-write failure must never fail the fetch that produced the payload.
        }
    }
}
