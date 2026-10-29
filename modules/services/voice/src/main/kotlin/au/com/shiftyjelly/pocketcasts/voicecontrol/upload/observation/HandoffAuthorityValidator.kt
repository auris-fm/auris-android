package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import javax.inject.Inject

/**
 * THE retention trust boundary at the completion handoff (reviewer 51bf10c2): the ORIGINAL
 * authority issued for the capture — account name, session incarnation, credential incarnation,
 * enrollment epoch and accepted disclosure version captured AT START — is revalidated INSIDE the
 * owner transaction. The observer lock serializes pipeline events; it cannot serialize the
 * owner's own state changes, so this owner-transaction check is mandatory: a handoff whose
 * original authority no longer matches current owner state FAILS CLOSED. A newer projection or
 * a new account NEVER renews admission for an old capture.
 */
fun interface HandoffValidator {
    /** True only when [issued] still matches authoritative owner + enrollment state. */
    fun validateUnderOwnerTransaction(issued: IssuedAuthority): Boolean
}

/** Everything frozen about authority at capture start; compared as a unit at handoff. */
data class IssuedAuthority(
    val accountName: String?,
    val sessionGeneration: Long,
    val credentialIncarnation: Long,
    val enrollmentEpoch: String?,
    val acceptedDisclosureVersion: String?,
    /** Policy lease incarnation frozen at issue (blocker 4): newer policy ≠ authorization. */
    val policyActivationGeneration: Long = 0L,
    val policyActivatedAtMonotonicMs: Long = 0L,
    val policyEnabledAtIssue: Boolean = false,
)

/**
 * Production validator: reads the LIVE owner snapshot inside ownership.transaction and compares
 * every identity component against what was issued at start. Enrollment epoch/disclosure must
 * match too — a rotation (new epoch) invalidates captures issued under the old enrollment even
 * within the same session.
 */
class OwnerHandoffValidator @Inject constructor(
    private val ownership: CredentialOwnershipOwner,
    private val enrollmentAtStart: (IssuedAuthority) -> Boolean,
) : HandoffValidator {
    override fun validateUnderOwnerTransaction(issued: IssuedAuthority): Boolean = ownership.transaction {
        val live = ownership.authoritySnapshot()
        live.accountName == issued.accountName &&
            live.session == issued.sessionGeneration &&
            live.credential == issued.credentialIncarnation &&
            enrollmentAtStart(issued)
    }
}

/** Fail-closed default used whenever no validator is composed: NO handoff can retain. */
object RefuseAllHandoffValidator : HandoffValidator {
    override fun validateUnderOwnerTransaction(issued: IssuedAuthority): Boolean = false
}
