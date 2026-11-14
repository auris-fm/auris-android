package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Buffer

/**
 * The account's membership projection, and nothing else.
 *
 * This is ORDINARY account state: the server's answer to "is this account enrolled, and under
 * which epoch / disclosure" — the evidence collection consent and the Settings surface read. It is
 * NOT an identity lifecycle: there are no session tokens, operation tickets or generations here.
 * A login/logout is handled by the ordinary account owner, and every request is authorized by the
 * ordinary signed-in bearer; an operation that loses that bearer simply fails.
 *
 * Parsing is STRICT and fails closed:
 *
 * - Moshi's STRICT JsonReader (an established strict parser, not hand-patched syntax): unquoted or
 *   single-quoted names, malformed nested syntax, and trailing junk or a second top-level value
 *   all fail. Legal key escapes are DECODED, so escaped duplicate aliases are caught.
 * - Exact values: schema_version must be the exact NUMERIC 1 (1, 1.0 and 1e0 are the same number;
 *   "1", true, null, and 1.5 fail). Epoch/disclosure must be actual JSON null OR a nonempty string.
 *   Booleans must be JSON booleans; enums must match schema literals exactly.
 * - [publish] is the ONE write point: a server answer replaces the projection atomically and
 *   bumps [publicationCount]. [clear] drops it (session ended, account removed).
 */
class VoiceSharingAccountState {
    private val lock = Any()
    private val projectionFlow = MutableStateFlow<VoiceSharingAccountProjection?>(null)
    private val publicationCount = AtomicLong(0)

    val projection: StateFlow<VoiceSharingAccountProjection?> = projectionFlow

    /**
     * How many projections this state has published. A publication count, not an identity: the
     * operations layer reads it around a request so an answer that a LATER request already
     * superseded is discarded instead of overwriting newer state.
     */
    fun publicationCount(): Long = publicationCount.get()

    /** Publishes a server answer atomically; the newest write wins. */
    fun publish(projection: VoiceSharingAccountProjection) = synchronized(lock) {
        projectionFlow.value = projection
        publicationCount.incrementAndGet()
        notifyProjection(projection)
    }

    /** Drops the projection (no live account session): the evidence fails closed until it returns. */
    fun clear() = synchronized(lock) {
        projectionFlow.value = null
        publicationCount.incrementAndGet()
        notifyProjection(null)
    }

    /** Post-publication hook used by the collection authority (mirror bookkeeping only). */
    @Volatile
    var onProjectionListener: ((VoiceSharingAccountProjection?) -> Unit)? = null

    /** Opt-in diagnostic sink (staging observability): the outcome of one refresh, no authority. */
    @Volatile
    var onRefreshObserved: ((VoiceSharingRefreshDiagnostic) -> Unit)? = null

    private fun notifyProjection(projection: VoiceSharingAccountProjection?) {
        onProjectionListener?.invoke(projection)
    }

    /** Strict parse: see [ProjectionJsonParser]. Any defect fails closed. */
    fun parseProjection(json: String): VoiceSharingAccountProjection? = try {
        ProjectionJsonParser.parse(json)
    } catch (_: Exception) {
        null
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
        val source = Buffer().writeUtf8(json)
        val reader = JsonReader.of(source)
        reader.beginObject()

        var schemaVersion: Double? = null
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
                    // nextString on a NUMBER token returns the exact lexeme; the exact decimal
                    // comparison below avoids nextDouble rounding (1.00000000000000001 -> 1.0).
                    val lexeme = reader.nextString()
                    schemaVersion = lexeme.toDouble()
                    if (BigDecimal(lexeme).compareTo(BigDecimal.ONE) != 0) return null
                }

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
     * Any other type — number, boolean, object, array — fails closed.
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
        "plan",
        "voice_sharing_enrolled",
        "enrollment_epoch",
        "accepted_disclosure_version",
        "withdrawal_state",
    )
}

data class VoiceSharingAccountProjection(
    val plan: VoiceSharingPlan,
    val voiceSharingEnrolled: Boolean,
    val enrollmentEpoch: String?,
    val acceptedDisclosureVersion: String?,
    val withdrawalState: WithdrawalState,
)

enum class VoiceSharingPlan { FREE, PRO }

enum class WithdrawalState { ACTIVE, WITHDRAWN, NOT_ENROLLED }

/**
 * Outcome of ONE membership projection refresh, for device observability only (no authority
 * effect, no credentials, no body): the installed-path diagnostic for "why is the projection
 * absent?" — an APPLIED projection that still reads not-enrolled, or a REJECTED/409/401 attempt.
 */
data class VoiceSharingRefreshDiagnostic(
    val kind: Kind,
    val status: Int?,
    val bodyBytes: Int?,
    val detail: String,
    /**
     * Bounded server correlation ids (`X-Request-Id`) observed on each response, in call order.
     * One element per HTTP attempt, so a retried 5xx yields two ids. Truncated; never a body or
     * a credential.
     */
    val requestIds: List<String> = emptyList(),
) {
    enum class Kind { APPLIED, REJECTED }
}
