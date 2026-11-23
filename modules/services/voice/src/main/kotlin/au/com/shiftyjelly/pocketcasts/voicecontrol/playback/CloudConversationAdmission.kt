package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

/**
 * Prepares the admission pair a turn carries: which conversation it belongs to, and whether it creates
 * or resumes it.
 *
 * The handle is CLIENT-generated (the owning request table's "client-generated conversation handle"),
 * so CREATE carries the UUID this client made -- there is no server-minted handle to wait for, and no
 * round trip before the first turn can be sent.
 *
 * PREPARED HERE, SERIALIZED BY THE SINK. The sink must not resolve "the current conversation" when it
 * finally sends, because a retry or a superseded origin would then borrow whichever handle happened to
 * be current at send time. The pair is therefore fixed at the moment the turn is prepared and travels
 * with the turn.
 */
@Singleton
class CloudConversationAdmission @Inject constructor() {
    private val lock = Any()

    /** The conversation in progress, or null when the next turn will create one. */
    private var handle: String? = null

    /**
     * The id most recently MINTED by this client that the worker has not yet acknowledged with
     * `connected`, or null once it has been.
     *
     * The handle is client-generated, so a create that dies before the handshake leaves a conversation
     * the worker never made. Resuming it would ask to continue something that does not exist. While this
     * is set the conversation is NOT resumable, and a follow-up creates a fresh id instead -- the rule in
     * the shared contract. Cleared by [acknowledge], which the sink calls on `connected`.
     */
    private var pending: String? = null

    /**
     * The pair for a turn being prepared under [origin], or null when this origin has been superseded
     * and must not be admitted at all.
     *
     * @param origin the caller's generation at the moment the turn was captured. A turn whose origin is
     * no longer current is DISCARDED rather than admitted: it would otherwise create or resume a
     * conversation on behalf of a turn that has already been superseded.
     * @param isLiveGraceTurn true when this turn continues an answer still inside its grace window. A
     * live grace turn RESUMES; anything else begins a new conversation and CREATES.
     */
    fun prepare(origin: Long, isLiveGraceTurn: Boolean, isWakeOriginated: Boolean): Prepared? = synchronized(lock) {
        // A turn is admitted only under a window that is LIVE. Two cases admit one:
        //
        //   the same window as the last admitted turn  -> it is current, so the turn may act
        //   a NEWER window                            -> it supersedes the old one and is adopted
        //
        // A NEWER WINDOW RETIRES WHAT THE OLDER ONES WERE HOLDING, on EVERY path that admits a turn --
        // not only the one the sink takes for an unstamped request. Normal stamped ASR preparation
        // arrives HERE, and a request from a superseded window stops being retryable the moment a newer
        // one is admitted: a retry is validated against its own window, so nothing will retry it.
        if (origin > currentOrigin) {
            retire { it.capturedOrigin != null && it.capturedOrigin != origin }
        }
        // ADOPTION IS NOT A ONE-TIME EVENT. The earlier version adopted only the very first origin it
        // ever saw, so a valid request under a LATER window was refused as stale -- a successor wake's
        // first request was dropped. A higher origin is genuinely newer, and the caller has already
        // established that its window is live and open; only a LOWER (explicitly superseded) origin is
        // refused, which is the stale case the check exists for.
        if (origin > currentOrigin) {
            currentOrigin = origin
        }
        if (origin != currentOrigin) return null
        val existing = handle
        // A WAKE ORIGIN ALWAYS STARTS A CONVERSATION: a request captured under a wake is a new user
        // act, so it creates even when an earlier handle exists. Only a wake-FREE request inside
        // the same live window may resume.
        // RESUMABLE ONLY WHEN ACKNOWLEDGED. `pending` holds an id the worker has not answered with
        // `connected`, so resuming it would continue a conversation that does not exist server-side.
        if (!isWakeOriginated && isLiveGraceTurn && existing != null && pending == null) {
            Prepared(existing, CloudConversationMode.Resume)
        } else {
            // A new conversation: the handle is minted HERE and remembered, so every later turn in it
            // resumes the same one -- but only once the worker has ACKNOWLEDGED it. `pending` records
            // that this id is not yet resumable; `handle` keeps its existing meaning so nothing else
            // about admission changes.
            val created = UUID.randomUUID().toString()
            // A CONTINUATION prepared while its predecessor is still unacknowledged does not take over the
            // handle. Overwriting it would lose the predecessor's identity, so its `connected` could never
            // promote anything and the continuation would be stuck creating a second conversation. The
            // predecessor keeps the slot; this turn carries its own id and re-decides at dispatch.
            if (!isLiveGraceTurn || isWakeOriginated || pending == null) {
                handle = created
                pending = created
            }
            Prepared(created, CloudConversationMode.Create)
        }
    }

    /**
     * Whether a RETRY under [origin] may proceed: the window must still be the current AND live one.
     *
     * A retry re-validates rather than re-admits. Re-admitting would mint a second conversation for the
     * same logical request, and skipping validation altogether would let a turn whose window has since
     * been superseded or closed still reach transport.
     *
     * Returns the admission the prepared turn already holds when the window is still valid, or null
     * when it is not.
     */
    fun validateForRetry(origin: Long): Prepared? = synchronized(lock) {
        if (origin != currentOrigin) return null
        // A LIVE WINDOW IS ENOUGH. With a conversation to resume, return it; with none, mint a fresh one
        // and record it as pending, so the turn is admitted and carries a real id.
        //
        // Refusing here instead dropped the user's follow-up outright after the worker forgot its
        // conversation: the dispatch-time conversion to a fresh CREATE never got the chance to run, and
        // the user's question was silently lost.
        // MIRRORS `prepare`'s gate: an UNACKNOWLEDGED handle is not resumable, so a turn admitted here
        // must not be handed a Resume of it. Returning Resume was masked only because routeTurn discards
        // this value for a prepared turn -- a latent disagreement waiting for the next caller.
        val existing = handle?.takeIf { pending == null }
        if (existing != null) return Prepared(existing, CloudConversationMode.Resume)
        val fresh = UUID.randomUUID().toString()
        handle = fresh
        pending = fresh
        Prepared(fresh, CloudConversationMode.Create)
    }

    /**
     * Marks [origin] as the only live generation, and RETIRES what the superseded ones were holding.
     *
     * A request is retryable while its OWN window is live. When a new window opens, requests issued
     * under the previous one stop being retryable -- nothing will retry them, because a retry is
     * validated against its window -- so their prepared turns are released here rather than kept for
     * the life of the process. This is the ordinary cloud-request lifecycle: a pending dialog does not
     * own an ordinary ASR request, so the window is what ends its retryability.
     *
     * @return the identities retired by this advance, so the owner can report or observe them.
     */
    fun beginOrigin(origin: Long): List<String> = synchronized(lock) {
        val superseded = retire { it.capturedOrigin != null && it.capturedOrigin != origin }
        currentOrigin = origin
        superseded
    }

    /**
     * This turn is going on the wire NOW, so its conversation pair is the live one.
     *
     * A CREATE establishes a conversation, so its id becomes the handle -- pending until `connected`.
     * Recording it here rather than at prepare matters for the RECOVERY path: a replacement minted after
     * a failed create was previously never stored, so its own `connected` could not promote it and every
     * later follow-up in the window kept opening fresh conversations, losing the user's context.
     *
     * Called for every dispatched turn on both entry paths (`routeToCloud` prepares and sends in one go;
     * `dispatchPrepared` sends a turn prepared earlier).
     */
    fun noteSending(turn: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn) {
        synchronized(lock) {
            if (turn.conversationMode == CloudConversationMode.Create) {
                handle = turn.conversationId
                pending = turn.conversationId
            }
        }
    }

    /**
     * The worker acknowledged [conversationId] with `connected`, so it is now RESUMABLE.
     *
     * Keyed by the id, because the handshake for an older exchange can arrive after a newer turn has
     * already minted its own conversation; clearing blindly would make the WRONG one resumable. A late
     * acknowledgement for a superseded id is therefore ignored.
     */
    fun acknowledge(conversationId: String) {
        synchronized(lock) {
            if (pending == conversationId) {
                pending = null
                handle = conversationId
            }
        }
    }

    /** Forgets the conversation, so the next turn creates a new one. */
    fun reset() = synchronized(lock) {
        handle = null
        pending = null
    }

    /**
     * Forgets the conversation the worker refused as expired, so the next turn creates a new one.
     *
     * Keyed by the refused id: a refusal can arrive after a newer conversation has begun, and it is
     * about the old one, so it must not clear the handle that replaced it.
     */
    fun forgetConversation(conversationId: String) {
        synchronized(lock) {
            if (handle == conversationId) handle = null
            // An unacknowledged create can lose the conversation too, and it must not stay pending.
            if (pending == conversationId) pending = null
        }
    }

    private var currentOrigin: Long = 0

    /** One in-memory owner of preparation and dispatch for each retained request. */
    private class RetainedRequest(
        val logicalId: String?,
        var turn: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn,
        /**
         * Whether this turn CONTINUES a live grace window, and whether a wake began it.
         *
         * Kept beside the turn rather than on the wire model: these are admission facts, not protocol
         * fields, and only the dispatch-time decision needs them.
         */
        val continuesGraceWindow: Boolean = false,
        val wakeOriginated: Boolean = false,
        var completion: CompletableDeferred<Unit>? = null,
    )

    private val prepared = mutableMapOf<String, RetainedRequest>()

    private fun retire(predicate: (au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn) -> Boolean): List<String> {
        val retired = prepared.filterValues { predicate(it.turn) }
        retired.keys.forEach { prepared.remove(it) }
        return retired.values.mapNotNull { it.logicalId }
    }

    /**
     * Retires every prepared turn belonging to [endedGeneration], reporting what it retired.
     *
     * The window-ended lifecycle: a window can end by privacy close, route change or timeout, and
     * requests issued under it stop being retryable in all three. Callers that only advance windows
     * (`beginOrigin`) cover replacement; this covers the endings that are not a replacement.
     */
    fun endWindow(endedGeneration: Long): List<String> = synchronized(lock) {
        retire { it.capturedOrigin == endedGeneration }
    }

    /** The turn already prepared for [logicalRequestId], when a retry names one. */
    fun retained(logicalRequestId: String): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? = synchronized(lock) { prepared["logical:$logicalRequestId"]?.turn }

    /** Lookup, creation and retention are atomic, so duplicate callbacks cannot mint two wire IDs. */
    internal fun retainOrPrepare(
        logicalRequestId: String?,
        isRetry: Boolean,
        continuesGraceWindow: Boolean = false,
        wakeOriginated: Boolean = false,
        create: () -> au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn?,
    ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? = synchronized(lock) {
        val key = logicalRequestId?.let { "logical:$it" }
        key?.let { prepared[it]?.let { existing -> return@synchronized existing.turn } }
        if (isRetry) return@synchronized null
        val turn = create() ?: return@synchronized null
        prepared[key ?: "wire:${turn.requestId}"] =
            RetainedRequest(logicalRequestId, turn, continuesGraceWindow, wakeOriginated)
        turn
    }

    internal class Dispatch(
        val turn: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn,
        val completion: CompletableDeferred<Unit>,
        val ownsDispatch: Boolean,
    )

    /** Duplicates join the existing completion. Only an explicit retry can start another attempt. */
    internal fun claimDispatch(turn: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn, isRetry: Boolean): Dispatch? = synchronized(lock) {
        val retained = prepared.values.firstOrNull { it.turn.requestId == turn.requestId } ?: return@synchronized null
        // THE MODE IS DECIDED HERE, AT DISPATCH, not at prepare. A follow-up can be prepared while its
        // predecessor is still on the wire -- the executor prepares and dispatches in separate calls, and
        // the predecessor is only cancelled inside the dispatch -- so the handshake may arrive in between.
        // Fixing the mode at prepare would ignore that acknowledgement and create a second conversation
        // for what is one continuation.
        //
        // A RETRY IS EXEMPT: it must resend the SAME logical request, so its id and mode stay exactly as
        // prepared. Only a first dispatch re-decides.
        if (!isRetry) {
            val resolved = resolveForDispatch(retained)
            if (resolved !== retained.turn) {
                retained.turn = resolved
            }
        }
        val previous = retained.completion
        if (previous != null && (!isRetry || !previous.isCompleted)) return@synchronized Dispatch(retained.turn, previous, false)
        val completion = CompletableDeferred<Unit>()
        retained.completion = completion
        Dispatch(retained.turn, completion, true)
    }

    /**
     * Forgets [logicalRequestId]'s turn, because it is no longer retryable.
     *
     * RETENTION MUST END. A prepared turn held indefinitely keeps an identity alive that nothing will
     * retry: a later dispatch naming it would be admitted as a retry of work that is finished, and the
     * store would grow for the life of the process. The lifecycle removes a request when its dialog is
     * abandoned, replaced, or completed without a retry pending.
     */
    fun forgetRetained(logicalRequestId: String) {
        synchronized(lock) { prepared.remove("logical:$logicalRequestId") }
    }

    /**
     * The conversation pair this turn should carry NOW, given what has been acknowledged since it was
     * prepared. Returns the same instance when nothing changed.
     *
     * Three cases, all decided against what is true NOW rather than at prepare:
     *  - a turn prepared as CREATE keeps its own minted id: it establishes the conversation;
     *  - a continuation whose handle became acknowledged since prepare adopts it and RESUMES;
     *  - a turn prepared as RESUME whose conversation has since been forgotten or superseded becomes a
     *    CREATE with a fresh id, because resuming it would fail on a conversation the worker no longer has.
     *
     * A wake-originated turn always begins a conversation, so it is never converted to a resume.
     */
    private fun resolveForDispatch(
        intent: RetainedRequest,
    ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn {
        val turn = intent.turn
        val acknowledged = handle?.takeIf { pending == null }
        if (acknowledged == null) {
            // Nothing is resumable. A prepared RESUME would ask to continue a conversation that is gone
            // (expired, forgotten, or superseded), so it becomes a fresh CREATE instead.
            if (turn.conversationMode == CloudConversationMode.Resume) {
                val fresh = UUID.randomUUID().toString()
                return turn.copy(conversationId = fresh, conversationMode = CloudConversationMode.Create)
            }
            return turn
        }
        // Only a CONTINUATION may adopt the acknowledged conversation. A wake-originated turn begins a new
        // one by contract, and that decision does not depend on acknowledgement.
        if (!intent.continuesGraceWindow || intent.wakeOriginated) return turn
        if (turn.conversationMode == CloudConversationMode.Resume && turn.conversationId == acknowledged) return turn
        return turn.copy(conversationId = acknowledged, conversationMode = CloudConversationMode.Resume)
    }

    /** The origin currently declared live. Exposed so a caller with no captured window can use it. */
    fun currentOriginForTest(): Long = synchronized(lock) { currentOrigin }

    /** A prepared admission pair, ready to travel with its turn. */
    data class Prepared(val conversationId: String, val mode: CloudConversationMode)
}
