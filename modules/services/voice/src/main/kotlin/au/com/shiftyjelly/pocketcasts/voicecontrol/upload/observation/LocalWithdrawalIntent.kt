package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState

/** The persistence seam: real implementations write app-private storage, never memory only. */
interface WithdrawalIntentPersistence {
    fun read(): Boolean

    fun write(requested: Boolean)
}

/**
 * DURABLE LOCAL WITHDRAWAL INTENT (reviewer 9156a8ea).
 *
 * The gap this closes: turning sharing off purges the queue, but purging only removes the entries
 * that exist at that instant. The authority's permission still came from the SERVER projection,
 * which keeps saying `voiceSharingEnrolled == true` while a withdrawal is inline or after it fails.
 * The next captured segment therefore obtained the new generation and the user who had just said
 * stop went on contributing — until the server happened to agree.
 *
 * The user's decision is the authority for STOPPING. It is recorded durably BEFORE the request goes
 * out, it survives process death, and it is only released when the user explicitly opts in again.
 * The server remains authoritative for GRANTING: a stale server "enrolled" can never outvote a
 * fresh local "stop", but a local "stop" never grants anything either.
 */
class LocalWithdrawalIntentStore(private val persistence: WithdrawalIntentPersistence) {

    /** Recorded BEFORE the network call, so no window exists in which collection could resume. */
    fun recordWithdrawalRequested() {
        persistence.write(true)
    }

    /**
     * Collection is permitted locally only when the user has NOT asked to stop. The server
     * projection is consulted by the caller; this answers the narrower, overriding question.
     */
    fun collectionPermittedByLocalIntent(projection: VoiceSharingAccountProjection?): Boolean {
        if (persistence.read()) return false
        if (projection == null) return false
        return projection.voiceSharingEnrolled && projection.withdrawalState == WithdrawalState.ACTIVE
    }

    /**
     * The server now reflects the withdrawal. See the body: the intent is KEPT, not cleared.
     *
     * My first version cleared here on the reasoning that a withdrawn projection cannot be collected
     * against. That is true only while the projection stays withdrawn — and projections go stale, so
     * a later enrolled read would have resumed collection with nothing left to veto it. The
     * composition control caught it.
     */
    fun onServerAgrees() {
        // DELIBERATELY DOES NOT CLEAR. Clearing on server agreement re-opens the exact window this
        // store exists to close: the server can serve a STALE "enrolled" projection after applying a
        // withdrawal, and with the flag gone that projection would resume collection on its own. Only
        // an explicit new opt-in (clearOnUserEnrollment) may release the stop, because that is a fresh
        // statement of consent rather than an inference from server state.
        //
        // The stop is already durable and correct while the withdrawal stands; keeping it costs
        // nothing, and clearing it would be a belief about the server that this class has no way to
        // verify.
        persistence.write(true)
    }

    /**
     * A failed server withdrawal leaves the intent in place. The user asked to stop; a network
     * failure is not consent to resume, and collection stays stopped until they opt in again.
     */
    fun onWithdrawalFailed() {
        // Deliberately a no-op with a name, so deleting this does not read as tightening a screen.
        persistence.write(true)
    }

    /** An explicit new opt-in is a fresh user action and releases the local stop. */
    fun clearOnUserEnrollment() {
        persistence.write(false)
    }
}
