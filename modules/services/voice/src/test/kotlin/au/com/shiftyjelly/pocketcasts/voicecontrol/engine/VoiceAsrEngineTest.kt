@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")
package au.com.shiftyjelly.pocketcasts.voicecontrol.engine
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrToken
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.CanaryFlashBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothCandidate
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionPlatform
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionCeilings
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.NoopSegmentSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class VoiceAsrEngineTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()
    @get:Rule
    val tmp = TemporaryFolder()
    private val context = mock<Context>()
    private val audioManager = mock<AudioManager>()
    private val voiceAudioProcessor = mock<VoiceAudioProcessor>()
    private val utteranceFilter = mock<UtteranceFilter>()
    private val intentRecognizer = mock<VoiceRecognizer>()
    private val wakeWordDetector = mock<WakeWordDetector>()
    private val gracePeriodSignal = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal>()
    private val audioFeedbackRenderer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer>()
    private val backend = mock<AsrBackend>()
    private var authority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = false, fullRevocation = true) }
    private var retentionSink = ContributionRetentionSink()
    private var observer = SegmentObserver(
        CollectionGate { _ -> false },
        NoopSegmentSink,
        authority,
        issueAuthority = { issuedAuthorityForTests },
        handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
    )
    private val translationStage = mock<TranslationStage>()
    private val issuedAuthorityForTests = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority(
        "a@b.c",
        "epoch-1",
        "disclosure-1",
    )
    private companion object {
        val issuedAuthorityForTests = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority(
            "a@b.c",
            "epoch-1",
            "disclosure-1",
        )
    }
    init {
        `when`(gracePeriodSignal.isActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        `when`(voiceAudioProcessor.attachCapture()).thenReturn(true)
        `when`(voiceAudioProcessor.peekSpeechDelta(any())).thenReturn(0 to ShortArray(0))
    }
    private val captureFlow: Flow<VoiceSegmenterResult> = MutableStateFlow(VoiceSegmenterResult.Silence)
    private lateinit var engine: VoiceAsrEngine
    private fun CoroutineScope.createEngine() {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(captureFlow)
        kotlinx.coroutines.runBlocking {
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                    detected = false,
                    confidence = 0f,
                    completionSample = 4000,
                ),
            )
        }
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = intentRecognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
    }
    private fun startEngine(route: AudioRoute, mode: ListeningMode = ListeningMode.Continuous) {
        engine.start(
            backend = backend,
            audioRoute = route,
            listeningMode = mode,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
    }
    @Test
    fun `a processing flow that ends reports termination`() = runTest {
        createEngine()
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(flowOf(VoiceSegmenterResult.Silence))
        var terminated = false
        engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
            onTerminated = { terminated = true },
        )
        advanceUntilIdle()
        assertTrue("the service must learn when the pipeline ends", terminated)
    }
    @Test
    fun `a finished processing job stays owned until pruned, and pruning forgets it`() = runTest {
        createEngine()
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(flowOf(VoiceSegmenterResult.Silence))
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        val owned = engine.ownedJobs
        assertTrue("a processing job the engine created must be owned", owned.isNotEmpty())
        engine.stop()
        advanceUntilIdle()
        val finishedOwned = engine.ownedJobs.filter { it.isCompleted }
        assertTrue("finished work must remain owned until it is judged", finishedOwned.isNotEmpty())
        engine.pruneConsumedBookkeeping(onlyKeep = emptySet())
        assertTrue(
            "pruning must FORGET finished jobs (mutation: scanning the unfinished list prunes nothing)",
            engine.ownedJobs.none { it.isCompleted },
        )
    }
    @Test
    fun `a pipeline that never attaches reports termination`() = runTest {
        createEngine()
        `when`(voiceAudioProcessor.attachCapture()).thenReturn(false)
        var terminated = false
        engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
            onTerminated = { terminated = true },
        )
        advanceUntilIdle()
        assertTrue("a start that ends before attaching must report termination", terminated)
    }
    @Test
    fun `start prepares the ASR backend`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(backend, times(1)).ensureReady()
        engine.stop()
    }
    @Test
    fun `a restarted engine prepares the ASR backend again`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        engine.stop()
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(backend, times(2)).ensureReady()
        engine.stop()
    }
    @Test
    fun `stop keeps the backend warm rather than releasing it`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        engine.stop()
        verify(backend, never()).release()
    }
    @Test
    fun `an unprepared backend does not consume audio`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.failure(IllegalStateException("model missing")))
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(voiceAudioProcessor, never()).startProcessing()
    }
    @Test
    fun `start with Speaker route skips SCO and starts capture`() = runTest {
        createEngine()
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(audioManager, never()).startBluetoothSco()
        verify(voiceAudioProcessor).startProcessing()
        engine.stop()
    }
    @Test
    fun `start with WiredHeadset route skips SCO`() = runTest {
        createEngine()
        startEngine(AudioRoute.Headset(hasMicrophone = true))
        advanceUntilIdle()
        verify(audioManager, never()).startBluetoothSco()
        verify(voiceAudioProcessor).startProcessing()
        engine.stop()
    }
    @Test
    fun `start with BluetoothA2dpOnly starts capture`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()
        verify(voiceAudioProcessor).startProcessing()
        engine.stop()
    }
    @Test
    fun `the wake path reaches routing while the real cue's audio write is held`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        val holdWrite = java.util.concurrent.CountDownLatch(1)
        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16_000)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any<ShortArray>(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { inv ->
            enteredWrite.countDown()
            assertTrue("test bug: the held write was never released", holdWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
            inv.getArgument<Int>(2) // accept everything, once released
        }
        val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val player = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer(appContext)
        player.trackFactoryForTest = { track }
        player.installClipForTest(EarconId.WAKE_WORD, ShortArray(1_600) { 1 }, 16_000)
        val realRenderer = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer(
            player,
            au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine(),
        )
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(ShortArray(1_600) { (it % 251).toShort() }, 16_000)),
                    speechOnsetSample = 0,
                    speechEndSample = 0,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        kotlinx.coroutines.runBlocking {
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                WakeWordResult(detected = true, confidence = 0.95f, completionSample = 800, threshold = 0.812f),
            )
        }
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = realRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        val handledIntents = mutableListOf<VoiceIntent>()
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handledIntents += intent },
        )
        advanceUntilIdle()
        try {
            assertTrue(
                "precondition: the real player must be INSIDE the accepted cue write",
                enteredWrite.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertEquals(
                "ASR must reach the recognizer while the acknowledgement cue is still being written",
                listOf("ensureReady", "recognize:pause"),
                recognizer.calls,
            )
            assertEquals(
                "the intent must be dispatched to the caller while the cue write is held",
                listOf(VoiceIntent.Playback.Pause),
                handledIntents,
            )
        } finally {
            holdWrite.countDown()
            runCatching { realRenderer.release() }
            runCatching { engine.stop() }
        }
    }
    @Test
    fun `the filter receives the segment buffer start position, not the speech onset`() = runTest {
        val capturedMicPositionMs = mutableListOf<Long?>()
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        val frames = listOf(
            PcmAudioFrame(ShortArray(16_000), 16_000),
            PcmAudioFrame(ShortArray(16_000), 16_000),
        )
        val capture = flowOf(
            VoiceSegmenterResult.SpeechEnded(
                frames = frames,
                speechOnsetSample = 24_000,
                captureClock = longArrayOf(32_000L, 10_000_000_000L, 16_000L, 1L),
                segmentEndStreamPosition = 32_000L,
            ),
        )
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(capture)
        `when`(
            utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull()),
        ).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            capturedMicPositionMs.add(invocation.getArgument(4))
            true
        }
        kotlinx.coroutines.runBlocking {
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                WakeWordResult(detected = false, confidence = 0f, completionSample = 4000),
            )
        }
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        startEngine(AudioRoute.Speaker)
        try {
            kotlinx.coroutines.withTimeout(5_000) {
                while (capturedMicPositionMs.isEmpty()) kotlinx.coroutines.delay(10)
            }
            assertEquals(
                "the filter must receive the buffer's sample-0 time (8 000 ms), not the onset (9 500 ms)",
                listOf(8_000L),
                capturedMicPositionMs,
            )
        } finally {
            runCatching { engine.stop() }
        }
    }
    private fun speechSegment(): Flow<VoiceSegmenterResult> = flowOf(
        VoiceSegmenterResult.SpeechEnded(
            listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
            speechOnsetSample = 2,
        ),
    )
    private fun CoroutineScope.nondiscardFixture(
        recognizer: RecordingRecognizer,
        wakeDetected: Boolean = false,
    ): Flow<VoiceSegmenterResult> {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        val capture = speechSegment()
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(capture)
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        kotlinx.coroutines.runBlocking {
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                WakeWordResult(
                    detected = wakeDetected,
                    confidence = if (wakeDetected) 0.95f else 0f,
                    completionSample = if (wakeDetected) 4 else 4000,
                ),
            )
        }
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        return capture
    }
    private fun discardAsserts(recognizer: RecordingRecognizer, handled: List<VoiceIntent>) {
        assertEquals("routing was never entered", emptyList<String>(), recognizer.calls)
        assertTrue("the recognizer was never even asked to load", !recognizer.readyAsked)
        assertEquals("nothing routed", emptyList<VoiceIntent>(), handled)
        verify(audioFeedbackRenderer, never()).submitEarcon(any<EarconId>())
        engine.stop()
    }
    @Test
    fun `a punctuation-only transcript is silently discarded before routing`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("."),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        discardAsserts(recognizer, handled)
    }
    @Test
    fun `a whitespace-only transcript is silently discarded before routing`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("   "),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        discardAsserts(recognizer, handled)
    }
    @Test
    fun `a no-wake transcript still routes exactly as before`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = false)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        assertEquals(listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }
    @Test
    fun `a wake-trimmed non-content remainder is discarded, not treated as a command`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = true)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend(
                transcript = "hey auris ...",
                tokens = listOf(
                    au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrToken("hey auris", 0, 100),
                    au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrToken("...", 900, 1000),
                ),
            ),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        assertEquals("nothing routed", emptyList<VoiceIntent>(), handled)
        engine.stop()
    }
    @Test
    fun `numeric content such as a square or fraction mark is not discarded`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = true)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("\u00B2\u00BD"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        assertEquals("numeric marks are content, so the turn still routes", listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }
    @Test
    fun `a supplementary letter such as CJK Extension B is not discarded`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = true)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("\uD840\uDC00"), // U+20000
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        assertEquals("supplementary letters are content", listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }
    @Test
    fun `a supplementary digit such as mathematical bold digit nine is not discarded`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = true)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("\uD835\uDDDF"), // U+1D7DF
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        assertEquals("supplementary digits are content", listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }
    @Test
    fun `a punctuation-only discard followed by content on the same engine routes the second`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        val preDiscardTimerJob = signal.timerJobForTest()
        assertNotNull("wake armed a timer", preDiscardTimerJob)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(400, 300, 200, 100), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        val intents = mutableListOf<VoiceIntent>()
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = object : AsrBackend {
                private var callCount = 0
                override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
                override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult {
                    callCount++
                    return if (callCount == 1) {
                        AsrResult("\u2026", detectedLanguage = "yue") // "…" in Cantonese
                    } else {
                        assertSame(
                            "discard did not re-arm the grace timer",
                            preDiscardTimerJob,
                            signal.timerJobForTest(),
                        )
                        AsrResult("pause")
                    }
                }
                override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "")
                override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en", "yue"))
                override fun release() = Unit
            },
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> intents += intent },
        )
        advanceUntilIdle()
        assertEquals(
            "only the content utterance routed; the punctuation-only one was silently discarded",
            1,
            intents.size,
        )
        assertEquals(VoiceIntent.Playback.Pause, intents.single())
        verify(translationStage, never()).ensureReady("yue")
        verify(translationStage, never()).translate(any(), eq("yue"))
        assertSame(
            "grace timer was not re-armed by either discard or content routing",
            preDiscardTimerJob,
            signal.timerJobForTest(),
        )
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        assertEquals(
            "recognizer was consulted exactly once (for the content utterance only)",
            1,
            recognizer.calls.count { it.startsWith("recognize:") },
        )
        engine.stop()
    }
    @Test
    fun `content transcripts still route normally`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handled += intent },
        )
        advanceUntilIdle()
        assertEquals("content is not discarded", listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }
    @Test
    fun `transcription initializes recognizer before matching intent`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        val handledIntents = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handledIntents += intent },
        )
        advanceUntilIdle()
        assertEquals(listOf("ensureReady", "recognize:pause"), recognizer.calls)
        assertEquals(listOf(VoiceIntent.Playback.Pause), handledIntents)
        verify(wakeWordDetector).detect(any(), eq(16000), eq(2))
        engine.stop()
    }
    @Test
    fun `playback bleed is dropped before transcription and produces no accepted activity`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        val backend = FakeAsrBackend("pause")
        var resets = 0
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(false)
        `when`(utteranceFilter.reset()).thenAnswer { resets += 1 }
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        val handledIntents = mutableListOf<VoiceIntent>()
        engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handledIntents += intent },
        )
        advanceUntilIdle()
        assertEquals("a bleed-rejected segment must not reach the recognizer", emptyList<String>(), recognizer.calls)
        assertEquals("and no intent may be accepted", emptyList<VoiceIntent>(), handledIntents)
        assertEquals(
            "nor may the segment have been transcribed",
            0,
            backend.transcribeCalls,
        )
        assertEquals("a rejected segment must not reset the session filter", 1, resets)
        verify(gracePeriodSignal, never()).onWakeWordDetected()
        verify(gracePeriodSignal, never()).onCommandRecognized(any())
        engine.stop()
    }
    @Test
    fun `a routing failure escalates to the cloud with the transcript verbatim`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_MAPPER_OR_DIALOG_FAILED,
            transcript = "what did the guests say about sleep and memory",
        )
        assertEquals(
            listOf(
                VoiceIntent.CloudRoute(
                    request = "what did the guests say about sleep and memory",
                    tier = VoiceIntent.CloudTier.Unknown,
                    windowGeneration = 1L,
                    origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
                    logicalRequestId = (intents.single() as VoiceIntent.CloudRoute).logicalRequestId,
                ),
            ),
            intents,
        )
        assertTrue(
            "an ordinary ASR cloud dispatch must carry a logical identity, so a retry of it can " +
                "name the same request (id=${(intents.single() as VoiceIntent.CloudRoute).logicalRequestId})",
            !(intents.single() as VoiceIntent.CloudRoute).logicalRequestId.isNullOrBlank(),
        )
        verify(audioFeedbackRenderer, never()).submitEarcon(any())
        engine.stop()
    }
    @Test
    fun `a no_match from a wake-detected segment speaks the unclear-command line`() = runTest {
        val resolver = SpokenTemplateResolver(
            mapOf("pipeline.unclear_command" to "I didn't catch that."),
        )
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_NO_MATCH,
            transcript = "Hi, allri.",
            wakeDetected = true,
            templateResolver = resolver,
        )
        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).render(VoiceResponse.Spoken("I didn't catch that."))
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        engine.stop()
    }
    @Test
    fun `a capture inside the completion band is dropped as the wake word alone`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_NO_MATCH,
            transcript = "Hi, allri.",
            wakeDetected = true,
            speechEndSample = 5000,
            templateResolver = SpokenTemplateResolver(
                mapOf("pipeline.unclear_command" to "I didn't catch that."),
            ),
        )
        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer, never()).render(any(), any(), any())
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        engine.stop()
    }
    @Test
    fun `a capture outside the completion band routes, because speech followed the wake word`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_NO_MATCH,
            transcript = "Hi, allri.",
            wakeDetected = true,
            speechEndSample = 8000,
            templateResolver = SpokenTemplateResolver(
                mapOf("pipeline.unclear_command" to "I didn't catch that."),
            ),
        )
        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).render(VoiceResponse.Spoken("I didn't catch that."))
        engine.stop()
    }
    @Test
    fun `a segment with no end sample is unknown, not wake-only`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_NO_MATCH,
            transcript = "Hi, allri.",
            wakeDetected = true,
            speechEndSample = 0,
            completionSample = 0,
            templateResolver = SpokenTemplateResolver(
                mapOf("pipeline.unclear_command" to "I didn't catch that."),
            ),
        )
        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).render(VoiceResponse.Spoken("I didn't catch that."))
        engine.stop()
    }
    @Test
    fun `a no_match from the room keeps the earcon and stays local`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_NO_MATCH,
            transcript = "Hi, allri.",
            templateResolver = SpokenTemplateResolver(
                mapOf("pipeline.unclear_command" to "I didn't catch that."),
            ),
        )
        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).submitEarcon(EarconId.ERROR)
        verify(audioFeedbackRenderer, never()).render(any(), any(), any())
        engine.stop()
    }
    @Test
    fun `an empty transcript never reaches the cloud`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT,
            transcript = "",
        )
        assertTrue(intents.isEmpty())
        engine.stop()
    }
    @Test
    fun `a local capability failure stays local and says so`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_MODEL_NOT_LOADED,
            transcript = "pause",
        )
        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).submitEarcon(EarconId.ERROR)
        engine.stop()
    }
    @Test
    fun `independent utterances in one grace window each dispatch once`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(400, 300, 200, 100), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        val intents = mutableListOf<VoiceIntent>()
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = FailingRecognizer(RouterStageDiagnostic.REASON_CLASSIFY_FAILED),
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unclear question"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> intents += intent },
        )
        advanceUntilIdle()
        assertEquals("independent utterances own independent logical dispatches", 2, intents.size)
        val cloud = intents.map { it as VoiceIntent.CloudRoute }
        org.junit.Assert.assertNotEquals(cloud[0].logicalRequestId, cloud[1].logicalRequestId)
        assertEquals(cloud[0].windowGeneration, cloud[1].windowGeneration)
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        engine.stop()
    }
    @Test
    fun `an escalation after a successor wake is refused and spends no allowance`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        val intents = mutableListOf<VoiceIntent>()
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = SuccessorWakeRecognizer(
                reason = RouterStageDiagnostic.REASON_CLASSIFY_FAILED,
            ) { signal.onWakeWordDetected() },
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unclear question"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> intents += intent },
        )
        advanceUntilIdle()
        assertTrue(
            "an escalation from a superseded recognition must not dispatch " +
                "(intents=${intents.map { (it as? VoiceIntent.CloudRoute)?.windowGeneration }})",
            intents.none { it is VoiceIntent.CloudRoute },
        )
        engine.stop()
    }
    fun `a router that cannot load its model says so and does not escalate`() = runTest {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        val recognizer = NotReadyRecognizer()
        val intents = mutableListOf<VoiceIntent>()
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = GracePeriodSignal(timeoutMs = 30_000L),
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unclear question"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> intents += intent },
        )
        advanceUntilIdle()
        assertTrue(intents.isEmpty())
        assertEquals(0, recognizer.recognizes)
        verify(audioFeedbackRenderer).submitEarcon(EarconId.ERROR)
        engine.stop()
    }
    @Test
    fun `a bare wake phrase is rejected silently`() = runTest {
        val recognizer = RecordingRecognizer(null)
        val engine = startTokenless(recognizer, "Hi, allri.")
        assertTrue("the router may see it; the decision is what matters", recognizer.calls.isNotEmpty())
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        engine.stop()
    }
    private suspend fun TestScope.startTokenless(
        recognizer: VoiceRecognizer,
        transcript: String,
    ): VoiceAsrEngine {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16_000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = true,
                confidence = 0.95f,
                completionSample = 1_600,
                threshold = 0.812f,
            ),
        )
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = GracePeriodSignal(timeoutMs = 30_000L),
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend(transcript),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        return engine
    }
    private suspend fun TestScope.startFailingEngine(
        reason: String,
        transcript: String,
        wakeDetected: Boolean = false,
        templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(emptyMap()),
        currentLocale: () -> Locale = { Locale.ENGLISH },
        speechEndSample: Int = 0,
        completionSample: Int = 4000,
    ): Pair<VoiceAsrEngine, MutableList<VoiceIntent>> {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                    speechEndSample = speechEndSample,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = wakeDetected,
                confidence = 0f,
                completionSample = completionSample,
            ),
        )
        val intents = mutableListOf<VoiceIntent>()
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = FailingRecognizer(reason),
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
            templateResolver = templateResolver,
            currentLocale = currentLocale,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend(transcript),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> intents += intent },
        )
        advanceUntilIdle()
        return engine to intents
    }
    @Test
    fun `non-English transcript translated by stage before intent routing when backend cannot translate`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
            ),
        )
        `when`(translationStage.ensureReady("zh")).thenReturn(Result.success(Unit))
        `when`(translationStage.translate("你好", "zh")).thenReturn(Result.success("hello"))
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = ResultBackend(AsrResult(text = "你好", detectedLanguage = "zh")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        verify(translationStage).ensureReady("zh")
        verify(translationStage).translate("你好", "zh")
        assertTrue("Expected translated 'hello' to reach recognizer", recognizer.calls.contains("recognize:hello"))
        val routed = recognizer.inputs.single()
        assertEquals("你好", routed.sourceTranscript)
        assertEquals("zh", routed.sourceLanguage)
        assertEquals("hello", routed.routerTranscript)
        assertEquals(TranslationKind.PLATFORM, routed.translationKind)
        engine.stop()
    }
    @Test
    fun `zh translation preserves source envelope for rewind phrase`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            WakeWordResult(detected = false, confidence = 0f),
        )
        `when`(translationStage.ensureReady("zh")).thenReturn(Result.success(Unit))
        `when`(translationStage.translate("倒回去3分钟。", "zh"))
            .thenReturn(Result.success("Go back to 3 minutes."))
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = ResultBackend(AsrResult(text = "倒回去3分钟。", detectedLanguage = "zh")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        val routed = recognizer.inputs.single()
        assertEquals("倒回去3分钟。", routed.sourceTranscript)
        assertEquals("zh", routed.sourceLanguage)
        assertEquals("Go back to 3 minutes.", routed.routerTranscript)
        assertEquals(TranslationKind.PLATFORM, routed.translationKind)
        engine.stop()
    }
    @Test
    fun `english transcript uses none translation kind with identical source and router`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            WakeWordResult(detected = false, confidence = 0f),
        )
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = ResultBackend(AsrResult(text = "pause", detectedLanguage = "en")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        val routed = recognizer.inputs.single()
        assertEquals("pause", routed.sourceTranscript)
        assertEquals("en", routed.sourceLanguage)
        assertEquals("pause", routed.routerTranscript)
        assertEquals(TranslationKind.NONE, routed.translationKind)
        verify(translationStage, never()).translate(any(), any())
        engine.stop()
    }
    @Test
    fun `canary result semantics yield backend envelope with configured source lang`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            WakeWordResult(detected = false, confidence = 0f),
        )
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        val canaryResult = CanaryFlashBackend.asrResultForTranslatedText(
            text = "Go back to 3 minutes.",
            sourceLanguage = "de",
        )
        engine.start(
            backend = ResultBackend(canaryResult, canTranslateToEnglish = true),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        val routed = recognizer.inputs.single()
        assertNull(routed.sourceTranscript)
        assertEquals("de", routed.sourceLanguage)
        assertEquals("Go back to 3 minutes.", routed.routerTranscript)
        assertEquals(TranslationKind.BACKEND, routed.translationKind)
        verify(translationStage, never()).translate(any(), any())
        engine.stop()
    }
    @Test
    fun `asr log keeps source lang and text with english only in translate note`() = runTest {
        val logs = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
            `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
            `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
            `when`(voiceAudioProcessor.startProcessing()).thenReturn(
                flowOf(
                    VoiceSegmenterResult.SpeechEnded(
                        listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                        speechOnsetSample = 2,
                    ),
                ),
            )
            `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                WakeWordResult(detected = false, confidence = 0f),
            )
            `when`(translationStage.ensureReady("yue")).thenReturn(Result.success(Unit))
            `when`(translationStage.translate("播放。", "yue")).thenReturn(Result.success("Play."))
            engine = VoiceAsrEngine(
                voiceAudioProcessor = voiceAudioProcessor,
                utteranceFilter = utteranceFilter,
                intentRecognizer = recognizer,
                wakeWordDetector = wakeWordDetector,
                gracePeriodSignal = gracePeriodSignal,
                audioFeedbackRenderer = audioFeedbackRenderer,
                translationStage = translationStage,
                collectionAuthority = authority,
                retentionSink = retentionSink,
                segmentObserver = observer,
                context = context,
            )
            engine.scope = this
            engine.start(
                backend = ResultBackend(AsrResult(text = "播放。", detectedLanguage = "yue")),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
                micExposureProvider = { MicExposure.Exposed },
                onIntent = { _, _ -> },
            )
            advanceUntilIdle()
            val asrLog = logs.single {
                it.startsWith("[VoicePipeline] asr ") && !it.contains("→ drop")
            }
            assertTrue(
                "Expected source-first log, got: $asrLog",
                asrLog.matches(
                    Regex("""\[VoicePipeline\] asr ResultBackend \d+ms lang=yue id=[0-9a-f-]+ '播放。' translate=yue→en 'Play\.'"""),
                ),
            )
            assertTrue(recognizer.calls.contains("recognize:Play."))
            engine.stop()
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }
    @Test
    fun `translate fail blank and noop drop with ERROR earcon and skip intent`() = runTest {
        data class Case(
            val label: String,
            val ensureReady: Result<Unit>,
            val translateResult: Result<String>?,
        )
        val cases = listOf(
            Case("fail-ready", Result.failure(IllegalStateException("no model")), null),
            Case("fail-translate", Result.success(Unit), Result.failure(IllegalStateException("mlkit"))),
            Case("blank", Result.success(Unit), Result.success("")),
            Case("noop", Result.success(Unit), Result.success("你好")),
        )
        for (case in cases) {
            val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
            val localTranslation = mock<TranslationStage>()
            `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
            `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
            `when`(voiceAudioProcessor.startProcessing()).thenReturn(
                flowOf(
                    VoiceSegmenterResult.SpeechEnded(
                        listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                        speechOnsetSample = 2,
                    ),
                ),
            )
            `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                WakeWordResult(detected = false, confidence = 0f),
            )
            `when`(localTranslation.ensureReady("zh")).thenReturn(case.ensureReady)
            if (case.translateResult != null) {
                `when`(localTranslation.translate("你好", "zh")).thenReturn(case.translateResult)
            }
            engine = VoiceAsrEngine(
                voiceAudioProcessor = voiceAudioProcessor,
                utteranceFilter = utteranceFilter,
                intentRecognizer = recognizer,
                wakeWordDetector = wakeWordDetector,
                gracePeriodSignal = gracePeriodSignal,
                audioFeedbackRenderer = audioFeedbackRenderer,
                translationStage = localTranslation,
                collectionAuthority = authority,
                retentionSink = retentionSink,
                segmentObserver = observer,
                context = context,
            )
            engine.scope = this
            engine.start(
                backend = ResultBackend(AsrResult(text = "你好", detectedLanguage = "zh")),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
                micExposureProvider = { MicExposure.Exposed },
                onIntent = { _, _ -> },
            )
            advanceUntilIdle()
            assertTrue(
                "${case.label}: expected no intent routing, got ${recognizer.calls}",
                recognizer.calls.isEmpty(),
            )
            verify(audioFeedbackRenderer).submitEarcon(EarconId.ERROR)
            engine.stop()
            org.mockito.Mockito.reset(audioFeedbackRenderer)
        }
    }
    @Test
    fun `english transcript bypasses translation stage`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
            ),
        )
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = ResultBackend(AsrResult(text = "pause", detectedLanguage = "en")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        verify(translationStage, never()).translate(any(), any())
        assertTrue("Expected native 'pause' to reach recognizer", recognizer.calls.contains("recognize:pause"))
        engine.stop()
    }
    private fun CoroutineScope.createEngineWithSpeech(
        recognizer: VoiceRecognizer,
        wakeWordResult: WakeWordResult,
    ) = createEngineWithSpeech(observer, retentionSink, authority, recognizer, wakeWordResult)
    @Test
    fun `no allowance authority - a consented capture is still admitted and retained while recognition proceeds`() = runTest {
        val retention = ContributionRetentionSink()
        val liveAuthority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        val bareObserver = SegmentObserver(
            CollectionGate { _ -> true },
            retention,
            liveAuthority,
            issueAuthority = { issuedAuthorityForTests },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(bareObserver, retention, liveAuthority, recognizer, WakeWordResult(detected = false, confidence = 0f, completionSample = 4000))
        engine.start(
            backend = FakeAsrBackend("play something"),
            audioRoute = AudioRoute.Headset(hasMicrophone = true),
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        assertTrue("a consented capture with no allowance authority must be admitted", recognizer.calls.any { it.startsWith("recognize:") })
        assertEquals("exactly one capture retained from the bare path", 1, retention.retainedCount())
        engine.stop()
        advanceUntilIdle()
    }
    @Test
    fun `prepaid positive companion - the same engine path retains exactly one capture`() = runTest {
        val retention = ContributionRetentionSink()
        val liveAuthority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        val prepaidObserver = SegmentObserver(
            CollectionGate { _ -> true },
            retention,
            liveAuthority,
            issueAuthority = { issuedAuthorityForTests },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(prepaidObserver, retention, liveAuthority, recognizer, WakeWordResult(detected = false, confidence = 0f, completionSample = 4000))
        engine.start(
            backend = FakeAsrBackend("play something"),
            audioRoute = AudioRoute.Headset(hasMicrophone = true),
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        val candidate = retention.retainedSnapshot().singleOrNull()
        assertTrue("the prepaid positive companion must retain exactly one capture", candidate != null)
        assertEquals(1L, candidate!!.context.captureSession)
        engine.stop()
        advanceUntilIdle()
    }
    @Test
    fun `an over-ceiling capture is refused at admission and recognition still proceeds`() = runTest {
        val retention = ContributionRetentionSink()
        val liveAuthority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        val overObserver = SegmentObserver(
            CollectionGate { _ -> true },
            retention,
            liveAuthority,
            issueAuthority = { issuedAuthorityForTests },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(overObserver, retention, liveAuthority, recognizer, WakeWordResult(detected = false, confidence = 0f, completionSample = 4000))
        `when`(voiceAudioProcessor.speechPcmSize()).thenReturn((ContributionCeilings.MAX_CLIP_BYTES / 2L + 44L).toInt())
        engine.start(
            backend = FakeAsrBackend("play something"),
            audioRoute = AudioRoute.Headset(hasMicrophone = true),
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        verify(voiceAudioProcessor, times(0)).drainSpeechPcm(org.mockito.kotlin.any<ShortArray>())
        assertEquals("nothing may be retained from an over-ceiling capture", 0, retention.retainedCount())
        assertEquals(0L, retention.retainedBytes())
        assertTrue(
            "recognition continues: the utterance still reached the recognizer after retention refused",
            recognizer.calls.any { it.startsWith("recognize:") },
        )
        engine.stop()
        advanceUntilIdle()
    }
    private fun CoroutineScope.createEngineWithSpeech(
        observer: SegmentObserver,
        sink: ContributionRetentionSink,
        authorityOverride: () -> AuthorityDecision,
        recognizer: VoiceRecognizer,
        wakeWordResult: WakeWordResult,
    ) {
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechStarted,
                VoiceSegmenterResult.SpeechContinuing,
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        kotlinx.coroutines.runBlocking {
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(wakeWordResult)
        }
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authorityOverride,
            retentionSink = sink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
    }
    @Test
    fun `wake word detection opens grace plays earcon and forwards full segment to ASR`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(
            recognizer = recognizer,
            wakeWordResult = WakeWordResult(
                detected = true,
                confidence = 0.9f,
                completionSample = 4000,
            ),
        )
        val handledIntents = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend(
                "Auris skip forward",
                tokens = listOf(
                    AsrToken("Auris", 0, 300),
                    AsrToken(" skip", 500, 800),
                    AsrToken(" forward", 800, 1200),
                ),
            ),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> handledIntents += intent },
        )
        advanceUntilIdle()
        verify(gracePeriodSignal).onWakeWordDetected()
        verify(audioFeedbackRenderer).submitEarcon(EarconId.WAKE_WORD)
        assertEquals(listOf("ensureReady", "recognize:skip forward"), recognizer.calls)
        assertEquals(listOf(VoiceIntent.Playback.Pause), handledIntents)
        engine.stop()
    }
    @Test
    fun `wake-only detection opens grace and stays silent after an empty command transcript`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(
            recognizer = recognizer,
            wakeWordResult = WakeWordResult(
                detected = true,
                confidence = 0.9f,
                completionSample = 4000,
            ),
        )
        engine.start(
            backend = FakeAsrBackend(
                "Auris",
                tokens = listOf(AsrToken("Auris", 0, 400)),
            ),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        verify(gracePeriodSignal).onWakeWordDetected()
        verify(audioFeedbackRenderer).submitEarcon(EarconId.WAKE_WORD)
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        assertTrue("Expected no intent routing, got ${recognizer.calls}", recognizer.calls.isEmpty())
        engine.stop()
    }
    @Test
    fun `negative detection in WakeWord mode drops segment`() = runTest {
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(
            recognizer = recognizer,
            wakeWordResult = WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        verify(gracePeriodSignal, never()).onWakeWordDetected()
        verify(audioFeedbackRenderer, never()).submitEarcon(any())
        assertTrue("Expected no ASR calls, got ${recognizer.calls}", recognizer.calls.isEmpty())
        engine.stop()
    }
    @Test
    fun `a pipeline that fails to attach reports the stream was not taken`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        `when`(voiceAudioProcessor.attachCapture()).thenReturn(false)
        val attachment = engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        assertFalse("the pipeline never attached capture", attachment.await())
        verify(voiceAudioProcessor, never()).startProcessing()
        engine.stop()
    }
    @Test
    fun `a pipeline that attaches reports the start took the stream`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        val attachment = engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        assertTrue("the pipeline attached capture", attachment.await())
        verify(voiceAudioProcessor).attachCapture()
        engine.stop()
    }
    @Test
    fun `a start that fails to attach closes the confirmed stream and leaves no active capture`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        `when`(voiceAudioProcessor.attachCapture()).thenReturn(false)
        val platform = FakeAcquisitionPlatform()
        val acquisition = RouteAcquisition(platform, timeoutMs = 1_000L, pollIntervalMs = 10L)
        assertTrue(acquisition.confirmCaptureStream())
        val attachment = engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        assertFalse("the pipeline never attached capture", attachment.await())
        verify(voiceAudioProcessor, never()).startProcessing()
        acquisition.release()
        assertEquals("the confirmed stream must be closed", 1, platform.closeCalls)
        assertFalse("no capture stream is left open", acquisition.hasConfirmedStream)
        engine.stop()
    }
    @Test
    fun `a start that attaches hands the confirmed stream to the engine exactly once`() = runTest {
        createEngine()
        `when`(backend.ensureReady()).thenReturn(Result.success(Unit))
        val platform = FakeAcquisitionPlatform()
        val acquisition = RouteAcquisition(platform, timeoutMs = 1_000L, pollIntervalMs = 10L)
        assertTrue(acquisition.confirmCaptureStream())
        val attachment = engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        assertTrue("the pipeline attached capture", attachment.await())
        acquisition.handOffStream()
        acquisition.release()
        assertEquals(0, platform.closeCalls)
        engine.stop()
    }
    @Test
    fun `a chosen-route dispatch after a successor wake carries the original window and admitted ID`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        val intents = mutableListOf<VoiceIntent>()
        val recognizer = SuccessorWakeWithCloudResultRecognizer(
            onSuspended = { signal.onWakeWordDetected() },
        ) { signal.capturedGeneration() }
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("play something"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> intents += intent },
        )
        advanceUntilIdle()
        assertEquals("exactly one cloud dispatch", 1, intents.size)
        val cloud = intents.single() as VoiceIntent.CloudRoute
        assertEquals(
            "the dispatch carries the ORIGINAL window generation, not the successor's",
            1L,
            cloud.windowGeneration!!,
        )
        assertTrue(
            "the dispatch carries an admitted identity",
            !cloud.logicalRequestId.isNullOrBlank(),
        )
        assertNotNull("dispatch must carry a window generation", cloud.windowGeneration)
        assertTrue(
            "the signal advanced past the dispatch's window (successor opened)",
            signal.capturedGeneration() > cloud.windowGeneration,
        )
        assertEquals(
            "the dispatched ID is the SAME one the recognizer received at admission",
            recognizer.receivedLogicalRequestId,
            cloud.logicalRequestId,
        )
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        engine.stop()
    }
    @Test
    fun `engine stamps a dialog-completed cloud route with the current utterance admitted ID`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        val intents = mutableListOf<VoiceIntent>()
        var receivedAdmittedId: String? = null
        val recognizer = object : VoiceRecognizer {
            override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
            override suspend fun recognize(
                input: IntentRoutingInput,
                context: VoiceRecognitionContext,
                logicalRequestId: String,
            ): VoiceRecognizeResult {
                receivedAdmittedId = logicalRequestId
                return VoiceRecognizeResult(
                    intent = VoiceIntent.CloudRoute(
                        request = "What is the weather?",
                        tier = VoiceIntent.CloudTier.Unknown,
                        windowGeneration = null,
                        origin = VoiceIntent.CloudRouteOrigin.ModelCall,
                        logicalRequestId = null, // dialog-completed: no identity
                    ),
                )
            }
            override fun release() = Unit
        }
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("what is the weather"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> intents += intent },
        )
        advanceUntilIdle()
        assertEquals("exactly one intent dispatched", 1, intents.size)
        val cloud = intents.single() as VoiceIntent.CloudRoute
        assertNotNull("recognizer received an admitted ID", receivedAdmittedId)
        assertEquals(
            "engine stamped the dialog-completed route with the utterance's admitted ID",
            receivedAdmittedId,
            cloud.logicalRequestId,
        )
        assertTrue(
            "the stamped ID is non-empty",
            !cloud.logicalRequestId.isNullOrBlank(),
        )
        engine.stop()
    }
    @Test
    fun `engine-stamped dialog completion flows through sink to CloudTurnRoute authenticate frame`() = runTest {
        val admittedA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        val dialogManager = au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager(
            au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper(),
        )
        assertNull(
            dialogManager.resolve(
                "Ask about weather.",
                "<start_function_call>call:dialog_control{action:<escape>begin<escape>}<end_function_call>",
                au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall(
                    name = "dialog_control",
                    action = "begin",
                    params = mapOf("target_tool" to "cloud_route", "target_action" to "route"),
                ),
                admittedId = admittedA,
            ),
        )
        assertEquals(admittedA, dialogManager.pendingDialog?.logicalRequestId)
        val engineIntents = mutableListOf<VoiceIntent>()
        var engineAdmittedId: String? = null
        val recognizer = object : VoiceRecognizer {
            override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
            override suspend fun recognize(
                input: IntentRoutingInput,
                context: VoiceRecognitionContext,
                logicalRequestId: String,
            ): VoiceRecognizeResult {
                engineAdmittedId = logicalRequestId
                val completedIntent = dialogManager.resolve(
                    "What is the weather?",
                    "<start_function_call>call:dialog_control{action:<escape>provide_slot<escape>}<end_function_call>",
                    au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall(
                        name = "dialog_control",
                        action = "provide_slot",
                        params = mapOf("slot" to "request", "value" to "What is the weather?"),
                    ),
                    admittedId = logicalRequestId,
                )
                return VoiceRecognizeResult(intent = completedIntent)
            }
            override fun release() = Unit
        }
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("what is the weather"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, _ -> engineIntents += intent },
        )
        advanceUntilIdle()
        engine.stop()
        assertNotNull("engine minted an admitted ID", engineAdmittedId)
        assertEquals("exactly one intent dispatched", 1, engineIntents.size)
        val stampedCloud = engineIntents.single() as VoiceIntent.CloudRoute
        assertEquals(
            "engine stamped the dialog-completed route",
            engineAdmittedId,
            stampedCloud.logicalRequestId,
        )
        val sentTextFrames = mutableListOf<String>()
        val mockSocket = object : okhttp3.WebSocket {
            override fun cancel() = Unit
            override fun close(code: Int, reason: String?) = true
            override fun queueSize(): Long = 0
            override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("https://example.com").build()
            override fun send(text: String): Boolean {
                sentTextFrames += text
                return true
            }
            override fun send(bytes: okio.ByteString): Boolean = true
        }
        val wsClient = object : okhttp3.OkHttpClient() {
            override fun newWebSocket(request: okhttp3.Request, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
                listener.onOpen(
                    mockSocket,
                    okhttp3.Response.Builder()
                        .code(101).message("Switching Protocols")
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .request(request).build(),
                )
                listener.onMessage(mockSocket, """{"type":"connected","codec":"pcm_s16le@24k","reservation_id":"r-1"}""")
                listener.onMessage(mockSocket, """{"type":"done","outcome":"completed","usage":{"input_tokens":0,"output_tokens":0,"speech":{"amount":0,"unit":"characters"}}}""")
                listener.onClosed(mockSocket, 1000, "")
                return mockSocket
            }
        }
        val realRoute = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnRoute(
            baseUrl = "https://example.com",
            tokenProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding {
                override suspend fun currentToken(): String = "test-token"
                override suspend fun refreshToken() = Unit
            },
            transport = au.com.shiftyjelly.pocketcasts.repositories.cloud.WebSocketCloudTurnTransport(
                "https://example.com",
                wsClient,
            ),
        )
        val conversationAdmission = au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudConversationAdmission()
        val sink = au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudRouteSink(
            resolveBaseUrl = { "https://example.com" },
            tokenProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding {
                override suspend fun currentToken(): String = "test-token"
                override suspend fun refreshToken() = Unit
            },
            playbackSink = mock(),
            fingerprintTimingManager = mock(),
            playbackContextProvider = mock(),
            conversationAdmission = conversationAdmission,
            cloudPlaybackContextState = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudPlaybackContextState>().apply {
                `when`(snapshot(anyOrNull())).thenReturn(
                    au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudPlaybackContextState.Snapshot(
                        recentReferencePositions = emptyList(),
                        previousReferencePositionMs = null,
                    ),
                )
            },
            analytics = mock(),
            routeInvoker = { turn -> realRoute.route(turn) },
            searchResultsRenderer = mock(),
            templateResolver = mock(),
            currentLocale = { java.util.Locale.US },
            isHostPlaying = { false },
            playbackCommandRevision = { 0L },
            isPrivacyClosed = { false },
            privacyClosureCount = { 0 },
            episodeDurationSeconds = { 0 },
            audioPlayer = mock(),
        )
        conversationAdmission.beginOrigin(stampedCloud.windowGeneration ?: 0L)
        val prepared = sink.prepareTurn(
            request = stampedCloud.request,
            tier = stampedCloud.tier,
            context = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext(
                episodeId = "ep-1",
                clientPositionMs = 50_000L,
            ),
            isLiveGraceTurn = false,
            capturedOrigin = stampedCloud.windowGeneration ?: 0L,
            isWakeOriginated = false,
            hint = null,
            logicalRequestId = stampedCloud.logicalRequestId,
            isRetry = false,
            sourceLanguage = null,
        )!!
        sink.dispatchPrepared(prepared)
        assertTrue("at least one text frame sent", sentTextFrames.isNotEmpty())
        val encodedFrame = sentTextFrames.first()
        assertTrue(
            "encoded wire frame carries the engine-stamped admitted ID (frame=$encodedFrame)",
            encodedFrame.contains("\"request_id\":\"$engineAdmittedId\""),
        )
        assertFalse(
            "encoded wire frame must NOT carry the begin identity A (frame=$encodedFrame)",
            encodedFrame.contains(admittedA),
        )
    }
    private class NotReadyRecognizer : VoiceRecognizer {
        var recognizes = 0
        override suspend fun ensureReady(): Result<Unit> = Result.failure(IllegalStateException("model missing"))
        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
            logicalRequestId: String,
        ): VoiceRecognizeResult {
            recognizes += 1
            return VoiceRecognizeResult(intent = null)
        }
        override fun release() = Unit
    }
    private class SuccessorWakeRecognizer(
        private val reason: String,
        private val onSuspended: () -> Unit,
    ) : VoiceRecognizer {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
            logicalRequestId: String,
        ): VoiceRecognizeResult {
            onSuspended()
            return VoiceRecognizeResult(
                intent = null,
                diagnostic = RouterStageDiagnostic(
                    modelRelease = null,
                    quant = null,
                    inputFormat = null,
                    sourceLanguage = null,
                    translationKind = "none",
                    classifierLabel = null,
                    finalOutcome = RouterStageDiagnostic.OUTCOME_NO_INTENT,
                    failedStage = RouterStageDiagnostic.STAGE_MAPPER_DIALOG,
                    reason = reason,
                    stageLatencyMs = emptyMap(),
                    totalLatencyMs = 5L,
                    requestId = "test-utterance-identity",
                ),
            )
        }
        override fun release() = Unit
    }
    private class SuccessorWakeWithCloudResultRecognizer(
        private val onSuspended: () -> Unit,
        private val currentGeneration: () -> Long,
    ) : VoiceRecognizer {
        var receivedLogicalRequestId: String? = null
            private set
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
            logicalRequestId: String,
        ): VoiceRecognizeResult {
            receivedLogicalRequestId = logicalRequestId
            onSuspended()
            return VoiceRecognizeResult(
                intent = VoiceIntent.CloudRoute(
                    request = input.routerTranscript,
                    tier = VoiceIntent.CloudTier.Unknown,
                    windowGeneration = null, // will be overwritten by engine's stamp
                    origin = VoiceIntent.CloudRouteOrigin.ModelCall,
                    logicalRequestId = logicalRequestId,
                ),
            )
        }
        override fun release() = Unit
    }
    private class FailingRecognizer(private val reason: String) : VoiceRecognizer {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
            logicalRequestId: String,
        ): VoiceRecognizeResult = VoiceRecognizeResult(
            intent = null,
            diagnostic = RouterStageDiagnostic(
                modelRelease = null,
                quant = null,
                inputFormat = null,
                sourceLanguage = null,
                translationKind = "none",
                classifierLabel = null,
                finalOutcome = RouterStageDiagnostic.OUTCOME_NO_INTENT,
                failedStage = RouterStageDiagnostic.STAGE_MAPPER_DIALOG,
                reason = reason,
                stageLatencyMs = emptyMap(),
                totalLatencyMs = 5L,
                requestId = "test-utterance-identity",
            ),
        )
        override fun release() = Unit
    }
    @Test
    fun `two identical dispatched intents receive distinct invocation tokens bound to their own captures`() = runTest {
        authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        val admittingObserver = SegmentObserver(
            CollectionGate { _ -> true },
            retentionSink,
            authority,
            issueAuthority = { issuedAuthorityForTests },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechStarted,
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
                VoiceSegmenterResult.SpeechStarted,
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(voiceAudioProcessor.speechPcmSize()).thenReturn(800)
        `when`(voiceAudioProcessor.drainSpeechPcm(any())).thenReturn(800)
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(gracePeriodSignal.isActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(true))
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = RecordingRecognizer(VoiceIntent.Playback.Pause),
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = admittingObserver,
            context = context,
        )
        engine.scope = this
        val tokens = mutableListOf<VoiceAsrEngine.InvocationToken>()
        val gotIntents = mutableListOf<VoiceIntent>()
        engine.start(
            backend = bleedingBackendForLifecycle(),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intent, token ->
                gotIntents += intent
                if (token != null) tokens += token
            },
        )
        advanceUntilIdle()
        engine.stop()
        assertEquals(listOf(VoiceIntent.Playback.Pause, VoiceIntent.Playback.Pause), gotIntents)
        assertEquals("both identical dispatches must carry a token", 2, tokens.size)
        assertTrue("identical intents are DIFFERENT dispatches", tokens[0].id != tokens[1].id)
        assertTrue("different dispatches belong to different captures", tokens[0].captureId != tokens[1].captureId)
    }
    @Test
    fun `a result reported after stop or settle is dropped, not applied`() = runTest {
        authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        val admittingObserver = SegmentObserver(
            CollectionGate { _ -> true },
            retentionSink,
            authority,
            issueAuthority = { issuedAuthorityForTests },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechStarted,
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
            ),
        )
        `when`(voiceAudioProcessor.speechPcmSize()).thenReturn(800)
        `when`(voiceAudioProcessor.drainSpeechPcm(any())).thenReturn(800)
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        `when`(gracePeriodSignal.isActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(true))
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = false,
                confidence = 0f,
                completionSample = 4000,
            ),
        )
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = RecordingRecognizer(VoiceIntent.Playback.Pause),
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = authority,
            retentionSink = retentionSink,
            segmentObserver = admittingObserver,
            context = context,
        )
        engine.scope = this
        var stopped: VoiceAsrEngine.InvocationToken? = null
        engine.start(
            backend = bleedingBackendForLifecycle(),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, token -> if (token != null) stopped = token },
        )
        advanceUntilIdle()
        engine.stop()
        assertEquals(
            "a stop must end the invocation; a late result must be refused",
            false,
            engine.reportExecutionResult(checkNotNull(stopped), au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome.Success),
        )
    }
    private fun bleedingBackendForLifecycle(): AsrBackend = object : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult("pause")
        override val requiredModel = ModelSpec(
            files = listOf(
                au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelFile(
                    url = "u",
                    filename = "f",
                    manifestId = "asr-1",
                ),
            ),
            targetDir = "manifest-version-1",
        )
        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en"))
        override fun release() = Unit
    }
    private class RecordingRecognizer(
        private val intent: VoiceIntent?,
    ) : VoiceRecognizer {
        val calls = mutableListOf<String>()
        val inputs = mutableListOf<IntentRoutingInput>()
        var readyAsked = false
            private set
        override suspend fun ensureReady(): Result<Unit> {
            calls += "ensureReady"
            readyAsked = true
            return Result.success(Unit)
        }
        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
            logicalRequestId: String,
        ): VoiceRecognizeResult {
            calls += "recognize:${input.routerTranscript}"
            inputs += input
            return VoiceRecognizeResult(intent = intent)
        }
        override fun release() = Unit
    }
    private class FakeAsrBackend(
        private val transcript: String,
        private val tokens: List<AsrToken>? = null,
        private val detectedLanguage: String? = null,
    ) : AsrBackend {
        var transcribeCalls = 0
            private set
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult {
            transcribeCalls += 1
            return AsrResult(transcript, detectedLanguage = detectedLanguage, tokens = tokens)
        }
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "")
        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en"))
        override fun release() = Unit
    }
    private class ResultBackend(
        private val result: AsrResult,
        private val canTranslateToEnglish: Boolean = false,
    ) : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = result
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "")
        override val capabilities: AsrCapabilities = AsrCapabilities(
            supportedLanguages = setOf("zh", "en", "de"),
            canTranslateToEnglish = canTranslateToEnglish,
        )
        override fun release() = Unit
    }
    private class FakeAcquisitionPlatform : RouteAcquisitionPlatform {
        var openCalls = 0
        var closeCalls = 0
        override fun bluetoothCandidates(): List<BluetoothCandidate> = emptyList()
        override fun startBluetoothSco() = Unit
        override fun stopBluetoothSco() = Unit
        override fun isBluetoothScoOn(): Boolean = false
        override fun setCommunicationDevice(deviceId: Int): Boolean = false
        override fun communicationDeviceId(): Int? = null
        override fun clearCommunicationDevice() = Unit
        override fun audioMode(): Int = AudioManager.MODE_NORMAL
        override fun setAudioMode(mode: Int) = Unit
        override fun deviceType(deviceId: Int): Int? = AudioDeviceInfo.TYPE_WIRED_HEADSET
        override fun openInputStream(): Int {
            openCalls++
            return 21
        }
        override fun startInputStream(): Boolean = true
        override fun closeInputStream() {
            closeCalls++
        }
    }
    @Test
    fun `the engine drives REAL segment events through the observer - frozen context, no double gate check`() = runTest {
        val retention = ContributionRetentionSink()
        var gateChecks = 0
        val liveAuthority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        observer = SegmentObserver(
            CollectionGate { _ ->
                gateChecks++
                true
            },
            retention,
            liveAuthority,
            issueAuthority = { issuedAuthorityForTests },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(
            observer,
            retention,
            liveAuthority,
            recognizer = recognizer,
            wakeWordResult = WakeWordResult(detected = false, confidence = 0f, completionSample = 4000),
        )
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        engine.start(
            backend = FakeAsrBackend("play something"),
            audioRoute = AudioRoute.Headset(hasMicrophone = true),
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        assertEquals(2, gateChecks)
        val candidate = retention.retainedSnapshot().single()
        assertEquals(1L, candidate.context.captureSession)
        assertEquals("Headset", candidate.context.routeClass)
        assertEquals(4L, candidate.incrementalSamplesAccounted)
        assertTrue(candidate.pcm!!.contentEquals(shortArrayOf(100, 200, 300, 400)))
        assertEquals(SegmentObservation.WakeState.NotDetected, candidate.wakeState)
        engine.stop()
        assertTrue(
            "the capture must end Executed, was " + (candidate.outcome?.let { it::class.simpleName } ?: "null"),
            candidate.outcome is CollectionOutcome.Executed,
        )
        assertEquals(candidate.context.captureId, (candidate.outcome as CollectionOutcome.Executed).captureId)
        assertTrue((candidate.outcome as CollectionOutcome.Executed).dispatchedWithoutThrowing)
    }
    @Test
    fun `stop during an open segment CANCELS it - partial discarded, nothing completes`() = runTest {
        val sink = EngineObservationSink()
        val liveAuthority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        observer = SegmentObserver(
            CollectionGate { true },
            sink,
            liveAuthority,
            { issuedAuthorityForTests },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val pending = MutableSharedFlow<VoiceSegmenterResult>()
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(pending)
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = intentRecognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            collectionAuthority = liveAuthority,
            retentionSink = retentionSink,
            segmentObserver = observer,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unused"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        pending.emit(VoiceSegmenterResult.SpeechStarted)
        advanceUntilIdle()
        pending.emit(VoiceSegmenterResult.SpeechContinuing)
        advanceUntilIdle()
        engine.stop()
        advanceUntilIdle()
        assertEquals(1, sink.cancelled.size)
        assertEquals("pipeline-stopped", sink.cancelled.single().reason)
        assertTrue(sink.completed.isEmpty()) // listening closure discards ONLY the current partial
    }
    @Test
    fun `gate OFF means the pipeline allocates NOTHING for upload across a full turn`() = runTest {
        val sink = EngineObservationSink()
        val deadSink = ContributionRetentionSink()
        val deadAuthority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = false, fullRevocation = true) }
        observer = SegmentObserver(
            CollectionGate { false },
            sink,
            deadAuthority,
            { issuedAuthorityForTests },
            CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement -> scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement) },
        )
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        createEngineWithSpeech(
            observer,
            deadSink,
            deadAuthority,
            recognizer = recognizer,
            wakeWordResult = WakeWordResult(detected = false, confidence = 0f, completionSample = 4000),
        )
        engine.start(
            backend = FakeAsrBackend("play something"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        advanceUntilIdle()
        engine.stop()
        assertEquals(listOf("ensureReady", "recognize:play something"), recognizer.calls)
        assertEquals(0, deadSink.retainedCount())
        assertEquals(0L, deadSink.retainedBytes())
        assertEquals(0, sink.started.size + sink.incremental.size + sink.completed.size)
        assertEquals(0, observer.retainedBytes())
    }
    private class EngineObservationSink : au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentSink {
        private var seq = 0L
        override fun adopted(observation: SegmentObservation.Started, token: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ReservationToken?): Boolean = true
        override fun admit(observation: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation.Admission): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ReservationToken? = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ReservationToken(observation.context.captureId, ++seq, observation.producerSamples.toLong() * 2)
        val started = mutableListOf<SegmentObservation.Started>()
        val incremental = mutableListOf<SegmentObservation.Incremental>()
        val completed = mutableListOf<SegmentObservation.Completed>()
        val cancelled = mutableListOf<SegmentObservation.Cancelled>()
        override fun started(observation: SegmentObservation.Started): Boolean {
            started += observation
            return true
        }
        override fun incremental(observation: SegmentObservation.Incremental, samples: ShortArray) {
            incremental += observation
        }
        override fun completed(observation: SegmentObservation.Completed): Boolean {
            completed += observation
            return true
        }
        override fun cancelled(observation: SegmentObservation.Cancelled) {
            cancelled += observation
        }
        fun allObservedPcmSamples(): Int = completed.sumOf { o -> o.frames.sumOf { it.size } }
    }
}
