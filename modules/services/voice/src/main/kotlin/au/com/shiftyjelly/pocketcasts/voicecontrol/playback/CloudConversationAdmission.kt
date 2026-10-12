package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
            prepared.filterValues { it.capturedOrigin != null && it.capturedOrigin != origin }
                .keys.toList()
                .forEach { prepared.remove(it) }
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
        if (!isWakeOriginated && isLiveGraceTurn && existing != null) {
            Prepared(existing, CloudConversationMode.Resume)
        } else {
            // A new conversation: the handle is minted HERE and remembered, so every later turn in it
            // resumes the same one.
            val created = UUID.randomUUID().toString()
            handle = created
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
        val existing = handle ?: return null
        Prepared(existing, CloudConversationMode.Resume)
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
        val superseded = prepared.filterValues { it.capturedOrigin != null && it.capturedOrigin != origin }
            .keys.toList()
        superseded.forEach { prepared.remove(it) }
        currentOrigin = origin
        superseded
    }

    /** Forgets the conversation, so the next turn creates a new one. */
    fun reset() = synchronized(lock) {
        handle = null
    }

    private var currentOrigin: Long = 0

    /** Prepared turns by logical request, so a retry reuses the turn its first attempt fixed. */
    private val prepared =
        mutableMapOf<String, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()

    /**
     * Retires every prepared turn belonging to [endedGeneration], reporting what it retired.
     *
     * The window-ended lifecycle: a window can end by privacy close, route change or timeout, and
     * requests issued under it stop being retryable in all three. Callers that only advance windows
     * (`beginOrigin`) cover replacement; this covers the endings that are not a replacement.
     */
    fun endWindow(endedGeneration: Long): List<String> = synchronized(lock) {
        val retired = prepared.filterValues { it.capturedOrigin == endedGeneration }.keys.toList()
        retired.forEach { prepared.remove(it) }
        retired
    }

    /** The turn already prepared for [logicalRequestId], when a retry names one. */
    fun retained(logicalRequestId: String): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? = synchronized(lock) { prepared[logicalRequestId] }

    /** Retains [turn] for [logicalRequestId] until it is no longer retryable. */
    fun retain(logicalRequestId: String, turn: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn) {
        synchronized(lock) { prepared[logicalRequestId] = turn }
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
        synchronized(lock) { prepared.remove(logicalRequestId) }
    }

    /** How many turns are currently retained. Test visibility for the lifecycle contract. */
    fun retainedCount(): Int = synchronized(lock) { prepared.size }

    /** The origin currently declared live. Exposed so a caller with no captured window can use it. */
    fun currentOriginForTest(): Long = synchronized(lock) { currentOrigin }

    /** A prepared admission pair, ready to travel with its turn. */
    data class Prepared(val conversationId: String, val mode: CloudConversationMode)
}
