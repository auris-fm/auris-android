package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.PrepaidTestAllowance
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * P1 ENGINE-SEAM INVOCATION CONTROLS (reviewer b33229ee): the mapping controls in
 * [InvocationProvenanceControlsTest] verify the sink/provenance mapping; THESE controls drive
 * the REAL [VoiceAsrEngine] transcribe/routing/translation branches through the existing fake
 * seams (same harness pattern as [MixedFactsPersistControlTest]). Deleting the engine's
 * invocation notes/catches makes these fail:
 *  - invocation identity frozen at the actual transcribe call (selection never claimed);
 *  - blank RAW result vs wake-only-with-text are DISTINCT facts;
 *  - a thrown provider failure is a published terminal; cancellation is a recorded
 *    nonterminal fact with no publication;
 *  - translation skip vs run-and-fail are distinct known facts;
 *  - the router model belongs to the recognizer actually invoked;
 *  - released/never-verified load state claims NO artifact.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineProvenanceSeamControlTest {

    private val translateCalls = java.util.concurrent.atomic.AtomicInteger(0)

    private fun retained(sink: ContributionRetentionSink) = sink.retainedSnapshot().firstOrNull()

    private fun firstCaptureId(sink: ContributionRetentionSink) = retained(sink)?.context?.captureId

    private fun asrStage(sink: ContributionRetentionSink, id: java.util.UUID) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.EngineStageProvenance
        .stagesFor(sink.sealedSnapshotForTest(id)!!).toMap().getValue(StageName.Asr)

    private fun Harness.firstCaptureId() = firstCaptureId(sink)

    private fun <T : Any> assertNotNull2(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun translationStageEntry(
        sink: ContributionRetentionSink,
        id: java.util.UUID,
    ) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.EngineStageProvenance
        .stagesFor(sink.sealedSnapshotForTest(id)!!).toMap().getValue(StageName.Translation)

    private fun reason(entry: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry) = (entry.fields["reason"] as? RecordNode.Str)?.value

    @Test
    fun `the engine claims the INVOKED backend identity - never the selection`() = runTest {
        val h = Harness(this, backend = IdentityBackend(Named("sense-voice", "2024-07-17")))
        h.startTurn()
        val id = h.firstCaptureId()!!
        val sealed = h.sink.sealedSnapshotForTest(id)!!
        assertEquals(Named("sense-voice", "2024-07-17"), sealed.asrInvocationModel)
        // The selection (a DIFFERENT model, minted by the observer from requiredModel) is
        // nowhere in the claim.
        assertTrue(sealed.context.asrModel != sealed.asrInvocationModel)
    }

    @Test
    fun `a blank RAW result through the engine is the known empty fact with the invoked model`() = runTest {
        // The fake EXPLICITLY carries the verified-load premise; a plain TextBackend supplies
        // no load evidence and would legitimately claim None.
        val h = Harness(
            this,
            backend = IdentifiedTextBackend("   ", detectedLanguage = "en", ref = Named("sense-voice", "2024-07-17")),
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val outcome = retained(h.sink)!!.outcome
        // The engine joins NotCollected("empty") ONLY for the genuinely blank raw result.
        assertEquals(CollectionOutcome.NotCollected(id, "empty"), outcome)
        // The invocation evidence was frozen before the call, independent of the empty result.
        assertEquals(Named("sense-voice", "2024-07-17"), h.sink.sealedSnapshotForTest(id)!!.asrInvocationModel)
    }

    @Test
    fun `wake-only with recognized text preserves the ACTUAL transcript`() = runTest {
        val h = Harness(
            this,
            backend = TextBackend(" ok pause ", detectedLanguage = "en"),
            wakeDetected = true,
            speechEndSample = 2, // within the wake-only tolerance of completionSample 4000
        )
        h.startTurn()
        val outcome = retained(h.sink)!!.outcome
        // The engine joins Recognized with the RAW text; trimming never erases the observation.
        val recognized = outcome as? CollectionOutcome.Recognized
        assertNotNull("wake-only-with-text must preserve the transcript as Recognized", recognized)
        assertEquals(" ok pause ", recognized!!.transcript)
    }

    @Test
    fun `a thrown provider failure publishes the known failed terminal`() = runTest {
        var published: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture? = null
        val h = Harness(
            this,
            backend = ThrowingBackend(RuntimeException("asr provider down")),
            onPublication = { published = it },
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val snapshot = published!!
        assertEquals(id, snapshot.captureId)
        assertEquals(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture.CAUSE_TERMINAL, snapshot.cause)
        assertTrue(snapshot.outcome is CollectionOutcome.AsrFailed)
        assertEquals(StageState.Failed, asrStage(h.sink, id).state)
        assertEquals("provider_error", reason(asrStage(h.sink, id)))
    }

    @Test
    fun `cancellation records the known cancelled fact and publishes nothing`() = runTest {
        var published: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture? = null
        val h = Harness(
            this,
            backend = ThrowingBackend(java.util.concurrent.CancellationException("user cancelled")),
            onPublication = { published = it },
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val snapshot = h.sink.sealedSnapshotForTest(id)!!
        // Nonterminal: recorded in the join history; NO early publication fired.
        assertTrue(snapshot.stageHistory.any { it is CollectionOutcome.AsrCancelled })
        assertNull(published)
        assertNull(snapshot.outcome as? CollectionOutcome.AsrFailed)
    }

    @Test
    fun `backend-handled translation is the known skip - never a claimed produced result`() = runTest {
        val h = Harness(
            this,
            backend = TextBackend("play music", detectedLanguage = "de", canTranslateToEnglish = true),
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val sealed = h.sink.sealedSnapshotForTest(id)!!
        val translation = sealed.translation
        assertNotNull(translation)
        assertEquals("backend_handles_translation", translation!!.skipReason)
        assertEquals(false, translation.producedResult)
    }

    @Test
    fun `an unavailable translator is a known pre-invocation SKIP with no produced result`() = runTest {
        val h = Harness(
            this,
            backend = IdentifiedTextBackend("播放音乐", detectedLanguage = "zh", ref = Named("sense-voice", "2024-07-17")),
            translationReady = false,
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val sealed = h.sink.sealedSnapshotForTest(id)!!
        val translation = sealed.translation
        assertNotNull(translation)
        assertEquals("translator_unavailable", translation!!.skipReason)
        assertEquals(false, translation.producedResult)
        assertNull(translation.failedWith)
    }

    @Test
    fun `a translator that RUNS and fails is the known failure - never a skip`() = runTest {
        val h = Harness(
            this,
            backend = IdentifiedTextBackend("播放音乐", detectedLanguage = "zh", ref = Named("sense-voice", "2024-07-17")),
            translateResult = Result.failure(IllegalStateException("mlkit translate exploded")),
            translateCallCounter = translateCalls,
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val sealed = h.sink.sealedSnapshotForTest(id)!!
        val translation = assertNotNull2(sealed.translation)
        // An ACTUAL translate invocation happened and failed: a failure fact, not a skip.
        assertEquals(1, translateCalls.get())
        assertEquals("provider_error", translation.failedWith)
        assertNull(translation.skipReason)
        assertEquals(false, translation.producedResult)
        assertEquals(StageState.Failed, translationStageEntry(h.sink, id).state)
        assertEquals("provider_error", reason(translationStageEntry(h.sink, id)))
    }

    @Test
    fun `a translator that RUNS and returns blank output is a failure - not a produced result`() = runTest {
        val translateCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val h = Harness(
            this,
            backend = IdentifiedTextBackend("播放音乐", detectedLanguage = "zh", ref = Named("sense-voice", "2024-07-17")),
            translateResult = Result.success("   "),
            translateCallCounter = translateCalls,
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val sealed = h.sink.sealedSnapshotForTest(id)!!
        val translation = assertNotNull2(sealed.translation)
        assertEquals(1, translateCalls.get())
        // Blank output is NOT a translation: recorded as a failure, and no equality claim.
        assertEquals("provider_error", translation.failedWith)
        assertEquals(false, translation.producedResult)
        assertNull(translation.targetLanguage)
    }

    @Test
    fun `a translator that returns the input unchanged claims no equality - it is a failed run`() = runTest {
        val translateCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val h = Harness(
            this,
            backend = IdentifiedTextBackend("播放音乐", detectedLanguage = "zh", ref = Named("sense-voice", "2024-07-17")),
            translateResult = Result.success("播放音乐"),
            translateCallCounter = translateCalls,
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val translation = assertNotNull2(h.sink.sealedSnapshotForTest(id)!!.translation)
        assertEquals(1, translateCalls.get())
        // A no-op output must never be recorded as source_language_target_language.
        assertEquals(false, translation.producedResult)
        assertEquals("provider_error", translation.failedWith)
    }

    @Test
    fun `the router model belongs to the recognizer actually invoked`() = runTest {
        val h = Harness(this, backend = IdentityBackend(Named("sense-voice", "2024-07-17")))
        h.startTurn()
        val id = h.firstCaptureId()!!
        val sealed = h.sink.sealedSnapshotForTest(id)!!
        // The intent recognizer RAN (PauseRecognizer routed): its verified identity is claimed.
        assertEquals(PauseRecognizerWithIdentity.LFM_REF, sealed.routerInvocationModel)
    }

    @Test
    fun `a null intent records the recognizer's OWN decision and leaves the stream open`() = runTest {
        var published: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture? = null
        val h = Harness(
            this,
            backend = IdentityBackend(Named("sense-voice", "2024-07-17")),
            recognizer = NoneRecognizer("low_confidence"),
            onPublication = { published = it },
        )
        h.startTurn()
        val id = h.firstCaptureId()!!
        val snapshot = h.sink.sealedSnapshotForTest(id)!!
        val routingNone = snapshot.stageHistory.any { it is CollectionOutcome.RoutingNone }
        assertTrue("the recognizer's own no-intent decision is recorded", routingNone)
        // The stream is NOT closed: no publication fired on the join itself.
        assertNull(published)
        assertEquals(ModelRef.Named("lfm", "1.4.2"), snapshot.routerInvocationModel)
    }

    /**
     * Scope note (reviewer 4927bb31): this control proves what the ENGINE does when load
     * evidence is ABSENT — it claims None. It deliberately does not prove that the real
     * loaders clear their evidence (a fake flag cannot show that); the loader evidence
     * clearing is covered by [BackendLoadEvidenceTest] against the real backends' own
     * load/release seams.
     */
    @Test
    fun `the engine claims NO asr artifact when load evidence is absent`() = runTest {
        val backend = ReleasableIdentityBackend(Named("sense-voice", "2024-07-17"))
        val h = Harness(this, backend = backend)
        h.startTurn()
        val id = h.firstCaptureId()!!
        assertEquals(Named("sense-voice", "2024-07-17"), h.sink.sealedSnapshotForTest(id)!!.asrInvocationModel)
        // Evidence is withdrawn before the next invocation: the engine must claim None, never
        // the stale identity.
        backend.release()
        assertNull(backend.loadedModelRef())
        val h2 = Harness(this, backend = backend)
        h2.startTurn()
        val id2 = h2.firstCaptureId()!!
        assertEquals(ModelRef.None, h2.sink.sealedSnapshotForTest(id2)!!.asrInvocationModel)
    }

    @Test
    fun `the sealed claim is frozen at invocation - a LATER backend swap cannot rewrite it`() = runTest {
        val backend = SwappingBackend(Named("sense-voice", "2024-07-17"))
        val h = Harness(this, backend = backend)
        h.startTurn()
        val id = h.firstCaptureId()!!
        assertEquals(Named("sense-voice", "2024-07-17"), h.sink.sealedSnapshotForTest(id)!!.asrInvocationModel)
        // The loaded identity ACTUALLY CHANGES after the invocation; the frozen claim keeps the
        // invocation-time value.
        backend.swapTo(Named("canary-flash", "2.0.0"))
        assertEquals(Named("canary-flash", "2.0.0"), backend.loadedModelRef())
        assertEquals(
            "a later model replacement must not be attributed to the earlier capture",
            Named("sense-voice", "2024-07-17"),
            h.sink.sealedSnapshotForTest(id)!!.asrInvocationModel,
        )
    }

    // ── fakes ──────────────────────────────────────────────────────────────────

    @Suppress("FunctionName")
    private fun Named(id: String, version: String) = ModelRef.Named(id, version)

    /** Backend with an EXISTING verified-load identity distinct from its configured selection. */
    private open class IdentityBackend(private val ref: ModelRef) : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult("pause")
        override fun loadedModelRef(): ModelRef = ref
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "selected-but-never-run")
        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en", "de", "zh"))
        override fun release() = Unit
    }

    private class ReleasableIdentityBackend(private val ref: ModelRef) : AsrBackend {
        @Volatile private var verified: ModelRef? = ref
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult("pause")
        override fun loadedModelRef(): ModelRef? = verified
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "selected-but-never-run")
        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en"))
        override fun release() {
            verified = null
        }
    }

    private open class TextBackend(
        private val text: String,
        private val detectedLanguage: String?,
        private val canTranslateToEnglish: Boolean = false,
    ) : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult(text, detectedLanguage = detectedLanguage)
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "selected-but-never-run")
        override val capabilities: AsrCapabilities = AsrCapabilities(
            supportedLanguages = setOf("en", "de", "zh"),
            canTranslateToEnglish = canTranslateToEnglish,
        )
        override fun release() = Unit
    }

    /** Text result WITH an explicit verified-load premise (unlike [TextBackend]). */
    private class IdentifiedTextBackend(
        private val text: String,
        private val detectedLanguage: String?,
        private val ref: ModelRef,
    ) : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult(text, detectedLanguage = detectedLanguage)
        override fun loadedModelRef(): ModelRef = ref
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "selected-but-never-run")
        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en"))
        override fun release() = Unit
    }

    /** [loadedModelRef] returns the CURRENT identity, which [swapTo] actually changes. */
    private class SwappingBackend(first: ModelRef) : AsrBackend {
        @Volatile private var current: ModelRef = first
        fun swapTo(ref: ModelRef) {
            current = ref
        }
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult("pause")
        override fun loadedModelRef(): ModelRef = current
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "selected-but-never-run")
        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en"))
        override fun release() = Unit
    }

    private class ThrowingBackend(private val thrown: Throwable) : AsrBackend {
        var throws = true
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult {
            if (throws) {
                throws = false
                throw thrown
            }
            return AsrResult("pause")
        }
        override val requiredModel: ModelSpec = ModelSpec(files = emptyList(), targetDir = "selected-but-never-run")
        override val capabilities: AsrCapabilities = AsrCapabilities(supportedLanguages = setOf("en"))
        override fun release() = Unit
    }

    /** Real recognizer boundary WITH a verified router identity, returning an intent. */
    private object PauseRecognizerWithIdentity : VoiceRecognizer {
        val LFM_REF: ModelRef = ModelRef.Named("lfm", "1.4.2")
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun recognize(input: IntentRoutingInput, context: VoiceRecognitionContext) = VoiceRecognizeResult(intent = VoiceIntent.Playback.Pause)
        override fun loadedModelRef(): ModelRef = LFM_REF
        override fun release() = Unit
    }

    /** Recognizer that RAN and reported no usable intent, with its OWN reason. */
    private class NoneRecognizer(private val reason: String?) : VoiceRecognizer {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun recognize(input: IntentRoutingInput, context: VoiceRecognitionContext) = VoiceRecognizeResult(
            intent = null,
            diagnostic = reason?.let {
                au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic(
                    modelRelease = "1.4.2",
                    quant = null,
                    inputFormat = null,
                    sourceLanguage = null,
                    translationKind = "none",
                    classifierLabel = null,
                    finalOutcome = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic.OUTCOME_NO_INTENT,
                    failedStage = au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic.STAGE_NO_MATCH,
                    reason = it,
                    stageLatencyMs = emptyMap(),
                    totalLatencyMs = 1L,
                )
            },
        )
        override fun loadedModelRef(): ModelRef = ModelRef.Named("lfm", "1.4.2")
        override fun release() = Unit
    }

    // ── harness (same pattern as MixedFactsPersistControlTest) ────────────────

    private inner class Harness(
        private val testScope: TestScope,
        val backend: AsrBackend,
        private val recognizer: VoiceRecognizer = PauseRecognizerWithIdentity,
        private val wakeDetected: Boolean = true,
        private val speechEndSample: Int = 0,
        translationReady: Boolean = true,
        private val translateResult: Result<String> = Result.success("translated"),
        translateCallCounter: java.util.concurrent.atomic.AtomicInteger? = null,
        private val onPublication: ((au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture) -> Unit)? = null,
    ) {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        private val context: Context = mock()
        private val audioManager: AudioManager = mock()
        private val voiceAudioProcessor: VoiceAudioProcessor = mock()

        // Real filter: its no-bleed decision is what lets the segment reach the transcribe call.
        // The capture is a HEADSET capture (route class above), and the REAL filter's route
        // monitor must agree: a route mismatch would take the bleed branch and never invoke ASR.
        private val utteranceFilter = UtteranceFilter(
            PlaybackCrossCorrelator(),
            mock<au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor>().also {
                whenever(it.route).thenReturn(
                    MutableStateFlow(au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute.Headset(hasMicrophone = true)),
                )
            },
        )
        private val wakeWordDetector: WakeWordDetector = mock()
        private val gracePeriodSignal: GracePeriodSignal = mock()
        private val renderer: AudioFeedbackRenderer = mock()
        private var translationStage: TranslationStage? = null

        private val authority: () -> AuthorityDecision = {
            AuthorityDecision(collectionPermitted = true, fullRevocation = false)
        }
        private val observer = SegmentObserver(
            CollectionGate { true },
            sink,
            authority,
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "disclosure-1") },
            handoff = CompletionHandoff { ctx, onset, end, wake, provider, scope, live ->
                scope.completeUnderOwner(ctx, onset, end, wake, provider)
            },
            allowance = PrepaidTestAllowance(),
        )
        private var engine: VoiceAsrEngine? = null
        private var nextInvocation = AtomicLong(0L)

        init {
            // A DETERMINISTIC fake stage: suspend funs are otherwise unstubbable (Mockito cannot
            // intercept suspend calls), so the real branch decides on these returns.
            translationStage = object : TranslationStage {
                override suspend fun ensureReady(sourceLanguage: String): Result<Unit> = if (translationReady) Result.success(Unit) else Result.failure(IllegalStateException("no translator"))
                override suspend fun translate(text: String, sourceLanguage: String): Result<String> {
                    translateCallCounter?.incrementAndGet()
                    return translateResult
                }
            }
        }

        init {
            whenever(gracePeriodSignal.isActive).thenReturn(MutableStateFlow(false))
            whenever(voiceAudioProcessor.attachCapture()).thenReturn(true)
            whenever(voiceAudioProcessor.peekSpeechDelta(any())).thenReturn(0 to ShortArray(0))
            whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
            whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        }

        fun startTurn() {
            whenever(voiceAudioProcessor.startProcessing()).thenReturn(
                flowOf(
                    VoiceSegmenterResult.SpeechStarted,
                    VoiceSegmenterResult.SpeechContinuing,
                    VoiceSegmenterResult.SpeechEnded(
                        listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                        speechOnsetSample = 2,
                        speechEndSample = speechEndSample,
                    ),
                ),
            )
            kotlinx.coroutines.runBlocking {
                whenever(wakeWordDetector.detect(any(), any(), any())).thenReturn(
                    WakeWordResult(detected = wakeDetected, confidence = if (wakeDetected) 0.9f else 0f, completionSample = 4000),
                )
            }
            if (onPublication != null) sink.onReadyForPublication = { onPublication(it) }
            val engine = VoiceAsrEngine(
                voiceAudioProcessor = voiceAudioProcessor,
                utteranceFilter = utteranceFilter,
                intentRecognizer = recognizer,
                wakeWordDetector = wakeWordDetector,
                gracePeriodSignal = gracePeriodSignal,
                audioFeedbackRenderer = renderer,
                translationStage = translationStage!!,
                collectionAuthority = authority,
                retentionSink = sink,
                segmentObserver = observer,
                context = context,
            )
            this.engine = engine
            engine.scope = testScope
            engine.start(
                backend = backend,
                audioRoute = AudioRoute.Headset(hasMicrophone = true),
                listeningMode = ListeningMode.WakeWord,
                playbackBufferProvider = { FloatArray(0) },
                micExposureProvider = { MicExposure.Exposed },
                onIntent = { _, _ -> },
            )
            testScope.advanceUntilIdle()
            assertNotNull("the turn must retain a capture", firstCaptureId())
        }
    }
}
