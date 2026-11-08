package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Production retry CALLER: the composition unit between the store and the uploader (reviewer 6b236641).
 * It polls the store's retry eligibility, enforces ONE attempt in flight per entry, and rechecks the
 * account/policy gate, the network gate and the OWNERSHIP EPOCH before EVERY attempt. The store owns
 * durability and accounting; this class owns only the calling discipline.
 *
 * NOT provided by this class: the actual upload transport, upload-completion handling (server ack ->
 * drop), and the scheduling clock source. Those are the uploader integration's responsibility; the
 * [attemptUpload] seam and [onSuccess] hook exist so the discipline can be pinned without a network.
 */
class ContributionRetryCaller(
    private val store: ContributionStore,
    private val clock: () -> Long,
    /** Network gate. Rechecked before EVERY attempt. */
    private val networkAvailable: () -> Boolean,
    /**
     * The upload attempt seam. Called ONLY after the atomic pickup durably consumed the attempt, with
     * the in-flight claim held. [isRevoked] is bound to the CURRENT authorization epoch and MUST be
     * consulted before the first network operation (and on continuation boundaries): a revocation that
     * lands between pickup and transport start ABORTS the upload - it authorizes nothing (reviewer
     * 279de1d4). Returns true on accepted upload; throws on lost/failed transport.
     */
    private val attemptUpload: (
        generation: Long,
        captureId: String,
        isRevoked: () -> Boolean,
        /** Installs this attempt's live call abort ON THE LEASE, so revocation reaches the Call. */
        bindCancellation: (action: () -> Unit) -> Unit,
    ) -> Boolean,
    /** Upload-completion hook (server ack path); not part of this slice's discipline. */
    private val onSuccess: (generation: Long, captureId: String) -> Unit = { _, _ -> },
    /**
     * The REAL ack resolver this caller composes with (spec 442bfbd7). A transport `true` NEVER deletes
     * by itself: the resolver validates the actual response payload against the wire receipt contract
     * BEFORE any release, and every mutation is token-scoped, so a same-generation successor is never
     * touched by an old completion.
     */
    private val ackResolver: ContributionAckResolver,
    /** The transport response payload for the attempt just run (null when the response was lost). */
    private val responseFor: (generation: Long, captureId: String) -> ByteArray? = { _, _ -> null },
    /** The server's Retry-After for this attempt, when it sent one. A MINIMUM for the next attempt. */
    private val retryAfterFor: (generation: Long, captureId: String) -> Long? = { _, _ -> null },
    /**
     * The HTTP status the acceptance arrived on. The receipt's own status must PUBLISH with it
     * (201/accepted, 200/duplicate); a body alone is never enough to release.
     */
    private val httpStatusFor: (generation: Long, captureId: String) -> Int? = { _, _ -> null },
    /** The owning-table disposition of this attempt's refusal, when the server refused with a code. */
    private val dispositionFor: (generation: Long, captureId: String) -> ContributionUploadTransport.Response? = { _, _ -> null },
    /**
     * MONITORING ONLY (review 3c0f31d3 gap 1): the status the transport observed, whatever family it
     * belonged to. Feeds ONLY the settle line, so a terminal/retry settlement names the real wire answer
     * instead of `http=none`. Never used for validation or classification. The WIRE line itself is
     * emitted in ContributionUploadAssembly at the point of observation (review 3271cf1c).
     */
    private val observedStatusFor: (generation: Long, captureId: String) -> Int? = { _, _ -> null },
) {

    data class PollResult(
        val attempted: Int,
        val deferredNetwork: Int,
        val skippedInFlight: Int,
        val cancelledEpoch: Int,
        /** Entries whose ATOMIC PICKUP refused: stale epoch, no longer eligible, or unpersisted count. */
        val pickupRefused: Int,
    )

    private val inFlight = mutableSetOf<String>()
    private val inFlightLock = Any()
    private val closed = AtomicBoolean(false)

    fun pollOnce(): PollResult {
        check(!closed.get()) { "caller closed" }
        var attempted = 0
        var deferredNetwork = 0
        var skippedInFlight = 0
        var cancelledEpoch = 0
        var pickupRefused = 0

        // The epoch is captured WITH (before) the eligibility snapshot: a revoke landing between the
        // two reads leaves a STALE generation, which the pickup refuses - old work is never relabelled
        // with a new epoch (reviewer 1b926fa0).
        val generation = store.fence.currentGeneration()
        // Eligibility ALREADY holds every store-level gate: readiness, accounting/coverage, attempt
        // budget, unresolved records and the durable not-before boundary (reviewers 199ccdee, 1d29fa1e).
        val eligible = store.retryEligible(clock())
        for (captureId in eligible) {
            // ONE attempt in flight per entry: the claim is taken before ANY gate runs, so a slow gate
            // or a re-entrant poll tick cannot start a second attempt on the same capture.
            val claimed = synchronized(inFlightLock) {
                if (captureId in inFlight) false else inFlight.add(captureId)
            }
            if (!claimed) {
                skippedInFlight++
                continue
            }
            try {
                if (!networkAvailable()) {
                    deferredNetwork++
                    continue
                }
                // ATOMIC PICKUP: the guarded transaction revalidates the COMPLETE current eligibility
                // and durably records/consumes the attempt BEFORE any transport runs (reviewer
                // 1b926fa0, spec 8aa20001). A gate callback that revoked the epoch, re-armed the backoff
                // or changed coverage is caught HERE - generation validity alone is not enough.
                // The token is this attempt's identity for its WHOLE lifetime: never re-read from the
                // store, never borrowed from currentGeneration (reviewer 20a5440b, spec d6213403).
                val token = store.pickupAttemptToken(generation, captureId, clock())
                if (token == null) {
                    pickupRefused++
                    continue
                }
                // LEASE, never hold the monitor (reviewer 4d72e463): authorization is taken in ONE
                // ownership transaction and RELEASED before any network work, so an external revocation
                // is never blocked and can cancel this attempt while it is in flight.
                val lease = store.leaseTransportLaunch(generation, captureId)
                if (lease == null) {
                    // Refused before the first request: the attempt was already consumed, so this is an
                    // unconfirmed outcome. Settlement is token-scoped: it validates AND mutates in one
                    // transaction, so a successor's state can never be touched here.
                    cancelledEpoch++
                    resolve(token, ContributionAckResolver.Ack.RETRYABLE, null)
                    continue
                }
                try {
                    // The binder is the LEASE itself, constructed here where the lease lives: whoever
                    // registers a live call with it hands the lease the real abort, and
                    // revokeAndCancelLaunches then cancels that Call directly (reviewer 99b3947d).
                    // MONITORING (spec 827c5962): upload START. Content-free: opaque capture id,
                    // attempt number and generation only. Paired with the settle line in
                    // ContributionAckResolver this makes "attempted but never settled" visible, which was
                    // previously indistinguishable from "never attempted".
                    Timber.tag(UPLOAD_TAG).i(
                        "voice-upload start: capture=%s attempt=%d generation=%d",
                        captureId,
                        token.attemptNumber,
                        generation,
                    )
                    val accepted = attemptUpload(
                        generation,
                        captureId,
                        { lease.isCancelled() || !store.fence.isValid(generation) },
                    ) { abort -> lease.bindAbort(abort) }
                    if (lease.isCancelled()) {
                        // A revocation withdrew authorization mid-flight, so the response cannot be trusted
                        // for this owner. Token-scoped RETRYABLE: retry-boundary disposal only.
                        cancelledEpoch++
                        resolve(token, ContributionAckResolver.Ack.RETRYABLE, null)
                    } else {
                        // The response is classified by the REAL resolver against the wire receipt; a
                        // Boolean transport success deletes nothing by itself.
                        // The owning response table decides: a terminal code is SERVER_TERMINAL
                        // disposal (token-scoped, epoch-gated); everything else retries or retains.
                        // The WIRE answer itself is logged in ContributionUploadAssembly at the point of
                        // observation (review 3271cf1c) - it must survive a cancellation throw, which a
                        // caller-side line cannot. Only the settlement decision is logged here.
                        val ack = when (val disposition = dispositionFor(generation, captureId)) {
                            is ContributionUploadTransport.Response.Terminal -> ContributionAckResolver.Ack.TERMINAL_DISPOSAL

                            // 400 invalid_request: delete at once through the reconciled purge
                            // (spec 1a0d3ffb, amending 5c811f83's retention ruling).
                            is ContributionUploadTransport.Response.Unservable -> ContributionAckResolver.Ack.UNSERVICED

                            else -> if (accepted) ContributionAckResolver.Ack.ACCEPTED else ContributionAckResolver.Ack.RETRYABLE
                        }
                        val payload = if (accepted) responseFor(generation, captureId) else null
                        // Receipt-validation input is UNCHANGED for ACCEPTED (review 3c0f31d3). Other
                        // families now carry their observed status ONLY so the settle line names the real
                        // wire answer; validation never runs on them.
                        val httpStatus =
                            if (accepted) {
                                httpStatusFor(generation, captureId)
                            } else {
                                observedStatusFor(generation, captureId)
                            }
                        // The server minimum survives the resolver: it is a floor on the next attempt.
                        val retryAfterS = if (accepted) null else retryAfterFor(generation, captureId)
                        // WHAT CAME IMMEDIATELY BEFORE, for the 409-after-5xx hold (spec 219d3bb6 /
                        // 7087cf00): a conflict is only decisive when the previous outcome was not a
                        // server failure. Recorded from the REAL observed status, never from the ack.
                        store.recordTransportOutcome(captureId, serverError = httpStatus != null && httpStatus in serverErrorRange)
                        when (resolve(token, ack, payload, retryAfterS, httpStatus)) {
                            is ContributionAckResolver.Resolution.Released -> {
                                attempted++
                                onSuccess(token.generation, captureId)
                            }

                            is ContributionAckResolver.Resolution.UnvalidatedAccept,
                            is ContributionAckResolver.Resolution.Retained,
                            is ContributionAckResolver.Resolution.Held,
                            -> attempted++

                            ContributionAckResolver.Resolution.StaleEpoch -> cancelledEpoch++
                        }
                    }
                } catch (lost: Throwable) {
                    // A thrown upload is AMBIGUOUS: token-scoped retain, never a release.
                    if (resolve(token, ContributionAckResolver.Ack.AMBIGUOUS, null) is ContributionAckResolver.Resolution.Retained) {
                        attempted++
                    } else {
                        cancelledEpoch++
                    }
                } finally {
                    store.releaseLaunchLease(lease)
                }
            } finally {
                synchronized(inFlightLock) { inFlight.remove(captureId) }
            }
        }
        val result = PollResult(attempted, deferredNetwork, skippedInFlight, cancelledEpoch, pickupRefused)
        // EVERY drain answers why (spec 2aea78c3): an empty drain must say whether it saw eligible
        // work and where that work was refused, instead of discarding the PollResult in the drain
        // lambda (VoiceUploadModule provideVoiceUploadDrain) and logging nothing.
        // CAPACITY IS A RECORD COUNT (spec a3ef694f): records and their bound are reported with every
        // drain, so an empty drain can be told apart from a full queue without any unknown-footprint state.
        Timber.i(
            "voice-upload drain: records=%d limit=%d",
            store.ownedRecordsForTest(),
            store.recordLimit(),
        )
        Timber.i(
            "voice-upload drain: eligible=%d attempted=%d deferredNetwork=%d skippedInFlight=%d cancelledEpoch=%d pickupRefused=%d",
            eligible.size,
            result.attempted,
            result.deferredNetwork,
            result.skippedInFlight,
            result.cancelledEpoch,
            result.pickupRefused,
        )
        return result
    }

    /** Durable re-arm, token-scoped: ONE ownership transaction validates the attempt AND mutates.
     *  Null means this attempt no longer owns the queue, so nothing was mutated. */
    // Token-scoped resolution through the REAL resolver; an uncertain store surface stays ambiguous.
    private fun resolve(
        token: ContributionStore.AdmittedAttempt,
        ack: ContributionAckResolver.Ack,
        payload: ByteArray?,
        retryAfterS: Long? = null,
        httpStatus: Int? = null,
    ): ContributionAckResolver.Resolution = runCatching { ackResolver.resolveForAttempt(token, ack, payload, retryAfterS, httpStatus) }
        .getOrElse { ContributionAckResolver.Resolution.StaleEpoch }

    fun close() {
        closed.set(true)
    }

/** HTTP statuses that mean the SERVER failed, so a following 409 proves nothing about the capture. */
    private val serverErrorRange = 500..599
}

private const val UPLOAD_TAG = "VoiceUploadCaller"
