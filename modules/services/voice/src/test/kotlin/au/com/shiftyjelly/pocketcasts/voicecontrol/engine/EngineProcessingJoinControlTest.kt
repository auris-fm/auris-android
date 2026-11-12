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
class EngineProcessingJoinControlTest {
    private val processingFlow = kotlinx.coroutines.flow.MutableSharedFlow<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult>()
    private val processor = mock(VoiceAudioProcessor::class.java).also {
        `when`(it.startProcessing()).thenReturn(processingFlow)
        `when`(it.attachCapture()).thenReturn(true)
    }
    private val backend = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend::class.java)
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
            handoff = CompletionHandoff { ctx, onset, end, wake, provider, scope, live, measurement ->
                scope.completeUnderOwner(ctx, onset, end, wake, provider, wakeMeasurement = measurement)
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
            retentionSink = ContributionRetentionSink(),
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
                playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            withTimeout(5_000) {
                while (engine.unfinishedProcessingJobs.firstOrNull() == null && processingFlow.subscriptionCount.value == 0) {
                    kotlinx.coroutines.delay(10)
                }
            }
            engine.stop()
            val owned = engine.ownedJobs
            assertTrue(
                "stop() must leave the cancelled processing job in the ownership set; without it " +
                    "service teardown cannot classify and await work that is NOT a child of the " +
                    "service scope",
                owned.isNotEmpty(),
            )
            owned.forEach { it.join() }
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun `a processing job that already FAILED is not relabelled as engine-cancelled`() = runBlocking {
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
                playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            withTimeout(5_000) { died.await() }
            val ownedBeforeStop = engine.ownedJobs.toSet()
            assertEquals("the failed job must be owned before stop", 1, ownedBeforeStop.size)
            withTimeout(5_000) { ownedBeforeStop.single().join() }
            assertTrue(
                "the processing job must have finished before stop; premise broken otherwise",
                ownedBeforeStop.single().isCompleted,
            )
            engine.stop()
            assertTrue(
                "a finished job is not outstanding work, got ${engine.unfinishedProcessingJobs}",
                engine.unfinishedProcessingJobs.none { it === ownedBeforeStop.single() },
            )
            assertTrue(
                "a job that had already FAILED must NOT be recorded as engine-cancelled",
                engine.cancelledProcessingJobs.none { it === ownedBeforeStop.single() },
            )
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun `both jobs stay retained while each is held, then prune independently`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val holdFirst = CompletableDeferred<Unit>()
        val holdSecond = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var starts = 0
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
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
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
            val jobs = engine.unfinishedProcessingJobs
            assertEquals("both held jobs must be retained", 2, jobs.size)
            assertTrue("the older held job must be retained", jobs.contains(firstJob))
            val secondJob = jobs.first { it !== firstJob }
            assertNotSame("the two retained jobs must be distinct", firstJob, secondJob)
            holdSecond.complete(Unit)
            withTimeout(5_000) { secondJob.join() }
            assertTrue(
                "the released job must actually have completed",
                secondJob.isCompleted,
            )
            withTimeout(5_000) {
                while (engine.unfinishedProcessingJobs.any { it === secondJob }) {
                    kotlinx.coroutines.delay(10)
                }
            }
            assertTrue(
                "only the finished job prunes; the still-held one must remain " +
                    "(firstJob.isCompleted=${firstJob.isCompleted} holdFirst=${holdFirst.isCompleted} " +
                    "unfinished=${engine.unfinishedProcessingJobs} owned=${engine.ownedJobs} " +
                    "cancelledJobs=${engine.cancelledProcessingJobs} secondJob.isCompleted=${secondJob.isCompleted})",
                engine.unfinishedProcessingJobs.none { it === secondJob },
            )
            assertTrue(
                "the still-held job must NOT be pruned just because its sibling finished " +
                    "(firstJob.isCompleted=${firstJob.isCompleted} holdFirst=${holdFirst.isCompleted} " +
                    "unfinished=${engine.unfinishedProcessingJobs} owned=${engine.ownedJobs} " +
                    "cancelledJobs=${engine.cancelledProcessingJobs})",
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heldFlow = kotlinx.coroutines.flow.MutableSharedFlow<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult>()
        val flowStarted = CompletableDeferred<Unit>()
        val releaseFlow = CompletableDeferred<Unit>()
        `when`(processor.startProcessing()).thenReturn(
            heldFlow.onEach { flowStarted.complete(Unit) }
                .onCompletion {
                    withContext(NonCancellable) { releaseFlow.await() }
                },
        )
        val engine = engine(scope)
        try {
            engine.start(
                backend = readyBackend(),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
                micExposureProvider = { MicExposure.NoMic },
                onIntent = { _, _ -> },
            )
            withTimeout(5_000) { heldFlow.subscriptionCount.first { it > 0 } }
            engine.stop()
            val jobs = engine.unfinishedProcessingJobs
            assertTrue("stop must expose the processing job(s) it cancelled", jobs.isNotEmpty())
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
