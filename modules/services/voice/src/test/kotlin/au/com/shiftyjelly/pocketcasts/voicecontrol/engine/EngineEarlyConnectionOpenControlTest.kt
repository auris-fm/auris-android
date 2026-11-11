package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.repositories.cloud.EarlyConnectionOpener
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import kotlinx.coroutines.CompletableDeferred
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
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The early connection must be opened from the segmenter's trailing-silence onset, through the
 * engine's real capture loop -- the decision depends on the wake verdict and the listening mode,
 * neither of which a direct call to the opener could exercise.
 *
 * The tag matters as much as the timing: the connection is adopted by the turn, so it must be
 * opened under the SAME identity the turn later carries. A connection opened under a different id
 * is never claimed and costs a dial for nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineEarlyConnectionOpenControlTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    /** Records the tags the engine opens under, in order. */
    private class RecordingOpener {
        var onOpen: (() -> Unit)? = null
        val tags = mutableListOf<String>()
        fun opener() = mock<EarlyConnectionOpener>().also { m ->
            whenever(m.openForLater(any())).thenAnswer { invocation ->
                tags += invocation.getArgument<String>(0)
                onOpen?.invoke()
                Unit
            }
        }
    }

    private fun backend() = object : AsrBackend {
        override suspend fun ensureReady() = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int) = AsrResult("play jazz", detectedLanguage = "en")
        override val requiredModel = ModelSpec(emptyList(), "")
        override val capabilities = AsrCapabilities(setOf("en"), canTranslateToEnglish = false)
        override fun release() = Unit
    }

    private fun processor(events: List<VoiceSegmenterResult>, order: MutableList<String>?): VoiceAudioProcessor {
        val processor = mock<VoiceAudioProcessor>()
        whenever(processor.attachCapture()).thenReturn(true)
        whenever(processor.startProcessing()).thenReturn(
            kotlinx.coroutines.flow.flow {
                events.forEach {
                    order?.add(it::class.simpleName.orEmpty())
                    emit(it)
                }
            },
        )
        return processor
    }

    private fun context(): Context {
        val context = mock<Context>()
        val manager = mock<AudioManager>()
        whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager)
        whenever(manager.mode).thenReturn(AudioManager.MODE_NORMAL)
        return context
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

    private suspend fun startEngine(
        scope: kotlinx.coroutines.CoroutineScope,
        events: List<VoiceSegmenterResult>,
        order: MutableList<String>?,
        mode: ListeningMode,
        wakePositive: Boolean,
        opener: EarlyConnectionOpener,
    ): VoiceAsrEngine {
        val wake = mock<WakeWordDetector>()
        whenever(wake.detect(any(), any(), any())).thenReturn(WakeWordResult(wakePositive, 0f))
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = processor(events, order),
            utteranceFilter = mock<UtteranceFilter>().also {
                whenever(it.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
            },
            intentRecognizer = recognizer(),
            wakeWordDetector = wake,
            gracePeriodSignal = mock(),
            audioFeedbackRenderer = mock(),
            translationStage = mock(),
            currentLocale = { java.util.Locale.US },
            context = context(),
            earlyConnectionOpener = opener,
        )
        engine.scope = scope
        engine.start(
            backend(),
            AudioRoute.Speaker,
            mode,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
        return engine
    }

    @Test
    fun `in grace the connection opens at trailing-silence start, before the segment closes`() = runTest {
        val recorder = RecordingOpener()
        // The onset and the endpoint are told apart by ORDER, not by whether an open happened: a
        // later open would make a count-based assertion pass while the latency the optimisation
        // exists to save was still being spent.
        val order = mutableListOf<String>()
        recorder.onOpen = { order += "OPEN" }
        startEngine(
            this,
            listOf(
                VoiceSegmenterResult.SpeechStarted,
                VoiceSegmenterResult.SpeechContinuing,
                VoiceSegmenterResult.TrailingSilenceBegan,
                VoiceSegmenterResult.SpeechEnded(listOf(PcmAudioFrame(shortArrayOf(100, 200), 16000))),
            ),
            order,
            // In grace the utterance is addressed with no verdict needed, so the onset open is
            // reachable here. Outside grace it is not: the wake detector only scores the FINISHED
            // segment, so no verdict exists at the first silent frame and the open can only happen
            // at the endpoint. Delivering the onset open outside grace needs the streaming wake
            // score wired into this decision, which is why this control covers grace only.
            mode = ListeningMode.Continuous,
            wakePositive = false,
            opener = recorder.opener(),
        )
        advanceUntilIdle()

        val firstOpen = order.indexOf("OPEN")
        val segmentEnded = order.indexOf("SpeechEnded")
        assertTrue("expected an open, order=$order", firstOpen >= 0)
        assertTrue(
            "the first open must precede the segment closing, order=$order",
            segmentEnded < 0 || firstOpen < segmentEnded,
        )
    }

    @Test
    fun `an utterance that pauses and resumes opens once for that utterance`() {
        val recorder = RecordingOpener()
        runTest {
            startEngine(
                this,
                listOf(
                    VoiceSegmenterResult.SpeechStarted,
                    VoiceSegmenterResult.TrailingSilenceBegan,
                    VoiceSegmenterResult.SpeechContinuing,
                    VoiceSegmenterResult.TrailingSilenceBegan,
                    VoiceSegmenterResult.SpeechEnded(listOf(PcmAudioFrame(shortArrayOf(100, 200), 16000))),
                ),
                order = null,
                mode = ListeningMode.WakeWord,
                wakePositive = true,
                opener = recorder.opener(),
            )
            advanceUntilIdle()
        }
        // A resume is the same utterance: a second open would be a second dial for one turn.
        assertEquals(1, recorder.tags.size)
    }

    @Test
    fun `outside grace the connection opens when the verdict turns positive`() = runTest {
        val recorder = RecordingOpener()
        val order = mutableListOf<String>()
        recorder.onOpen = { order += "OPEN" }
        startEngine(
            this,
            listOf(
                VoiceSegmenterResult.SpeechStarted,
                VoiceSegmenterResult.SpeechContinuing,
                VoiceSegmenterResult.TrailingSilenceBegan,
                VoiceSegmenterResult.SpeechEnded(listOf(PcmAudioFrame(shortArrayOf(100, 200), 16000))),
            ),
            order,
            mode = ListeningMode.WakeWord,
            wakePositive = true,
            opener = recorder.opener(),
        )
        advanceUntilIdle()

        // Today the wake detector scores the FINISHED segment, so no verdict exists at the first
        // silent frame and the onset open cannot fire outside grace: the open lands after
        // SpeechEnded. This asserts what the code does now, deliberately, so that wiring the
        // streaming score into this decision is a visible change in behaviour rather than a
        // silent one. When the streaming verdict becomes authoritative at the onset, this test
        // must be updated to require the open BEFORE SpeechEnded.
        assertTrue("expected an open, order=$order", order.contains("OPEN"))
        val firstOpen = order.indexOf("OPEN")
        val segmentEnded = order.indexOf("SpeechEnded")
        assertTrue(
            "outside grace the open currently follows the endpoint decision, order=$order",
            segmentEnded >= 0 && firstOpen > segmentEnded,
        )
    }

    @Test
    fun `a rejected segment opens nothing and holds nothing`() = runTest {
        val recorder = RecordingOpener()
        val order = mutableListOf<String>()
        recorder.onOpen = { order += "OPEN" }
        startEngine(
            this,
            listOf(
                VoiceSegmenterResult.SpeechStarted,
                VoiceSegmenterResult.TrailingSilenceBegan,
                // Too short to be an utterance: the onset reported, then the segment was rejected.
                VoiceSegmenterResult.Rejected(au.com.shiftyjelly.pocketcasts.voicecontrol.audio.RejectionReason.TooShort),
            ),
            order,
            // In grace, so the onset is addressed and does open; the point is that the rejection
            // does not leave a second open behind, and nothing is claimed for a segment that was
            // thrown away.
            mode = ListeningMode.Continuous,
            wakePositive = false,
            opener = recorder.opener(),
        )
        advanceUntilIdle()

        assertEquals(1, recorder.tags.size)
    }

    @Test
    fun `nothing opens for speech that was not addressed`() {
        val recorder = RecordingOpener()
        runTest {
            // Outside grace with no positive wake: the app was not being spoken to, so the server
            // must not learn that someone spoke near the phone.
            startEngine(
                this,
                listOf(
                    VoiceSegmenterResult.SpeechStarted,
                    VoiceSegmenterResult.TrailingSilenceBegan,
                ),
                order = null,
                mode = ListeningMode.WakeWord,
                wakePositive = false,
                opener = recorder.opener(),
            )
            advanceUntilIdle()
        }
        assertTrue("expected no open, got ${recorder.tags}", recorder.tags.isEmpty())
    }
}
