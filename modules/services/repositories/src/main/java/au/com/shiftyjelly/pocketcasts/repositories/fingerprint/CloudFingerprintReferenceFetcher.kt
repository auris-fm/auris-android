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
import uniffi.fingerprint_uniffi.CheckpointMatcher
import timber.log.Timber

/**
 * Fetches the Auris cloud reference fingerprints for an episode from
 * `GET /api/v1/episodes/{episode_uuid}/fingerprints` (cloud-ingestion.md) and
 * parses the `fingerprint-compact-v3` payload into the inherited library's [CheckpointMatcher].
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
        /** A 200: a decodable payload with usable checkpoints, and the ETag for the next revalidation. */
        data class Fresh(val payload: ByteArray, val etag: String?) : Fetch

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
        val token = tokenProvider.currentToken() ?: run {
            Timber.d("CloudFingerprintReferenceFetcher: skipped fetch — no verified token for $episodeUuid")
            return Fetch.Unavailable
        }
        val first = withContext(Dispatchers.IO) { fetchReferenceOnce(baseUrl, episodeUuid, podcastUuid, token, cachedEtag) }
        if (first !is Attempt.Unauthorized) return first.toFetch()
        // The route says this token is bad, not merely old: force a replacement and repeat the
        // request ONCE -- the turn route's posture, with the revalidation etag preserved.
        // A second refusal gives up rather than looping.
        Timber.d("CloudFingerprintReferenceFetcher: 401, refreshing token once for $episodeUuid")
        tokenProvider.refreshToken()
        val replacement = tokenProvider.currentToken() ?: run {
            Timber.d("CloudFingerprintReferenceFetcher: skipped retry — no replacement token for $episodeUuid")
            return Fetch.Unavailable
        }
        val second = withContext(Dispatchers.IO) { fetchReferenceOnce(baseUrl, episodeUuid, podcastUuid, replacement, cachedEtag) }
        return second.toFetch()
    }

    /** One request's outcome; a 401 is distinguishable so the caller can retry once. */
    private sealed interface Attempt {
        /** payload null = any non-304 non-401 outcome without a usable reference. */
        data class Done(val payload: ByteArray?, val etag: String?) : Attempt
        data class NotModified(val etag: String) : Attempt
        data object Unauthorized : Attempt
    }

    private fun Attempt.toFetch(): Fetch {
        if (this is Attempt.Done && payload != null) return Fetch.Fresh(payload, etag)
        if (this is Attempt.NotModified) return Fetch.NotModified(etag)
        return Fetch.Unavailable
    }

    private fun deadAttempt(): Attempt = Attempt.Done(null, null)

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
            ?: run {
                Timber.w("CloudFingerprintReferenceFetcher: skipped fetch — unparseable base URL for $episodeUuid")
                return deadAttempt()
            }
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
                    response.code == 401 -> {
                        Timber.w("CloudFingerprintReferenceFetcher: status 401 for $episodeUuid")
                        Attempt.Unauthorized
                    }

                    response.code == 304 -> {
                        val etag = response.header("ETag") ?: cachedEtag
                        if (etag == null) {
                            // A 304 without any etag to validate against cannot be trusted.
                            Timber.w("CloudFingerprintReferenceFetcher: 304 without ETag, unusable for $episodeUuid")
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
                        // Decodability is decided here; building the matcher is the caller's job,
                        // because that needs the native library.
                        val usable = ReferenceFingerprint.decode(payload, ReferenceFingerprint.AURIS_FORMAT)?.libraryCheckpoints()?.isNotEmpty() == true
                        // Diagnosability (spec: a failed retest must be readable
                        // from the phone alone): one line per fetch outcome.
                        if (!usable) {
                            Timber.w("CloudFingerprintReferenceFetcher: 200 but undecodable reference (${payload.size} bytes) for $episodeUuid")
                            deadAttempt()
                        } else {
                            Timber.d("CloudFingerprintReferenceFetcher: fetched ${payload.size} bytes for $episodeUuid")
                            Attempt.Done(payload, response.header("ETag"))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudFingerprintReferenceFetcher: fetch failed or timed out for $episodeUuid")
            deadAttempt()
        }
    }

    /** Parses compact-v3 bytes into the inherited library's matcher, or null when unusable. */
    internal fun buildMatcher(data: ByteArray): CheckpointMatcher? {
        val reference = ReferenceFingerprint.decode(data, ReferenceFingerprint.AURIS_FORMAT) ?: return null
        val checkpoints = reference.libraryCheckpoints()
        if (checkpoints.isEmpty()) return null

        val matcher = CheckpointMatcher()
        for (checkpoint in checkpoints) {
            // The values are a TIME-ORDERED sequence with duplicates kept: the library compares
            // position by position, so sorting or deduplicating here would change every score.
            // libraryCheckpoints decodes them as signed Ints holding the uint32 bit pattern.
            matcher.add(
                checkpoint.timestampSeconds,
                checkpoint.hashes.map { it.toUInt() },
                reference.checkpointDurationSeconds,
            )
        }
        return matcher
    }
}
