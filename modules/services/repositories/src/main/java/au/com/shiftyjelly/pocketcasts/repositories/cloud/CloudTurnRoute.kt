package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import timber.log.Timber

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
    private val observer: CloudTurnTransportObserver? = null,
    /**
     * Holds connections opened early, before the turn existed.
     *
     * Null disables adoption: every turn dials for itself, exactly as before this seam existed.
     */
    private val earlyConnections: EarlyConnectionOpener? = null,
) {
    fun route(turn: CloudRouteTurn): Flow<CloudRouteEvent> = flow {
        // Fail closed, and fail loudly: no token means no request is attempted.
        val token = tokenProvider.currentToken()
        if (token.isNullOrBlank()) {
            observer?.onAuthResolved(turn.requestId, CloudTurnTransportObserver.AuthOutcome.MISSING_TOKEN)
            emit(CloudRouteEvent.Error(code = CloudRouteErrorCodes.UNAUTHORIZED, message = ""))
            return@flow
        }
        observer?.onAuthResolved(turn.requestId, CloudTurnTransportObserver.AuthOutcome.RESOLVED)

        val frame = CloudTurnFrame.Authenticate(
            accessToken = token,
            requestId = turn.requestId,
            request = turn.request,
            sourceLanguage = turn.sourceLanguage,
            context = turn.context,
            capabilities = turn.capabilities,
            codecs = CloudRouteCodecs.advertised,
            routeHint = turn.routeHint,
            // Carried from the turn the caller prepared, not derived here: a route that looked up
            // "the current conversation" at send time would let a retry or a superseded origin borrow
            // whichever handle happened to be current.
            conversationId = turn.conversationId,
            conversationMode = turn.conversationMode,
        )
        // Adopt the connection opened for THIS utterance, if one is still held and usable.
        //
        // Keyed on the turn's own identity, so a turn can never borrow a connection opened for
        // another utterance. `takeFor` returns null when nothing was held, the handle belonged
        // elsewhere, it was already spent, or it aged past the staleness bound -- and every one of
        // those means the same thing here: dial for yourself. A stale handle is discarded silently,
        // because falling back to a dial is not an error the user should ever see.
        val adopted = earlyConnections?.let { opener ->
            try {
                opener.takeFor(turn.requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // An early connection is only ever an optimisation: if the opener misbehaves, the
                // turn must still dial for itself rather than fail.
                Timber.w(t, "Early connection could not be taken")
                null
            }
        }
        // Adoption lives on the concrete transport: it is the thing that owns a socket, and the
        // one-method interface exists so tests can substitute a transport wholesale. An injected
        // transport dials its own way and adoption does not apply to it.
        val socket = transport ?: WebSocketCloudTurnTransport(baseUrl, okHttpClient, observer).also {
            if (adopted != null) it.adopt(adopted)
        }
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
