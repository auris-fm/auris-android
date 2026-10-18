package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Buffer

/**
 * Membership projection state (Task 2 of docs/plans/android/voice-recording-uploads.md,
 * reviewer rounds 2-3). Strict, lifecycle-keyed, fail-closed:
 *
 * - Parsing uses Moshi's STRICT JsonReader (an established strict parser, not hand-patched
 *   syntax): unquoted or single-quoted names, malformed nested syntax, and trailing junk or a
 *   second top-level value all fail. Legal key escapes are DECODED and duplicate detection
 *   compares decoded keys, so escaped aliases are caught.
 * - Exact values: schema_version must be the exact NUMERIC 1 (1 and 1.0 and 1e0 are the same
 *   number; "1", true, null, and 1.5 fail). Epoch/disclosure must be actual JSON null OR a
 *   nonempty string — no other type coerces. Booleans must be JSON booleans; enums must match
 *   schema literals exactly.
 * - Lifecycle generation is issued SYNCHRONOUSLY: sessionStarted() supersedes the prior token
 *   and clears the prior projection ATOMICALLY (a new authoritative session never inherits the
 *   old one's projection), sessionEnded() ends it, and token refresh is not a boundary.
 * - Every authority apply requires the ORIGINALLY ISSUED token, reference-compared.
 */
class VoiceSharingAccountState {
    private val state = MutableStateFlow<VoiceSharingAccountProjection?>(null)

    /** Observed under [fence]; guards the issued session and revision currency. */
    private var currentToken: SessionToken? = null

    private val fence = Any()

    val projection: StateFlow<VoiceSharingAccountProjection?> = state

    /**
     * Issues a fresh session token synchronously, superseding any earlier token AND clearing
     * the earlier session's projection atomically: a newly authoritative session never starts
     * with the previous session's projection.
     */
    fun sessionStarted(): SessionToken = synchronized(fence) {
        state.value = null
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

    /** Strict parse: see [ProjectionJsonParser]. Any defect fails closed. */
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
 * Production strict parser over Moshi's JsonReader (strict by default): the established parser
 * owns syntax strictness (quoted names only, no single quotes, no trailing junk, well-formed
 * nested values); this layer owns the contract — decoded-key duplicate detection, exact field
 * set, exact value types.
 */
internal object ProjectionJsonParser {

    private const val SCHEMA_VERSION = 1

    private val PLANS = setOf("free", "pro")
    private val WITHDRAWAL_STATES = setOf("active", "withdrawn", "not_enrolled")

    fun parse(json: String): VoiceSharingAccountProjection? {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        reader.beginObject()

        var schemaVersion: Double? = null
        var revision: String? = null
        var plan: String? = null
        var enrolled: Boolean? = null
        var epoch: String? = null
        var disclosure: String? = null
        var withdrawal: String? = null
        val seen = HashSet<String>()

        while (reader.hasNext()) {
            val key = reader.nextName() // strict: names must be quoted; escapes decoded here
            if (!seen.add(key)) return null // decoded-key duplicate detection
            when (key) {
                "schema_version" -> {
                    if (reader.peek() != JsonReader.Token.NUMBER) return null
                    schemaVersion = reader.nextDouble() // 1, 1.0, 1e0 all read as exactly 1.0
                }

                "membership_revision" -> revision = reader.nonEmptyStringOrNull() ?: return null

                "plan" -> plan = reader.nonEmptyStringOrNull() ?: return null

                "voice_sharing_enrolled" -> {
                    if (reader.peek() != JsonReader.Token.BOOLEAN) return null
                    enrolled = reader.nextBoolean()
                }

                "enrollment_epoch" -> epoch = reader.nonEmptyStringOrNull()

                "accepted_disclosure_version" -> disclosure = reader.nonEmptyStringOrNull()

                "withdrawal_state" -> withdrawal = reader.nonEmptyStringOrNull() ?: return null

                else -> reader.skipValue() // unknown field: strict-consumed, then rejected below
            }
        }
        reader.endObject()
        if (reader.peek() != JsonReader.Token.END_DOCUMENT) return null // trailing junk / 2nd value

        if (seen != ALLOWED_KEYS) return null
        if (plan !in PLANS) return null // exact schema literal, no case coercion
        if (withdrawal !in WITHDRAWAL_STATES) return null
        if (schemaVersion == null || schemaVersion != SCHEMA_VERSION.toDouble()) return null
        if (enrolled == null) return null

        // Enrollment/withdrawal consistency.
        val consistent = when (withdrawal) {
            "active" -> enrolled && epoch != null && disclosure != null
            "withdrawn" -> !enrolled
            "not_enrolled" -> !enrolled && epoch == null
            else -> return null
        }
        if (!consistent) return null

        return VoiceSharingAccountProjection(
            membershipRevision = revision!!,
            plan = if (plan == "pro") VoiceSharingPlan.PRO else VoiceSharingPlan.FREE,
            voiceSharingEnrolled = enrolled,
            enrollmentEpoch = epoch,
            acceptedDisclosureVersion = disclosure,
            withdrawalState = when (withdrawal) {
                "withdrawn" -> WithdrawalState.WITHDRAWN
                "not_enrolled" -> WithdrawalState.NOT_ENROLLED
                else -> WithdrawalState.ACTIVE
            },
        )
    }

    /**
     * The value must be an actual JSON null (returns null) or a NONEMPTY string (returns it).
     * Any other type — number, boolean, object, array — fails closed (returns null marker via
     * exception is avoided here: we return null only for JSON null, and `fail` for others).
     */
    private fun JsonReader.nonEmptyStringOrNull(): String? = when (peek()) {
        JsonReader.Token.NULL -> {
            nextNull<Any?>()
            null
        }

        JsonReader.Token.STRING -> {
            val s = nextString()
            if (s.isEmpty()) throw JsonDataException("empty string not allowed") else s
        }

        else -> throw JsonDataException("non-string, non-null type not allowed")
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
