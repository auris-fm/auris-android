package au.com.shiftyjelly.pocketcasts.repositories.cloud

import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CloudRouteClient(
    private val baseUrl: String,
    private val userId: String,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
) {
    @VisibleForTesting
    internal val connectTimeoutSeconds: Long
        get() = okHttpClient.connectTimeoutMillis / 1000L

    @VisibleForTesting
    internal val readTimeoutSeconds: Long
        get() = okHttpClient.readTimeoutMillis / 1000L

    fun route(request: String, context: CloudRouteContext): Flow<CloudRouteEvent> = flow {
        val bodyJson = CloudRouteJson.requestBodyAdapter.toJson(
            CloudRouteRequestBody(request = request, context = context),
        )
        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + ROUTE_PATH)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $userId")
            .header("Accept", "text/event-stream")
            .build()

        val call = okHttpClient.newCall(httpRequest)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        val events = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext listOf(
                            preStreamError(response.code, response.body.string()),
                        )
                    }

                    val reader = InputStreamReader(response.body.byteStream())
                    val parseResult = CloudRouteSseParser().parse(reader.buffered())
                    buildList<CloudRouteEvent> {
                        addAll(parseResult.events)
                        if (parseResult.truncated) {
                            add(
                                CloudRouteEvent.Error(
                                    code = "connection_lost",
                                    message = "Connection lost",
                                ),
                            )
                        }
                    }
                }
            } catch (error: IOException) {
                if (!call.isCanceled()) {
                    currentCoroutineContext().ensureActive()
                }
                if (call.isCanceled()) {
                    throw CancellationException("Cloud route cancelled", error)
                }
                listOf(
                    CloudRouteEvent.Error(
                        code = "connection_lost",
                        message = error.message ?: "Connection lost",
                    ),
                )
            }
        }

        for (event in events) {
            currentCoroutineContext().ensureActive()
            emit(event)
        }
    }

    private fun preStreamError(httpStatus: Int, body: String): CloudRouteEvent.Error {
        val parsed = body.takeIf { it.isNotBlank() }?.let {
            runCatching { CloudRouteJson.httpErrorAdapter.fromJson(it) }.getOrNull()
        }
        val code = parsed?.code
            ?: parsed?.error
            ?: httpStatusToCode(httpStatus)
        val message = parsed?.message ?: defaultMessageForStatus(httpStatus)
        return CloudRouteEvent.Error(code = code, message = message)
    }

    private fun httpStatusToCode(httpStatus: Int): String = when (httpStatus) {
        400 -> "invalid_request"
        401 -> "unauthorized"
        else -> "http_$httpStatus"
    }

    private fun defaultMessageForStatus(httpStatus: Int): String = when (httpStatus) {
        400 -> "Invalid request"
        401 -> "Unauthorized"
        else -> "HTTP $httpStatus"
    }

    companion object {
        private const val ROUTE_PATH = "/api/v1/cloud/route"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
