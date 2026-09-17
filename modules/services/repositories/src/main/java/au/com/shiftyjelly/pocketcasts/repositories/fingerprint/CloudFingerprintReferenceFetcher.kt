package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintConstants
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Fetches the Auris cloud reference fingerprints for an episode from
 * `GET /api/v1/episodes/{episode_uuid}/fingerprints` (cloud-ingestion.md) and
 * parses the `fingerprint-compact-v2` payload into a [CloudReferenceMatcher].
 *
 * Returns null on any failure (unconfigured, network, decode, no checkpoints)
 * so callers degrade gracefully to the transcript-sync path.
 */
@Singleton
class CloudFingerprintReferenceFetcher @Inject constructor(
    private val cloudIdentity: CloudIdentity,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetchReference(baseUrl: String, episodeUuid: String): CloudReferenceMatcher? = withContext(Dispatchers.IO) {
        // Caller budget: preparation runs this before the transcript fetch,
        // so an unbounded wait here stalls the whole pipeline (the read
        // timeout alone allows ~60s).
        withTimeoutOrNull(FingerprintConstants.ON_DEMAND_FETCH_TIMEOUT_MS) {
            fetchReferenceOnce(baseUrl, episodeUuid)
        }.also { if (it == null) Timber.w("CloudFingerprintReferenceFetcher: fetch timed out or failed for %s", episodeUuid) }
    }

    private fun fetchReferenceOnce(baseUrl: String, episodeUuid: String): CloudReferenceMatcher? {
        val url = baseUrl.trimEnd('/') + "/api/v1/episodes/" + episodeUuid + "/fingerprints"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + cloudIdentity.userId())
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("CloudFingerprintReferenceFetcher: status ${response.code} for $episodeUuid")
                    null
                } else {
                    buildMatcher(response.body.bytes())
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudFingerprintReferenceFetcher: fetch failed for $episodeUuid")
            null
        }
    }

    /** Parses compact-v2 bytes into a matcher, or null when unusable. */
    internal fun buildMatcher(data: ByteArray): CloudReferenceMatcher? {
        val reference = ReferenceFingerprint.decode(data) ?: return null
        val checkpoints = reference.libraryCheckpoints()
        if (checkpoints.isEmpty()) return null

        val matcher = CloudReferenceMatcher()
        for (checkpoint in checkpoints) {
            // libraryCheckpoints decodes hashes as signed Ints; the uint32
            // value is the bit pattern, so re-interpret as an unsigned Long.
            val hashes = checkpoint.hashes.map { it.toLong() and 0xFFFFFFFFL }.toLongArray()
            matcher.add(checkpoint.timestampSeconds, hashes, reference.checkpointDurationSeconds)
        }
        return matcher
    }
}
