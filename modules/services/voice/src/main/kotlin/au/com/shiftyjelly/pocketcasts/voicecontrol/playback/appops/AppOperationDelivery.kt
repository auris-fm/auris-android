package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * The ONE route from a v1 app-operation frame to an effect.
 *
 * Everything that can refuse refuses before the effect runs, and the effect runs at most once because
 * the journal — not this class, and not the socket — decides whether admission happened. Settlement is
 * durable BEFORE the result is returned for sending, so a crash between the two leaves a record that
 * a later delivery replays rather than an effect nobody can account for.
 */
object AppOperationDelivery {
    const val REASON_PERSISTENCE_UNAVAILABLE = "persistence_unavailable"
    const val REASON_IDENTITY_CONFLICT = "identity_conflict"
    const val REASON_INVALID_REQUEST = "invalid_request"
    const val REASON_UNSUPPORTED = "unsupported_operation"
    const val REASON_UNOBSERVED = "unobserved_result"

    /** What the effect observed. Only [Observed] may settle `succeeded`. */
    sealed class Observation {
        /** The effect ran and the state it produced was read back from the player. */
        class Observed(val state: JSONObject) : Observation()

        /** The effect did not run, and the reason is known. */
        class Refused(val reason: String) : Observation()

        /**
         * We looked and the effect demonstrably did not take.
         *
         * A TYPED case rather than a reason string on [Unconfirmed]: the two are different facts about
         * the world, and the difference decides the wire outcome (`failed` vs `unknown`). Selecting an
         * outcome by comparing reason strings would make a typo in a constant silently reclassify a
         * failed operation as an unknown one.
         */
        class Failed(val reason: String) : Observation()

        /**
         * The effect ran but its outcome could not be observed; never reported as success.
         *
         * [reason] distinguishes which measurement was missing, because "we could not look" and
         * "the player reported something unreadable" are different stories for whoever reads the
         * record later, even though both resolve to `unknown`.
         */
        class Unconfirmed(val reason: String) : Observation()
    }

    /** The result document to send, and whether it came from the journal rather than this delivery. */
    class Delivery(val resultJson: String, val replayed: Boolean)

    /**
     * Admits, executes once, settles, and returns the result document.
     *
     * [effect] is the journal-protected adapter to the executor: it runs only on an executed
     * admission, and it is `suspend` because observing real player state is.
     */
    suspend fun deliver(
        request: AppOperationV1.Request,
        claim: AppOperationJournal.Claim,
        store: AppOperationJournal.Store?,
        effect: suspend () -> Observation,
    ): Delivery? {
        if (store == null) {
            return refused(request.operationId, REASON_PERSISTENCE_UNAVAILABLE, replayed = false)
        }
        // `payloadJson` is non-null only for a publishable result; a null answer is a codec verdict
        // that the result cannot be sent at all, and it must not be turned into a fabricated one.
        // The digest is over the ORIGINAL delivered bytes: replay identity is defined by what the
        // server sent, not by how this client decoded it.
        val digest = AppOperationJournal.digest(request.rawBytes)
        return when (val verdict = AppOperationJournal.admit(claim, digest, store)) {
            is AppOperationJournal.Verdict.Replay ->
                Delivery(
                    AppOperationV1.result(request.operationId, verdict.outcome, verdict.reason, verdict.state) ?: return null,
                    replayed = true,
                )

            is AppOperationJournal.Verdict.IdentityConflict ->
                refused(request.operationId, REASON_IDENTITY_CONFLICT, replayed = false)

            is AppOperationJournal.Verdict.PersistenceUnavailable ->
                refused(request.operationId, REASON_PERSISTENCE_UNAVAILABLE, replayed = false)

            is AppOperationJournal.Verdict.StorageCorrupt ->
                refused(request.operationId, REASON_PERSISTENCE_UNAVAILABLE, replayed = false)

            is AppOperationJournal.Verdict.Execute -> {
                // FROM HERE ON THE EFFECT MAY HAVE RUN. An exception out of either the effect or the
                // settlement leaves us unable to say whether it took effect, so it is reported as
                // `unknown` -- never as `refused`, which would claim no execution occurred and invite
                // a retry that must never happen. The claim stays in progress on disk for the same
                // reason. Cancellation is not caught here: it propagates, because a cancelled turn is
                // retired rather than answered.
                val observation = try {
                    effect()
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    // The executor may have mutated and then thrown; that is not a refusal.
                    return settlementUnknown(request)
                }
                when (observation) {
                    is Observation.Observed -> {
                        val settled = trySettle(
                            claim = verdict.claim,
                            digest = digest,
                            outcome = AppOperationV1.OUTCOME_SUCCEEDED,
                            reason = null,
                            state = observation.state,
                            store = store,
                        )
                        // THE EFFECT ALREADY HAPPENED HERE. A failed settlement is therefore NOT a
                        // refusal: `refused` would claim no execution occurred, which is false and
                        // would invite a retry that must never re-execute. The honest answer is
                        // `unknown` -- we cannot say what happened, because the record that would
                        // have said so was not written. The claim stays in progress on disk for the
                        // same reason: restart/replay must not run this operation a second time.
                        if (!settled) {
                            Delivery(
                                AppOperationV1.result(
                                    request.operationId,
                                    AppOperationV1.OUTCOME_UNKNOWN,
                                    REASON_UNOBSERVED,
                                ) ?: return null,
                                replayed = false,
                            )
                        } else {
                            Delivery(
                                AppOperationV1.result(request.operationId, AppOperationV1.OUTCOME_SUCCEEDED, null, observation.state) ?: return null,
                                replayed = false,
                            )
                        }
                    }

                    is Observation.Refused -> {
                        trySettle(
                            claim = verdict.claim,
                            digest = digest,
                            outcome = AppOperationV1.OUTCOME_REFUSED,
                            reason = observation.reason,
                            state = null,
                            store = store,
                        )
                        Delivery(
                            AppOperationV1.result(request.operationId, AppOperationV1.OUTCOME_REFUSED, observation.reason) ?: return null,
                            replayed = false,
                        )
                    }

                    // "WE LOOKED AND THE EFFECT DID NOT TAKE" IS NOT "UNKNOWN", and the TYPE says
                    // so: no reason-string comparison decides it.
                    is Observation.Failed -> settleNonSuccess(
                        request = request,
                        claim = verdict.claim,
                        digest = digest,
                        outcome = AppOperationV1.OUTCOME_FAILED,
                        reason = observation.reason,
                        store = store,
                    )

                    is Observation.Unconfirmed -> settleNonSuccess(
                        request = request,
                        claim = verdict.claim,
                        digest = digest,
                        outcome = AppOperationV1.OUTCOME_UNKNOWN,
                        reason = observation.reason,
                        store = store,
                    )
                }
            }
        }
    }

    /**
     * `settle`, as a boolean that also survives a THROW.
     *
     * A store may throw after the effect ran; a throw is not permission to answer `refused`. It is
     * folded into the same `false` the store's own failure returns, because both mean the same thing:
     * no durable record, so nothing can be claimed about what happened.
     */
    private fun trySettle(
        claim: AppOperationJournal.Claim,
        digest: String,
        outcome: String,
        reason: String?,
        state: JSONObject?,
        store: AppOperationJournal.Store,
    ): Boolean = try {
        AppOperationJournal.settle(
            claim = claim,
            digest = digest,
            outcome = outcome,
            reason = reason,
            state = state,
            store = store,
        )
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        false
    }

    /** The one honest answer when the effect may have happened and the record did not survive. */
    private fun settlementUnknown(request: AppOperationV1.Request): Delivery? {
        val payload = AppOperationV1.result(request.operationId, AppOperationV1.OUTCOME_UNKNOWN, REASON_UNOBSERVED)
            ?: return null
        return Delivery(payload, replayed = false)
    }

    /**
     * Settles a non-success outcome and answers with it.
     *
     * ONE place for both non-success arms so neither can accidentally reuse the observed path's
     * success shape. A failed settlement here still reports `unknown`: the effect may already have
     * happened, and no durable record means no honest claim about what it did.
     */
    private fun settleNonSuccess(
        request: AppOperationV1.Request,
        claim: AppOperationJournal.Claim,
        digest: String,
        outcome: String,
        reason: String,
        store: AppOperationJournal.Store,
    ): Delivery? {
        val settled = trySettle(
            claim = claim,
            digest = digest,
            outcome = outcome,
            reason = reason,
            state = null,
            store = store,
        )
        return if (!settled) {
            Delivery(
                AppOperationV1.result(request.operationId, AppOperationV1.OUTCOME_UNKNOWN, REASON_UNOBSERVED) ?: return null,
                replayed = false,
            )
        } else {
            Delivery(
                AppOperationV1.result(request.operationId, outcome, reason) ?: return null,
                replayed = false,
            )
        }
    }

    /**
     * A refusal that was NOT executed, so nothing is settled: the operation id stays unclaimed and a
     * later delivery is free to admit it. Answering is still required, and an unanswered operation is
     * a hang rather than a refusal.
     */
    fun refused(operationId: String, reason: String, replayed: Boolean): Delivery? = AppOperationV1.result(operationId, AppOperationV1.OUTCOME_REFUSED, reason)
        ?.let { Delivery(it, replayed) }
}
