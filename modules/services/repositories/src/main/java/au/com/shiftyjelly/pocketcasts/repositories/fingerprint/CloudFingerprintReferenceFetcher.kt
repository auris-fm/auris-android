package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintConstants
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    // THE Auris provider DIRECTLY, as the Voice Sharing callers inject it: the routing provider's
    // legacy branch would still send the TOFU user_<uuid> when no cutover is configured, and this
    // route's verifier cannot accept that. No config flag may swap this bearer.
    private val tokenProvider: AurisTokenProvider,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetchReference(baseUrl: String, episodeUuid: String, podcastUuid: String): CloudReferenceMatcher? {
        // The SAME credential the turn route presents, never the TOFU user_<uuid>: generating a
        // reference is paid work, so it needs a verified token.
        // FAIL CLOSED: no usable token means no request at all.
        val token = tokenProvider.currentToken() ?: return null
        val first = withContext(Dispatchers.IO) { fetchReferenceOnce(baseUrl, episodeUuid, podcastUuid, token) }
        if (first !is Attempt.Unauthorized) return first.matcher
        // The route says this token is bad, not merely old: force a replacement and repeat the
        // request ONCE -- the turn route's posture. A second refusal gives up rather than looping.
        tokenProvider.refreshToken()
        val replacement = tokenProvider.currentToken() ?: return null
        val second = withContext(Dispatchers.IO) { fetchReferenceOnce(baseUrl, episodeUuid, podcastUuid, replacement) }
        return second.matcher
    }

    /** One request's outcome; a 401 is distinguishable so the caller can retry once. */
    private sealed interface Attempt {
        val matcher: CloudReferenceMatcher?

        data class Done(override val matcher: CloudReferenceMatcher?) : Attempt
        data object Unauthorized : Attempt {
            override val matcher: CloudReferenceMatcher? get() = null
        }
    }

    private fun fetchReferenceOnce(baseUrl: String, episodeUuid: String, podcastUuid: String, token: String): Attempt {
        // podcast_id lets the server resolve uncached episodes through the public cache host,
        // which keys metadata by podcast + episode.
        val url = (baseUrl.trimEnd('/') + "/api/v1/episodes/" + episodeUuid + "/fingerprints")
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("podcast_id", podcastUuid)
            ?.build()
            ?: return Attempt.Done(null)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + token)
            .build()
        // The whole fetch is blocking I/O, so a coroutine timeout would never
        // fire mid-call (and would only discard late successes). call.timeout
        // is the lever that actually cancels the socket.
        val call = client.newCall(request)
        call.timeout().timeout(FingerprintConstants.CLOUD_REFERENCE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                when {
                    response.code == 401 -> Attempt.Unauthorized

                    !response.isSuccessful -> {
                        // Definitive statuses (e.g. 404 = no reference) are not
                        // timeouts — log them as what they are.
                        Timber.w("CloudFingerprintReferenceFetcher: status ${response.code} for $episodeUuid")
                        Attempt.Done(null)
                    }

                    else -> Attempt.Done(buildMatcher(response.body.bytes()))
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudFingerprintReferenceFetcher: fetch failed or timed out for $episodeUuid")
            Attempt.Done(null)
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
            matcher.add(checkpoint.timestampSeconds, hashes)
        }
        return matcher
    }
}
