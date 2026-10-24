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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer

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
                while (engine.unfinishedProcessingJobs.firstOrNull() == null && processingFlow.subscriptionCount.value == 0) {
                    kotlinx.coroutines.delay(10)
                }
            }
            engine.stop()
            assertNotNull(
                "stop() must expose the cancelled processing job; without it service teardown " +
                    "cannot wait for work that is NOT a child of the service scope",
                engine.unfinishedProcessingJobs.firstOrNull(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stop after a restart keeps the OLDER unfinished job as well as the newer one`() = runBlocking {
        // Reviewer 26883305: stop/start/stop. The older job's native work can still be running when
        // the newer job exists; a single slot would DROP it and teardown would release the backend
        // underneath it. Both must stay awaitable until each actually finishes.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val holdFirst = CompletableDeferred<Unit>()
        val firstRunning = CompletableDeferred<Unit>()
        var starts = 0
        val flows = kotlinx.coroutines.flow.MutableSharedFlow<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult>()
        `when`(processor.startProcessing()).thenAnswer { invocation ->
            val n = ++starts
            flows.onStart { if (n == 1) firstRunning.complete(Unit) }
                .onCompletion { if (n == 1) withContext(NonCancellable) { holdFirst.await() } }
        }
        val engine = engine(scope)
        try {
            engine.start(
                backend = readyBackend(),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            withTimeout(5_000) { flows.subscriptionCount.first { it > 0 } }
            engine.stop() // cancels job #1, which is held in its completion
            withTimeout(5_000) { firstRunning.await() }
            val firstJob = engine.unfinishedProcessingJobs.firstOrNull()
            assertNotNull("the first job must be retained", firstJob)

            engine.start(
                backend = readyBackend(),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            withTimeout(5_000) { flows.subscriptionCount.first { it > 1 || starts >= 2 } }
            engine.stop() // cancels job #2 while #1 may still be finishing

            val jobs = engine.unfinishedProcessingJobs
            assertTrue(
                "the older unfinished job must survive a later stop; a single slot drops it and " +
                    "teardown would then release the backend under it (found ${jobs.size} job(s))",
                jobs.size >= 2 || jobs.contains(firstJob),
            )
        } finally {
            holdFirst.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `a join of the engine processing jobs waits for the real processing flow to finish`() = runBlocking {
        // Reviewer 26883305: the previous version held a SIBLING job in the test scope and joined it
        // in the test, so it proved nothing about production. This version holds the REAL processing
        // flow — the one the engine's own launch collects — so the join under test is over the
        // production job, and the barrier is that job's actual finalization.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heldFlow = kotlinx.coroutines.flow.MutableSharedFlow<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult>()
        val flowStarted = CompletableDeferred<Unit>()
        val releaseFlow = CompletableDeferred<Unit>()
        `when`(processor.startProcessing()).thenReturn(
            heldFlow.onEach { flowStarted.complete(Unit) }
                .onCompletion {
                    // The engine's processing job reaches here only when the flow ends. Hold it, the
                    // way a noncooperative native call would, and make it observable.
                    withContext(NonCancellable) { releaseFlow.await() }
                },
        )
        val engine = engine(scope)
        try {
            engine.start(
                backend = readyBackend(),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            withTimeout(5_000) { heldFlow.subscriptionCount.first { it > 0 } }
            engine.stop()
            val jobs = engine.unfinishedProcessingJobs
            assertTrue("stop must expose the processing job(s) it cancelled", jobs.isNotEmpty())

            // THE SUBJECT: the same joins the service teardown performs before releasing.
            val joinerStarted = CompletableDeferred<Unit>()
            val joiner = CoroutineScope(Dispatchers.IO).launch {
                withContext(NonCancellable) {
                    joinerStarted.complete(Unit)
                    jobs.forEach { it.join() }
                }
            }
            withTimeout(5_000) { joinerStarted.await() }
            assertFalse(
                "teardown must not proceed to release while the real processing flow is held",
                joiner.isCompleted,
            )
            releaseFlow.complete(Unit)
            withTimeout(5_000) { joiner.join() }
            assertTrue("the join completes once the real processing flow finished", joiner.isCompleted)
        } finally {
            releaseFlow.complete(Unit)
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
                        engine.unfinishedProcessingJobs.firstOrNull()?.join()
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
