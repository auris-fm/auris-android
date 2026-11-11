package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M3, at the seam that matters: the engine actually drives the timing table.
 *
 * [au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecordTest] proves the record
 * can only say what was measured. This proves something calls it, and that the stages it reports
 * are the ones the pipeline really passes through — a table that is correct but never published
 * measures nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TurnTimingWiringTest {

    private val published = mutableListOf<String>()
    private val original = TurnTimingRecord.sink

    @After
    fun restore() {
        TurnTimingRecord.sink = original
    }

    @Test
    fun `the engine publishes a timing table for an utterance it dropped`() = runTest {
        TurnTimingRecord.sink = { _, message -> synchronized(published) { published += message } }

        val context = mock<Context>()
        val audioManager = mock<AudioManager>()
        val audioProcessor = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor>()
        val utteranceFilter = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter>()
        val recognizer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer>()
        val wakeWordDetector = mock<WakeWordDetector>()
        val gracePeriodSignal = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal>()
        val feedback = mock<AudioFeedbackRenderer>()
        val translationStage = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage>()

        whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        whenever(audioProcessor.attachCapture()).thenReturn(true)
        // No wake word, outside grace: the engine drops the utterance before ASR and publishes the
        // table for the dropped turn. That is the path that must still be measured -- a dropped
        // turn is exactly the case a timing table is most likely to lose.
        whenever(gracePeriodSignal.isActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        whenever(
            audioProcessor.startProcessing(),
        ).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(ShortArray(1_600) { (it % 251).toShort() }, 16_000)),
                    speechOnsetSample = 0,
                    // Speech fills the whole retained buffer (no drain silence), so the acoustic
                    // end IS the segment end: 1600 - 1600 + 1600 = stream position 1600.
                    speechEndSample = 1_600,
                    // Capture accounting: 1600 samples at 16k, segment end observed at stream
                    // position 1600 when the clock read 1_000_000_000ns (t=1000ms). The acoustic
                    // end is therefore 1000ms — derived from the accounting, not stamped "now".
                    captureClock = longArrayOf(1600, 1_000_000_000L, 16_000, 1),
                    segmentEndStreamPosition = 1600,
                ),
            ),
        )
        whenever(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        whenever(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            WakeWordResult(detected = false, confidence = 0.02f, completionSample = 800, threshold = 0.812f),
        )

        // A pinned wall clock far beyond the capture accounting's derived instant (1000ms): the
        // gap between the capture-derived acoustic end and the delivery-side stages is then a
        // huge, deterministic number — unless the engine stamped the acoustic end "now", which
        // collapses it to zero.
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = audioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = feedback,
            translationStage = translationStage,
            context = context,
            elapsedClockMs = { 5_000_000L },
        )
        engine.scope = this
        engine.start(
            backend = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend>(),
            audioRoute = au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute.Speaker,
            listeningMode = au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        testScheduler.advanceUntilIdle()

        val text = synchronized(published) { published.toList() }.joinToString("\n")
        assertTrue(
            "the engine must publish a timing table, not just construct one: got '$text'",
            text.isNotEmpty(),
        )
        assertTrue("the table starts at the acoustic end: $text", text.contains("stage=acoustic_end"))
        assertTrue(
            "the endpoint decision is its own stage, not folded into the acoustic end: $text",
            text.contains("stage=endpoint_decision"),
        )
        // THE ZERO POINT IS THE CAPTURE CLOCK, NOT THE DELIVERY INSTANT. The acoustic end comes
        // from the sample accounting (1000ms); the delivery lands later on the wall clock, so the
        // endpoint decision's elapsed against the capture-derived zero is the real drain delay.
        // If the engine stamped the acoustic end "now" at delivery, this elapsed would collapse
        // to ~0 and this assertion fails — the substitution the spec forbids.
        val endpointElapsed = Regex("stage=endpoint_decision elapsed_ms=(\\d+)").find(text)
            ?.groupValues?.get(1)?.toLongOrNull()
        assertNotNull("endpoint decision line must carry its elapsed: $text", endpointElapsed)
        assertTrue(
            "endpoint decision must be measured against the CAPTURE-derived acoustic end, not now: $text",
            endpointElapsed!! > 1_000_000L,
        )
        assertTrue(
            "the wake result is recorded even when it ended the turn: $text",
            text.contains("stage=wake_result"),
        )
        assertTrue("every line names its utterance: $text", text.contains("request_id="))
    }

    @Test
    fun `without capture accounting the acoustic end stays absent rather than becoming now`() = runTest {
        TurnTimingRecord.sink = { _, message -> synchronized(published) { published += message } }

        val context = mock<Context>()
        val audioManager = mock<AudioManager>()
        val audioProcessor = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor>()
        val utteranceFilter = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter>()
        val recognizer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer>()
        val wakeWordDetector = mock<WakeWordDetector>()
        val gracePeriodSignal = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal>()
        val feedback = mock<AudioFeedbackRenderer>()
        val translationStage = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage>()

        whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        whenever(audioProcessor.attachCapture()).thenReturn(true)
        whenever(gracePeriodSignal.isActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        // NO captureClock and NO segmentEndStreamPosition: the engine cannot know when the last
        // sample was captured, so the table must start at the endpoint decision instead of
        // substituting the delivery instant.
        whenever(
            audioProcessor.startProcessing(),
        ).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(ShortArray(1_600) { (it % 251).toShort() }, 16_000)),
                    speechOnsetSample = 0,
                    speechEndSample = 0,
                ),
            ),
        )
        whenever(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        whenever(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            WakeWordResult(detected = false, confidence = 0.02f, completionSample = 800, threshold = 0.812f),
        )

        val engine = VoiceAsrEngine(
            voiceAudioProcessor = audioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = feedback,
            translationStage = translationStage,
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend>(),
            audioRoute = au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute.Speaker,
            listeningMode = au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure.Exposed },
            onIntent = { _, _ -> },
        )
        testScheduler.advanceUntilIdle()

        val text = synchronized(published) { published.toList() }.joinToString("\n")
        assertTrue("the turn is still published: $text", text.contains("stage=endpoint_decision"))
        assertTrue(
            "absent accounting must leave the acoustic end absent, not stamped with now: $text",
            !text.contains("stage=acoustic_end"),
        )
    }
}
