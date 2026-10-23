package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState

/**
 * ONE account+epoch-bound credential authority (reviewer 064d5a00 / spec 6480d0c5): the account
 * identity, session, credential and owner-published token are read under the REAL owner lock in a
 * single transaction, together with the epoch projection. The projection read and the token come
 * from the SAME transaction - never two separate reads labelled atomic - so a concurrent account
 * switch can only be observed wholly before or wholly after: a mixture (old bearer, new epoch) is
 * unconstructible by the lock, and the control proves it.
 */
class UploadIdentityAuthority(
    private val state: VoiceSharingAccountState,
    private val underOwnerTransaction: ((AuthoritySnapshot) -> Pair<String, String>?) -> Pair<String, String>?,
) {
    /** (bearer, epoch) for [admittedEpoch], or null when the live identity is not that epoch. */
    fun credentialFor(admittedEpoch: String): Pair<String, String>? = underOwnerTransaction { snapshot ->
        val projection = state.projection.value
        val token = snapshot.token
        when {
            projection?.enrollmentEpoch != admittedEpoch -> null
            token == null -> null
            else -> token.value to projection.enrollmentEpoch
        }
    }
}
