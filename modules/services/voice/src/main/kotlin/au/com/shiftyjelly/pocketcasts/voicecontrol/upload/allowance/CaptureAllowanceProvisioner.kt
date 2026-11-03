package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * The transport-facing provisioner slice for the server-issued capture allowance
 * (docs/specs/voice-recording-uploads.md, "Server-issued capture allowance"; spec ruling
 * 746cb082): builds the strict request envelope, sends ONE POST, strictly parses the 200 body,
 * and — only on a fully validated batch — composes the per-batch prepaid authority and binds it
 * through the lifecycle. Capture remains fail-closed: until this path provisions a batch,
 * [UnavailableCaptureAllowance] still refuses every capture.
 *
 * CONTRACT POINTS THIS CLASS OWNS:
 * - REQUEST IDENTITY: the request UUID and the enrollment epoch are chosen HERE and the response
 *   MUST echo exactly both; a mismatched echo is a refused document, never a provisioned batch.
 * - FRESHNESS ANCHOR (reviewer 978e8c11): the monotonic elapsed time is captured BEFORE the
 *   transport call and the ORIGINAL anchor is retained on the provisioned grant — expiry is
 *   evaluated against request start, never against installation time, so transit time cannot
 *   extend the server interval.
 * - PER-BATCH LEDGER (746cb082): the batch id keys the ledger area and joins the account
 *   binding; the server day-window id is the shared accounting window; the batch capacity pair
 *   (already identity-checked by the parser) becomes the ledger limits. `bind` is
 *   retire-then-set, so re-provisioning replaces the previous batch authority.
 * - REPLAY ASYMMETRY (spec b002b0d4): ONLY an `issued` response may FIRST-provision a ledger.
 *   A `replayed` response binds an authority with NO establishment power (the ledger's
 *   Unavailable provisioning path): it reopens an INTACT existing batch ledger and refuses a
 *   missing or lost one — a replay can never reset spent capacity after ledger loss.
 * - FAILURE SHAPES: every refusal is a reason; there is no success-shaped failure and no
 *   partial provision (a bind only happens after the full validation chain).
 */
class CaptureAllowanceProvisioner(
    private val api: CaptureAllowanceTransport,
    private val credential: suspend () -> Credential?,
    /**
     * SIDE-EFFECT-FREE readiness probe (reviewer 70d0801b): "could I present a credential right
     * now", answered WITHOUT acquiring one.
     *
     * It must not refresh, exchange, or otherwise reach the network. [credential] acquires the
     * bearer and in production that starts with `aurisToken.currentToken()`, which CAN refresh
     * over HTTP -- so using it to decide whether to re-arm made a closed gate perform credential
     * acquisition on every refused utterance. A shutdown check must be free.
     */
    private val readinessProvider: () -> Boolean,
    private val baseArea: File,
    private val lifecycle: CaptureAllowanceLifecycle,
    private val ceilings: Ceilings,
    private val elapsedMs: () -> Long,
    /**
     * REQUIRED atomic install transaction, implemented by the account operations layer. Under
     * ONE owner-transaction fence (the credential store's lock, never released in between) it
     * must: (a) validate the credential generation observed at request time is still live
     * (including request-generation supersession), (b) execute `lifecycle.bind(owner)`, and
     * (c) recheck the retained deadline (`grant` freshness against its original request-start
     * anchor) — returning true only if all held and the bind committed. A Boolean recheck that
     * releases the fence before bind is NOT acceptable: a Boolean callback + external bind lets
     * another thread switch identity in the gap even without suspension. No default: omitting
     * the fence silently omits authority.
     */
    private val beginInstallAttempt: suspend (String) -> Unit,
    /**
     * REQUIRED atomic install transaction (owned by the ops layer / install fence). Under ONE
     * fence it must validate overlapping-attempt supersession ([attemptId] is still the live
     * one), identity (credential generation AND session still live), and the retained deadline
     * ([grant] fresh against its request-start anchor), then bind and — still under the fence —
     * invoke [publish] to record the installed state. Publication INSIDE the fence is what
     * prevents a slower older attempt from overwriting a newer installation after its callback
     * returns.
     */
    private val installAuthority: suspend (
        owner: CaptureAllowanceOwner,
        credentialGeneration: Long,
        observedSession: Long,
        observedEnrollmentEpoch: String,
        attemptId: String,
        grant: Grant,
        publish: () -> Unit,
    ) -> Boolean,
    /**
     * DURABLE allocation identity (reviewer 3ba1e605, cloud a42eea42). The server is idempotent
     * on `request_id` ALONE, so a fresh UUID per attempt does not retry -- it allocates a new
     * batch. The id is therefore persisted BEFORE the send and reused across restarts, retries
     * and unknown transport outcomes; it is replaced only on a server-confirmed `410`, and
     * RETAINED on `429`. MANDATORY in production (reviewer b36642db): a nullable default silently
     * disabled the whole mechanism in `VoiceObservationModule`, which still minted a fresh UUID
     * per call. Null remains available only to controls that assert un-persisted behaviour.
     */
    private val identityStore: CaptureAllowanceAllocationIdentityStore?,
    /**
     * Invoked on a `409 request_conflict` (cloud bcc9a42a). This is a CLIENT BUG REPORT, not a
     * transient: it means a persisted id outlived the enrollment epoch it was minted under. The
     * production wiring logs loudly here; silence would hide the state-machine bug.
     */
    private val identityConflict: () -> Unit = {},
    /**
     * The server's accounting day for an attempt, as the CLIENT reads it. Used only to notice
     * that a persisted identity belongs to a different day and must not be reused (that reuse
     * would be a server `410`); it never advances the identity by itself -- only a `410` does.
     */
    private val accountingDay: () -> String = { "unknown" },
) {

    /**
     * The authenticated identity the provisioner needs, captured under the credential owner.
     * `generation` is the owner-side credential generation observed when this snapshot was
     * taken; the atomic recheck before bind compares exactly this value.
     */
    data class Credential(
        val bearer: String,
        val accountId: String,
        val enrollmentEpoch: String,
        val generation: Long = 0,
        val session: Long = 0,
    )

    /** The transport seam; production binds the real [VoiceSharingAccountApi] method. */
    interface CaptureAllowanceTransport {
        @POST("/api/v1/account/voice-sharing/capture-allowance")
        suspend fun postCaptureAllowance(
            @Header("Authorization") bearer: String?,
            @Body body: okhttp3.RequestBody,
        ): Response<okhttp3.ResponseBody>
    }

    sealed interface Result {
        /** A validated batch is bound and live. Freshness is anchored at REQUEST start. */
        data class Provisioned(
            val batchId: String,
            val windowId: String,
            val disposition: String,
            val requestStartElapsedMs: Long,
            val validForMs: Long,
        ) : Result

        data class Refused(val reason: String) : Result

        /**
         * NOT SENT, and not a refusal of the capture's worth (spec e2cd78d8, cloud 8d1ffaaf).
         *
         * The credential's account is not yet authoritative, so there is no identity this request
         * could be scoped to. Sending anyway does not risk a mismatch -- it GUARANTEES the server
         * records the id under whatever principal the bearer turns out to be, as a first attempt by
         * a new user, with no way afterwards to relate it to the identity the client meant.
         *
         * This is deliberately a distinct outcome from [Refused] so the caller can DEFER: hold the
         * attempt and resume when the account becomes knowable, replaying the same persisted id.
         * It must never be resolved by a timeout that ends in a send -- that is the bypass with a
         * delay in front of it.
         */
        data object Deferred : Result
    }

    /**
     * The live grant's freshness, anchored at the ORIGINAL request start (reviewer 978e8c11):
     * `isFresh` is false at or beyond `validForMs` elapsed since the request started. This is
     * the value the composition/watcher must consult for batch expiry; re-deriving it from
     * bind time would restore time spent in transit.
     */
    @Volatile
    var currentGrant: Grant? = null
        private set

    /** The last composed (and bound) per-batch owner; observation seam for controls and wiring. */
    @Volatile
    var lastComposedOwner: CaptureAllowanceOwner? = null
        internal set

    /**
     * True between a proven ledger exhaustion and the next successful provision.
     *
     * The owner is cleared as part of advancing, so this is the surviving evidence that the last
     * batch was spent. [composedOwnerExhausted] reports it, because the gate must be able to ask
     * "is there anything left to refill?" BEFORE provision() gets a chance to clear the answer.
     */
    @Volatile
    private var spentSinceLastProvision = false

    /** The allocation identity [lastComposedOwner] was composed under, for compare-and-swap. */
    @Volatile
    private var composedIdentity: CaptureAllowanceAllocationIdentityStore.Identity? = null

    data class Grant(val batchId: String, val requestStartElapsedMs: Long, val validForMs: Long) {
        fun isFresh(nowElapsedMs: Long): Boolean {
            if (requestStartElapsedMs < 0 || nowElapsedMs < 0) return false
            // BACKWARDS CLOCK: a read earlier than the request start is damage, not freshness.
            if (nowElapsedMs < requestStartElapsedMs) return false
            return nowElapsedMs - requestStartElapsedMs < validForMs
        }
    }

    /**
     * The TERMINATION CONDITION for the refill path (cloud 0b659d72).
     *
     * A refill spends records already held; it can never create new ones. So "refuse -> refill ->
     * refuse -> refill" must STOP at a proven-exhaustion state and route to provisioning through
     * the durable identity, not loop locally: a refill that merely returns false forever is a
     * silent spin the server sees as inactivity.
     *
     * True when the composed owner's own ledger is spent. [prepareComposedToken] returns false in
     * that state for the same reason a fresh owner would, so callers must distinguish "nothing to
     * refill yet" from "nothing left to refill" -- this is that distinction, and it is the signal
     * the engine uses to advance the allocation instead of retrying.
     */
    fun composedOwnerExhausted(): Boolean = synchronized(prepareLock) {
        spentSinceLastProvision || lastComposedOwner?.isExhausted() == true
    }

    /**
     * The readiness state this provisioner has ALREADY ACTED ON (reviewer f77aaa69).
     *
     * The previous version was a flag set by failure and cleared by consumption, which is a retry
     * counter wearing the name of an event (cloud 610bec98). It never observed readiness; it
     * observed the last unresolved attempt, so consuming it simply re-fired the same attempt.
     *
     * Recording the OBSERVED STATE instead makes resumption a genuine transition: re-arm only when
     * readiness now differs from the state the last attempt saw. An unresolved -> unresolved
     * sequence is therefore a no-op however many utterances arrive, and a resolved transition
     * fires exactly once.
     */
    @Volatile
    private var readinessActedOn: Readiness = Readiness.UNKNOWN

    /** What was true about the client's ability to send when an attempt started. */
    private enum class Readiness {
        /** No attempt has been made yet, so there is no state to differ from. */
        UNKNOWN,

        /** Credential present and its account authoritative: the attempt may send. */
        READY,

        /** Credential absent, or its account not yet authoritative: nothing may be sent. */
        NOT_READY,
    }

    /**
     * True only when readiness has CHANGED since the last attempt acted on it (reviewer f77aaa69,
     * spec 1a340261), decided from a SIDE-EFFECT-FREE source (reviewer 70d0801b).
     *
     * The credential supplier is NOT a passive read. In production it starts with
     * `aurisToken.currentToken()`, which can exchange or refresh over HTTP, so calling it from a
     * gate check meant every refused utterance performed credential acquisition even when the gate
     * was closed and no attempt was permitted. A closed gate doing network work is precisely the
     * spin this whole mechanism exists to prevent, so the check must consult a snapshot instead.
     *
     * [readinessProvider] answers "could I present a credential right now" without acquiring one;
     * the bearer is still acquired inside the permitted attempt, where spending it is the point.
     */
    fun consumeReadinessTransition(): Boolean {
        val now = if (readinessProvider()) Readiness.READY else Readiness.NOT_READY
        if (now == readinessActedOn) return false
        readinessActedOn = now
        return now == Readiness.READY
    }

    /** True when the composed grant has passed its own `valid_for_ms`; a refill cannot help. */
    fun composedOwnerExpired(): Boolean = synchronized(prepareLock) {
        val grant = currentGrant ?: return false
        !grant.isFresh(elapsedMs())
    }

    /**
     * PREPARES the durable charge on the currently composed owner, so the next admission on this
     * authority can hand out a prepaid token.
     *
     * This is the production caller `prepareToken()` was missing (reviewer b36642db). It returns
     * false when nothing is composed, when the owner is closed, or when the ledger cannot charge —
     * every one of which must leave the capture refused rather than admitting uncharged.
     */
    fun prepareComposedToken(): Boolean = synchronized(prepareLock) {
        // FRESHNESS BEFORE THE CHARGE (reviewer 3d1f87be #4). Checking only in the deadline wrapper
        // guards the TAKE, by which point the ledger has already been charged: a preparation after
        // expiry would consume capacity the server will no longer honour. The charge must not
        // happen at all, so the check belongs here, before prepareToken().
        val grant = currentGrant ?: return false
        if (!grant.isFresh(elapsedMs())) return false
        lastComposedOwner?.prepareToken() == true
    }

    suspend fun provision(): Result {
        // THE ATTEMPT RECORDS WHICH PREREQUISITE STATE IT SAW, NEVER WHETHER IT SUCCEEDED
        // (spec 9a9faf8f). The gate re-arms on a change of the PASSIVE state. An attempt's outcome
        // is not a change of that state, but it IS the attempt's business which state it observed:
        // whichever state the passive probe reported when this attempt began is what has now been
        // acted on. Recording the outcome instead made a single failed acquisition re-arm forever
        // (probe still READY, recorded NOT_READY), and recording a blanket READY here swallowed the
        // transition so a LATER genuine sign-in could no longer re-arm.
        val observedLocally = if (readinessProvider()) Readiness.READY else Readiness.NOT_READY
        val cred = credential()
        if (cred == null) {
            readinessActedOn = observedLocally
            return Result.Refused(REASON_NO_CREDENTIAL)
        }
        // UNKNOWN ACCOUNT DEFERS, IT DOES NOT SEND (spec e2cd78d8, cloud 8d1ffaaf). A cold cache
        // is a readiness condition, not permission to present an unbound request. Note this is
        // checked before ANY durable or transport work, so there is nothing to undo.
        if (cred.accountId.isBlank()) {
            // Same rule: record the state observed, not the outcome.
            readinessActedOn = observedLocally
            return Result.Deferred
        }
        // Past this point the attempt is authorised to exist, so readiness is no longer the
        // blocker: whatever happens next (issued / refused / transport) is a server-side verdict.
        readinessActedOn = Readiness.READY
        val attemptId = UUID.randomUUID().toString()
        // Announce THIS attempt before any transport work: a newer attempt supersedes any
        // older in-flight one at install time.
        beginInstallAttempt(attemptId)
        val start = elapsedMs()
        // PERSIST BEFORE SENDING (reviewer 3ba1e605): the id that goes on the wire is the one the
        // next attempt will reuse. A crash between here and the response leaves a persisted id
        // whose re-send is a replay -- never a second allocation.
        val store = identityStore
        // PROVEN EXHAUSTION ADVANCES (reviewer 3d1f87be #1). Without this the ONLY way the client
        // ever advances is by failing, and every advancement-by-failure is a fresh charge. A ledger
        // that can no longer admit is provable locally and needs no server round trip: retire the
        // allocation so this attempt asks for a new one. Note this runs BEFORE acquire(), so the
        // retired id is not presented again.
        store?.let { st ->
            val spent = lastComposedOwner
            val spentIdentity = composedIdentity
            if (spent != null && spent.isExhausted()) {
                // COMPARE-AND-SWAP BOUND TO THE OWNER'S OWN ALLOCATION (reviewer 7c220c1d, cloud
                // b2f0df12): the proof was computed from a SPENT owner, so it may only advance the
                // identity that owner was composed under -- never whatever is current now, which
                // could be a newer allocation this stale proof would otherwise delete.
                // AN UNCONFIRMED ADVANCE STOPS THE ATTEMPT (reviewer 6a6eec2a, c690ad32). If the
                // retirement could not be made durable, presenting a replacement would allocate
                // against a state nobody can vouch for.
                if (spentIdentity != null && !st.advance(spentIdentity)) {
                    return Result.Refused(REASON_IDENTITY_AMBIGUOUS)
                }
                lastComposedOwner = null
                currentGrant = null
                composedIdentity = null
                // RECORD the spend so the next decision can see it. provision() clears the owner
                // above, and the gate runs BEFORE provision() -- so without this the very state
                // that justifies asking the server is erased one step before it is consulted, and
                // the boundary utterance is refused again (reviewer 6a6eec2a gap 3).
                spentSinceLastProvision = true
            }
        }
        // ACCOUNT PER DECISION, not captured at construction (reviewer 7c220c1d #2): the credential
        // that authorises THIS attempt is the authoritative account for it.
        val acquisition = store?.acquire(cred.enrollmentEpoch, cred.accountId)
        // AMBIGUOUS PERSISTED STATE REFUSES (reviewer b36642db / cloud ffeaa62d). Re-minting is
        // indistinguishable server-side from a first attempt: it allocates and charges a new
        // batch, with no record that an identity was lost. Refusing costs a capture; re-minting
        // costs capacity that cannot be returned.
        if (acquisition is CaptureAllowanceAllocationIdentityStore.Acquisition.Ambiguous) {
            return Result.Refused(REASON_IDENTITY_AMBIGUOUS)
        }
        val identity = (acquisition as? CaptureAllowanceAllocationIdentityStore.Acquisition.Held)?.identity
        // NO STORE = the caller opted out of durability (controls only). Otherwise the id is the
        // persisted one; a fresh UUID here would defeat the whole mechanism.
        val requestId = identity?.let { UUID.fromString(it.requestId) } ?: UUID.randomUUID()
        val body = try {
            CaptureAllowanceBatch.requestJson(requestId, cred.enrollmentEpoch)
        } catch (e: IllegalArgumentException) {
            return Result.Refused(REASON_EPOCH_TOO_LONG)
        }
        val response = try {
            api.postCaptureAllowance("Bearer " + cred.bearer, body.toRequestBody(JSON_MEDIA))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Refused(REASON_TRANSPORT)
        }
        // BODY HYGIENE: every exit path closes the response (and error) body — non-200,
        // missing 200 body, oversized Content-Length, read failure — nothing leaks a socket.
        val responseBody = response.body()
        val errorBody = response.errorBody()
        if (response.code() != 200) {
            responseBody?.close()
            errorBody?.close()
            // ADVANCEMENT IS THE SERVER'S CALL (reviewer d6171776, cloud a42eea42/bcc9a42a).
            // A client-side calendar change is never proof of anything: the accounting day is
            // server state and is deliberately not persisted, so a day rollover costs one `410`
            // round trip rather than a comparison against this device's clock.
            when {
                // Durability disabled by the caller (controls only).
                store == null || identity == null -> Unit

                // 410: the server's window moved. One round trip, no charge.
                // `410` ADVANCES ONLY IF THE RETIREMENT IS DURABLE (reviewer c690ad32): an
                // unconfirmed advance must refuse, not report success and mint a replacement.
                response.code() == 410 -> if (!store.advance(identity)) {
                    return Result.Refused(REASON_IDENTITY_AMBIGUOUS)
                }

                // 409: we presented an id whose stored epoch differs from the one we sent. Since
                // the epoch travels WITH the id, that can only mean the id survived an enrollment
                // change -- a client state-machine bug. Discard and mint, and say so loudly: a
                // silent retry would hide the bug that produced it (cloud bcc9a42a).
                // 409: record it and REFUSE further allocation under this identity. Deleting the
                // file here -- as an earlier revision did -- let the next ordinary acquire mint a
                // fresh id under unchanged authority, which is the bypass, not the fix.
                response.code() == 409 -> {
                    store.recordConflict(identity)
                    identityConflict()
                }

                // 429 RETAINS the id: a fresh one refuses identically and stores another row.
                response.code() == 429 -> store.retain(identity)

                // 503 retries the SAME id; other 4xx are terminal for that id, no advance.
                else -> Unit
            }
            return Result.Refused("http_${response.code()}")
        }
        if (responseBody == null) {
            errorBody?.close()
            return Result.Refused(CaptureAllowanceBatch.REASON_MALFORMED)
        }
        // BOUNDED READ: the envelope ceiling applies to the WIRE, not just the parsed string.
        // Content-Length is checked first when present; the stream is then read with a hard
        // cap so an oversized or endless body can neither balloon memory nor be parsed. A
        // stream-read failure is a refusal, not an exception carrying stale-authority state.
        val contentLength = response.headers()["Content-Length"]?.toLongOrNull()
        if (contentLength != null && contentLength > CaptureAllowanceBatch.MAX_ENVELOPE_BYTES) {
            responseBody.close()
            return Result.Refused(CaptureAllowanceBatch.REASON_OVERSIZED)
        }
        val cap = CaptureAllowanceBatch.MAX_ENVELOPE_BYTES + 1L
        val text: String
        try {
            var oversized = false
            var read: String? = null
            // FINALLY-CLOSE: the explicit body close runs on EVERY exit — oversized, read
            // failure, even a throw from source() acquisition itself.
            try {
                val src = responseBody.source()
                try {
                    val buffered = okio.Buffer()
                    while (buffered.size <= CaptureAllowanceBatch.MAX_ENVELOPE_BYTES) {
                        if (src.read(buffered, cap - buffered.size) == -1L) break
                    }
                    if (buffered.size > CaptureAllowanceBatch.MAX_ENVELOPE_BYTES) {
                        oversized = true
                    } else {
                        read = buffered.readUtf8()
                    }
                } finally {
                    responseBody.close()
                }
            } finally {
                responseBody.close() // idempotent; second close for hygiene clarity
            }
            if (oversized) return Result.Refused(CaptureAllowanceBatch.REASON_OVERSIZED)
            text = read ?: return Result.Refused(CaptureAllowanceBatch.REASON_MALFORMED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Refused(REASON_TRANSPORT)
        }
        return when (val parsed = CaptureAllowanceBatch.parse(text, start, elapsedMs())) {
            is CaptureAllowanceBatch.Parsed.Refused -> Result.Refused(parsed.reason)

            is CaptureAllowanceBatch.Parsed.Batch -> {
                // REQUEST ECHO: the response must answer exactly the request we sent, with the
                // epoch we sent. Anything else is a different conversation wearing a 200.
                if (parsed.requestId != requestId.toString() || parsed.enrollmentEpoch != cred.enrollmentEpoch) {
                    return Result.Refused(REASON_MISMATCH)
                }
                val owner = CaptureAllowanceOwner(
                    // PER-BATCH AREA: the batch id (a parser-validated UUID) keys the ledger
                    // area; the batch id joins the account binding so a batch ledger can never
                    // be read as another batch's or another account's accounting.
                    area = File(baseArea, parsed.batchId),
                    account = cred.accountId + ":" + parsed.batchId,
                    limits = AllowanceLimits(
                        maxDailyRecords = parsed.batchMaxRecords,
                        maxDailyBytes = parsed.batchMaxBytes,
                        maxClipBytes = parsed.maxClipBytes,
                        maxMetadataBytes = parsed.maxMetadataBytes.toInt(),
                        ceilings = ceilings,
                    ),
                    window = AllowanceWindow.Established(parsed.windowId),
                    // REPLAY ASYMMETRY (spec b002b0d4): only `issued` may establish a fresh
                    // ledger. A `replayed` grant binds WITHOUT establishment power, so it can
                    // reopen an intact ledger but can never first-provision — or reset spent
                    // capacity after — a lost one.
                    provisioning = if (parsed.disposition == CaptureAllowanceBatch.DISPOSITION_ISSUED) {
                        AllowanceProvisioning.ServerIssued(batchId = parsed.batchId, windowId = parsed.windowId)
                    } else {
                        AllowanceProvisioning.Unavailable
                    },
                )
                // IDENTITY FENCE: supersession + identity + deadline validation AND
                // installation AND publication all execute inside ONE owner transaction —
                // the provisioner records nothing itself after a suspending callback.
                val grant = Grant(parsed.batchId, start, parsed.validForMs)
                val publish = {
                    // The COMPOSED owner is published so the capture path can PREPARE the durable
                    // charge on the exact object the fence bound (reviewer b36642db: the composed
                    // owner was never prepared, so a successful response could not admit).
                    lastComposedOwner = owner
                    currentGrant = grant
                    composedIdentity = identity
                    spentSinceLastProvision = false
                }
                if (!installAuthority(owner, cred.generation, cred.session, cred.enrollmentEpoch, attemptId, grant, publish)) {
                    return Result.Refused(REASON_IDENTITY_CHANGED)
                }
                Result.Provisioned(
                    batchId = parsed.batchId,
                    windowId = parsed.windowId,
                    disposition = parsed.disposition,
                    requestStartElapsedMs = start,
                    validForMs = parsed.validForMs,
                )
            }
        }
    }

    private val prepareLock = Any()

    companion object {
        const val REASON_NO_CREDENTIAL = "allowance_no_credential"

        /** Reported when a deferral is eventually abandoned; never used to justify a send. */
        const val REASON_DEFERRED = "allowance_deferred_account_unknown"
        const val REASON_EPOCH_TOO_LONG = CaptureAllowanceBatch.REASON_EPOCH_TOO_LONG
        const val REASON_TRANSPORT = "allowance_transport_failed"
        const val REASON_MISMATCH = "allowance_response_mismatch"
        const val REASON_IDENTITY_CHANGED = "allowance_identity_changed"

        /** Persisted allocation state exists but cannot be trusted; capture must refuse. */
        const val REASON_IDENTITY_AMBIGUOUS = "allowance_identity_ambiguous"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
