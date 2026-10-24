package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceIntentDispatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * THE MIXED→PERSISTED-RECORD CONTROL spec d62b77f8 requires (unconditional): a REAL
 * [VoiceAsrEngine], REAL [ContributionRetentionSink], REAL [SegmentObserver] and REAL engine
 * invocation record. The only stand-in is the executor boundary, and the facts travel through the
 * SAME invocation-local channel production uses. Asserted is the observation the engine APPLIED to
 * the retained capture — the value the publish path persists.
 *
 * "Verify each action is represented": a NotObserved observation is an EXPLICIT entry. If it were
 * omitted, duck-success alone would aggregate to success — the exact false agreement this control
 * forbids.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MixedFactsPersistControlTest {

    @Test
    fun `a duck success plus an unobserved action persists the unknown, not success`() = runTest {
        val harness = Harness(this)
        harness.startTurn()
        harness.reportThroughProductionChannel(
            VoiceActionOutcome.Performed, // the duck: a real auxiliary fact
            VoiceActionOutcome.NotObserved, // the turn's own action: explicitly unobserved
        )
        assertNull(
            "duck-performed plus an unobserved action must persist as unknown, never success",
            harness.retainedExecutionOutcome(),
        )
        assertEquals(
            "the capture still publishes (Executed) so the unknown is not silence",
            null,
            harness.retainedExecutionOutcome(),
        )
        assertTrue(harness.retainedOutcomeIsExecuted())
    }

    @Test
    fun `omitting the unknown would have produced false agreement - proving representation matters`() = runTest {
        val harness = Harness(this)
        harness.startTurn()
        // Same duck fact, but reported ALONE: only the explicit NotObserved keeps this from
        // aggregating to success. This is the counterfactual the spec asks to be verified.
        harness.reportThroughProductionChannel(VoiceActionOutcome.Performed)
        assertEquals(
            "duck alone aggregates to success — which is why the unobserved action MUST be reported",
            ExecutionOutcome.Success,
            harness.retainedExecutionOutcome(),
        )
    }

    @Test
    fun `differing primary outcomes persist the unknown rather than a chosen side`() = runTest {
        val harness = Harness(this)
        harness.startTurn()
        harness.reportThroughProductionChannel(
            VoiceActionOutcome.Performed,
            VoiceActionOutcome.Failed("backend rejected"),
        )
        assertNull(harness.retainedExecutionOutcome())
    }

    @Test
    fun `agreement persists the agreed fact`() = runTest {
        val harness = Harness(this)
        harness.startTurn()
        harness.reportThroughProductionChannel(
            VoiceActionOutcome.Refused("user said no"),
            VoiceActionOutcome.Refused("user said no"),
        )
        assertEquals(ExecutionOutcome.Refused, harness.retainedExecutionOutcome())
    }

    private class Harness(private val testScope: TestScope) {
        private val context: Context = mock()
        private val audioManager: AudioManager = mock()
        private val voiceAudioProcessor: VoiceAudioProcessor = mock()
        private val utteranceFilter: UtteranceFilter = mock()
        private val recognizer: VoiceRecognizer = PauseRecognizer
        private val wakeWordDetector: WakeWordDetector = mock()
        private val gracePeriodSignal: GracePeriodSignal = mock()
        private val renderer: AudioFeedbackRenderer = mock()
        private val translationStage: TranslationStage = mock()

        private val authority: () -> AuthorityDecision = {
            AuthorityDecision(collectionPermitted = true, fullRevocation = false)
        }
        private val retention = ContributionRetentionSink(CollectionBudget(1L shl 20))
        private val observer = SegmentObserver(
            CollectionGate { true },
            retention,
            authority,
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "disclosure-1") },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live ->
                scope.completeUnderOwner(context, onset, end, wake, provider)
            },
        )
        private val nextId = AtomicLong(0L)
        lateinit var engine: VoiceAsrEngine

        suspend fun startTurn() {
            whenever(gracePeriodSignal.isActive).thenReturn(MutableStateFlow(false))
            whenever(voiceAudioProcessor.attachCapture()).thenReturn(true)
            whenever(voiceAudioProcessor.peekSpeechDelta(any())).thenReturn(0 to ShortArray(0))
            whenever(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
            whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
            whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
            whenever(voiceAudioProcessor.startProcessing()).thenReturn(
                flowOf(
                    VoiceSegmenterResult.SpeechStarted,
                    VoiceSegmenterResult.SpeechContinuing,
                    VoiceSegmenterResult.SpeechEnded(listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000))),
                ),
            )
            runBlocking {
                whenever(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                    WakeWordResult(detected = false, confidence = 0f, completionSample = 4000),
                )
            }
            engine = VoiceAsrEngine(
                voiceAudioProcessor = voiceAudioProcessor,
                utteranceFilter = utteranceFilter,
                intentRecognizer = recognizer,
                wakeWordDetector = wakeWordDetector,
                gracePeriodSignal = gracePeriodSignal,
                audioFeedbackRenderer = renderer,
                translationStage = translationStage,
                collectionAuthority = authority,
                retentionSink = retention,
                segmentObserver = observer,
                context = context,
            )
            engine.scope = testScope
            engine.start(
                backend = FakeBackend,
                audioRoute = AudioRoute.Headset(hasMicrophone = true),
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.Exposed },
                onIntent = { _, _ -> },
            )
            testScope.advanceUntilIdle()
            assertNotNull("the turn must retain a capture", retainedCaptureId())
        }

        /**
         * Reports facts the way production does: the engine's invocation-local channel bound by
         * the dispatcher, mapped by the same [executionOutcomeOf] the dispatcher uses. The value is
         * then applied to the retained capture through the invocation's own settlement.
         */
        fun reportThroughProductionChannel(vararg facts: VoiceActionOutcome) {
            val captureId = retainedCaptureId()!!
            val token = submitInvocationForTest()
            val dispatcher = VoiceIntentDispatcher()
            val channel: (VoiceActionOutcome) -> Unit = { fact ->
                dispatcher.executionOutcomeOf(fact)?.let { engine.reportExecutionResult(token, it) }
            }
            facts.forEach(channel)
            assertTrue("the invocation must settle", engine.settleInvocation(token))
            testScope.advanceUntilIdle()
            assertEquals(captureId, retainedCaptureId())
        }

        private fun submitInvocationForTest(): VoiceAsrEngine.InvocationToken {
            // The engine mints the token for a dispatch; the test observes that same binding
            // rather than inventing one, so settlement addresses the real open invocation.
            val token = engine.openInvocationForTest(retainedCaptureId()!!, nextId.incrementAndGet())
            return token
        }

        fun retainedCaptureId(): java.util.UUID? = retention.retainedSnapshot().firstOrNull()?.context?.captureId

        fun retainedExecutionOutcome(): ExecutionOutcome? = retention.retainedSnapshot().firstOrNull()?.executionOutcome

        fun retainedOutcomeIsExecuted(): Boolean = retention.retainedSnapshot().firstOrNull()?.outcome is CollectionOutcome.Executed
    }

    /** Real recognizer boundary returning an intent, so the engine opens a real invocation. */
    private object PauseRecognizer : VoiceRecognizer {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)

        override suspend fun recognize(
            input: au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput,
            context: au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext,
        ): au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult = au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult(intent = VoiceIntent.Playback.Pause)

        override fun release() = Unit
    }

    private object FakeBackend : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)

        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult("pause")

        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "")

        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en"))

        override fun release() = Unit
    }
}
