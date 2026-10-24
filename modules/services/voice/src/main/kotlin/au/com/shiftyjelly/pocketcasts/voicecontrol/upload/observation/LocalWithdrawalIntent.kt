package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState

/** The persistence seam: real implementations write app-private storage, never memory only. */
interface WithdrawalIntentPersistence {
    fun read(): Boolean

    /**
     * Persist the marker. Returns TRUE only when the marker is durably in place.
     *
     * The result is not decoration (reviewer 60647af3): a write that reports nothing lets the caller
     * believe a stop landed when it did not, so a first withdrawal whose write fails leaves the old
     * PERMITTED marker behind and collection resumes on restart. Callers MUST treat false as
     * "the durable stop is unknown" and fail closed.
     */
    fun write(requested: Boolean): Boolean
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

    /**
     * Set when a stop could not be made durable.
     *
     * This latch lives HERE, in the object the collection gate actually asks, because a failure flag
     * anywhere else is decorative (reviewer 7a2e10a6): my previous version put it in the Settings
     * fragment, where nothing read it, and collection stayed open for the rest of the session even
     * though the user had asked to stop. A stop whose write failed is still a stop, and the only
     * entity that can enforce that is the one the gate consults.
     *
     * Cleared only by an explicit opt-in, exactly like the durable marker.
     */
    @Volatile
    private var stopCouldNotPersist: Boolean = false

    /**
     * Recorded BEFORE the network call, so no window exists in which collection could resume.
     *
     * Returns false when the marker could not be durably written, and LATCHES that failure so the
     * collection gate refuses admission even though the disk has no record of the stop.
     */
    fun recordWithdrawalRequested(): Boolean {
        val written = persistence.write(true)
        if (!written) stopCouldNotPersist = true
        return written
    }

    /**
     * Collection is permitted locally only when the user has NOT asked to stop. The server
     * projection is consulted by the caller; this answers the narrower, overriding question.
     */
    fun collectionPermittedByLocalIntent(projection: VoiceSharingAccountProjection?): Boolean {
        // A latch from a failed write outranks everything: the user asked to stop and the disk does
        // not know it, so the only safe answer is no.
        if (stopCouldNotPersist) return false
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
    fun onServerAgrees(): Boolean {
        // DELIBERATELY DOES NOT CLEAR. Clearing on server agreement re-opens the exact window this
        // store exists to close: the server can serve a STALE "enrolled" projection after applying a
        // withdrawal, and with the flag gone that projection would resume collection on its own. Only
        // an explicit new opt-in (clearOnUserEnrollment) may release the stop, because that is a fresh
        // statement of consent rather than an inference from server state.
        //
        // The stop is already durable and correct while the withdrawal stands; keeping it costs
        // nothing, and clearing it would be a belief about the server that this class has no way to
        // verify.
        return persistence.write(true)
    }

    /**
     * A failed server withdrawal leaves the intent in place. The user asked to stop; a network
     * failure is not consent to resume, and collection stays stopped until they opt in again.
     */
    fun onWithdrawalFailed(): Boolean {
        // Deliberately a no-op with a name, so deleting this does not read as tightening a screen.
        return persistence.write(true)
    }

    /** An explicit new opt-in is a fresh user action and releases the local stop. */
    fun clearOnUserEnrollment(): Boolean {
        val written = persistence.write(false)
        // Only a DURABLE clear releases the latch. A failed clear leaves the session stopped, which is
        // the safe direction and matches what the on-disk marker now says.
        if (written) stopCouldNotPersist = false
        return written
    }
}
