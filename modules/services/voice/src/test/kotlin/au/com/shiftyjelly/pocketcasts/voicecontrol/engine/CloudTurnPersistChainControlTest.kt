package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerPlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceCloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceIntentDispatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentSink
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * THE ACTUAL CLOUD → PERSIST CHAIN (reviewer 58889f29): a REAL
 * [PlaybackManagerPlaybackSink] grants audio focus, a REAL
 * [VoicePlaybackIntentExecutor] decides what to report through its production
 * `execute(intent, onOutcome)` channel, and the engine applies the aggregate to the
 * retained capture. NO fact is injected by the test — the very bug the reviewer described
 * (duck success marking "a fact was reported" and suppressing the primary unknown) can only
 * be caught here.
 */
class CloudTurnPersistChainControlTest {

    @Suppress("DEPRECATION")
    private fun grantingSink(): PlaybackManagerPlaybackSink {
        val context = mock<Context>()
        val audioManager = mock<AudioManager>()
        whenever(context.getSystemService(AudioManager::class.java)).thenReturn(audioManager)
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        whenever(audioManager.requestAudioFocus(any(), any(), any()))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return PlaybackManagerPlaybackSink(mock<PlaybackManager>(), context)
    }

    @Test
    fun `a granted duck on the real executor path persists unknown, never success`() {
        val sink = grantingSink()
        val engine = CloudHarness.newEngine()
        val observed = mutableListOf<ExecutionOutcome>()

        // REAL executor, REAL sink, REAL production channel.
        val executor = VoicePlaybackIntentExecutor(
            playbackSink = sink,
            effectsSink = mock(),
            volumeSink = mock(),
            sleepSink = mock(),
            chapterSink = mock(),
            bookmarkSink = mock(),
            cloudRouteSink = DuckingCloudRoute(sink),
            playbackContextProvider = object : au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextProvider {
                override fun current(): PlaybackContext = PlaybackContext()
            },
            queueSink = mock(),
            playbackQuerySink = mock(),
            statsQuerySink = mock(),
            gracePeriodSignal = mock(),
            playbackManager = mock(),
        )
        val token = engine.openInvocationForTest(UUID.randomUUID(), id = 11L)
        val dispatcher = VoiceIntentDispatcher()
        runBlocking {
            executor.execute(VoiceIntent.CloudRoute(request = "play something", tier = VoiceIntent.CloudTier.Premium)) { fact ->
                dispatcher.executionOutcomeOf(fact)?.let {
                    observed += it
                    engine.reportExecutionResult(token, it)
                }
            }
        }
        engine.settleInvocation(token)

        // The auxiliary grant IS observed, AND the primary action is explicitly unobserved —
        // so the aggregate is the unknown, not success.
        assertTrue("the duck grant must be observed", observed.contains(ExecutionOutcome.Success))
        assertTrue("the primary action must be explicitly unobserved", observed.contains(ExecutionOutcome.NotObserved))
        assertNull(
            "success may not be aggregated from an auxiliary fact alone",
            VoiceAsrEngine.aggregateObservations(observed),
        )
    }

    /** The production cloud sink's ducking step, with the per-call channel passed straight through. */
    private class DuckingCloudRoute(private val playback: PlaybackManagerPlaybackSink) : VoiceCloudRouteSink {
        override suspend fun routeToCloud(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            onDuck: ((VoiceActionOutcome) -> Unit)?,
        ): VoiceResponse {
            playback.duckReporting(onDuck)
            return VoiceResponse.Silent
        }

        override suspend fun routeToCloudWithHint(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            hint: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint,
        ): VoiceResponse = routeToCloud(request, tier, context)
    }

    private object CloudHarness {
        fun newEngine(): VoiceAsrEngine {
            val authority: () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision = {
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, true)
            }
            val retention = ContributionRetentionSink(CollectionBudget(1L shl 16))
            return VoiceAsrEngine(
                voiceAudioProcessor = mock(),
                utteranceFilter = mock(),
                intentRecognizer = mock(),
                wakeWordDetector = mock(),
                gracePeriodSignal = mock(),
                audioFeedbackRenderer = mock(),
                translationStage = mock(),
                collectionAuthority = authority,
                retentionSink = retention,
                segmentObserver = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver(
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate { false },
                    retention,
                    authority,
                    issueAuthority = {
                        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority("a@b.c", 1L, 1L, "e", "d")
                    },
                    handoff = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff { context, onset, end, wake, provider, scope, live ->
                        scope.completeUnderOwner(context, onset, end, wake, provider)
                    },
                ),
                context = mock(),
            )
        }
    }
}
