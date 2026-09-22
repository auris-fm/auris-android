package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/** Seam so the playback-start observer can be tested without a network client. */
interface CloudPrefetchHinter {
    suspend fun prefetch(episodeId: String, podcastId: String? = null): CloudPrefetchClient.Outcome
}

/**
 * Best-effort prefetch hint fired on playback start
 * (`POST /api/v1/cloud/context/prefetch`, cloud-assistant.md).
 *
 * Contract: it never blocks playback, is attempted at most once per
 * playback start (no retry loop), and any failure is swallowed — a skipped or
 * failed prefetch never consumes a conversational turn.
 */
class CloudPrefetchClient(
    private val baseUrl: String,
    private val userId: String,
    private val okHttpClient: OkHttpClient = sharedClient(),
) : CloudPrefetchHinter {
    /** Result of a best-effort prefetch hint; informational only. */
    enum class Outcome { ACCEPTED, SKIPPED, NOT_SENT }

    /**
     * Fires the hint for [episodeId]. Returns the server's outcome when it
     * answered, [Outcome.NOT_SENT] when nothing was attempted (missing
     * configuration/identity) or the attempt failed. Never throws.
     */
    override suspend fun prefetch(episodeId: String, podcastId: String?): Outcome = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        if (base.isBlank() || userId.isBlank() || episodeId.isBlank()) return@withContext Outcome.NOT_SENT

        val body = CloudRouteJson.prefetchRequestAdapter.toJson(
            CloudPrefetchRequest(episodeId = episodeId, podcastId = podcastId),
        )
        val request = Request.Builder()
            .url(base + PREFETCH_PATH)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $userId")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> {
                        Timber.w("CloudPrefetch: unauthorized")
                        Outcome.NOT_SENT
                    }

                    response.code == 400 -> {
                        Timber.w("CloudPrefetch: invalid request")
                        Outcome.NOT_SENT
                    }

                    response.isSuccessful -> {
                        val parsed = runCatching {
                            CloudRouteJson.prefetchResponseAdapter.fromJson(response.body.string())
                        }.getOrNull()
                        when (parsed?.status) {
                            STATUS_ACCEPTED -> Outcome.ACCEPTED
                            STATUS_SKIPPED -> Outcome.SKIPPED
                            else -> Outcome.NOT_SENT
                        }
                    }

                    else -> {
                        // Any other status is a best-effort miss: no retry.
                        Timber.w("CloudPrefetch: status %s", response.code)
                        Outcome.NOT_SENT
                    }
                }
            }
        } catch (error: IOException) {
            // Best effort: a failed hint must never surface to the user.
            Timber.w(error, "CloudPrefetch: hint failed")
            Outcome.NOT_SENT
        }
    }

    companion object {
        private const val PREFETCH_PATH = "/api/v1/cloud/context/prefetch"
        private const val STATUS_ACCEPTED = "accepted"
        private const val STATUS_SKIPPED = "skipped"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Short ceiling so a hint can never outlive the playback start. */
        private const val PREFETCH_TIMEOUT_MS = 5_000L

        // Shared with the route client's profile: the hint must not pay a
        // fresh TLS handshake on every playback start.
        private val SHARED: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(PREFETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        private fun sharedClient(): OkHttpClient = SHARED
    }
}

@JsonClass(generateAdapter = true)
internal data class CloudPrefetchRequest(
    @Json(name = "episode_id") val episodeId: String,
    @Json(name = "podcast_id") val podcastId: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class CloudPrefetchResponse(
    @Json(name = "status") val status: String? = null,
)
