package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.repositories.cloud.EarlyConnectionOpener
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.InProgressUtteranceReader
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.EarlyWakeScore
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The streaming wake score must be the finished segment's score, arrived early.
 *
 * The detector scores a fixed onset-relative window, so a prefix cut from the in-progress
 * utterance returns exactly what the finished segment will return: not a prediction, the same
 * measurement taken sooner. These controls drive the engine's real capture loop with a reader
 * over an in-progress utterance and a detector that records the windows it is handed, so a
 * different window, a re-scored window, or a verdict that arrives late is observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EarlyWakeStreamScoreControlTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val sampleRateHz = EarlyWakeScore.SCORED_SAMPLE_RATE_HZ
    private val onsetSamples = 320
    private val totalSamples = onsetSamples + EarlyWakeScore.CUTOFF_SAMPLES

    /** Deterministic audio: each sample encodes its index, so any window mismatch is visible. */
    private fun inProgressBuffer() = ShortArray(totalSamples) { (it % 32768).toShort() }

    /** A reader over one in-progress utterance that already holds the full cutoff window. */
    private fun reader(generation: Long = 0L): InProgressUtteranceReader {
        val captured = inProgressBuffer()
        return object : InProgressUtteranceReader {
            override fun sampleRateHz() = sampleRateHz
            override fun readSnapshot(buffer: ShortArray, header: LongArray): Int {
                header[0] = generation
                header[1] = onsetSamples.toLong()
                header[2] = captured.size.toLong()
                val count = minOf(buffer.size, captured.size)
                System.arraycopy(captured, 0, buffer, 0, count)
                return count
            }
        }
    }

    /**
     * A detector that records every window it is scored on. Hits unless [quiet] is set, so the
     * negative path can be driven without a second detector implementation.
     */
    private class RecordingDetector(var quiet: Boolean = false) : WakeWordDetector {
        val windows = mutableListOf<FloatArray>()
        val onsets = mutableListOf<Int>()
        override val isReady = true
        override suspend fun detect(segment: FloatArray, sampleRateHz: Int, speechOnsetSample: Int): WakeWordResult {
            windows += segment.copyOf()
            onsets += speechOnsetSample
            return WakeWordResult(detected = !quiet, confidence = 0.9f, completionSample = segment.size)
        }
        override fun release() = Unit
    }

    /**
     * A REAL opener whose open lambda counts dials. `openForLater` is legitimately invoked more
     * than once per utterance (early hit, onset, endpoint all ask); the opener is the layer that
     * turns repeats into one dial, so the count must be taken below it.
     */
    private class RecordingOpener {
        /** The ENGINE's event order, shared so open marks sit beside segmenter events. */
        var order: MutableList<String>? = null
        private val dials = java.util.concurrent.atomic.AtomicInteger()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "early-conn-recorder")
        }
        fun opener() = EarlyConnectionOpener(
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            open = { tag, _ ->
                dials.incrementAndGet()
                order?.add("OPEN")
                au.com.shiftyjelly.pocketcasts.repositories.cloud.SocketCloudTurnConnection(
                    ownerTag = tag,
                    openedAtMs = 0L,
                    onClose = {},
                )
            },
            now = { 0L },
        )
        fun dialCount() = dials.get()
        fun shutdown() = executor.shutdown()
    }

    private fun context(): Context {
        val context = mock<Context>()
        val manager = mock<AudioManager>()
        whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager)
        whenever(manager.mode).thenReturn(AudioManager.MODE_NORMAL)
        return context
    }

    private fun backend() = object : AsrBackend {
        override suspend fun ensureReady() = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int) = AsrResult("play jazz", detectedLanguage = "en")
        override val requiredModel = ModelSpec(emptyList(), "")
        override val capabilities = AsrCapabilities(setOf("en"), canTranslateToEnglish = false)
        override fun release() = Unit
    }

    private fun recognizer(): VoiceRecognizer = object : VoiceRecognizer {
        override suspend fun ensureReady() = Result.success(Unit)
        override suspend fun recognize(
            input: IntentRoutingInput,
            context: VoiceRecognitionContext,
            logicalRequestId: String,
        ): VoiceRecognizeResult = VoiceRecognizeResult(null)
        override fun release() = Unit
    }

    private fun events() = listOf(
        VoiceSegmenterResult.SpeechStarted,
        VoiceSegmenterResult.SpeechContinuing,
        VoiceSegmenterResult.SpeechContinuing,
        VoiceSegmenterResult.SpeechContinuing,
        VoiceSegmenterResult.TrailingSilenceBegan,
        VoiceSegmenterResult.SpeechEnded(
            listOf(PcmAudioFrame(inProgressBuffer(), sampleRateHz)),
            speechOnsetSample = onsetSamples,
        ),
    )

    @Test
    fun `the early window is exactly the window the finished segment is scored on`() {
        val detector = RecordingDetector()
        val order = mutableListOf<String>()
        kotlinx.coroutines.test.runTest {
            val engine = buildEngine(detector, reader(), mock(), mock(), null, order)
            startAndDrain(engine, this)
        }

        // One early score plus one finished-segment score: a cached negative is not re-scored per
        // frame, and the endpoint path keeps running the detector so wake audio is still stripped.
        assertEquals(2, detector.windows.size)
        assertEquals(0, detector.onsets[0])
        assertEquals(onsetSamples, detector.onsets[1])
        // The early prefix and the finished segment's onset-relative window are the same bytes:
        // the early score is the finished score, not an approximation of it.
        val early = detector.windows[0]
        val finishedWindow = detector.windows[1]
            .toList()
            .drop(onsetSamples)
            .take(EarlyWakeScore.CUTOFF_SAMPLES)
        assertEquals(finishedWindow, early.toList())
    }

    private fun buildEngine(
        detector: WakeWordDetector,
        reader: InProgressUtteranceReader?,
        renderer: AudioFeedbackRenderer,
        grace: GracePeriodSignal,
        opener: EarlyConnectionOpener?,
        order: MutableList<String>,
    ): VoiceAsrEngine = VoiceAsrEngine(
        voiceAudioProcessor = mock<VoiceAudioProcessor>().also {
            whenever(it.attachCapture()).thenReturn(true)
            whenever(it.startProcessing()).thenReturn(
                kotlinx.coroutines.flow.flow {
                    events().forEach {
                        order.add(it::class.simpleName.orEmpty())
                        emit(it)
                    }
                },
            )
        },
        utteranceFilter = mock<UtteranceFilter>().also {
            whenever(it.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        },
        intentRecognizer = recognizer(),
        wakeWordDetector = detector,
        gracePeriodSignal = grace,
        audioFeedbackRenderer = renderer,
        translationStage = mock(),
        currentLocale = { java.util.Locale.US },
        context = context(),
        earlyConnectionOpener = opener,
        inProgressUtterance = reader,
    )

    private suspend fun startAndDrain(engine: VoiceAsrEngine, scope: kotlinx.coroutines.test.TestScope) {
        engine.scope = scope
        engine.start(
            backend(),
            AudioRoute.Speaker,
            ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), sampleRateHz, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        scope.advanceUntilIdle()
    }

    @Test
    fun `a negative early result is cached, not re-scored on every frame`() {
        val detector = RecordingDetector(quiet = true)
        val order = mutableListOf<String>()
        kotlinx.coroutines.test.runTest {
            val engine = buildEngine(detector, reader(), mock(), mock(), null, order)
            startAndDrain(engine, this)
        }

        // Three SpeechContinuing frames arrive; only the first may reach the detector. The second
        // call is the finished segment, which must still run: it strips wake audio.
        assertEquals(2, detector.windows.size)
    }

    @Test
    fun `an early hit fires the earcon and grace once, at the early hit`() {
        val renderer = mock<AudioFeedbackRenderer>()
        val grace = mock<GracePeriodSignal>()
        val order = mutableListOf<String>()
        kotlinx.coroutines.test.runTest {
            val engine = buildEngine(RecordingDetector(), reader(), renderer, grace, null, order)
            startAndDrain(engine, this)
        }

        verify(renderer).submitEarcon(EarconId.WAKE_WORD)
        verify(grace).onWakeWordDetected()
    }

    @Test
    fun `outside grace the early verdict opens the connection before the trailing-silence onset`() {
        val recorder = RecordingOpener()
        val order = mutableListOf<String>()
        recorder.order = order
        kotlinx.coroutines.test.runTest {
            val engine = buildEngine(RecordingDetector(), reader(), mock(), mock(), recorder.opener(), order)
            startAndDrain(engine, this)
        }

        // The verdict exists while the user is still speaking, so the open happens at the early
        // score -- strictly before the first silent frame. This is the latency the task exists
        // for: the handshake overlaps everything the user still has left to say.
        val firstOpen = order.indexOf("OPEN")
        val onset = order.indexOf("TrailingSilenceBegan")
        val segmentEnded = order.indexOf("SpeechEnded")
        assertTrue("expected an open, order=$order", firstOpen >= 0)
        assertTrue("the open must precede the onset, order=$order", onset >= 0 && firstOpen < onset)
        assertTrue(
            "the open must precede the segment closing, order=$order",
            segmentEnded < 0 || firstOpen < segmentEnded,
        )
        assertEquals("one utterance, one dial", 1, recorder.dialCount())
    }

    @Test
    fun `without a reader the endpoint path is unchanged`() {
        val renderer = mock<AudioFeedbackRenderer>()
        val grace = mock<GracePeriodSignal>()
        val detector = RecordingDetector()
        val order = mutableListOf<String>()
        kotlinx.coroutines.test.runTest {
            val engine = buildEngine(detector, null, renderer, grace, null, order)
            startAndDrain(engine, this)
        }

        // No in-progress reader, no early score: the detector first sees the finished segment, and
        // the acknowledgement happens at the endpoint, exactly as before this change.
        assertEquals(1, detector.windows.size)
        verify(renderer).submitEarcon(EarconId.WAKE_WORD)
        verify(grace).onWakeWordDetected()
        assertTrue(order.contains("SpeechEnded"))
    }
}
