package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import java.time.Instant

data class VoiceCommandRouteResult(
    val session: VoiceCommandSession,
    val decision: ArbitrationDecision?,
    val selectedIntent: CommandIntent?,
    val outcome: IntentResolutionOutcome,
    val retainedSample: AnonymizedVoiceSample?,
)

class VoiceCommandRouter(
    private val mapper: CommandIntentMapper,
    private val listeningModeResolver: ListeningModeResolver,
    private val recognitionOrchestrator: VoiceRecognitionOrchestrator,
    private val commandExecutor: VoiceCommandExecutor,
    private val retentionManager: VoiceRetentionManager,
    private val analyticsTracker: AnalyticsTracker,
) {
    suspend fun route(
        utterance: String,
        isPlaybackActive: Boolean,
        isNetworkAvailable: Boolean,
        retentionEnabled: Boolean,
    ): VoiceCommandRouteResult {
        val session = VoiceCommandSession(
            playbackStateAtStart = if (isPlaybackActive) PlaybackCommandState.ACTIVE else PlaybackCommandState.PAUSED,
            listeningMode = listeningModeResolver.resolve(isPlaybackActive),
            retentionEnabled = retentionEnabled,
        )

        val phraseToRecognize = if (session.listeningMode == ListeningMode.WAKE_WORD) {
            mapper.extractWakeWordCommand(utterance)
        } else {
            utterance
        }

        if (phraseToRecognize.isNullOrBlank()) {
            val ignoredOutcome = IntentResolutionOutcome(
                decisionId = null,
                resultType = ResultType.FAILED_SAFELY,
                userFeedbackType = FeedbackType.IGNORED,
            )
            analyticsTracker.trackVoiceCommandOutcome(
                resultType = ignoredOutcome.resultType.name.lowercase(),
                feedbackType = ignoredOutcome.userFeedbackType.name.lowercase(),
                selectedSource = null,
                intentType = null,
            )
            return VoiceCommandRouteResult(
                session = session,
                decision = null,
                selectedIntent = null,
                outcome = ignoredOutcome,
                retainedSample = null,
            )
        }

        val recognitionResult = recognitionOrchestrator.recognize(
            sessionId = session.sessionId,
            phrase = phraseToRecognize,
            useCloud = isNetworkAvailable,
        )

        val selectedCommand = recognitionResult.selectedCommand
        if (selectedCommand == null) {
            val failedOutcome = IntentResolutionOutcome(
                decisionId = null,
                resultType = ResultType.FAILED_SAFELY,
                userFeedbackType = FeedbackType.FAILED,
            )
            analyticsTracker.trackVoiceCommandOutcome(
                resultType = failedOutcome.resultType.name.lowercase(),
                feedbackType = failedOutcome.userFeedbackType.name.lowercase(),
                selectedSource = null,
                intentType = null,
            )
            return VoiceCommandRouteResult(
                session = session,
                decision = recognitionResult.decision,
                selectedIntent = null,
                outcome = failedOutcome,
                retainedSample = null,
            )
        }

        val wasExecuted = commandExecutor.execute(selectedCommand.intent.intentType)
        val resultType = when {
            wasExecuted -> ResultType.EXECUTED
            selectedCommand.intent.intentType == IntentType.UNSUPPORTED_ADVANCED ||
                selectedCommand.intent.intentType == IntentType.UNKNOWN -> ResultType.UNSUPPORTED
            else -> ResultType.FAILED_SAFELY
        }
        val feedbackType = when (resultType) {
            ResultType.EXECUTED -> FeedbackType.SUCCESS
            ResultType.UNSUPPORTED -> FeedbackType.UNSUPPORTED
            ResultType.FAILED_SAFELY -> FeedbackType.FAILED
        }

        val outcome = IntentResolutionOutcome(
            decisionId = recognitionResult.decision?.decisionId,
            resultType = resultType,
            userFeedbackType = feedbackType,
            executedAt = if (wasExecuted) Instant.now() else null,
        )

        val retainedSample = retentionManager.createRetainedSample(
            sessionId = session.sessionId,
            capturedAt = session.startedAt,
            retentionEnabled = retentionEnabled,
        )

        recognitionResult.decision?.let { decision ->
            analyticsTracker.trackVoiceArbitration(
                source = decision.selectedSource.name.lowercase(),
                latencyMs = selectedCommand.latencyMs,
                lateSourceIgnored = decision.lateSourceIgnored,
            )
        }

        analyticsTracker.trackVoiceCommandOutcome(
            resultType = resultType.name.lowercase(),
            feedbackType = feedbackType.name.lowercase(),
            selectedSource = selectedCommand.source.name.lowercase(),
            intentType = selectedCommand.intent.intentType.name.lowercase(),
        )

        return VoiceCommandRouteResult(
            session = session,
            decision = recognitionResult.decision,
            selectedIntent = selectedCommand.intent,
            outcome = outcome,
            retainedSample = retainedSample,
        )
    }

    companion object {
        fun createDefault(
            playbackManager: PlaybackManager,
            settings: Settings,
            analyticsTracker: AnalyticsTracker,
        ): VoiceCommandRouter {
            val mapper = CommandIntentMapper()
            return VoiceCommandRouter(
                mapper = mapper,
                listeningModeResolver = ListeningModeResolver(),
                recognitionOrchestrator = VoiceRecognitionOrchestrator(
                    localRecognizer = LocalRuleBasedRecognizer(mapper),
                    cloudRecognizer = CloudRuleBasedRecognizer(mapper),
                    voiceArbitrationEngine = DeterministicVoiceArbitrationEngine(),
                ),
                commandExecutor = VoiceCommandExecutor(playbackManager, settings),
                retentionManager = VoiceRetentionManager(),
                analyticsTracker = analyticsTracker,
            )
        }
    }
}
