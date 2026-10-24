package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.NoopSegmentSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * ENGINE PROCESSING JOIN CONTROL.
 *
 * Reviewer 267001c1 caught a false claim I made: I wrote that `VoiceAsrEngine.stop()` is
 * "synchronous for the engine's own resources". It is not. The engine runs its processing work on
 * its OWN `SupervisorJob + Dispatchers.IO` scope (`VoiceAsrEngine.scope`), so cancelling the service
 * scope's root cannot wait for it. A noncooperative ASR/native call, or that job's `finally`, can
 * outlive `stop()` and overlap `VoiceControlService`'s ASR backend release.
 *
 * `stop()` therefore keeps the cancelled job in [VoiceAsrEngine.stoppedProcessingJob] and the service
 * teardown joins it BEFORE releasing anything. This control exercises the REAL engine (constructed
 * with a real `VoiceAsrEngine` and a test scope) rather than modelling it:
 *
 * - [stop] hands back the processing job it cancelled, and
 * - a joiner of that job stays incomplete while its `finally` is held, and completes once released.
 *
 * BOUNDARY: no Android `Service` is constructed here, so this does not execute the service teardown
 * itself. It does execute the engine's real stop/join contract — the part the reviewer showed was
 * missing — rather than restating it.
 */
class EngineProcessingJoinControlTest {
    // VoiceAudioProcessor is a FINAL class, so it is mocked rather than implemented (the existing
    // VoiceAsrEngineTest does the same). startProcessing() returns the flow the engine collects.
    private val processingFlow = kotlinx.coroutines.flow.MutableSharedFlow<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult>()
    private val processor = mock(VoiceAudioProcessor::class.java).also {
        `when`(it.startProcessing()).thenReturn(processingFlow)
        // The engine refuses to listen unless capture attaches and the backend is ready.
        `when`(it.attachCapture()).thenReturn(true)
    }
    private val backend = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend::class.java)

    // ensureReady() is suspend, so it is stubbed under runBlocking rather than in a field initializer.
    private fun readyBackend() = runBlocking {
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        backend
    }

    private fun engine(scope: CoroutineScope): VoiceAsrEngine {
        val context = mock(android.content.Context::class.java)
        val issued = IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "disclosure-1")
        val authority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = false, fullRevocation = true) }
        val observer = SegmentObserver(
            CollectionGate { _ -> false },
            NoopSegmentSink,
            authority,
            issueAuthority = { issued },
            handoff = CompletionHandoff { ctx, onset, end, wake, provider, scope, live ->
                scope.completeUnderOwner(ctx, onset, end, wake, provider)
            },
        )
        return VoiceAsrEngine(
            voiceAudioProcessor = processor,
            utteranceFilter = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter::class.java),
            intentRecognizer = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer::class.java),
            wakeWordDetector = mock(WakeWordDetector::class.java),
            gracePeriodSignal = GracePeriodSignal(),
            audioFeedbackRenderer = mock(AudioFeedbackRenderer::class.java),
            templateResolver = SpokenTemplateResolver(emptyMap()),
            currentLocale = { Locale.US },
            translationStage = mock(TranslationStage::class.java),
            collectionAuthority = authority,
            retentionSink = ContributionRetentionSink(CollectionBudget(1L shl 20)),
            segmentObserver = observer,
            context = context,
        ).also { it.scope = scope }
    }

    @Test
    fun `stop exposes the processing job it cancelled so teardown can await it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = engine(scope)
        try {
            engine.start(
                backend = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend::class.java),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            // Wait until the engine actually reached its processing launch (attachCapture was called).
            withTimeout(5_000) {
                while (engine.stoppedProcessingJob == null && processingFlow.subscriptionCount.value == 0) {
                    kotlinx.coroutines.delay(10)
                }
            }
            engine.stop()
            assertNotNull(
                "stop() must expose the cancelled processing job; without it service teardown " +
                    "cannot wait for work that is NOT a child of the service scope",
                engine.stoppedProcessingJob,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a join of the engine processing job waits for held engine work`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = engine(scope)
        try {
            engine.start(
                backend = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend::class.java),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            withTimeout(5_000) { processingFlow.subscriptionCount.first { it > 0 } }

            // Hold work the way a noncooperative native call would: a child of the SAME engine scope
            // that the service-root join cannot see.
            val finallyEntered = CompletableDeferred<Unit>()
            val releaseFinally = CompletableDeferred<Unit>()
            val held = scope.launch {
                withContext(NonCancellable) {
                    finallyEntered.complete(Unit)
                    releaseFinally.await()
                }
            }
            try {
                withTimeout(5_000) { finallyEntered.await() }
                engine.stop()

                val joinerStarted = CompletableDeferred<Unit>()
                val joiner = CoroutineScope(Dispatchers.IO).launch {
                    withContext(NonCancellable) {
                        joinerStarted.complete(Unit)
                        engine.stoppedProcessingJob?.join()
                        held.join()
                    }
                }
                withTimeout(5_000) { joinerStarted.await() }
                assertFalse(
                    "teardown must not proceed to release while engine work is still held",
                    joiner.isCompleted,
                )
                releaseFinally.complete(Unit)
                withTimeout(5_000) { joiner.join() }
                assertTrue("the join completes once engine work has finished", joiner.isCompleted)
            } finally {
                releaseFinally.complete(Unit)
            }
        } finally {
            scope.cancel()
        }
    }
}
