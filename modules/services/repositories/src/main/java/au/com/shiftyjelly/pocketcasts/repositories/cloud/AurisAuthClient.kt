package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * Auth endpoints for the edge identity contract (task #33): exchange the
 * account credential the app already holds for an Auris access token, and
 * rotate the refresh token.
 *
 * Classification is the whole point: 401 is a definitive answer (re-acquire
 * from the credential), anything else is inconclusive and must fail closed.
 */
class AurisAuthClient(
    private val baseUrl: String,
    private val okHttpClient: OkHttpClient = SHARED,
) {
    suspend fun exchange(credential: String, device: AurisDeviceInfo? = null): AurisAuthResult = post(AuthPaths.TOKEN, CloudRouteJson.aurisTokenRequestAdapter.toJson(AurisTokenRequest(credential, device)))

    suspend fun refresh(refreshToken: String): AurisAuthResult = post(AuthPaths.REFRESH, CloudRouteJson.aurisRefreshRequestAdapter.toJson(AurisRefreshRequest(refreshToken)))

    private suspend fun post(path: String, body: String): AurisAuthResult = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        if (base.isBlank()) return@withContext AurisAuthResult.Unavailable
        val request = Request.Builder()
            .url(base + path)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> AurisAuthResult.Unauthorized(errorCode(response.body.string()))

                    response.isSuccessful -> {
                        val tokens = runCatching {
                            CloudRouteJson.aurisTokensAdapter.fromJson(response.body.string())
                        }.getOrNull()
                        if (tokens == null) {
                            Timber.w("AurisAuth: unparseable success response for %s", path)
                            AurisAuthResult.Unavailable
                        } else {
                            AurisAuthResult.Success(tokens)
                        }
                    }

                    else -> {
                        Timber.w("AurisAuth: status %s for %s", response.code, path)
                        AurisAuthResult.Unavailable
                    }
                }
            }
        } catch (error: IOException) {
            Timber.w(error, "AurisAuth: transport failure for %s", path)
            AurisAuthResult.Unavailable
        }
    }

    private fun errorCode(body: String): String? = runCatching {
        CloudRouteJson.aurisAuthErrorAdapter.fromJson(body)?.code
    }.getOrNull()

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val TIMEOUT_SECONDS = 10L

        private val SHARED: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

/** Auth endpoint paths, relative to the configured Auris API base URL. */
internal object AuthPaths {
    const val TOKEN = "/api/v1/auth/token"
    const val REFRESH = "/api/v1/auth/refresh"
}
