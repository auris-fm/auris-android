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
        // A turn is admitted only under the window that is CURRENT. The FIRST turn of a window
        // declares its own captured origin current -- nothing else would admit it, and without this a
        // live window that has simply not run a turn yet is indistinguishable from a superseded one.
        if (requiresAdoption) {
            currentOrigin = origin
            requiresAdoption = false
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

    /** Marks [origin] as the only live generation; older origins can no longer prepare a turn. */
    fun beginOrigin(origin: Long) = synchronized(lock) {
        currentOrigin = origin
        requiresAdoption = false
    }

    /**
     * True until an origin has been declared or adopted: the first prepared turn adopts its own
     * captured window, so a window that has not run a turn yet is not read as superseded.
     */
    private var requiresAdoption = true

    /** Forgets the conversation, so the next turn creates a new one. */
    fun reset() = synchronized(lock) {
        handle = null
    }

    private var currentOrigin: Long = 0

    /** The origin currently declared live. Exposed so a caller with no captured window can use it. */
    fun currentOriginForTest(): Long = synchronized(lock) { currentOrigin }

    /** A prepared admission pair, ready to travel with its turn. */
    data class Prepared(val conversationId: String, val mode: CloudConversationMode)
}
