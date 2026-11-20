package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * the observable seam for intent-dispatch lifecycle: the
 * service's `handleIntent` body extracted so its ownership contract — debounce settle,
 * invokeOnCompletion all-exit cleanup, one-shot settlement — can be driven by REAL controls
 * (overlap→persist, pre-body cancellation, handler throw) instead of callback-map tests.
 *
 * WHAT THIS OWNS: turning a dispatched intent + invocation token into exactly one finalization.
 * WHAT IT DOES NOT OWN: outcome semantics (the engine's aggregation rules) or presentation.
 */
@Singleton
class VoiceIntentDispatcher @Inject constructor() {

    internal var debounceMs: Long = COMMAND_DEBOUNCE_MS

    internal var lastIntentType: String? = null
    internal var lastCommandTime: Long = 0L

    /**
     * Dispatch ONE intent. Settlement is guaranteed on EVERY exit — debounce, body completion,
     * launch cancellation before the body, launch failure, handler throw — via invokeOnCompletion
     * on the actual Job plus the pre-launch catch; the engine's settlement is one-shot, so all
     * paths converge on exactly one publication.
     */
    fun dispatch(
        scope: CoroutineScope,
        engine: VoiceAsrEngine,
        executor: VoicePlaybackIntentExecutor,
        render: (VoiceIntent, au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse) -> Unit,
        onCommandRecognized: (VoiceIntent) -> Unit,
        intent: VoiceIntent,
        invocationToken: VoiceAsrEngine.InvocationToken?,
        // The utterance's admitted request id, forwarded so local actions mark the timing table.
        timingRequestId: String? = null,
    ): Job? = dispatch(scope, engine, executor, render, onCommandRecognized, intent, invocationToken, timingRequestId, Dispatchers.IO)

    /**
     * Test seam: the launch context is a parameter so a control can FORCE the two completion
     * interleavings (cancelled-before-registration vs registered-before-completion) instead of
     * racing the host's load for them. Production always takes the [Dispatchers.IO] form above.
     */
    internal fun dispatch(
        scope: CoroutineScope,
        engine: VoiceAsrEngine,
        executor: VoicePlaybackIntentExecutor,
        render: (VoiceIntent, au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse) -> Unit,
        onCommandRecognized: (VoiceIntent) -> Unit,
        intent: VoiceIntent,
        invocationToken: VoiceAsrEngine.InvocationToken?,
        timingRequestId: String?,
        launchDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): Job? {
        val now = System.currentTimeMillis()
        val intentType = intent::class.simpleName ?: intent.toString()
        if (intentType == lastIntentType && (now - lastCommandTime) < debounceMs) {
            Timber.i("[VoicePipeline] debounce %s", intentType)
            // A debounced dispatch still had a token bound by the engine: settling it finalizes
            // its publication instead of leaking an open invocation forever. The suppression is
            // the app's own refusal to run the command a second time inside the window, so the
            // record carries the fixed refusal mapping (failed/refused/client_refused) rather
            // than the not_run fallthrough a bare settle produces, which reads as "nothing was
            // attempted" -- the user did speak and the app did decline.
            invocationToken?.let {
                engine.reportExecutionResult(it, ExecutionOutcome.Refused)
                engine.settleInvocation(it)
            }
            // A debounced dispatch never reaches the executor, whose finally would finish the
            // record — finish it here, or the registry keeps one live entry per dropped turn.
            if (timingRequestId != null) {
                TurnTimingRecord.finishFor(
                    timingRequestId,
                    cancelled = false,
                    failed = true,
                )
            }
            return null
        }
        lastIntentType = intentType
        lastCommandTime = now

        val token = invocationToken
        // The action-result channel is INVOCATION-LOCAL: the reporter below belongs to this
        // execute call only; overlapping dispatches cannot overwrite or detach each other's.
        val onOutcome: (VoiceActionOutcome) -> Unit = { fact ->
            if (token != null) {
                executionOutcomeOf(fact)?.let { engine.reportExecutionResult(token, it) }
            }
        }
        return try {
            val executorOwnsRecord = AtomicBoolean(false)
            scope.launch(launchDispatcher) {
                try {
                    val turnOutcome = VoicePlaybackIntentExecutor.TurnOutcome()
                    val response = try {
                        // The handoff, marked IMMEDIATELY before the call whose finally finishes the
                        // record. Marking at the top of the body instead leaves a gap: a cancellation
                        // between there and this call has an owner in name only, and the record stays
                        // live. From this point the executor's finally runs on every exit.
                        executorOwnsRecord.set(true)
                        executor.execute(intent, outcome = turnOutcome, onOutcome = onOutcome, timingRequestId = timingRequestId)
                    } catch (t: kotlinx.coroutines.CancellationException) {
                        // Cancellation is NOT "the user cancelled" and NOT "the action did not
                        // happen": work may already have run. No fact is invented; the settle
                        // boundary records the explicit unknown unless a sink reported one.
                        throw t
                    }
                    render(intent, response)
                    if (executor.capabilityOf(intent, turnOutcome).allowsRecognition) onCommandRecognized(intent)
                } catch (t: Throwable) {
                    // NO throw→failure inference: a generic throw after an unobserved effect
                    // cannot prove action_failed. A sink's own fact stands; otherwise settlement
                    // records the explicit schema unknown.
                    throw t
                }
            }.also { job ->
                // ALL-EXIT OWNERSHIP CLEANUP: invokeOnCompletion runs even when the coroutine is
                // cancelled BEFORE its body first runs (a launch finally does not).
                job.invokeOnCompletion { _ ->
                    if (token != null) engine.settleInvocation(token)
                    // A dispatch that never reached the executor has no other owner for its record:
                    // the executor's finally is what finishes it on every exit once the call begins.
                    // Until then -- cancelled before the body ran, or between the body's start and
                    // the call -- this is the only place that can finish it. After the handoff the
                    // executor decides, including the case where a sink took ownership and
                    // deliberately finishes nothing, so the mark gates this rather than finishing on
                    // every completion.
                    if (timingRequestId != null && !executorOwnsRecord.get()) {
                        TurnTimingRecord.finishFor(
                            timingRequestId,
                            // Nothing ran: no action was attempted and none failed.
                            cancelled = true,
                            failed = false,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            // The launch itself failed before a Job existed: this call site is the only observer.
            if (token != null) engine.settleInvocation(token)
            null
        }
    }

    /**
     * The SINK'S OWN structured fact as an execution outcome. Presentation never appears here.
     * Dispatched and NotObserved map to NOTHING: neither is a result — dispatch is not effect,
     * and a lost observation asserts nothing — so provenance keeps schema unknown for both.
     */
    internal fun executionOutcomeOf(fact: VoiceActionOutcome): ExecutionOutcome? = when (fact) {
        // Dispatch alone is still nothing (dispatch is not effect). NotObserved IS delivered:
        // it is the explicit unobserved observation the aggregation needs alongside an auxiliary
        // fact, so duck-success plus unobserved primary actions cannot aggregate to success.
        VoiceActionOutcome.Dispatched -> null

        VoiceActionOutcome.NotObserved -> ExecutionOutcome.NotObserved

        VoiceActionOutcome.Performed -> ExecutionOutcome.Success

        is VoiceActionOutcome.Failed -> ExecutionOutcome.Failure

        is VoiceActionOutcome.PreconditionFailed -> ExecutionOutcome.Failure

        // Auxiliary operations are OBSERVATIONS too: the duck grant is known, and what happened
        // to the turn's own action is unknown. Reporting it keeps the aggregate honest
        // (agreement-or-unknown) instead of letting it vanish.
        is VoiceActionOutcome.AuxiliaryPerformed -> ExecutionOutcome.Success

        is VoiceActionOutcome.AuxiliaryPreconditionFailed -> ExecutionOutcome.Failure

        is VoiceActionOutcome.Refused -> ExecutionOutcome.Refused
    }

    internal companion object {
        internal const val COMMAND_DEBOUNCE_MS: Long = 800L
    }
}
