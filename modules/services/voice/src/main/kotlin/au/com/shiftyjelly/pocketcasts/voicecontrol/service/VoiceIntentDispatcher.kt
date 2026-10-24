package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * THE OBSERVABLE SEAM for intent-dispatch lifecycle (spec 72e66336, reviewer dab802ec): the
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
    ): Job? {
        val now = System.currentTimeMillis()
        val intentType = intent::class.simpleName ?: intent.toString()
        if (intentType == lastIntentType && (now - lastCommandTime) < debounceMs) {
            Timber.i("[VoicePipeline] debounce %s", intentType)
            // A debounced dispatch still had a token bound by the engine: settling it finalizes
            // its publication (schema unknown — no action was attempted) instead of leaking an
            // open invocation forever.
            invocationToken?.let { engine.settleInvocation(it) }
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
            scope.launch(Dispatchers.IO) {
                try {
                    val response = try {
                        executor.execute(intent, onOutcome)
                    } catch (t: kotlinx.coroutines.CancellationException) {
                        // Cancellation is NOT "the user cancelled" and NOT "the action did not
                        // happen": work may already have run. No fact is invented; the settle
                        // boundary records the explicit unknown unless a sink reported one.
                        throw t
                    }
                    render(intent, response)
                    onCommandRecognized(intent)
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
        VoiceActionOutcome.Dispatched -> null
        VoiceActionOutcome.NotObserved -> null
        VoiceActionOutcome.Performed -> ExecutionOutcome.Success
        is VoiceActionOutcome.Failed -> ExecutionOutcome.Failure
        is VoiceActionOutcome.PreconditionFailed -> ExecutionOutcome.Failure
        is VoiceActionOutcome.Refused -> ExecutionOutcome.Refused
    }

    internal companion object {
        internal const val COMMAND_DEBOUNCE_MS: Long = 800L
    }
}
