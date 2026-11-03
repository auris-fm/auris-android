package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
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
    /** Post-commit notification hook (applied session + projection); mirror bookkeeping only. */
    var onProjectionApplied: ((VoiceSharingAccountProjection, Long) -> Unit)? = null

    /** Opt-in diagnostic sink (staging observability): receives the projection outcome of one
     *  refresh WITHOUT any authority effect; null in production consumers that do not need it. */
    var onRefreshObserved: ((VoiceSharingRefreshDiagnostic) -> Unit)? = null

    /** Central post-transition observer (reviewer dde0905a + 6b6d62b8): fired for EVERY
     *  authoritative projection change OR LOSS - applied outcomes AND nulling transitions
     *  (scoped 401 processUnauthorized, session-moved revocation, session end). The payload
     *  projection is NULL for a loss: the connector re-reads a coherent optional identity and
     *  cancels in-flight attempts whose admitted identity no longer matches anything. */
    @Volatile
    var projectionInvalidatedListener: ((VoiceSharingAccountProjection?, Long) -> Unit)? = null

    private fun notifyProjection(projection: VoiceSharingAccountProjection?, ownerSession: Long) {
        projectionInvalidatedListener?.invoke(projection, ownerSession)
    }
    private val state = MutableStateFlow<VoiceSharingAccountProjection?>(null)

    /**
     * The projection WITH its authoritative owner session, published under the same fence as
     * [state] (reviewer 20b681f0): consumers must not infer a projection's owner from their own
     * collection time - a singleton constructed under session A, switching to B before its
     * collector first runs, replays A's value and would stamp it as B's.
     */
    private val tagged =
        MutableStateFlow(VoiceSharingTaggedProjection(ownerSession = Long.MIN_VALUE, projection = null))

    val taggedProjection: StateFlow<VoiceSharingTaggedProjection> = tagged

    private fun publish(projection: VoiceSharingAccountProjection?) {
        tagged.value = VoiceSharingTaggedProjection(currentSessionGeneration, projection)
    }

    /** Observed under [fence]; guards the issued session and revision currency. */
    private var currentToken: SessionToken? = null
    private var currentSessionGeneration: Long = Long.MIN_VALUE

    private val fence = Any()

    /** Store-owned operation generation: bumped on EVERY session lifecycle transition. */
    private val operationGeneration = AtomicLong(0)

    val projection: StateFlow<VoiceSharingAccountProjection?> = state

    /**
     * Issues a fresh session token synchronously, superseding any earlier token AND clearing
     * the earlier session's projection atomically: a newly authoritative session never starts
     * with the previous session's projection.
     */
    fun sessionStarted(sessionGeneration: Long = Long.MIN_VALUE): SessionToken = synchronized(fence) {
        operationGeneration.incrementAndGet() // outstanding tickets die with the old session
        state.value = null
        // The NULL projection published for the new session must already carry the NEW session's
        // generation, so the tag identifies the authoritative session even while empty
        // (reviewer 278e595f).
        currentSessionGeneration = sessionGeneration
        signalIdentityChange() // a NEW identity is live from here on
        publish(null)
        notifyProjection(null, sessionGeneration)
        SessionToken().also { currentToken = it }
    }

    /** Ends the session synchronously: invalidates the projection and the token. */
    fun sessionEnded() = synchronized(fence) {
        operationGeneration.incrementAndGet()
        currentToken = null
        state.value = null
        signalIdentityChange() // identity gone
        publish(null)
        notifyProjection(null, currentSessionGeneration)
    }

    /**
     * SYNCHRONOUS authority validation at the owner/state boundary (reviewer efbe0945): if the
     * REAL owner's session incarnation has moved since this session was armed (REAL signOut /
     * account replacement), the projection is revoked HERE — at every authority read — instead
     * of waiting for the async emailFlow collector. Returns true when it revoked.
     */
    fun revokeIfSessionMoved(liveSessionGeneration: Long): Boolean = synchronized(fence) {
        if (currentToken != null && currentSessionGeneration != liveSessionGeneration) {
            operationGeneration.incrementAndGet()
            currentToken = null
            state.value = null
            signalIdentityChange()
            publish(null)
            notifyProjection(null, currentSessionGeneration)
            true
        } else {
            false
        }
    }

    /** Token refresh on the same session: NOT a lifecycle boundary; nothing is invalidated. */
    fun onTokenRefresh() {
        // Deliberately no invalidation; projection and authority survive.
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

    /** Fenced check: is this the session currently in authority? */
    fun isCurrentSession(token: SessionToken): Boolean = synchronized(fence) {
        token === currentToken
    }

    /**
     * STORE-OWNED atomic operation issuance: under one fence, validates the issued session,
     * captures the projection snapshot, and issues a generation ticket. Mutations require a
     * current-session GET projection; the GET authority-creation path does not.
     */
    fun beginOperation(token: SessionToken, requireProjection: Boolean, ownerSession: Long = Long.MIN_VALUE): OperationTicket? = synchronized(fence) {
        // OWNER→voice: this runs inside the owner's transaction — the ticket stamps the owner
        // session incarnation observed under the owner lock, so later checks compare against
        // the LIVE incarnation, not collector timing.
        if (token !== currentToken || currentSessionGeneration != ownerSession) return null
        val projection = state.value
        if (requireProjection && projection == null) return null
        // The issued projection snapshot is IMMUTABLE on the ticket: request bodies are
        // serialized from exactly these captured values, never re-read after awaits.
        OperationTicket(operationGeneration.incrementAndGet(), projection, ownerSession)
    }

    /**
     * STORE-OWNED conditional apply: under one fence, validates the issued session AND the
     * ticket generation, strictly parses, and only then writes — a new same-session intent
     * (which bumps the generation) can never slip between check and write.
     */
    fun applyOutcome(token: SessionToken, ticket: OperationTicket, json: String, ownerSession: Long = Long.MIN_VALUE): Boolean = synchronized(fence) {
        // OWNER→voice: ownerSession was read under the owner lock by the caller's transaction.
        if (token !== currentToken || ticket.generation != operationGeneration.get() || ticket.session != ownerSession) return false
        val projection = parseProjection(json) ?: return false
        state.value = projection
        publish(projection)
        onProjectionApplied?.invoke(projection, ownerSession)
        notifyProjection(projection, ownerSession)
        true
    }

    /** Atomic issued-session invalidation: ends the session ONLY if it is still current. */
    fun endSessionIfCurrent(token: SessionToken): Boolean = synchronized(fence) {
        if (token !== currentToken) return false
        operationGeneration.incrementAndGet()
        currentToken = null
        state.value = null
        signalIdentityChange()
        publish(null)
        notifyProjection(null, currentSessionGeneration)
        true
    }

    /** Fenced revalidation used before each send and before terminal outcomes. */
    fun isTicketCurrent(token: SessionToken, ticket: OperationTicket, ownerSession: Long = Long.MIN_VALUE): Boolean = synchronized(fence) {
        token === currentToken && ticket.generation == operationGeneration.get() && ticket.session == ownerSession
    }

    /**
     * SCOPED state side of the coordinated 401 invalidation transaction: validates the issued
     * TICKET generation and the session under the state fence, so a newer same-session
     * operation (which bumped the generation) or a superseded session makes a stale 401 a
     * no-op. The credential owner ([VoiceSharingCredentialStore.invalidateIfCurrent]) runs
     * this check INSIDE its transaction lock, making credential compare + state invalidation
     * one coordinated transaction. No credential data passes through or is stored.
     */
    fun processUnauthorized(token: SessionToken, ticket: OperationTicket): Boolean = synchronized(fence) {
        if (token !== currentToken) return false
        if (ticket.generation != operationGeneration.get()) return false
        operationGeneration.incrementAndGet()
        currentToken = null
        state.value = null
        signalIdentityChange()
        publish(null)
        notifyProjection(null, ticket.session)
        true
    }

    /** Test/diagnostic accessor for the current issued session (null when ended). */
    fun currentSessionTokenForTest(): SessionToken? = synchronized(fence) { currentToken }

    fun currentOwnerSessionForTest(): Long? = synchronized(fence) { currentToken?.let { currentSessionGeneration } }

    /** Re-arms the session under the SAME owner generation: the reinstatement a 401 allows for. */
    fun sessionStartedForTest(ownerSession: Long): SessionToken = synchronized(fence) { sessionStarted(ownerSession) }

    /** Same-session authority publication, for controls that must SKIP the op that a 401 ended. */
    fun applyOutcomeForTest(token: SessionToken, json: String, ownerSession: Long): Boolean {
        synchronized(fence) {
            if (token !== currentToken) return false
            val projection = parseProjection(json) ?: return false
            state.value = projection
            publish(projection)
            notifyProjection(projection, ownerSession)
            return true
        }
    }

    fun hasCurrentSessionProjection(): Boolean = synchronized(fence) {
        currentToken != null && state.value != null
    }

    /**
     * The voice identity of a live session: the session incarnation ([token], a NEW instance per
     * issued session) plus the owner session generation it was started under. A delayed effect
     * records the one it rejected and asks whether the live one is STILL that same incarnation.
     */
    class VoiceIdentity internal constructor(
        internal val token: SessionToken,
        internal val ownerSession: Long,
    )

    private val identityChangeSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Bumped on EVERY identity transition, so a waiter can tell "changed since I looked" apart. */
    private val identityChangeCount = java.util.concurrent.atomic.AtomicLong(0)

    /** Wakes a SUSPENDED caller when the live voice identity changes. Nested inside [fence]: leaf. */
    private fun signalIdentityChange() {
        identityChangeCount.incrementAndGet()
        identityChangeSignal.trySend(Unit)
    }

    /** A token that becomes stale the moment the identity changes: read it BEFORE the effect. */
    fun identityChangeMarker(): Long = identityChangeCount.get()

    /** Wakes a waiting rejection when ANY live authority is observed (identity unchanged). */
    fun signalLiveAuthority() {
        identityChangeSignal.trySend(Unit) // wakes the WAIT, does not advance the change counter
    }

    /**
     * Suspends until the live voice identity changes (or the caller times out). NEVER blocks a
     * thread and never runs under a lock: the caller awaits this, then re-reads [voiceIdentity]
     * inside its own critical section. Correctness rests on that re-read, not on this signal.
     */
    suspend fun awaitIdentityChange(seen: Long, until: () -> Boolean = { true }) {
        // DRAIN FIRST: a wake buffered before this call proves nothing about the state RIGHT NOW.
        // The loop reads the counter AFTER every drain, so a stale wake can never decide anything.
        while (true) {
            if (identityChangeMarker() != seen) return // a real change landed
            if (until()) return
            identityChangeSignal.receive() // block until something happens, then re-read
        }
    }

    fun voiceIdentity(): VoiceIdentity? = synchronized(fence) {
        currentToken?.let { VoiceIdentity(it, currentSessionGeneration) }
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
                    // nextString on a NUMBER token returns the exact lexeme; the exact decimal
                    // comparison below avoids nextDouble rounding (1.00000000000000001 -> 1.0).
                    val lexeme = reader.nextString()
                    schemaVersion = lexeme.toDouble()
                    if (BigDecimal(lexeme).compareTo(BigDecimal.ONE) != 0) return null
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

/** Store-issued operation generation: valid only while its session, generation AND owner session incarnation are current. */
class OperationTicket internal constructor(
    internal val generation: Long,
    internal val projection: VoiceSharingAccountProjection?,
    internal val session: Long,
)

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

/** A membership projection and the authoritative owner session it was published under. */
data class VoiceSharingTaggedProjection(
    val ownerSession: Long,
    val projection: VoiceSharingAccountProjection?,
)

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
