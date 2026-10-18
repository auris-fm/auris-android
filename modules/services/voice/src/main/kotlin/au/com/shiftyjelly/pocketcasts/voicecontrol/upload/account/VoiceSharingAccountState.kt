package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONTokener

/**
 * Membership projection state (Task 2 of docs/plans/android/voice-recording-uploads.md,
 * reviewer round 2). Strict, identity-lifecycle-keyed, fail-closed:
 *
 * - Parsing uses EXACT JSON types with no coercion: `schema_version` must be a JSON integer
 *   (a string, a fraction, or null fails), booleans must be JSON booleans, enum values must
 *   match the schema literals exactly (no case coercion). The field set is exact — a missing
 *   or unknown field fails.
 * - Duplicate top-level keys are detected EXPLICITLY by this parser (not delegated to a
 *   platform behavior).
 * - Session lifecycle generation is issued SYNCHRONOUSLY at request issuance
 *   ([sessionStarted]) and ended synchronously ([sessionEnded]). Same-account logout/relogin
 *   issues a NEW token, so old responses are rejected even where an account-identity flow
 *   would conflate A→null→A. A token refresh ([onTokenRefresh]) is not a lifecycle boundary.
 * - Every authority apply ([applySessionResponse]) requires the ORIGINALLY ISSUED token;
 *   whatever account is current when a response arrives never grants authority.
 * - The membership revision is opaque: equality with the current revision only, never sorted.
 */
class VoiceSharingAccountState {
    private val state = MutableStateFlow<VoiceSharingAccountProjection?>(null)

    /** Observed under [fence]; guards the issued session and revision currency. */
    private var currentToken: SessionToken? = null

    private val fence = Any()

    val projection: StateFlow<VoiceSharingAccountProjection?> = state

    /** Issues a fresh session token synchronously; supersedes any earlier token. */
    fun sessionStarted(): SessionToken = synchronized(fence) {
        SessionToken().also { currentToken = it }
    }

    /** Ends the session synchronously: invalidates the projection and the token. */
    fun sessionEnded() = synchronized(fence) {
        currentToken = null
        state.value = null
    }

    /** Token refresh on the same session: NOT a lifecycle boundary; nothing is invalidated. */
    fun onTokenRefresh() {
        // Deliberately no invalidation; projection and authority survive.
    }

    /**
     * Applies a response ONLY for the token that was issued when the request went out; a stale
     * or superseded token never applies, whatever account is current at response time.
     */
    fun applySessionResponse(token: SessionToken, json: String): Boolean {
        val projection = parseProjection(json) ?: return false
        synchronized(fence) {
            if (token !== currentToken) {
                return false
            }
            state.value = projection
        }
        return true
    }

    /**
     * Strict parse: exact JSON types (no coercion), exact field set, explicit duplicate-key
     * detection, nonempty strings, and enrollment/withdrawal consistency. Any defect fails
     * closed.
     */
    fun parseProjection(json: String): VoiceSharingAccountProjection? = try {
        ProjectionJsonParser.parse(json)
    } catch (_: Exception) {
        null
    }

    /** Opaque revision comparison: equality with the CURRENT revision only, never ordering. */
    fun isCurrentRevision(revision: String): Boolean = synchronized(fence) {
        state.value?.membershipRevision == revision
    }

    fun hasCurrentSessionProjection(): Boolean = synchronized(fence) {
        currentToken != null && state.value != null
    }
}

/**
 * Production strict parser with its own real-boundary controls: walks the top-level object with
 * [JSONTokener] so duplicate keys are detected here, then validates exact types. This file is
 * the shared implementation — the "desktop org.json rejects duplicates" behavior is never
 * relied on.
 */
internal object ProjectionJsonParser {

    private const val SCHEMA_VERSION = 1

    private val PLANS = setOf("free", "pro")
    private val WITHDRAWAL_STATES = setOf("active", "withdrawn", "not_enrolled")

    fun parse(json: String): VoiceSharingAccountProjection? {
        val tokener = JSONTokener(json)
        if (tokener.nextClean() != '{') return null

        val values = LinkedHashMap<String, Any?>()
        var c = tokener.nextClean()
        if (c == '}') return null // empty object lacks the required fields
        while (true) {
            if (c != '"') return null
            val key = parseString(tokener) ?: return null
            if (key in values) return null // explicit duplicate detection
            if (tokener.nextClean() != ':') return null
            values[key] = tokener.nextValue()

            c = tokener.nextClean()
            when (c) {
                ';' -> return null
                ',' -> c = tokener.nextClean()
                '}' -> break
                else -> return null
            }
        }

        if (values.keys != ALLOWED_KEYS) return null

        // Exact types: schema_version must be a JSON integer, not a string, fraction or null.
        val schemaVersion = values["schema_version"]
        if (schemaVersion !is Int || schemaVersion != SCHEMA_VERSION) return null

        val revision = values["membership_revision"] as? String
        if (revision.isNullOrEmpty()) return null

        val plan = values["plan"] as? String
        if (plan == null || plan !in PLANS) return null

        val enrolled = values["voice_sharing_enrolled"]
        if (enrolled !is Boolean) return null

        val epoch = values["enrollment_epoch"] as? String
        if (epoch != null && epoch.isEmpty()) return null

        val disclosure = values["accepted_disclosure_version"] as? String
        if (disclosure != null && disclosure.isEmpty()) return null

        val withdrawal = values["withdrawal_state"] as? String
        if (withdrawal == null || withdrawal !in WITHDRAWAL_STATES) return null

        // Enrollment/withdrawal consistency.
        val consistent = when (withdrawal) {
            "active" -> enrolled && epoch != null && disclosure != null
            "withdrawn" -> !enrolled
            else -> !enrolled && epoch == null // not_enrolled
        }
        if (!consistent) return null

        return VoiceSharingAccountProjection(
            membershipRevision = revision,
            plan = VoiceSharingPlan.valueOf(plan.uppercase()),
            voiceSharingEnrolled = enrolled,
            enrollmentEpoch = epoch,
            acceptedDisclosureVersion = disclosure,
            withdrawalState = WithdrawalState.valueOf(withdrawal.uppercase()),
        )
    }

    /** Reads a complete quoted string (no escapes expected in schema keys; reject escapes). */
    private fun parseString(tokener: JSONTokener): String? {
        val sb = StringBuilder()
        while (true) {
            val c = tokener.next()
            when {
                c == '\\' -> return null

                // reject escape sequences in keys
                c == '"' -> return sb.toString()

                c == 0.toChar() -> return null

                // unterminated
                else -> sb.append(c)
            }
        }
    }

    private val ALLOWED_KEYS = setOf(
        "schema_version",
        "membership_revision",
        "plan",
        "voice_sharing_enrolled",
        "enrollment_epoch",
        "accepted_disclosure_version",
        "withdrawal_state",
    )
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
