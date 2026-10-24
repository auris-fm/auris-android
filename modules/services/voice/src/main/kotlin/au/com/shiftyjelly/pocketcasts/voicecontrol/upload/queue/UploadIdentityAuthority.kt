package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState

/**
 * ONE account+epoch-bound credential authority (reviewer 064d5a00 / d2f1bfc8, spec 6480d0c5):
 * the account identity, session, credential and owner-published token are read under the REAL
 * owner lock in a single transaction, together with the epoch projection, and the ADMITTED
 * account AND session are compared, not merely read - any mismatch refuses. The projection and
 * the token come from the SAME transaction, so a concurrent account switch is observed wholly
 * before or wholly after; the launch revalidation covers the window between acquisition and
 * transmission, which the acquisition lock alone cannot.
 */
class UploadIdentityAuthority(
    private val state: VoiceSharingAccountState,
    private val underOwnerTransaction: ((AuthoritySnapshot) -> LiveCredential?) -> LiveCredential?,
) {
    /** The admitted identity a capture carries: full ownership snapshot, epoch included. */
    data class AdmittedIdentity(val account: String?, val session: Long?, val epoch: String?)

    /** What one owner transaction vouched for: bearer bound to account/session/epoch. */
    data class LiveCredential(val bearer: String, val account: String, val session: Long, val epoch: String?)

    /**
     * (bearer, account, session, epoch) for the admitted identity, or null when ANY component of
     * the live authority disagrees with what was admitted. A null admitted component means the
     * capture predates full-identity sidecars; that component is then checked as epoch-only - a
     * weaker claim, never a weaker CHECK.
     */
    fun credentialFor(admitted: AdmittedIdentity): LiveCredential? = underOwnerTransaction { snapshot ->
        val projection = state.projection.value
        val token = snapshot.token
        val account = snapshot.accountName
        when {
            account == null -> null
            admitted.account != null && admitted.account != account -> null
            admitted.session != null && admitted.session != snapshot.session -> null
            projection?.enrollmentEpoch != admitted.epoch -> null
            token == null -> null
            projection == null -> null
            else -> LiveCredential(token.value, account, snapshot.session, projection.enrollmentEpoch)
        }
    }
}
