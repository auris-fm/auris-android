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

    /** Marks [origin] as the only live generation; older origins can no longer prepare a turn. */
    fun beginOrigin(origin: Long) = synchronized(lock) {
        currentOrigin = origin
    }

    /** Forgets the conversation, so the next turn creates a new one. */
    fun reset() = synchronized(lock) {
        handle = null
    }

    private var currentOrigin: Long = 0

    /** Prepared turns by logical request, so a retry reuses the turn its first attempt fixed. */
    private val prepared =
        mutableMapOf<String, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()

    /** The turn already prepared for [logicalRequestId], when a retry names one. */
    fun retained(logicalRequestId: String): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? = synchronized(lock) { prepared[logicalRequestId] }

    /** Retains [turn] for [logicalRequestId] until it is no longer retryable. */
    fun retain(logicalRequestId: String, turn: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn) {
        synchronized(lock) { prepared[logicalRequestId] = turn }
    }

    /** The origin currently declared live. Exposed so a caller with no captured window can use it. */
    fun currentOriginForTest(): Long = synchronized(lock) { currentOrigin }

    /** A prepared admission pair, ready to travel with its turn. */
    data class Prepared(val conversationId: String, val mode: CloudConversationMode)
}
