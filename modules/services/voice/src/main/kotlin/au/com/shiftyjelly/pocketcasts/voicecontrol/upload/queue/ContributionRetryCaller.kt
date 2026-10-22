package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.util.concurrent.atomic.AtomicBoolean

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
    /** Account/policy gate (sharing enabled, account valid). Rechecked before EVERY attempt. */
    private val policyAllows: () -> Boolean,
    /** Network gate. Rechecked before EVERY attempt, after the policy gate. */
    private val networkAvailable: () -> Boolean,
    /**
     * The upload attempt seam. Called ONLY after the atomic pickup durably consumed the attempt, with
     * the in-flight claim held. [isRevoked] is bound to the CURRENT authorization epoch and MUST be
     * consulted before the first network operation (and on continuation boundaries): a revocation that
     * lands between pickup and transport start ABORTS the upload - it authorizes nothing (reviewer
     * 279de1d4). Returns true on accepted upload; throws on lost/failed transport.
     */
    private val attemptUpload: (generation: Long, captureId: String, isRevoked: () -> Boolean) -> Boolean,
    /** Upload-completion hook (server ack path); not part of this slice's discipline. */
    private val onSuccess: (generation: Long, captureId: String) -> Unit = { _, _ -> },
) {

    data class PollResult(
        val attempted: Int,
        val deferredPolicy: Int,
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
        var deferredPolicy = 0
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
                // Gate order is policy -> network: cheapest and most semantic first.
                if (!policyAllows()) {
                    deferredPolicy++
                    continue
                }
                if (!networkAvailable()) {
                    deferredNetwork++
                    continue
                }
                // ATOMIC PICKUP: the guarded transaction revalidates the COMPLETE current eligibility
                // and durably records/consumes the attempt BEFORE any transport runs (reviewer
                // 1b926fa0, spec 8aa20001). A gate callback that revoked the epoch, re-armed the backoff
                // or changed coverage is caught HERE - generation validity alone is not enough.
                val recordedAttempt = store.pickupAttempt(generation, captureId, clock())
                if (recordedAttempt == null) {
                    pickupRefused++
                    continue
                }
                // The admitted attempt's identity: completions settle ONLY against this, never against
                // a re-read currentGeneration (reviewer d1484829 finding 3).
                val admitted = store.admittedAttemptForTest(captureId)
                if (admitted == null) {
                    pickupRefused++
                    continue
                }
                // Between pickup and transport start the authorization can still disappear. That is
                // checked in the SAME atomic decision as the transport launch: the check runs inside the
                // ownership transaction that would be the first network operation's authorization, so a
                // revocation that completed before this point cannot be raced past (reviewer 279de1d4).
                // The transport launch is the authorization boundary: the final validity check runs in
                // the SAME transaction as the launch, so an epoch bump that completed before it is seen
                // (a revocation that lands after it is handled by the completion resolution).
                // LEASE, don't hold the monitor (reviewer 4d72e463): the launch authorization is taken in
                // ONE ownership transaction that is RELEASED before any network work, so an external
                // revocation is never blocked and can cancel this attempt while it is in flight.
                val lease = store.leaseTransportLaunch(generation, captureId)
                if (lease == null) {
                    // Refused before the first request: the attempt was already consumed, so this is an
                    // unconfirmed outcome. Durable boundary under a CURRENT generation - never a stale
                    // one, so a fresh owner recovers the same wait (reviewer 4d72e463).
                    cancelledEpoch++
                    reArmBoundary(store.fence.currentGeneration(), captureId)
                    continue
                }
                try {
                    val accepted = attemptUpload(generation, captureId) { lease.isCancelled() || !store.fence.isValid(generation) }
                    if (!store.attemptIdentityHolds(generation, captureId, admitted.attemptNumber)) {
                        // This attempt is superseded (epoch changed or another attempt was admitted):
                        // neither success nor failure may touch the queue for it.
                        cancelledEpoch++
                    } else if (lease.isCancelled()) {
                        // A revocation withdrew authorization while the upload ran. The response cannot be
                        // trusted for THIS owner: retain and leave a durable boundary under a CURRENT
                        // generation so the next poll cannot retry immediately (reviewer d1484829 - this
                        // branch is an unconfirmed outcome just like a throw).
                        cancelledEpoch++
                        reArmBoundary(store.fence.currentGeneration(), captureId)
                    } else if (accepted) {
                        attempted++
                        onSuccess(generation, captureId) // reached only under a still-valid attempt identity
                    } else {
                        attempted++
                        reArmBoundary(generation, captureId)
                    }
                } catch (lost: Throwable) {
                    // A thrown upload (ordinary network exception / lost response) is an AMBIGUOUS
                    // outcome: ownership is RETAINED and the durable boundary re-armed under a CURRENT
                    // generation - never written through a stale one (reviewer 4d72e463). The next poll
                    // must not retry immediately.
                    attempted++
                    reArmBoundary(store.fence.currentGeneration(), captureId)
                } finally {
                    store.releaseLaunchLease(lease)
                }
            } finally {
                synchronized(inFlightLock) { inFlight.remove(captureId) }
            }
        }
        return PollResult(attempted, deferredPolicy, deferredNetwork, skippedInFlight, cancelledEpoch, pickupRefused)
    }

    /** Durable re-arm: ONE ownership transaction in the store; a racing revocation surfaces as the
     *  store's own refusal and does not fail the poll. */
    private fun reArmBoundary(generation: Long, captureId: String) {
        runCatching { store.scheduleRetryAfterFailure(generation, captureId, retryAfterS = null) }
    }

    fun close() {
        closed.set(true)
    }
}
