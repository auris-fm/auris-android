package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * RED SKELETON (Task 2, reviewer round 2): every authority path fails closed. The real
 * implementation must parse with exact JSON types and no coercion, detect duplicate keys
 * explicitly, issue lifecycle tokens synchronously at request issuance, and scope every
 * authority apply to the originally issued token. The red control pins the contract.
 */
class VoiceSharingAccountState {
    private val state = MutableStateFlow<VoiceSharingAccountProjection?>(null)
    private var currentToken: SessionToken? = null

    val projection: StateFlow<VoiceSharingAccountProjection?> = state

    fun sessionStarted(): SessionToken = SessionToken().also { currentToken = it } // RED: no fence

    fun sessionEnded() {
        currentToken = null // RED: projection not invalidated
    }

    fun parseProjection(json: String): VoiceSharingAccountProjection? = null // RED

    fun onTokenRefresh() { /* intentionally no invalidation */ }

    fun applySessionResponse(token: SessionToken, json: String): Boolean = false // RED

    fun isCurrentRevision(revision: String): Boolean = false // RED

    fun hasCurrentSessionProjection(): Boolean = false // RED
}

/** Non-secret lifecycle incarnation: unique per issued session, reference-compared only. */
class SessionToken internal constructor()

/** Server-issued opaque membership revision: never sorted, equality/current-only usage. */
data class VoiceSharingAccountProjection(
    val membershipRevision: String,
    val plan: VoiceSharingPlan,
    val voiceSharingEnrolled: Boolean,
    val enrollmentEpoch: String?,
    val acceptedDisclosureVersion: String?,
    val withdrawalState: WithdrawalState,
)

enum class VoiceSharingPlan { FREE, PRO }

enum class WithdrawalState { ACTIVE, WITHDRAWN, NOT_ENROLLED }
