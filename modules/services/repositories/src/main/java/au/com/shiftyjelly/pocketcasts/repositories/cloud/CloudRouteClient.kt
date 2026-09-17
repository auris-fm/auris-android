package au.com.shiftyjelly.pocketcasts.repositories.cloud

import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * Streams the gateway's SSE turn as events, not as a buffered list: tokens
 * and `play_quote` actions are emitted as they arrive so the turn can play
 * mid-response instead of after it.
 */
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

    fun route(request: String, context: CloudRouteContext): Flow<CloudRouteEvent> = channelFlow {
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

        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        send(preStreamError(response.code, response.body.string()))
                        return@withContext
                    }

                    val reader = InputStreamReader(response.body.byteStream())
                    // A throw out of the parser is a protocol/payload problem,
                    // not a transport one — give it its own code so it cannot
                    // be mistaken for a network failure (the outer catch maps
                    // IOException to connection_lost).
                    val truncated = try {
                        CloudRouteSseParser().parse(reader.buffered()) { event ->
                            // Blocks the SSE reader when the collector is slow,
                            // which is the desired backpressure shape.
                            trySendBlocking(event).getOrThrow()
                        }
                    } catch (error: IOException) {
                        // Parser detail goes to the log; the user hears a
                        // fixed, speakable line — never parser internals.
                        Timber.w(error, "Cloud route payload could not be parsed")
                        send(
                            CloudRouteEvent.Error(
                                code = "invalid_response",
                                message = "Sorry, I couldn't understand the response.",
                            ),
                        )
                        return@withContext
                    }
                    if (truncated) {
                        send(
                            CloudRouteEvent.Error(
                                code = "connection_lost",
                                message = "Connection lost",
                            ),
                        )
                    }
                }
            } catch (error: IOException) {
                // Answers "did the *collector* cancel us?" — a callTimeout
                // cancels the OkHttp call too, so call.isCanceled() alone
                // cannot distinguish the two.
                currentCoroutineContext().ensureActive()
                if (call.isCanceled()) {
                    // Call timeout (or external cancel of the call): the turn
                    // ended without a verdict — surface it as an error event
                    // so analytics and the user both see it.
                    send(
                        CloudRouteEvent.Error(
                            code = "connection_lost",
                            message = "Request timed out",
                        ),
                    )
                } else {
                    send(
                        CloudRouteEvent.Error(
                            code = "connection_lost",
                            message = error.message ?: "Connection lost",
                        ),
                    )
                }
            }
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

        fun defaultOkHttpClient(): OkHttpClient = SHARED

        // One client (connection pool, dispatcher, threads) for the process
        // lifetime: constructing a client per turn leaked both handshakes and
        // worker threads.
        private val SHARED = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
