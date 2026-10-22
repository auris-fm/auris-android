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
     * The upload attempt seam. Called ONLY under a freshly validated ownership generation, after the
     * policy and network gates passed, with the in-flight claim held. Returns true on accepted upload.
     */
    private val attemptUpload: (generation: Long, captureId: String) -> Boolean,
    /** Upload-completion hook (server ack path); not part of this slice's discipline. */
    private val onSuccess: (generation: Long, captureId: String) -> Unit = { _, _ -> },
) {

    data class PollResult(
        val attempted: Int,
        val deferredPolicy: Int,
        val deferredNetwork: Int,
        val skippedInFlight: Int,
        val cancelledEpoch: Int,
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

        // Eligibility ALREADY holds every store-level gate: readiness, accounting/coverage, attempt
        // budget, unresolved records and the durable not-before boundary (reviewers 199ccdee, 1d29fa1e).
        val eligible = store.retryEligible(clock())
        val generation = store.fence.currentGeneration()
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
                // Gate order is policy -> network -> epoch: cheapest and most semantic first, and the
                // EPOCH last so the generation is as fresh as possible when the attempt starts.
                if (!policyAllows()) {
                    deferredPolicy++
                    continue
                }
                if (!networkAvailable()) {
                    deferredNetwork++
                    continue
                }
                // The attempt runs INSIDE the fence: if a revocation landed since the poll, the epoch is
                // invalid and the attempt is cancelled, not started (reviewer 6b236641).
                val outcome = store.fence.guard(generation) {
                    if (attemptUpload(generation, captureId)) AttemptOutcome.ACCEPTED else AttemptOutcome.RETRYABLE
                }
                when (outcome) {
                    null -> cancelledEpoch++

                    AttemptOutcome.ACCEPTED -> {
                        attempted++
                        onSuccess(generation, captureId)
                    }

                    AttemptOutcome.RETRYABLE -> {
                        attempted++
                        // Durable re-arm: ONE ownership transaction in the store; a revocation racing the
                        // schedule surfaces as the store's own refusal and does not fail the poll.
                        runCatching { store.scheduleRetryAfterFailure(generation, captureId, retryAfterS = null) }
                    }
                }
            } finally {
                synchronized(inFlightLock) { inFlight.remove(captureId) }
            }
        }
        return PollResult(attempted, deferredPolicy, deferredNetwork, skippedInFlight, cancelledEpoch)
    }

    fun close() {
        closed.set(true)
    }

    private enum class AttemptOutcome { ACCEPTED, RETRYABLE }
}
