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
@Singleton
class VoiceIntentDispatcher @Inject constructor() {
    internal var debounceMs: Long = COMMAND_DEBOUNCE_MS
    internal var lastIntentType: String? = null
    internal var lastCommandTime: Long = 0L
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
            invocationToken?.let {
                engine.reportExecutionResult(it, ExecutionOutcome.Refused)
                engine.settleInvocation(it)
            }
            return null
        }
        lastIntentType = intentType
        lastCommandTime = now
        val token = invocationToken
        val onOutcome: (VoiceActionOutcome) -> Unit = { fact ->
            if (token != null) {
                executionOutcomeOf(fact)?.let { engine.reportExecutionResult(token, it) }
            }
        }
        return try {
            scope.launch(Dispatchers.IO) {
                try {
                    val turnOutcome = VoicePlaybackIntentExecutor.TurnOutcome()
                    val response = try {
                        executor.execute(intent, outcome = turnOutcome, onOutcome = onOutcome)
                    } catch (t: kotlinx.coroutines.CancellationException) {
                        throw t
                    }
                    render(intent, response)
                    if (executor.capabilityOf(intent, turnOutcome).allowsRecognition) onCommandRecognized(intent)
                } catch (t: Throwable) {
                    throw t
                }
            }.also { job ->
                job.invokeOnCompletion { _ ->
                    if (token != null) engine.settleInvocation(token)
                }
            }
        } catch (t: Throwable) {
            if (token != null) engine.settleInvocation(token)
            null
        }
    }
    internal fun executionOutcomeOf(fact: VoiceActionOutcome): ExecutionOutcome? = when (fact) {
        VoiceActionOutcome.Dispatched -> null
        VoiceActionOutcome.NotObserved -> ExecutionOutcome.NotObserved
        VoiceActionOutcome.Performed -> ExecutionOutcome.Success
        is VoiceActionOutcome.Failed -> ExecutionOutcome.Failure
        is VoiceActionOutcome.PreconditionFailed -> ExecutionOutcome.Failure
        is VoiceActionOutcome.AuxiliaryPerformed -> ExecutionOutcome.Success
        is VoiceActionOutcome.AuxiliaryPreconditionFailed -> ExecutionOutcome.Failure
        is VoiceActionOutcome.Refused -> ExecutionOutcome.Refused
    }
    internal companion object {
        internal const val COMMAND_DEBOUNCE_MS: Long = 800L
    }
}
