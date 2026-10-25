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
import org.junit.Assert.assertEquals
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
            // ROOT-CAUSED FLAKE. The old assertion read `unfinishedProcessingJobs` after stop, but
            // that list's completion callback removes a job the moment it finishes -- so under load
            // the assertion raced the removal and lost, failing a stop that had done exactly the
            // right thing. The surface production teardown actually consumes is the OWNERSHIP set
            // (`ownedJobs`), which is registered at job CREATION and outlives completion; the
            // unfinished list is only the still-running subset. The deterministic claim is:
            // after stop, the engine still owns the launched processing job, so teardown can
            // classify and await it -- and that job's completion is observable.
            engine.stop()
            val owned = engine.ownedJobs
            assertTrue(
                "stop() must leave the cancelled processing job in the ownership set; without it " +
                    "service teardown cannot classify and await work that is NOT a child of the " +
                    "service scope",
                owned.isNotEmpty(),
            )
            // The exposure is awaitable: joining every owned job terminates.
            owned.forEach { it.join() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a processing job that already FAILED is not relabelled as engine-cancelled`() = runBlocking {
        // Reviewer 1ff17ee6: stop() references the current processing job even when it has already
        // failed. Adding it to cancelledJobs unconditionally would relabel a real failure as
        // expected shutdown and hide it from the quiescence verdict.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val died = CompletableDeferred<Unit>()
        `when`(processor.startProcessing()).thenReturn(
            kotlinx.coroutines.flow.flow<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult> {
                throw IllegalStateException("segmenter died")
            }.onCompletion { died.complete(Unit) },
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
            // BARRIER: the processing flow has failed, so the job has already FAILED by the time
            // stop() runs. The engine tracks the job only when stop() references it, so the state
            // to assert is what stop() records for an already-failed job.
            withTimeout(5_000) { died.await() }
            engine.stop()
            // The job had ALREADY finished, so stop() prunes it rather than tracking it — it is not
            // outstanding work. What matters is what stop() recorded for it: a failure must not be
            // relabelled as expected shutdown.
            assertTrue(
                "a finished job is not outstanding work, got ${engine.unfinishedProcessingJobs}",
                engine.unfinishedProcessingJobs.isEmpty(),
            )
            assertTrue(
                "a job that had already FAILED must NOT be recorded as engine-cancelled",
                engine.cancelledProcessingJobs.none { it.isCompleted },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `both jobs stay retained while each is held, then prune independently`() = runBlocking {
        // Reviewer e1b11171: job #2 could COMPLETE and be pruned before the size assertion, making
        // it flaky. So BOTH completions are held: capture distinct identities, assert exact
        // retention while both are held, then release each one and assert it prunes on its own.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val holdFirst = CompletableDeferred<Unit>()
        val holdSecond = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var starts = 0
        // A FRESH flow per start: sharing one MutableSharedFlow meant the second collection never
        // reached its own onCompletion, and the test hung instead of proving anything.
        val flowStarted = CompletableDeferred<Unit>()
        `when`(processor.startProcessing()).thenAnswer {
            val n = ++starts
            kotlinx.coroutines.flow.emptyFlow<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult>()
                .onStart { flowStarted.complete(Unit) }
                .onCompletion {
                    if (n == 1) {
                        firstEntered.complete(Unit)
                        withContext(NonCancellable) { holdFirst.await() }
                    }
                    if (n == 2) {
                        secondEntered.complete(Unit)
                        withContext(NonCancellable) { holdSecond.await() }
                    }
                }
        }
        val engine = engine(scope)
        fun startOnce() = engine.start(
            backend = readyBackend(),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.NoMic },
            onIntent = { _, _ -> },
        )
        try {
            startOnce()
            withTimeout(5_000) { flowStarted.await() }
            engine.stop()
            withTimeout(5_000) { firstEntered.await() } // BARRIER: job #1 is in its finalization
            val firstJob = checkNotNull(engine.unfinishedProcessingJobs.singleOrNull()) { "job #1 must be retained" }

            startOnce()
            withTimeout(5_000) { secondEntered.await() }
            engine.stop()
            withTimeout(5_000) { secondEntered.await() } // BARRIER: job #2 is in its finalization

            // BOTH held, so neither can be pruned by completion: the exact set is stable.
            val jobs = engine.unfinishedProcessingJobs
            assertEquals("both held jobs must be retained", 2, jobs.size)
            assertTrue("the older held job must be retained", jobs.contains(firstJob))
            val secondJob = jobs.first { it !== firstJob }
            assertNotSame("the two retained jobs must be distinct", firstJob, secondJob)

            // Release ONE and assert only that one prunes; the other stays.
            holdSecond.complete(Unit)
            withTimeout(5_000) { secondJob.join() }
            assertTrue(
                "only the finished job prunes; the still-held one must remain",
                engine.unfinishedProcessingJobs.none { it === secondJob },
            )
            assertTrue(
                "the still-held job must NOT be pruned just because its sibling finished",
                engine.unfinishedProcessingJobs.contains(firstJob),
            )

            holdFirst.complete(Unit)
            withTimeout(5_000) { firstJob.join() }
        } finally {
            holdFirst.complete(Unit)
            holdSecond.complete(Unit)
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
}
