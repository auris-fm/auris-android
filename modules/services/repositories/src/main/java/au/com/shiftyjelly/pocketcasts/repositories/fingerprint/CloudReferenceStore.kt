package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The stored cloud reference for one episode: payload bytes plus the ETag to revalidate with. */
data class CachedCloudReference(val payload: ByteArray, val etag: String)

/**
 * Persists cloud fingerprint references per episode so a later preparation can revalidate with
 * If-None-Match instead of re-downloading. The fingerprint cloud reference has no other disk
 * representation on Android; this store IS the disk path (delta: fingerprint revalidation).
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
    private fun fileFor(episodeUuid: String): File = File(dir(), episodeUuid)

    override fun load(episodeUuid: String): CachedCloudReference? {
        val file = fileFor(episodeUuid)
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            // Line 1: etag; the rest: raw payload bytes as base64.
            CachedCloudReference(java.util.Base64.getDecoder().decode(lines[1]), lines[0])
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    override fun save(episodeUuid: String, payload: ByteArray, etag: String?) {
        if (etag == null) return // Without an ETag there is nothing to revalidate with later.
        try {
            fileFor(episodeUuid).writeText(etag + "\n" + java.util.Base64.getEncoder().encodeToString(payload))
        } catch (e: Exception) {
            // Cache-write failure must never fail the fetch that produced the payload.
        }
    }
}
