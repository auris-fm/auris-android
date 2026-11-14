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
    private val originalClock = TurnTimingRecord.clockNanos

    @After
    fun restore() {
        TurnTimingRecord.sink = original
        TurnTimingRecord.clockNanos = originalClock
        // The registry is static: a test that deliberately leaves a turn open must not leak that
        // entry into the next test's emptiness assertion.
        TurnTimingRecord.liveIdsForTest().forEach { TurnTimingRecord.unregister(it) }
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
        // Pin the record's clock so the delivery-side stages are deterministic against the
        // capture-derived acoustic end (1_000_000_000ns).
        TurnTimingRecord.clockNanos = { 2_000_000_000_000L }
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = audioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = feedback,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(true, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
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
            onIntent = { _, _, _ -> },
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
    fun `an utterance that exits before dispatch leaves no live timing entry`() = runTest {
        // The engine registers the record when it admits the utterance, and ownership transfers to
        // the downstream producers only at a dispatch site. An empty transcript ends segment
        // processing BEFORE any dispatch, so the engine's own cleanup must have finished the
        // record — the registry must be empty, not holding a live entry forever.
        val context = mock<Context>()
        val audioManager = mock<AudioManager>()
        val audioProcessor = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor>()
        val utteranceFilter = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter>()
        val recognizer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer>()
        val wakeWordDetector = mock<WakeWordDetector>()
        val gracePeriodSignal = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal>()
        val feedback = mock<AudioFeedbackRenderer>()
        val translationStage = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage>()
        val backend = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend>()

        whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        whenever(audioProcessor.attachCapture()).thenReturn(true)
        whenever(audioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(ShortArray(1_600) { (it % 251).toShort() }, 16_000)),
                    speechOnsetSample = 0,
                    speechEndSample = 1_600,
                    captureClock = longArrayOf(1600, 1_000_000_000L, 16_000, 1),
                    segmentEndStreamPosition = 1600,
                ),
            ),
        )
        whenever(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        // WAKE POSITIVE: the utterance is admitted, so the engine REGISTERS a timing record for it.
        whenever(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            WakeWordResult(detected = true, confidence = 0.9f, completionSample = 800, threshold = 0.812f),
        )
        whenever(backend.ensureReady()).thenReturn(Result.success(Unit))
        whenever(backend.requiredModel).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec(emptyList(), ""))
        whenever(backend.capabilities).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities(setOf("en")))
        // EMPTY TRANSCRIPT: the turn is dropped before any dispatch — the early-exit path.
        whenever(backend.transcribe(any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult(text = ""),
        )
        // This turn must END LOCALLY, so the recognizer reports a reason the escalation policy
        // answers without the cloud (a rejection/earcon), not a DISPATCH reason: the test is about
        // the pre-dispatch cleanup path, not about escalation.
        whenever(recognizer.ensureReady()).thenReturn(Result.success(Unit))
        whenever(recognizer.recognize(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult(
                intent = null,
                diagnostic = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic(
                    modelRelease = null,
                    quant = null,
                    inputFormat = null,
                    sourceLanguage = null,
                    translationKind = "none",
                    classifierLabel = null,
                    finalOutcome = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic.OUTCOME_NO_INTENT,
                    failedStage = "no_match",
                    reason = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic.REASON_NO_MATCH,
                    stageLatencyMs = emptyMap(),
                    totalLatencyMs = 12,
                    requestId = "test-empty",
                ),
            ),
        )

        val engine = VoiceAsrEngine(
            voiceAudioProcessor = audioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = feedback,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(true, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
            elapsedClockMs = { 5_000_000L },
        )
        engine.scope = this
        engine.start(
            backend = backend,
            audioRoute = au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute.Speaker,
            listeningMode = au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure.Exposed },
            onIntent = { _, _, _ -> },
        )
        testScheduler.advanceUntilIdle()

        // The pump may still be mid-flight; let it settle before reading the registry.
        testScheduler.advanceUntilIdle()
        val companion = TurnTimingRecord.Companion
        val field = (companion.javaClass.declaredFields + TurnTimingRecord::class.java.declaredFields)
            .firstOrNull { it.name == "active" } ?: throw AssertionError("registry field not found")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST") val active = field.get(companion) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        assertTrue(
            "a turn dropped before dispatch must leave no live timing entry: ${active.keys} " +
                "recordLines=" + synchronized(published) { published.toList() }.joinToString("|"),
            active.isEmpty(),
        )
    }


    @Test
    fun `a locally declined turn that escalates keeps its record open for the first audible mark`() = runTest {
        // THE DEVICE FINDING (Pixel, 07:49:13 local): the router declined the utterance, the turn
        // escalated to the cloud, the answer played -- and no first_audible was recorded anywhere.
        // The no_intent path published the table BEFORE the escalation decision, and publish()
        // unregisters the record, so the route sink's later first-audible mark landed on nothing.
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
        val backend = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend>()

        whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        whenever(audioProcessor.attachCapture()).thenReturn(true)
        whenever(gracePeriodSignal.isActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        // The escalation is rejected before dispatch unless its captured window is still live.
        whenever(gracePeriodSignal.capturedGeneration()).thenReturn(1L)
        whenever(gracePeriodSignal.isCurrent(any())).thenReturn(true)
        // THREE SECONDS of speech (48_000 samples at 16 kHz). A segment shorter than
        // WAKE_ONLY_TOLERANCE_MS (120 ms = 1920 samples) counts as "wake only" for ANY wake
        // reading and would be dropped before routing; this one is a real command.
        whenever(audioProcessor.startProcessing()).thenReturn(
            flowOf(
                VoiceSegmenterResult.SpeechEnded(
                    listOf(PcmAudioFrame(ShortArray(48_000) { (it % 251).toShort() }, 16_000)),
                    speechOnsetSample = 0,
                    speechEndSample = 48_000,
                    captureClock = longArrayOf(48_000, 1_000_000_000L, 16_000, 1),
                    segmentEndStreamPosition = 48_000,
                ),
            ),
        )
        whenever(utteranceFilter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        // The wake ends 0.6 s in, far before the 3 s speech end, so this is a COMMAND.
        whenever(wakeWordDetector.detect(any(), any(), any())).thenReturn(
            WakeWordResult(detected = true, confidence = 0.9f, completionSample = 9_600, threshold = 0.812f),
        )
        whenever(backend.ensureReady()).thenReturn(Result.success(Unit))
        whenever(backend.requiredModel).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec(emptyList(), ""))
        whenever(backend.capabilities).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities(setOf("en")))
        // English detected, and English is the target: prepareRoutingInput skips the separate
        // translation stage, so the mocked translator is never consulted.
        whenever(backend.transcribe(any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult(
                text = "Can you suggest some reading.",
                detectedLanguage = "en",
            ),
        )
        // THE ROUTER DECLINES, and CloudEscalationPolicy sends that reason to the cloud.
        whenever(recognizer.ensureReady()).thenReturn(Result.success(Unit))
        whenever(recognizer.recognize(any(), any(), any())).thenReturn(
            au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult(
                intent = null,
                diagnostic = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic(
                    modelRelease = null,
                    quant = null,
                    inputFormat = null,
                    sourceLanguage = "en",
                    translationKind = "none",
                    classifierLabel = null,
                    finalOutcome = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic.OUTCOME_NO_INTENT,
                    failedStage = "mapper_dialog",
                    reason = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic.REASON_MAPPER_OR_DIALOG_FAILED,
                    stageLatencyMs = emptyMap(),
                    totalLatencyMs = 775,
                    requestId = "test-request",
                ),
            ),
        )

        var dispatchedId: String? = null
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = audioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = feedback,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(true, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
            elapsedClockMs = { 5_000_000L },
        )
        engine.scope = this
        engine.start(
            backend = backend,
            audioRoute = au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute.Speaker,
            listeningMode = au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure.Exposed },
            onIntent = { _, _, admittedId -> dispatchedId = admittedId },
        )
        testScheduler.advanceUntilIdle()

        val id = dispatchedId
        assertTrue(
            "the declined turn must escalate to the cloud, dispatching a turn: published=" +
                synchronized(published) { published.toList() }.joinToString("\n"),
            id != null,
        )
        // The escalation handed the turn to the sink, so its record must STILL be live: that is
        // the only way the route sink's first-audible mark can land on it.
        assertTrue(
            "a turn that escalated to the cloud must keep its timing record live: registry held " +
                TurnTimingRecord.liveIdsForTest(),
            TurnTimingRecord.isActive(id!!),
        )
        testScheduler.advanceUntilIdle()
        val text = synchronized(published) { published.toList() }.joinToString("\n")
        assertTrue(
            "the record must not have been published before the cloud owns the turn: got '$text'",
            !text.contains("stage=acoustic_end"),
        )
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

        // Pin the record's clock so the delivery-side stages are deterministic against the
        // capture-derived acoustic end (1_000_000_000ns).
        TurnTimingRecord.clockNanos = { 2_000_000_000_000L }
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = audioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = recognizer,
            wakeWordDetector = wakeWordDetector,
            gracePeriodSignal = gracePeriodSignal,
            audioFeedbackRenderer = feedback,
            translationStage = translationStage,
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(true, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )
        engine.scope = this
        engine.start(
            backend = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend>(),
            audioRoute = au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute.Speaker,
            listeningMode = au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode.WakeWord,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16_000, startPositionMs = null) },
            micExposureProvider = { au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure.Exposed },
            onIntent = { _, _, _ -> },
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
