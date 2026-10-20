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
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
        // By default the native capture pipeline attaches. Tests that exercise a failed or
        // cancelled start override this with false.
        `when`(voiceAudioProcessor.attachCapture()).thenReturn(true)
    }

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
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
    }

    @Test
    fun `a processing flow that ends reports termination`() = runTest {
        createEngine()
        // The pipeline exiting on its own (a backend failure, a stream that stops) must reach the
        // service so it can clear capture state instead of reporting a listening engine forever.
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(flowOf(VoiceSegmenterResult.Silence))
        var terminated = false

        engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
            onTerminated = { terminated = true },
        )
        advanceUntilIdle()

        assertTrue("the service must learn when the pipeline ends", terminated)
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
            onIntent = {},
            onTerminated = { terminated = true },
        )
        advanceUntilIdle()

        assertTrue("a start that ends before attaching must report termination", terminated)
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

    // ── Bluetooth route: acquisition owns SCO; the engine captures the opened stream ──

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
        // 35f7e2d8 / b124a183 ASR-PROGRESS control at the REAL renderer+player boundary.
        //
        // WHY THE RENDERER IS NOT MOCKED HERE: a mocked AudioFeedbackRenderer returns from
        // submitEarcon immediately no matter what the engine does, so "the callback was recorded but
        // never invoked" proves nothing about admission -- the same case passes unchanged if the
        // engine blocks on the cue. That made the previous version of this control vacuous. This one
        // wires the REAL AudioFeedbackRenderer over the REAL EarconPlayer and holds an ACTUAL
        // accepted AudioTrack.write() on a latch, so "ASR progressed while the cue was still being
        // written" is an observation of the shipped mechanism rather than of a stub's default.
        //
        // MUTANT TARGET (what must make this fail): replacing the wake branch's
        // `audioFeedbackRenderer.submitEarcon(EarconId.WAKE_WORD)` with
        // `playEarcon(EarconId.WAKE_WORD)`. playEarcon -> EarconPlayer.play -> onPlayerThread does
        // future.get(), so the collect coroutine parks until the held write completes and BOTH
        // positive assertions below come back empty. Verified by running that mutant.
        //
        // SHIPPED SHAPE (not a token fixture): both selected backends return tokens=null
        // (SenseVoiceBackend:92, CanaryFlashBackend:203), so WakeTranscriptTrimmer.commandText
        // short-circuits to the raw transcript at :23 and the band trim never fires. The transcript
        // therefore survives a wake-positive capture as-is and reaches routing without any
        // timestamp-dependent branch. A token-bearing fixture would have been testing a path
        // production does not take.
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
            // The worker enters the write and STAYS there until the test releases it. Nothing in
            // this control counts the release; the assertions below run inside the hold.
            enteredWrite.countDown()
            assertTrue("test bug: the held write was never released", holdWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
            inv.getArgument<Int>(2) // accept everything, once released
        }

        // The REAL application context, not the class's mocked one: EarconPlayer resolves clips and
        // the AudioTrack factory off it, and a bare mock answers null to applicationContext.
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
        // 100 ms of audio. speechEndSample stays 0 (the production default for segmenters that do not
        // report it), which the engine reads as UNKNOWN -- so the wake-only drop at :334 cannot fire:
        // it requires speechEndSample > 0. Stated explicitly because leaving it implicit let a later
        // default change silently alter what this test proves.
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
        // `detect` is SUSPEND: Mockito can only intercept the stub when the call is registered inside
        // a coroutine (same pattern as createEngineWithSpeech). Stubbed bare it answers null, the
        // engine's runCatching swallows the NPE as "wake detection failed", and the segment is dropped
        // BEFORE ASR -- which would make "recognizer never called" pass for entirely the wrong reason.
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
            context = context,
        )
        val handledIntents = mutableListOf<VoiceIntent>()
        // Unconfined test dispatcher (MainCoroutineRule): the collect body runs inline as frames
        // arrive, so by the time start() has returned the engine is parked INSIDE the held write.
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handledIntents += it },
        )

        // The cue reaches AudioTrack.write on the PLAYER'S OWN thread via submit(), so it must be
        // scheduled before the precondition can hold.
        advanceUntilIdle()

        try {
            // PRECONDITION, asserted before any claim: the cue's own write is genuinely in progress.
            assertTrue(
                "precondition: the real player must be INSIDE the accepted cue write",
                enteredWrite.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            // POSITIVE assertions, all made WHILE the write is still held (holdWrite has not counted down).
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
            // BOUNDARY CLEANUP UNCONDITIONALLY (ea63aebe). The mocked write() blocks on `holdWrite`, so an
            // assertion failure BEFORE the release would leave the player worker parked inside that write
            // until its own 10s timeout -- and `engine.stop()`/`realRenderer.release()` would never run at
            // all. Every step here is individually guarded: a failure in one must not skip the others, or
            // the cleanup itself becomes the thing that leaks the blocked worker.
            holdWrite.countDown()
            runCatching { realRenderer.release() }
            runCatching { engine.stop() }
        }
    }

    // ── Task #78: non-content ASR output is silently discarded before any routing ──

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
            context = context,
        )
        engine.scope = this
        return capture
    }

    private fun discardAsserts(recognizer: RecordingRecognizer, handled: List<VoiceIntent>) {
        // Silent discard: the transcript reached ASR, nothing asked the recognizer for a verdict,
        // nothing was routed, and no cue (error included) was played.
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
            onIntent = { handled += it },
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
            onIntent = { handled += it },
        )
        advanceUntilIdle()
        discardAsserts(recognizer, handled)
    }

    @Test
    fun `a no-wake transcript still routes exactly as before`() = runTest {
        // PRODUCTION-ENTRY comparison for the discard guard: with the SAME wake state (no wake
        // detected) and the same Continuous mode, a content transcript routes. Without this, the
        // punctuation cases above would pass equally for a guard that swallowed every no-wake turn.
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = false)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("pause"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handled += it },
        )
        advanceUntilIdle()
        assertEquals(listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }

    @Test
    fun `a wake-trimmed non-content remainder is discarded, not treated as a command`() = runTest {
        // The rule is applied to the WAKE-TRIMMED text, not the raw one: the tokens under the wake
        // band are dropped by WakeTranscriptTrimmer, leaving only "..." — which is not a request.
        // (In-process backends emit tokens; without them the trimmer keeps the raw text, so this
        // control supplies the timestamps that make the trim happen.)
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
            onIntent = { handled += it },
        )
        advanceUntilIdle()
        assertEquals("nothing routed", emptyList<VoiceIntent>(), handled)
        engine.stop()
    }

    @Test
    fun `numeric content such as a square or fraction mark is not discarded`() = runTest {
        // Unicode nuance from the shared rule: ² (No) and ½ (No) are numbers, so they are content.
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = true)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("\u00B2\u00BD"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handled += it },
        )
        advanceUntilIdle()
        assertEquals("numeric marks are content, so the turn still routes", listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }

    @Test
    fun `a supplementary letter such as CJK Extension B is not discarded`() = runTest {
        // U+20000 (𠀀) is a CJK Unified Ideograph Extension B letter, encoded as a surrogate pair
        // in UTF-16. A Char-unit iteration would miss it; code-point iteration must not.
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = true)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("\uD840\uDC00"), // U+20000
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handled += it },
        )
        advanceUntilIdle()
        assertEquals("supplementary letters are content", listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }

    @Test
    fun `a supplementary digit such as mathematical bold digit nine is not discarded`() = runTest {
        // U+1D7DF (𝟟) is MATHEMATICAL BOLD DIGIT NINE, category Nd, outside the BMP.
        val recognizer = RecordingRecognizer(VoiceIntent.Playback.Pause)
        this.nondiscardFixture(recognizer, wakeDetected = true)
        val handled = mutableListOf<VoiceIntent>()
        engine.start(
            backend = FakeAsrBackend("\uD835\uDDDF"), // U+1D7DF
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handled += it },
        )
        advanceUntilIdle()
        assertEquals("supplementary digits are content", listOf(VoiceIntent.Playback.Pause), handled)
        engine.stop()
    }

    @Test
    fun `a punctuation-only discard followed by content on the same engine routes the second`() = runTest {
        // SAME-ENGINE CLEANUP CONTROL: a punctuation-only utterance is silently discarded,
        // then a content-bearing utterance arrives on the SAME running engine. The discard
        // must not leave state that prevents the next capture from routing normally.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(
            flowOf(
                // First: punctuation only — should be discarded
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                    speechOnsetSample = 2,
                ),
                // Second: real content — should route
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
            context = context,
        )
        engine.scope = this
        engine.start(
            // First segment returns "…" in Cantonese (punctuation only, non-English so that
            // bypassing the guard WOULD trigger translation). Second returns "pause" in English.
            backend = object : AsrBackend {
                private var callCount = 0
                override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
                override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult {
                    callCount++
                    return if (callCount == 1) {
                        AsrResult("\u2026", detectedLanguage = "yue") // "…" in Cantonese
                    } else {
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
            onIntent = { intents += it },
        )
        advanceUntilIdle()

        assertEquals(
            "only the content utterance routed; the punctuation-only one was silently discarded",
            1,
            intents.size,
        )
        assertEquals(VoiceIntent.Playback.Pause, intents.single())
        // DISCARD-SPECIFIC OBSERVATIONS:
        // 1. Translation was NOT invoked for the discarded yue transcript: the guard fires
        //    BEFORE prepareRoutingInput, so ensureReady/translate are never called for it.
        //    If the guard were bypassed, the Cantonese "…" would trigger translation.
        verify(translationStage, never()).ensureReady("yue")
        verify(translationStage, never()).translate(any(), eq("yue"))
        // 2. Grace was NOT extended by the discard: the non-content path returns before
        //    any feedback or grace interaction. capturedGeneration() alone cannot distinguish
        //    a timer reset within the same generation (onCommandRecognized extends without
        //    advancing). Use the timer job identity: if the discard re-armed the timer, the
        //    job reference would change. Capture the job after both utterances and compare
        //    to the post-wake baseline.
        val postWakeTimerJob = signal.timerJobForTest()
        assertNotNull("wake armed a timer", postWakeTimerJob)
        // After processing both utterances, the timer job must be the SAME instance.
        // A discard that called onCommandRecognized would have cancelled and re-created it.
        assertSame(
            "discard did not re-arm the grace timer (same job identity)",
            postWakeTimerJob,
            signal.timerJobForTest(),
        )
        // 3. No error earcon for the discard (silent), and no error for the successful route.
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)
        // 4. MUTATION RECEIPT: if the punctuation transcript were admitted to routing (guard
        //    bypassed), the RecordingRecognizer would return Pause for it too, producing TWO
        //    intents. The single-intent result proves the guard, not recognizer filtering,
        //    prevented the first utterance from reaching the router.
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
            onIntent = { handled += it },
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

        // Wake word not detected, so the whole segment is handed to transcription. NOTE (4236593975):
        // this fixture does NOT observe the wake/grace path -- the mocked grace window is shut (see
        // init), and no wake signal is emitted here. The claim it previously justified ("flows through
        // during grace") was dropped because the test cannot see that path; what it actually establishes
        // is recognizer-before-match ordering with a non-detected wake result.
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
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { handledIntents += it },
        )
        advanceUntilIdle()

        assertEquals(listOf("ensureReady", "recognize:pause"), recognizer.calls)
        assertEquals(listOf(VoiceIntent.Playback.Pause), handledIntents)
        verify(wakeWordDetector).detect(any(), eq(16000), eq(2))

        engine.stop()
    }

    @Test
    fun `playback bleed is dropped before transcription and produces no accepted activity`() = runTest {
        // The acceptance property for echo, at the level where it is decided: a segment the bleed
        // filter rejects must not reach ASR, the recognizer, or feedback, so no transcript, no intent,
        // no request and no spend can follow. The filter's own return value is covered separately; this
        // is the consequence, which is what "no accepted activity" means.
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
        // The filter refuses this segment: it is the playback the app itself submitted, returning to
        // the mic. No speaker diarization runs (hasSpeakerId=false), so the bleed check is what decides.
        `when`(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(false)
        // Counted so the row's "no reset" half is observed rather than assumed.
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
            onIntent = { handledIntents += it },
        )
        advanceUntilIdle()

        assertEquals("a bleed-rejected segment must not reach the recognizer", emptyList<String>(), recognizer.calls)
        assertEquals("and no intent may be accepted", emptyList<VoiceIntent>(), handledIntents)
        assertEquals(
            "nor may the segment have been transcribed",
            0,
            backend.transcribeCalls,
        )
        // A dropped segment must not be treated as a listening session beginning, which would reset the
        // session's speaker target. start() resets once, before processing, so exactly one reset is the
        // drop adding none of its own.
        assertEquals("a rejected segment must not reset the session filter", 1, resets)

        // The two effects the acceptance row names, read directly rather than through behaviour that a
        // mock would have to reproduce. The drop path ends in a bare `return` between the wake handling
        // and the ASR, so nothing below it may touch grace: no window opened or reset, and no dispatch
        // allowance spent. These are `never` because a single call is the defect.
        verify(gracePeriodSignal, never()).onWakeWordDetected()
        verify(gracePeriodSignal, never()).onCommandRecognized(any())

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
                    // MINTED AT INPUT REGISTRATION: an ordinary cloud dispatch carries its own logical
                    // identity, before the recognition suspends. Asserted separately below rather than
                    // as a literal, since it is generated per dispatch.
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
        // Escalating is not an error, so no error tone: the answer (or the
        // cloud path's own failure tone) is what the user hears.
        verify(audioFeedbackRenderer, never()).submitEarcon(any())

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
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)

        engine.stop()
    }

    @Test
    fun `a capture inside the completion band is dropped as the wake word alone`() = runTest {
        // The timing path: the transcript is not empty, so nothing else drops this capture — the
        // detector's completion band covering the speech is what says the user only said the wake
        // word. 120ms at 16kHz is 1920 samples, so 5000 is inside 4000 + 1920.
        // The resolver matters: with an empty one, a capture that escaped the drop would fall back
        // to the earcon and never call render — so the test would pass even with the predicate gone.
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
        verify(audioFeedbackRenderer, never()).render(any(), any())
        // No ERROR tone either: that is what the missing-line fallback would play if this capture
        // had reached the router. The WAKE_WORD cue is expected and not asserted against.
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)

        engine.stop()
    }

    @Test
    fun `a capture outside the completion band routes, because speech followed the wake word`() = runTest {
        // Same shape with the speech ending well past the band: someone said something.
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
        // speechEndSample 0 means the segmenter does not report it (the energy path). Treating that
        // as "nothing was said" would silence every turn it produces, so the guard must keep it
        // out of the wake-only branch even with an empty-looking band.
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
        // A wake-negative capture: the microphone caught the room, not a request to us. It must not
        // spend the window (measured: it did, on every wake), it must not be answered with words,
        // and a soft tone is what the spec asks for instead of silence.
        // The resolver matters here too: with an empty one, an inverted `addressed` flag would take
        // the missing-line earcon fallback and still pass. A real line makes inversion observable.
        val (engine, intents) = startFailingEngine(
            reason = RouterStageDiagnostic.REASON_NO_MATCH,
            transcript = "Hi, allri.",
            templateResolver = SpokenTemplateResolver(
                mapOf("pipeline.unclear_command" to "I didn't catch that."),
            ),
        )

        assertTrue(intents.isEmpty())
        verify(audioFeedbackRenderer).submitEarcon(EarconId.ERROR)
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
        verify(audioFeedbackRenderer).submitEarcon(EarconId.ERROR)

        engine.stop()
    }

    @Test
    fun `independent utterances in one grace window each dispatch once`() = runTest {
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
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unclear question"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intents += it },
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
        // @reviewer's suspension case: recognition begins in generation 1, a successor wake opens
        // generation 2 WHILE it is suspended, and it then returns no intent. The escalation must be
        // refused -- it belongs to a window that is no longer live, and dispatching under the successor
        // would let old recognition spend the successor's allowance.
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
            // The successor wake lands WHILE recognition is suspended.
            intentRecognizer = SuccessorWakeRecognizer(
                reason = RouterStageDiagnostic.REASON_CLASSIFY_FAILED,
            ) { signal.onWakeWordDetected() },
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
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intents += it },
        )
        advanceUntilIdle()

        assertTrue(
            "an escalation from a superseded recognition must not dispatch " +
                "(intents=${intents.map { (it as? VoiceIntent.CloudRoute)?.windowGeneration }})",
            intents.none { it is VoiceIntent.CloudRoute },
        )
        // THE ALLOWANCE ITSELF IS PINNED AT THE SIGNAL, not here: driving it through the engine
        // entangles it with the engine's own lifecycle, and in this fixture the window is already
        // inactive by the time the assertion would run -- so liveness fails for a reason
        // that has nothing to do with the claim. `GracePeriodSignalTest` asserts the property directly,
        // where the window state is controlled: a claim for a superseded window is refused AND the
        // successor's allowance survives it.
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
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("unclear question"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intents += it },
        )
        advanceUntilIdle()

        // Same class as the router's `model_not_loaded`: the turn happened, so
        // it gets a tone rather than silence — and stays local, because a
        // capability failure is not something to spend a server call on.
        assertTrue(intents.isEmpty())
        assertEquals(0, recognizer.recognizes)
        verify(audioFeedbackRenderer).submitEarcon(EarconId.ERROR)

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
        verify(audioFeedbackRenderer, never()).submitEarcon(EarconId.ERROR)

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
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend(transcript),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
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
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "你好", detectedLanguage = "zh")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "倒回去3分钟。", detectedLanguage = "zh")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "pause", detectedLanguage = "en")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
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
                context = context,
            )
            engine.scope = this

            engine.start(
                backend = ResultBackend(AsrResult(text = "播放。", detectedLanguage = "yue")),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
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
                context = context,
            )
            engine.scope = this

            engine.start(
                backend = ResultBackend(AsrResult(text = "你好", detectedLanguage = "zh")),
                audioRoute = AudioRoute.Speaker,
                listeningMode = ListeningMode.Continuous,
                playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
                micExposureProvider = { MicExposure.Exposed },
                onIntent = {},
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
            context = context,
        )
        engine.scope = this

        engine.start(
            backend = ResultBackend(AsrResult(text = "pause", detectedLanguage = "en")),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
        advanceUntilIdle()

        verify(translationStage, never()).translate(any(), any())
        assertTrue("Expected native 'pause' to reach recognizer", recognizer.calls.contains("recognize:pause"))

        engine.stop()
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
            onIntent = { handledIntents += it },
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
            onIntent = {},
        )
        advanceUntilIdle()

        verify(gracePeriodSignal).onWakeWordDetected()
        verify(audioFeedbackRenderer).submitEarcon(EarconId.WAKE_WORD)
        // Nothing was asked: a wake-only capture starts the session, it is not a
        // question that failed, so it plays nothing beyond the wake earcon.
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
            onIntent = {},
        )
        advanceUntilIdle()

        verify(gracePeriodSignal, never()).onWakeWordDetected()
        verify(audioFeedbackRenderer, never()).submitEarcon(any())
        assertTrue("Expected no ASR calls, got ${recognizer.calls}", recognizer.calls.isEmpty())

        engine.stop()
    }

    // ── Capture attachment gates the stream hand-off ─────────────────
    //
    // A confirmed stream is only handed to the engine once the native VAD pipeline has
    // attached. A start that ends before attaching (backend failure, cancellation) must
    // leave the stream owned by the caller so it can be closed — never a live microphone
    // with no consumer.

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
            onIntent = {},
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
            onIntent = {},
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

        // The service sequence: confirm the external stream, start the engine, wait for attach.
        assertTrue(acquisition.confirmCaptureStream())
        val attachment = engine.start(
            backend = backend,
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
        assertFalse("the pipeline never attached capture", attachment.await())
        verify(voiceAudioProcessor, never()).startProcessing()

        // The engine never took the stream, so the service releases it and it must be closed.
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
            onIntent = {},
        )
        assertTrue("the pipeline attached capture", attachment.await())

        acquisition.handOffStream()
        acquisition.release()
        // Handed off: the engine owns the live stream, so release must not close it.
        assertEquals(0, platform.closeCalls)

        engine.stop()
    }

    @Test
    fun `a chosen-route dispatch after a successor wake carries the original window and admitted ID`() = runTest {
        // STALE-ID NEGATIVE CONTROL: recognition begins in generation 1 and mints admitted ID-A.
        // A successor wake opens generation 2 WHILE recognition is suspended. The recognition then
        // returns a successful cloud intent. The dispatch must carry generation 1's window stamp and
        // ID-A — NOT generation 2's window or a new ID. This proves the admitted identity is bound
        // to the original recognition, not the successor window.
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
            // Triggers a successor wake during recognition, then returns a successful cloud intent.
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = audioFeedbackRenderer,
            translationStage = translationStage,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = FakeAsrBackend("play something"),
            audioRoute = AudioRoute.Speaker,
            listeningMode = ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { intents += it },
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
        // The successor wake advanced the generation to 2, but the dispatch stays at generation 1.
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

    /** A router that cannot load its model at all. */
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

    /** Reports a routing failure the way the router does: no intent, plus why. */
    // A recognizer that opens a SUCCESSOR wake window while recognition is suspended, then reports no
    // intent. Recognition takes real time, so this is the ordinary shape of a wake arriving during it --
    // and the escalation that follows must not act under the new window.
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

    // A recognizer that opens a SUCCESSOR wake window while recognition is suspended, then returns
    // a successful cloud intent. Used to verify the dispatch carries the ORIGINAL generation's
    // window stamp and admitted ID — not the successor's.
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
            // Open the successor window while this recognition is in flight.
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

    private class RecordingRecognizer(
        private val intent: VoiceIntent?,
    ) : VoiceRecognizer {
        val calls = mutableListOf<String>()
        val inputs = mutableListOf<IntentRoutingInput>()

        /** True once ensureReady has been asked, so a case can assert the recognizer was CONSULTED. */
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
    ) : AsrBackend {
        /** Counted so a test can assert a segment never reached transcription. */
        var transcribeCalls = 0
            private set

        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)

        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult {
            transcribeCalls += 1
            return AsrResult(transcript, tokens = tokens)
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
}
