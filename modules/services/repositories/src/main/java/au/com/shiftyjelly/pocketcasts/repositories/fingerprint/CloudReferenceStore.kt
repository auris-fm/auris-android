package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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
    suspend fun load(episodeUuid: String): CachedCloudReference?
    suspend fun save(episodeUuid: String, payload: ByteArray, etag: String?)
}

@Singleton
class FileCloudReferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudReferenceStore {
    private fun dir(): File = File(context.cacheDir, "fingerprint_cloud_refs").apply { mkdirs() }
    private fun payloadFile(episodeUuid: String): File = File(dir(), "$episodeUuid.payload")
    private fun etagFile(episodeUuid: String): File = File(dir(), "$episodeUuid.etag")

    // Multi-megabyte reads/writes must not sit on the caller's (Default) dispatcher.
    override suspend fun load(episodeUuid: String): CachedCloudReference? = withContext(Dispatchers.IO) {
        loadBlocking(episodeUuid)
    }

    private suspend fun saveBlocking(episodeUuid: String, payload: ByteArray, etag: String?): Unit = withContext(Dispatchers.IO) {
        saveInternal(episodeUuid, payload, etag)
    }

    private fun loadBlocking(episodeUuid: String): CachedCloudReference? {
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

    override suspend fun save(episodeUuid: String, payload: ByteArray, etag: String?) = saveBlocking(episodeUuid, payload, etag)

    private fun saveInternal(episodeUuid: String, payload: ByteArray, etag: String?) {
        // The outcome is logged HERE: only the store knows whether a write was
        // skipped, failed, or landed, so callers must not claim "cached".
        if (etag == null) {
            // Without an ETag there is nothing to revalidate with later.
            Timber.d("FileCloudReferenceStore: cache write skipped (no ETag), ${payload.size} bytes for $episodeUuid")
            return
        }
        try {
            // Payload first, etag last: a crash between the two leaves no etag sidecar, and
            // load() then discards the half-written entry instead of trusting a truncated payload.
            payloadFile(episodeUuid).writeBytes(payload)
            etagFile(episodeUuid).writeText(etag)
        } catch (e: Exception) {
            // Cache-write failure must never fail the fetch that produced the payload.
            Timber.w(e, "FileCloudReferenceStore: cache write failed, ${payload.size} bytes for $episodeUuid")
            return
        }
        Timber.d("FileCloudReferenceStore: cached ${payload.size} bytes for $episodeUuid")
        prune()
    }

    companion object {
        // Two bounds. ENTRY: the store must not grow with the number of episodes ever prepared.
        // BYTES: a single uncompressed v2 reference can be ~100 MB for a long episode, so the
        // count cap alone could hold gigabytes in cacheDir. Total bytes are bounded regardless
        // of how large each generation's references are (a smaller v3 simply fits more episodes).
        private const val MAX_ENTRIES = 20
        private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    }

    /**
     * Drops the oldest entries (by lastModified) until both caps hold. Best effort: pruning
     * failures are cache noise and never fail the fetch that produced the payload.
     */
    private fun prune() {
        runCatching {
            val entries = dir().listFiles { f -> f.name.endsWith(".payload") } ?: return
            val totalBytes = entries.sumOf { it.length() }
            val drop = entries.sortedBy { it.lastModified() }.let { sorted ->
                val overCount = sorted.size - MAX_ENTRIES
                var bytes = totalBytes
                var dropCount = 0
                for ((index, payload) in sorted.withIndex()) {
                    if (index < overCount || bytes > MAX_TOTAL_BYTES) {
                        bytes -= payload.length()
                        dropCount++
                    }
                }
                sorted.take(dropCount)
            }
            drop.forEach { payload ->
                runCatching {
                    payload.delete()
                    etagFile(payload.name.removeSuffix(".payload")).delete()
                }
            }
        }
    }
}
