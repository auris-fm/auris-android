package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    private val observer: CloudTurnTransportObserver? = null,
) {
    /**
     * Opens THE turn: its authenticated snapshot, its events and its result sender, produced together
     * from one transport and one exchange.
     *
     * Everything a v1 operation needs is captured here, once, into an immutable object the sink holds
     * locally in its collection closure. Nothing about a turn is read back from a shared field at
     * delivery time, so two overlapping turns cannot bind each other's owner, conversation or socket.
     *
     * The snapshot is also the answer to "which account does this token belong to": token and account
     * are resolved in one call, and when the provider cannot associate them the snapshot's account is
     * null, which every caller must treat as refuse-v1 rather than execute-under-a-guess.
     */
    suspend fun openTurn(turn: CloudRouteTurn): Turn {
        // ONE read of the credential: the token and its account come from the same association, so an
        // account switch between two awaits cannot pair this turn's token with another account.
        val credential = tokenProvider.currentCredential()
        val token = credential.token
        if (token.isNullOrBlank()) {
            observer?.onAuthResolved(turn.requestId, CloudTurnTransportObserver.AuthOutcome.MISSING_TOKEN)
            return Turn(
                accountId = null,
                session = CloudTurnTransport.TurnSession(
                    events = flowOf(CloudRouteEvent.Error(code = CloudRouteErrorCodes.UNAUTHORIZED, message = "")),
                    sender = CloudTurnTransport.NO_RESULTS,
                ),
            )
        }
        observer?.onAuthResolved(turn.requestId, CloudTurnTransportObserver.AuthOutcome.RESOLVED)
        val accountId = credential.accountId
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
        val transport = this.transport ?: WebSocketCloudTurnTransport(baseUrl, okHttpClient, observer)
        return Turn(accountId = accountId, session = transport.openTurn(frame))
    }

    /**
     * One turn, immutable: the account its token belongs to, and the socket session carrying both its
     * events and its sender. Handed to the sink, which captures it locally.
     */
    class Turn(
        /** The authenticated account, or null when it could not be established (refuse v1). */
        val accountId: String?,
        val session: CloudTurnTransport.TurnSession,
    )

    /** Runs the turn over the socket [openTurn] opened. Prefer [openTurn], which keeps sender and events together. */
    fun route(turn: CloudRouteTurn): Flow<CloudRouteEvent> = flow { emitAll(openTurn(turn).session.events) }

    private fun exchange(
        turn: CloudRouteTurn,
        socket: CloudTurnTransport,
    ): Flow<CloudRouteEvent> = flow {
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
        // Everything the socket reports is the turn's outcome - including a refused upgrade, which
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
