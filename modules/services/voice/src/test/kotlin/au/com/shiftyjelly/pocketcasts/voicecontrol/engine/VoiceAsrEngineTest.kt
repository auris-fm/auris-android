@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

class VoiceAsrEngineTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val context = mock<Context>()
    private val audioManager = mock<AudioManager>()
    private val voiceAudioProcessor = mock<VoiceAudioProcessor>()
    private val utteranceFilter = mock<UtteranceFilter>()
    private val intentRecognizer = mock<VoiceRecognizer>()
    private val wakeWordDetector = mock<WakeWordDetector>()
    private val gracePeriodSignal = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal>()
    private val audioFeedbackRenderer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer>()
    private val backend = mock<AsrBackend>()
    private val translationStage = mock<TranslationStage>()

    init {
        // The engine reads the grace window to decide whether the user was addressing us, so the
        // mock has to answer. Default false = the window is shut: a capture from the room.
        `when`(gracePeriodSignal.isActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
    }

    private var capturedReceiver: BroadcastReceiver? = null

    private val captureFlow: Flow<VoiceSegmenterResult> = MutableStateFlow(VoiceSegmenterResult.Silence)

    private lateinit var engine: VoiceAsrEngine

    private fun CoroutineScope.createEngine() {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(captureFlow)

        // Default: wake word not detected (allows segments through during grace)
        kotlinx.coroutines.runBlocking {
            `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                    detected = false,
                    confidence = 0f,
                    completionSample = 4000,
                ),
            )
        }

        `when`(
            context.registerReceiver(
                any<BroadcastReceiver>(),
                any<IntentFilter>(),
            ),
        ).thenAnswer { invocation ->
            capturedReceiver = invocation.getArgument(0)
            Intent()
        }

        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = intentRecognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            context = context,
        )
        engine.scope = this
    }

    private fun startEngine(route: AudioRoute, mode: ListeningMode = ListeningMode.Continuous) {
        engine.start(
            backend = backend,
            audioRoute = route,
            listeningMode = mode,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
    }

    private fun simulateScoState(state: Int) {
        val intent = mock<Intent>()
        `when`(
            intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR,
            ),
        ).thenReturn(state)
        capturedReceiver?.onReceive(context, intent)
    }

    // ── The backend must be prepared on every start, not just the first ──

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
        // stop() releases the backend and drops it, leaving the recogniser null.
        // If a restart does not prepare it again, every later transcription returns
        // empty in ~0ms and voice recognition is silently dead for the whole process.
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
        // Listening pauses are frequent (gate conflicts, route changes). Releasing here would
        // force a full model reload on the next start, and everything said during that reload
        // is lost. The service owns teardown and releases the backend there.
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

    // ── Speaker / WiredHeadset routes: no SCO ──────────────────────────

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

    // ── Bluetooth route: SCO await ─────────────────────────────────────

    @Test
    fun `start with BluetoothA2dpOnly awaits SCO connected before capture`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        // Do not advanceUntilIdle — that would fire the SCO timeout.
        runCurrent()

        verify(audioManager).startBluetoothSco()
        verify(voiceAudioProcessor, never()).startProcessing()
        assertTrue("Expected receiver registered", capturedReceiver != null)

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()

        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
    }

    @Test
    fun `start with BluetoothA2dpOnly starts capture even when SCO disconnects`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        runCurrent()

        verify(audioManager).startBluetoothSco()
        verify(voiceAudioProcessor, never()).startProcessing()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
        advanceUntilIdle()

        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
    }

    @Test
    fun `start with BluetoothA2dpOnly falls back after SCO timeout`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        runCurrent()

        verify(audioManager).startBluetoothSco()
        verify(voiceAudioProcessor, never()).startProcessing()

        advanceTimeBy(3_001)
        advanceUntilIdle()

        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
    }

    // ── Cancellation / Stop ────────────────────────────────────────────

    @Test
    fun `stop during SCO await cancels wait and unregisters receiver`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        runCurrent()

        verify(audioManager).startBluetoothSco()
        assertTrue("Expected receiver registered", capturedReceiver != null)

        engine.stop()
        advanceUntilIdle()

        verify(context).unregisterReceiver(any<BroadcastReceiver>())
        verify(voiceAudioProcessor, never()).startProcessing()
    }

    @Test
    fun `stop after capture started closes SCO and keeps the backend warm`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        runCurrent()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()
        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
        advanceUntilIdle()

        verify(audioManager).stopBluetoothSco()
        // Deliberately not released: a listening pause must not cost a model reload on the
        // next start. VoiceControlService releases the backend in its teardown.
        verify(backend, never()).release()
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)

        // Wake word not detected: full segment flows through during grace
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
            context = context,
        )
        engine.scope = this
        val handledIntents = mutableListOf<VoiceIntent>()

        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handledIntents += it },
        )
        advanceUntilIdle()

        assertEquals(listOf("ensureReady", "recognize:pause"), recognizer.calls)
        assertEquals(listOf(VoiceIntent.Playback.Pause), handledIntents)
        verify(wakeWordDetector).detect(any(), eq(16000), eq(2))

        engine.stop()
    }

    // ── Escalation on a routing failure ───────────────────────────────

    @Test
    fun `a routing failure escalates to the cloud with the transcript verbatim`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_MAPPER_OR_DIALOG_FAILED,
            transcript = "what did the guests say about sleep and memory",
        )

        // Through the same handler a chosen cloud_route uses, so turn ownership
        // and the auto-pause obligation still apply.
        assertEquals(
            listOf(
                VoiceIntent.CloudRoute(
                    request = "what did the guests say about sleep and memory",
                    tier = VoiceIntent.CloudTier.Unknown,
                    // Issued under the open window the helper opened, so handling
                    // it cannot re-arm that window's allowance or reopen a later
                    // one; and marked as the fallback, which must not restore it.
                    windowGeneration = 1L,
                    origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
                ),
            ),
            intents,
        )
        // Escalating is not an error, so no error tone: the answer (or the
        // cloud path's own failure tone) is what the user hears.
        verify(audioFeedbackRenderer, never()).playEarcon(any())

        engine.stop()
    }

    @Test
    fun `a no_match from a wake-detected segment speaks the unclear-command line`() = runTest {
        // The other side of the spec's rule: the user was addressing us and we could not route it,
        // so they hear the line rather than a tone. Without a resolver holding the key the engine
        // falls back to that same earcon, which is what made this path untestable before.
        val resolver = SpokenTemplateResolver(
            // The engine's KEY_CLOUD_UNROUTED is private to its companion, so the key is spelled out.
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
        // The WAKE_WORD cue is expected here — it acknowledges the detection. What must not happen
        // is the error tone, because that would mean the engine had no line to speak.
        verify(audioFeedbackRenderer, never()).playEarcon(EarconId.ERROR)

        engine.stop()
    }

    @Test
    fun `a no_match from the room keeps the earcon and stays local`() = runTest {
        // A wake-negative capture: the microphone caught the room, not a request to us. It must not
        // spend the window (measured: it did, on every wake), it must not be answered with words,
        // and a soft tone is what the spec asks for instead of silence.
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_NO_MATCH,
            transcript = "Hi, allri.",
        )

        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).playEarcon(EarconId.ERROR)
        verify(audioFeedbackRenderer, never()).render(any(), any())

        engine.stop()
    }

    @Test
    fun `an empty transcript never reaches the cloud`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT,
            transcript = "",
        )

        // An empty question must not be posted. (This utterance is dropped
        // before routing at all, which is why the policy test — not this one —
        // owns the blank-transcript rule.)
        assertTrue(intents.isEmpty())

        engine.stop()
    }

    @Test
    fun `a local capability failure stays local and says so`() = runTest {
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_MODEL_NOT_LOADED,
            transcript = "pause",
        )

        // Escalating this would turn a broken install into cloud traffic and
        // make "the model never loaded" look like a healthy turn.
        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).playEarcon(EarconId.ERROR)

        engine.stop()
    }

    @Test
    fun `only one escalation is dispatched per grace window`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        // The bound is per user act, so the window has to be open for either
        // dispatch to be allowed at all.
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unclear question"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intents += it },
        )
        advanceUntilIdle()

        // Two failures in one window: the second is refused, and says so.
        assertEquals(1, intents.size)
        verify(audioFeedbackRenderer).playEarcon(EarconId.ERROR)

        engine.stop()
    }

    @Test
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unclear question"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intents += it },
        )
        advanceUntilIdle()

        // Same class as the router's `model_not_loaded`: the turn happened, so
        // it gets a tone rather than silence — and stays local, because a
        // capability failure is not something to spend a server call on.
        assertTrue(intents.isEmpty())
        assertEquals(0, recognizer.recognizes)
        verify(audioFeedbackRenderer).playEarcon(EarconId.ERROR)

        engine.stop()
    }

    @Test
    fun `a bare wake phrase is rejected silently`() = runTest {
        // The router is consulted and rejects it (`no_match`), which is a
        // deliberate rejection rather than a failure: nothing is dispatched, no
        // window is spent, and nothing beeps. The guard that tried to keep the
        // phrase out of the router measured the buffer rather than the words and
        // was deleted; this is the behaviour that replaced it.
        val recognizer = RecordingRecognizer(null)
        val engine = startTokenless(recognizer, "Hi, allri.")

        assertTrue("the router may see it; the decision is what matters", recognizer.calls.isNotEmpty())
        verify(audioFeedbackRenderer, never()).playEarcon(EarconId.ERROR)

        engine.stop()
    }

    /** One wake-positive utterance, for the rejection cases. */
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend(transcript),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
        advanceUntilIdle()
        return engine
    }

    /**
     * Drives one failed utterance through the engine in an open window and
     * returns the intents the handler saw.
     */
    private suspend fun TestScope.startFailingEngine(
        reason: String,
        transcript: String,
        wakeDetected: Boolean = false,
        templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(emptyMap()),
        // SpokenLine only speaks for an English locale, so a test that expects speech must say so.
        currentLocale: () -> Locale = { Locale.ENGLISH },
    ): Pair<VoiceAsrEngine, MutableList<VoiceIntent>> {
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
        `when`(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult(
                detected = wakeDetected,
                confidence = 0f,
                completionSample = 4000,
            ),
        )

        val intents = mutableListOf<VoiceIntent>()
        // A real signal, so the window budget under test is the real one.
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
            context = context,
            templateResolver = templateResolver,
            currentLocale = currentLocale,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend(transcript),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intents += it },
        )
        advanceUntilIdle()
        return engine to intents
    }

    // ── Translation stage wiring ───────────────────────────────────────

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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "你好", detectedLanguage = "zh")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "倒回去3分钟。", detectedLanguage = "zh")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "pause", detectedLanguage = "en")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
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
        // Production Canary returns English text with configured src lang (de/es/fr), not "en".
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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
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
        // Merlin's Pixel line was confusing because post-translate fields were printed first:
        //   lang=en 'Play.' translate=yue→en '播放。'
        // Desired left-to-right story: ASR first, English result in the translate note.
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
            `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
                context = context,
            )
            engine.scope = this

            engine.start(
                backend = ResultBackend(AsrResult(text = "播放。", detectedLanguage = "yue")),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.Exposed },
                onIntent = {},
            )
            advanceUntilIdle()

            val asrLog = logs.single {
                it.startsWith("[VoicePipeline] asr ") && !it.contains("→ drop")
            }
            assertTrue(
                "Expected source-first log, got: $asrLog",
                asrLog.matches(
                    Regex("""\[VoicePipeline\] asr ResultBackend \d+ms lang=yue '播放。' translate=yue→en 'Play\.'"""),
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
            `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
                context = context,
            )
            engine.scope = this

            engine.start(
                backend = ResultBackend(AsrResult(text = "你好", detectedLanguage = "zh")),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.Exposed },
                onIntent = {},
            )
            advanceUntilIdle()

            assertTrue(
                "${case.label}: expected no intent routing, got ${recognizer.calls}",
                recognizer.calls.isEmpty(),
            )
            verify(audioFeedbackRenderer).playEarcon(EarconId.ERROR)

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
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "pause", detectedLanguage = "en")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
        advanceUntilIdle()

        verify(translationStage, never()).translate(any(), any())
        assertTrue("Expected native 'pause' to reach recognizer", recognizer.calls.contains("recognize:pause"))

        engine.stop()
    }

    // ── SCO not reopened for subsequent starts ─────────────────────────

    @Test
    fun `stop then restart on Bluetooth re-opens SCO`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        runCurrent()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()

        engine.stop()
        advanceUntilIdle()
        verify(audioManager).stopBluetoothSco()

        // Restart — stop cleared scoStarted, so SCO must be re-opened
        capturedReceiver = null
        startEngine(AudioRoute.BluetoothA2dpOnly)
        runCurrent()
        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()

        verify(audioManager, times(2)).startBluetoothSco()
        verify(voiceAudioProcessor, times(2)).startProcessing()

        engine.stop()
    }

    // ── Route switching scenarios ──────────────────────────────────────

    @Test
    fun `restart from Speaker to Bluetooth triggers SCO await`() = runTest {
        createEngine()
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(audioManager, never()).startBluetoothSco()

        engine.stop()
        capturedReceiver = null

        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(audioManager, times(1)).startBluetoothSco()
        assertTrue("Expected receiver registered", capturedReceiver != null)

        engine.stop()
    }

    @Test
    fun `restart from Bluetooth to Speaker closes SCO`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        runCurrent()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()

        engine.stop()
        advanceUntilIdle()
        verify(audioManager).stopBluetoothSco()

        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(voiceAudioProcessor, times(2)).startProcessing()

        verify(audioManager, times(1)).startBluetoothSco()
        verify(audioManager, times(1)).stopBluetoothSco()

        engine.stop()
    }

    @Test
    fun `Bluetooth SCO setup exception falls back to phone mic capture`() = runTest {
        createEngine()
        `when`(
            context.registerReceiver(
                any<BroadcastReceiver>(),
                any<IntentFilter>(),
            ),
        ).thenThrow(RuntimeException("registerReceiver failed"))

        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(voiceAudioProcessor).startProcessing()
        verify(audioManager, never()).startBluetoothSco()

        engine.stop()
    }

    @Test
    fun `Bluetooth SCO startBluetoothSco exception unregisters receiver and restores audio mode`() = runTest {
        createEngine()
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_IN_COMMUNICATION)
        org.mockito.Mockito.doThrow(RuntimeException("startBluetoothSco failed"))
            .`when`(audioManager).startBluetoothSco()

        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(voiceAudioProcessor).startProcessing()
        assertTrue("Expected receiver to have been registered", capturedReceiver != null)
        verify(context).unregisterReceiver(capturedReceiver!!)
        verify(audioManager).setMode(AudioManager.MODE_IN_COMMUNICATION)
        // Fallback path must not leave scoStarted set — stop should not call stopBluetoothSco.
        engine.stop()
        verify(audioManager, never()).stopBluetoothSco()
    }

    @Test
    fun `Bluetooth SCO CancellationException after register rolls back receiver and mode`() = runTest {
        createEngine()
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_IN_COMMUNICATION)
        org.mockito.Mockito.doThrow(kotlinx.coroutines.CancellationException("sco cancelled"))
            .`when`(audioManager).startBluetoothSco()

        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        // Cancellation aborts the processing job before capture; rollback must still run.
        verify(voiceAudioProcessor, never()).startProcessing()
        assertTrue("Expected receiver to have been registered", capturedReceiver != null)
        verify(context).unregisterReceiver(capturedReceiver!!)
        verify(audioManager).setMode(AudioManager.MODE_IN_COMMUNICATION)

        engine.stop()
        verify(audioManager, never()).stopBluetoothSco()
    }

    // ── Wake-word detection paths ──────────────────────────────────────

    private fun CoroutineScope.createEngineWithSpeech(
        recognizer: VoiceRecognizer,
        wakeWordResult: WakeWordResult,
    ) {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                ),
            ),
        )
        `when`(utteranceFilter.shouldProcess(any(), any(), any(), any())).thenReturn(true)
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
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handledIntents += it },
        )
        advanceUntilIdle()

        verify(gracePeriodSignal).onWakeWordDetected()
        verify(audioFeedbackRenderer).playEarcon(EarconId.WAKE_WORD)
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
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
        advanceUntilIdle()

        verify(gracePeriodSignal).onWakeWordDetected()
        verify(audioFeedbackRenderer).playEarcon(EarconId.WAKE_WORD)
        // Nothing was asked: a wake-only capture starts the session, it is not a
        // question that failed, so it plays nothing beyond the wake earcon.
        verify(audioFeedbackRenderer, never()).playEarcon(EarconId.ERROR)
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
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
        advanceUntilIdle()

        verify(gracePeriodSignal, never()).onWakeWordDetected()
        verify(audioFeedbackRenderer, never()).playEarcon(any())
        assertTrue("Expected no ASR calls, got ${recognizer.calls}", recognizer.calls.isEmpty())

        engine.stop()
    }

    /** A router that cannot load its model at all. */
    private class NotReadyRecognizer : VoiceRecognizer {
        var recognizes = 0

        override suspend fun ensureReady(): Result<Unit> = Result.failure(IllegalStateException("model missing"))

        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
        ): VoiceRecognizeResult {
            recognizes += 1
            return VoiceRecognizeResult(intent = null)
        }

        override fun release() = Unit
    }

    /** Reports a routing failure the way the router does: no intent, plus why. */
    private class FailingRecognizer(private val reason: String) : VoiceRecognizer {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)

        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
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
            ),
        )

        override fun release() = Unit
    }

    private class RecordingRecognizer(
        private val intent: VoiceIntent?,
    ) : VoiceRecognizer {
        val calls = mutableListOf<String>()
        val inputs = mutableListOf<IntentRoutingInput>()

        override suspend fun ensureReady(): Result<Unit> {
            calls += "ensureReady"
            return Result.success(Unit)
        }

        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
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
    ) : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)

        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult(transcript, tokens = tokens)

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
}
