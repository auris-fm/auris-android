package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class UnsupportedAdvancedCommandTest {

    @Test
    fun `unsupported advanced command fails safely without playback side effects`() = runTest {
        val controller = FakeVoicePlaybackController()
        val mapper = CommandIntentMapper()
        val router = VoiceCommandRouter(
            mapper = mapper,
            listeningModeResolver = ListeningModeResolver(),
            recognitionOrchestrator = VoiceRecognitionOrchestrator(
                localRecognizer = LocalRuleBasedRecognizer(mapper = mapper),
                cloudRecognizer = CloudRuleBasedRecognizer(mapper = mapper, delayMs = 50),
                voiceArbitrationEngine = DeterministicVoiceArbitrationEngine(),
            ),
            commandExecutor = VoiceCommandExecutor(controller),
            retentionManager = VoiceRetentionManager(),
            analyticsTracker = AnalyticsTracker.test(),
        )

        val result = router.route(
            utterance = "bookmark this episode",
            isPlaybackActive = true,
            isNetworkAvailable = true,
            retentionEnabled = true,
        )

        assertNotNull(result.selectedIntent)
        assertEquals(IntentType.UNSUPPORTED_ADVANCED, result.selectedIntent?.intentType)
        assertEquals(ResultType.UNSUPPORTED, result.outcome.resultType)
        assertEquals(FeedbackType.UNSUPPORTED, result.outcome.userFeedbackType)
        assertFalse(controller.didExecuteAnyAction)
    }

    private class FakeVoicePlaybackController : VoicePlaybackController {
        var didExecuteAnyAction = false

        override fun skipForward() {
            didExecuteAnyAction = true
        }

        override fun rewind() {
            didExecuteAnyAction = true
        }

        override fun speedUp() {
            didExecuteAnyAction = true
        }

        override fun speedDown() {
            didExecuteAnyAction = true
        }

        override fun nextEpisode() {
            didExecuteAnyAction = true
        }
    }
}
