package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient

/** The codecs this client can play, in preference order. */
object CloudRouteCodecs {
    /** Raw signed-16-bit little-endian PCM at 24 kHz — mono, what the route currently serves. */
    const val PCM_S16LE_24K = "pcm_s16le@24k"

    /** Opus at 48 kHz, decoded by the platform's MediaCodec. */
    const val OPUS_48K = "opus@48k"

    /**
     * A list is sent rather than a single codec, so the server's choice can change without a
     * client change. PCM leads because it is what the route produces today; Opus follows because
     * the decoder is real, not aspirational.
     */
    val advertised = listOf(PCM_S16LE_24K, OPUS_48K)
}

/**
 * Runs a turn over the WebSocket — the negotiated path, with **no fallback**.
 *
 * The transport rule is WebSocket only (cloud-assistant.md, owner's ruling): a refused upgrade is
 * a terminal turn failure carrying a visible error, not a reason to retry on the older transport.
 * That is deliberate while the feature is in development, and it buys the property the owner's
 * test needs: **every completed turn is evidence the negotiated path served it**, so "the socket
 * ran" and "the turn finished" stop being two facts to reconcile.
 *
 * The typed-UI flow is unaffected — a `route_hint` rides this same socket request as the first
 * frame's optional field, so a hint from a typed flow reaches the server by the one transport.
 */
class CloudTurnRoute(
    private val baseUrl: String,
    private val tokenProvider: CloudTokenProviding,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    /** Socket seam: the unit suite has no WebSocket upgrade, so the transport is injectable. */
    private val transport: CloudTurnTransport? = null,
) {
    fun route(turn: CloudRouteTurn): Flow<CloudRouteEvent> = flow {
        // Fail closed, and fail loudly: no token means no request is attempted.
        val token = tokenProvider.currentToken()
        if (token.isNullOrBlank()) {
            emit(CloudRouteEvent.Error(code = CloudRouteErrorCodes.UNAUTHORIZED, message = ""))
            return@flow
        }

        val frame = CloudTurnFrame.Authenticate(
            accessToken = token,
            requestId = turn.requestId,
            request = turn.request,
            context = turn.context,
            capabilities = turn.capabilities,
            codecs = CloudRouteCodecs.advertised,
            routeHint = turn.routeHint,
        )
        val socket = transport ?: WebSocketCloudTurnTransport(baseUrl, okHttpClient)
        // Everything the socket reports is the turn's outcome — including a refused upgrade, which
        // arrives as `connection_lost` and ends the turn rather than being retried elsewhere.
        emitAll(socket.exchange(frame))
    }

    companion object {
        fun defaultOkHttpClient(): OkHttpClient = SHARED

        // One client (connection pool, dispatcher, threads) for the process lifetime: constructing
        // one per turn leaked both handshakes and worker threads.
        private val SHARED = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
