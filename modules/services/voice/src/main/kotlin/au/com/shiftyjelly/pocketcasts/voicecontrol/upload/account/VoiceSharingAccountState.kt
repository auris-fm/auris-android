package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Membership projection state (Task 2 of docs/plans/android/voice-recording-uploads.md).
 *
 * Fail-closed and identity-keyed: the projection parses STRICTLY (schema_version 1, complete
 * field set — unknown and missing fields rejected, duplicate keys rejected by the parser),
 * is keyed on the ACCOUNT IDENTITY — a change invalidates the previous projection, and
 * responses belonging to an obsolete session are dropped — and a token refresh on the SAME
 * identity is not a logout. A current-session GET is required before the state carries any
 * authority. Membership revisions are opaque server strings, exposed for equality with the
 * current revision only; they are never sorted.
 */
class VoiceSharingAccountState(
    identity: StateFlow<String?>,
) {
    private val state = MutableStateFlow<VoiceSharingAccountProjection?>(null)

    /** Observed under [fence]; guards identity, session authority, and revision currency. */
    private var currentIdentity: String? = identity.value

    private val fence = Any()

    private val identitySource: StateFlow<String?> = identity

    val projection: StateFlow<VoiceSharingAccountProjection?> = state

    /**
     * Starts identity observation on the given scope (composition entry). Until a non-null
     * identity is observed, no projection may be applied: no session, no authority.
     */
    fun observeIdentity(scope: CoroutineScope, context: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext) {
        scope.launch(context) {
            identitySource.collect { newIdentity ->
                synchronized(fence) {
                    if (newIdentity != currentIdentity) {
                        currentIdentity = newIdentity
                        state.value = null
                    }
                }
            }
        }
    }

    /** Strict parse: unknown/missing keys, wrong schema_version, or invalid enums fail closed. */
    fun parseProjection(json: String): VoiceSharingAccountProjection? = try {
        val obj = JSONObject(json)
        val schemaVersionOk = obj.isNull("schema_version") || obj.getInt("schema_version") == 1
        val fieldSetExact = obj.length() == ALLOWED_KEYS.size && obj.keys().asSequence().toSet() == ALLOWED_KEYS
        if (!fieldSetExact || !schemaVersionOk) {
            null
        } else {
            VoiceSharingAccountProjection(
                membershipRevision = obj.getString("membership_revision"),
                plan = VoiceSharingPlan.valueOf(obj.getString("plan").uppercase()),
                voiceSharingEnrolled = obj.getBoolean("voice_sharing_enrolled"),
                enrollmentEpoch = if (obj.isNull("enrollment_epoch")) null else obj.getString("enrollment_epoch"),
                acceptedDisclosureVersion = if (obj.isNull("accepted_disclosure_version")) null else obj.getString("accepted_disclosure_version"),
                withdrawalState = WithdrawalState.valueOf(obj.getString("withdrawal_state").uppercase()),
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Applies a freshly GET-ed projection for the CURRENT identity; current-session GET authority. */
    fun onProjectionFetched(json: String): Boolean {
        val projection = parseProjection(json) ?: return false
        synchronized(fence) {
            if (currentIdentity == null) {
                // Identity observation must have established the current session first.
                return false
            }
            state.value = projection
        }
        return true
    }

    /** Token refresh on the same identity: NOT a logout; nothing is invalidated. */
    fun onTokenRefresh() {
        // Deliberately no invalidation; projection and authority survive.
    }

    /** Applies a response ONLY if it belongs to the current session; obsolete responses drop. */
    fun applyResponseForSession(sessionIdentity: String, json: String): Boolean {
        val projection = parseProjection(json) ?: return false
        synchronized(fence) {
            if (sessionIdentity != currentIdentity) {
                return false
            }
            state.value = projection
        }
        return true
    }

    /** Opaque revision comparison: equality with the CURRENT revision only, never ordering. */
    fun isCurrentRevision(revision: String): Boolean = synchronized(fence) {
        state.value?.membershipRevision == revision
    }

    fun hasCurrentSessionProjection(): Boolean = synchronized(fence) {
        currentIdentity != null && state.value != null
    }

    private companion object {
        /** Exactly the fields the schema_version-1 projection carries; anything else fails. */
        val ALLOWED_KEYS = setOf(
            "schema_version",
            "membership_revision",
            "plan",
            "voice_sharing_enrolled",
            "enrollment_epoch",
            "accepted_disclosure_version",
            "withdrawal_state",
        )
    }
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
