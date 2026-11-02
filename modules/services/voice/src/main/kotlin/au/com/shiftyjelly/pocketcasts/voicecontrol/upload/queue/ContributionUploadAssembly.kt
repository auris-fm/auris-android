package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import java.io.File

/**
 * The ONE production composition of transport, caller and token-scoped resolver.
 *
 * Until now the caller took `attemptUpload` / `dispositionFor` / `responseFor` / `retryAfterFor` as
 * separate seams and the tests satisfied each by hand, so nothing in the product actually ran
 * caller -> HTTP -> token settlement. This class is that path, and it is the only place a real
 * [ContributionUploadTransport] is wired to a real [ContributionRetryCaller].
 *
 * Discipline this class exists to preserve, all of it enforced elsewhere and merely ROUTED here:
 * - the transport response is classified once, by the transport's owning table, and its `Accepted` payload
 *   is the receipt handed to validation (never a Boolean that could delete on its own);
 * - a cancellation (attempt lease cancelled, or the authorization epoch gone) is checked BEFORE any byte
 *   leaves and again before the outcome is settled, so a response that arrives after revocation is
 *   settled as an unconfirmed outcome rather than an acceptance;
 * - the caller still owns the attempt token and performs every mutation through it.
 */
class ContributionUploadAssembly(
    private val store: ContributionStore,
    private val transport: ContributionUploadTransport,
    /**
     * Supplies the durable capture for a capture id: the audio file, the record that describes it, and the
     * account epoch the record was signed under. Null means "not on disk any more", which is not an
     * acceptance and not a transport outcome - the attempt is held (retained) rather than released.
     */
    private val captureFor: (captureId: String) -> Capture?,
    clock: () -> Long,
    policyAllows: () -> Boolean,
    networkAvailable: () -> Boolean,
    /** Called after a VALIDATED acceptance releases the entry; never on an unvalidated one. */
    private val onAccepted: (generation: Long, captureId: String) -> Unit = { _, _ -> },
    /**
     * IDENTITY BINDING (reviewer 13eeec80): the admitted entry's enrollment epoch must still be the
     * account's current epoch at send time. A switch - including during credential await, which the
     * fence revocation covers - retains the capture instead of sending it under a retired identity.
     */
    private val identityHolds: ((epoch: String) -> Boolean)? = null,
    /**
     * Acquires the credential FOR THE ADMITTED IDENTITY (account/session/epoch) as one owner-lock
     * snapshot, or null when any component disagrees. The assembly freezes the returned bearer into
     * the attempt, so the transport can never consult a fresher identity mid-flight.
     */
    private val credentialFor: ((admitted: UploadIdentityAuthority.AdmittedIdentity) -> UploadIdentityAuthority.LiveCredential?)? = null,
    /**
     * LAUNCH revalidation (reviewer d2f1bfc8): the acquisition lock ends before transmission, so the
     * window between acquisition and Call execution is closed by THIS check - it re-runs the full
     * identity comparison immediately before transport.upload on the caller thread. False refuses
     * (retains), which is the only safe answer for a moved account.
     */
    private val launchIdentityCheck: ((admitted: UploadIdentityAuthority.AdmittedIdentity) -> Boolean)? = null,
    /**
     * Reports that an attempt could NOT prove its descriptor settled within the bounded timeout.
     *
     * Reviewer 7c2f30e3 finding 3: the attempt's `finally` discarded the false result, so an unresolved
     * ownership looked exactly like a clean one. It is reported here instead of being swallowed: the
     * descriptor may still be open, its bytes still retained, and its source lease still holding the
     * directory. This is a diagnostic channel, not a recovery path - ownership stays unresolved until the
     * descriptor actually closes.
     */
    private val onUnresolvedOwnership: (captureId: String, reason: String) -> Unit = { _, _ -> },
    /** Deterministic control seam: runs after acquisition, before launch validation. Tests only. */
    @Volatile var afterAcquisitionForTest: (() -> Unit)? = null,
) {
    /** A durable capture plus the record that claims it, and the epoch the store admitted it under. */
    data class Capture(
        val record: RecordNode.Obj,
        val audio: File,
        val enrollmentEpoch: String? = null,
        /** Admitted ownership from the store's owner sidecar; null = epoch-only claim. */
        val enrollmentAccount: String? = null,
        val enrollmentSession: Long? = null,
        /**
         * The validated bytes as an OPEN DESCRIPTOR (spec ee31a3cc). Non-null means this attempt OWNS the
         * descriptor and must settle it once the call finishes; [audio] is a pathname and must not be read
         * for transmission, because a successor owner can replace it after validation.
         */
        val owned: OwnedAudio? = null,
    )

    /**
     * The last transport answer per capture id. The caller's seams are synchronous and read-after-attempt;
     * keeping this here means the caller never has to re-derive a disposition from a second request.
     */
    private val lastResponse = object : LinkedHashMap<String, ContributionUploadTransport.Response>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ContributionUploadTransport.Response>?): Boolean = size > MAX_TRACKED
    }

    // The receipt must echo the digest the store recorded for THIS capture; the validator reads it from
    // the same queue authority that will be mutated, so a receipt for different bytes never releases.
    private val resolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store))

    val caller: ContributionRetryCaller = ContributionRetryCaller(
        store = store,
        clock = clock,
        policyAllows = policyAllows,
        networkAvailable = networkAvailable,
        ackResolver = resolver,
        // One attempt: refuse before any byte leaves, then send the metadata-first envelope and remember
        // the classified answer. `true` here means only "a receipt came back", never "the capture is safe
        // to delete" - deletion happens solely through the resolver's validation.
        attemptUpload = { _, captureId, isRevoked, bindCancellation ->
            val capture = captureFor(captureId)
            if (capture == null) {
                // A local "nothing to send" is not a transport answer: the caller settles the thrown
                // attempt as ambiguous (retain), which is what a missing capture deserves.
                throw CaptureUnavailable(captureId)
            }
            // OWNERSHIP LIFETIME (reviewer 9a47123e, spec c12c569f): this attempt owns the open descriptor
            // for exactly as long as the call runs. Every exit - refusal, cancellation, failure, or an
            // accepted answer - settles it once. Leaking it here was the defect: captureFor built a Capture
            // and silently dropped the descriptor, one per successful capture.
            //
            // NO PATH FALLBACK: a capture without an owned source cannot be bound to what was validated,
            // so it is refused as unavailable (and retained) rather than sent by pathname.
            val owned = capture.owned ?: throw CaptureUnavailable(captureId)
            try {
                // IDENTITY BINDING: an admitted epoch that is no longer the account's current epoch is a
                // retired identity - the capture is RETAINED, never sent under it.
                if (capture.enrollmentEpoch != null && identityHolds?.invoke(capture.enrollmentEpoch) == false) {
                    throw CaptureUnavailable(captureId)
                }
                // CREDENTIAL ACQUISITION BOUND TO THE ADMITTED IDENTITY (account/session/epoch): one
                // owner-lock snapshot; any component mismatch retains.
                val admitted = UploadIdentityAuthority.AdmittedIdentity(
                    capture.enrollmentAccount,
                    capture.enrollmentSession,
                    capture.enrollmentEpoch,
                )
                var frozenBearer: String? = null
                if (capture.enrollmentEpoch != null && credentialFor != null) {
                    val live = credentialFor(admitted)
                        ?: throw CaptureUnavailable(captureId) // identity moved or no credential in force
                    frozenBearer = live.bearer
                }
                afterAcquisitionForTest?.invoke()
                if (isRevoked()) {
                    throw AttemptCancelled(captureId)
                }
                // The revocation signal now reaches INSIDE the upload: the transport checks it before the
                // first byte, per request-body chunk, and around the response read, so a withdrawal stops a
                // transfer already in flight instead of only being noticed after it finishes.
                // The lease owns this attempt's authorization, so the live call is bound to it: a revocation
                // then cancels the CALL rather than only flipping a flag the blocked writer cannot read.
                val response = transport.upload(
                    captureId,
                    capture.record,
                    owned,
                    isCancelled = isRevoked,
                    authorizationOverride = frozenBearer,
                    // LAUNCH OWNERSHIP at the network edge (reviewer 0081c95f): evaluated by the
                    // transport after construction, before registration - the caller-side check alone
                    // cannot close the acquisition→launch window.
                    validateAtLaunch = if (capture.enrollmentEpoch != null && launchIdentityCheck != null) {
                        { launchIdentityCheck(admitted) }
                    } else {
                        null
                    },
                    // The REAL chain: the transport reports the registered handle, and the abort that cancels
                    // THAT call is installed on the lease which owns this attempt's authorization. A
                    // revocation then reaches a transmitting Call without anyone polling a flag.
                    // SOURCE CANCELLATION IS BOUND TOGETHER WITH CALL CANCELLATION (reviewer 4e8592ba).
                    // Binding only the Call left `cancelFurtherBorrows` with no production caller: the flag
                    // refused future borrows in TESTS while production never set it. Both now happen in the
                    // ONE action the lease installs, so a withdrawal that cancels the transmitting call
                    // ALSO refuses the next replay - including a retry OkHttp asks for after the body was
                    // cut short, which is the case the Call alone cannot cover.
                    onRegistered = { handle ->
                        bindCancellation {
                            // Refuse further borrows FIRST: a body must not be handed out after withdrawal
                            // even if the Call is already finishing.
                            runCatching { owned.cancelFurtherBorrows() }
                            transport.cancelRegistered(handle)
                        }
                    },
                )
                lastResponse[captureId] = response
                // A cancelled attempt has NO answer to settle from, whatever the socket happened to return.
                if (response is ContributionUploadTransport.Response.Cancelled) throw AttemptCancelled(captureId)
                // Cancellation observed again before settlement: the response may belong to a revoked owner.
                if (isRevoked()) throw AttemptCancelled(captureId)
                response is ContributionUploadTransport.Response.Accepted
            } finally {
                // Settle the descriptor on EVERY path above, including the thrown refusals. A bounded
                // timeout is enough because a body writer exits when the call completes; if it does not,
                // ownership stays unresolved rather than being declared clean (spec c12c569f).
                // The RESULT IS REPORTED, never dropped (reviewer 7c2f30e3 finding 3): a false means the
                // descriptor may still be open, its bytes still retained and its source lease still holding
                // the directory. Treating that as success would hide a leak as well as a blocked teardown.
                if (!owned.settle(DESCRIPTOR_SETTLE_TIMEOUT_MS)) {
                    onUnresolvedOwnership(captureId, "descriptor did not settle within ${DESCRIPTOR_SETTLE_TIMEOUT_MS}ms")
                }
            }
        },
        onSuccess = onAccepted,
        // The owning table's terminal family is the ONLY source of terminal disposal.
        dispositionFor = { _, captureId -> lastResponse[captureId] },
        // Only a 2xx body can be a receipt, and only the 2xx body is offered to validation.
        responseFor = { _, captureId ->
            (lastResponse[captureId] as? ContributionUploadTransport.Response.Accepted)?.payload
        },
        retryAfterFor = { _, captureId ->
            (lastResponse[captureId] as? ContributionUploadTransport.Response.Retryable)?.retryAfterS
        },
        // The status the receipt arrived on: validation pairs it with the receipt's own status, so a 200
        // claiming `accepted` (or a 201 claiming `duplicate`) never releases.
        httpStatusFor = { _, captureId ->
            (lastResponse[captureId] as? ContributionUploadTransport.Response.Accepted)?.statusCode
        },
    )

    /** Poll once. Present so callers do not reach past the assembly for anything. */
    fun poll(): ContributionRetryCaller.PollResult = caller.pollOnce()

    /** A revoked attempt, detected before or after the wire exchange. Never an acceptance. */
    class AttemptCancelled(val captureId: String) : Exception("attempt cancelled: $captureId") {
        override fun fillInStackTrace(): Throwable = this
    }

    /** No durable capture to send. Settled as an unconfirmed outcome, never as a release. */
    class CaptureUnavailable(val captureId: String) : Exception("no capture on disk: $captureId") {
        override fun fillInStackTrace(): Throwable = this
    }

    private companion object {
        /** Bounded: this cache exists for the duration of one attempt's settlement, not as storage. */
        const val MAX_TRACKED = 32

        /**
         * How long an attempt waits for its descriptor to settle when the call returns.
         *
         * A received response means the body writer has already finished, so this normally returns at
         * once. It is a deadline only for the pathological case; on expiry ownership stays UNRESOLVED
         * rather than being declared clean, because the descriptor may still be open in a writer.
         */
        const val DESCRIPTOR_SETTLE_TIMEOUT_MS = 2_000L
    }
}
