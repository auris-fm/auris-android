package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * RED SKELETON (Task 2): fail-closed by construction. The real implementation must parse the
 * membership projection strictly (schema_version 1, full field set, unknown fields rejected),
 * key the projection on account identity, drop obsolete-session responses, and treat token
 * refresh on the same identity as non-logout. The red control pins the contract.
 */
class VoiceSharingAccountState(
    identity: Flow<String?>,
) {
    private val state = MutableStateFlow<VoiceSharingAccountProjection?>(null)
    private var currentIdentity: String? = null

    val projection: StateFlow<VoiceSharingAccountProjection?> = state

    init {
        // RED: identity observation intentionally not wired yet.
        @Suppress("UNUSED_EXPRESSION")
        identity
    }

    fun parseProjection(json: String): VoiceSharingAccountProjection? = null // RED

    fun onProjectionFetched(json: String) { /* RED */ }

    fun onTokenRefresh() { /* RED */ }

    fun applyResponseForSession(sessionIdentity: String, json: String): Boolean = false // RED

    fun isCurrentRevision(revision: String): Boolean = false // RED

    fun hasCurrentSessionProjection(): Boolean = false // RED
}

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
