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

    /**
     * Conditional fetch (delta: fingerprint revalidation). When [cachedEtag] is non-null it is
     * sent as If-None-Match; the server answers 304 (stored reference still current) or 200
     * (fresh payload with its new ETag). Every failure collapses to [Fetch.Unavailable] so the
     * caller degrades exactly as before.
     */
    sealed interface Fetch {
        /** A 200: new payload, decoded matcher, and the ETag to store for the next revalidation. */
        data class Fresh(val matcher: CloudReferenceMatcher, val payload: ByteArray, val etag: String?) : Fetch

        /** A 304 against [cachedEtag]: keep the stored payload; [etag] is the validated one. */
        data class NotModified(val etag: String) : Fetch

        /** Anything else: no token, network failure, non-success status, undecodable body. */
        data object Unavailable : Fetch
    }

    suspend fun fetchReference(
        baseUrl: String,
        episodeUuid: String,
        podcastUuid: String,
        cachedEtag: String? = null,
    ): Fetch {
        // The SAME credential the turn route presents, never the TOFU user_<uuid>: generating a
        // reference is paid work, so it needs a verified token.
        // FAIL CLOSED: no usable token means no request at all.
        val token = tokenProvider.currentToken() ?: return Fetch.Unavailable
        val first = withContext(Dispatchers.IO) { fetchReferenceOnce(baseUrl, episodeUuid, podcastUuid, token, cachedEtag) }
        if (first !is Attempt.Unauthorized) return first.toFetch()
        // The route says this token is bad, not merely old: force a replacement and repeat the
        // request ONCE -- the turn route's posture, with the revalidation etag preserved.
        // A second refusal gives up rather than looping.
        tokenProvider.refreshToken()
        val replacement = tokenProvider.currentToken() ?: return Fetch.Unavailable
        val second = withContext(Dispatchers.IO) { fetchReferenceOnce(baseUrl, episodeUuid, podcastUuid, replacement, cachedEtag) }
        return second.toFetch()
    }

    /** One request's outcome; a 401 is distinguishable so the caller can retry once. */
    private sealed interface Attempt {
        /** matcher null = any non-304 non-401 outcome without a usable reference. */
        data class Done(val matcher: CloudReferenceMatcher?, val payload: ByteArray?, val etag: String?) : Attempt
        data class NotModified(val etag: String) : Attempt
        data object Unauthorized : Attempt
    }

    private fun Attempt.toFetch(): Fetch {
        if (this is Attempt.Done && matcher != null && payload != null) return Fetch.Fresh(matcher, payload, etag)
        if (this is Attempt.NotModified) return Fetch.NotModified(etag)
        return Fetch.Unavailable
    }

    private fun deadAttempt(): Attempt = Attempt.Done(null, null, null)

    private fun fetchReferenceOnce(
        baseUrl: String,
        episodeUuid: String,
        podcastUuid: String,
        token: String,
        cachedEtag: String?,
    ): Attempt {
        // podcast_id lets the server resolve uncached episodes through the public cache host,
        // which keys metadata by podcast + episode.
        val url = (baseUrl.trimEnd('/') + "/api/v1/episodes/" + episodeUuid + "/fingerprints")
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("podcast_id", podcastUuid)
            ?.build()
            ?: return deadAttempt()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + token)
            .also { if (cachedEtag != null) it.header("If-None-Match", cachedEtag) }
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

                    response.code == 304 -> {
                        val etag = response.header("ETag") ?: cachedEtag
                        if (etag == null) {
                            // A 304 without any etag to validate against cannot be trusted.
                            deadAttempt()
                        } else {
                            Timber.d("CloudFingerprintReferenceFetcher: 304 not modified for $episodeUuid")
                            Attempt.NotModified(etag)
                        }
                    }

                    !response.isSuccessful -> {
                        // Definitive statuses (e.g. 404 = no reference) are not
                        // timeouts — log them as what they are.
                        Timber.w("CloudFingerprintReferenceFetcher: status ${response.code} for $episodeUuid")
                        deadAttempt()
                    }

                    else -> {
                        val payload = response.body.bytes()
                        val matcher = buildMatcher(payload)
                        // Diagnosability (spec: a failed retest must be readable
                        // from the phone alone): one line per fetch outcome.
                        if (matcher == null) {
                            Timber.w("CloudFingerprintReferenceFetcher: 200 but undecodable reference (${payload.size} bytes) for $episodeUuid")
                            Attempt.Done(null, null, null)
                        } else {
                            Timber.d("CloudFingerprintReferenceFetcher: fetched ${payload.size} bytes, ${matcher.count} checkpoints for $episodeUuid")
                            Attempt.Done(matcher, payload, response.header("ETag"))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudFingerprintReferenceFetcher: fetch failed or timed out for $episodeUuid")
            deadAttempt()
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
