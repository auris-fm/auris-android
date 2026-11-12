package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import javax.inject.Inject
import javax.inject.Singleton

/** Everything frozen about consent at capture start; compared as a unit at handoff. */
data class IssuedAuthority(
    val accountName: String?,
    val enrollmentEpoch: String?,
    val acceptedDisclosureVersion: String?,
)

/**
 * LIVE collection authority composed from the ACTUAL production authorities — the current
 * signed-in account name and the account's durable saved sharing choice. Consulted synchronously
 * at every retention decision; a START-time decision never authorizes later retention after
 * withdrawal or account change.
 *
 * Enrollment rule: retention requires a durable ENROLLED record for the CURRENT account
 * (`SavedSharingChoiceStore.enrolledChoiceFor`) with a real epoch and accepted disclosure version.
 * No separate evidence cache: the saved account-owned choice IS the evidence.
 */
@Singleton
class CollectionAuthority @Inject constructor(
    private val accountStartup: VoiceSharingAccountStartup,
    /**
     * THE SAVED ORDINARY CHOICE (spec 1af5e705). The durable record of what the user actually decided,
     * written at the existing enrollment/withdrawal operations. Admission reads THIS rather than the
     * in-process membership projection, so saved consent authorizes a capture without waiting for a
     * server refresh in the current process.
     */
    private val savedSharingChoice: SavedSharingChoiceStore,
) {

    /**
     * Freeze the ORIGINAL authority from the SAME saved record the decision read: the account name
     * and the consent facts (epoch + accepted disclosure) that authorize this capture.
     */
    fun issueAuthority(): IssuedAuthority {
        val accountName = accountStartup.currentAccountName()
        val saved = savedSharingChoice.enrolledChoiceFor(accountName)
        return IssuedAuthority(
            accountName = accountName,
            enrollmentEpoch = saved?.epoch,
            acceptedDisclosureVersion = saved?.acceptedDisclosureVersion,
        )
    }

    /** Handoff check: the current account and the saved choice must still match what was frozen. */
    fun validateIssuedAuthority(issued: IssuedAuthority): Boolean {
        if (accountStartup.currentAccountName() != issued.accountName) return false
        val saved = savedSharingChoice.enrolledChoiceFor(issued.accountName) ?: return false
        return saved.epoch == issued.enrollmentEpoch &&
            saved.acceptedDisclosureVersion == issued.acceptedDisclosureVersion
    }

    fun decide(): AuthorityDecision {
        val accountName = accountStartup.currentAccountName()
        val hasAccount = accountName != null
        // THE SAVED ORDINARY CHOICE, NOT A REFRESH IN THIS PROCESS (spec 1af5e705). The membership
        // projection stays in the app for the Settings display, but it is no longer an admission
        // prerequisite: requiring one made saved consent unusable after a restart and refused a user
        // who had already opted in. The record is account-scoped, so another account never inherits it.
        val saved = savedSharingChoice.enrolledChoiceFor(accountName)
        val enrolled = hasAccount && saved != null
        return AuthorityDecision(
            collectionPermitted = enrolled,
            fullRevocation = !enrolled,
            // FIXED REASON at the decision site itself (@spec 56184117, Merlin a611202c): the refusal
            // must name WHICH clause failed, not a generic gate-off. The reason is a fixed token, not
            // free text, and carries no account or epoch contents.
            refusalReason = when {
                !hasAccount -> AdmissionRefusalReason.NO_SESSION
                saved == null -> AdmissionRefusalReason.NO_SAVED_CHOICE
                else -> null
            },
        )
    }
}
