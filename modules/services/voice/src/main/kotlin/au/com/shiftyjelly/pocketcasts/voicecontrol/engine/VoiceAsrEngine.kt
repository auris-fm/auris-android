package au.com.shiftyjelly.pocketcasts.voicecontrol.engine
import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.OboeConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.captureTimeOfSegmentStartMs
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenLine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PredictedIntentName
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.CloudEscalation
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.CloudEscalationPolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.withLogicalRequest
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.WakeOnlyRoutingEmission
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeTranscriptTrimmer
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
private const val WAKE_ONLY_TOLERANCE_MS = 120
class CaptureAttachment internal constructor(private val attached: CompletableDeferred<Boolean>) {
    suspend fun await(): Boolean = attached.await()
}
@Singleton
class VoiceAsrEngine @Inject constructor(
    private val voiceAudioProcessor: VoiceAudioProcessor,
    private val utteranceFilter: UtteranceFilter,
    private val intentRecognizer: VoiceRecognizer,
    private val wakeWordDetector: WakeWordDetector,
    private val gracePeriodSignal: GracePeriodSignal,
    private val audioFeedbackRenderer: AudioFeedbackRenderer,
    private val templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(emptyMap()),
    private val currentLocale: () -> Locale = { Locale.getDefault() },
    private val translationStage: TranslationStage,
    private val collectionAuthority: () -> AuthorityDecision,
    private val retentionSink: ContributionRetentionSink,
    private val segmentObserver: SegmentObserver,
    @ApplicationContext private val context: Context,
) {
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    internal val cancelledProcessingJobs: Set<Job> get() = cancelledJobs.toSet()
    internal fun recordedOutcome(job: Job): Throwable? = outcomes[job]?.takeIf { it !== CLEAN_COMPLETION }
    internal val cancelledByThisTeardown: Set<Job> get() = cancelledJobs.toSet()
    internal fun pruneConsumedBookkeeping(onlyKeep: Set<Job>) {
        val finished = ownedProcessingJobs.keys.filter { it.isCompleted && it !in onlyKeep }
        finished.forEach {
            outcomes.remove(it)
            cancelledJobs.remove(it)
            ownedProcessingJobs.remove(it)
        }
        unfinished.removeAll(finished.toSet())
    }
    private val cancelledJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val outcomes = java.util.concurrent.ConcurrentHashMap<Job, Throwable>()
    private val ownedProcessingJobs = java.util.concurrent.ConcurrentHashMap<Job, Unit>()
    internal val unfinishedProcessingJobs: List<Job>
        get() = unfinished.toList()
    internal val ownedJobs: Set<Job> get() = ownedProcessingJobs.keys.toSet()
    private val unfinished = java.util.concurrent.CopyOnWriteArrayList<Job>()
    private var playbackBufferProvider: (() -> PlaybackReference)? = null
    private var backend: AsrBackend? = null
    private var onIntent: ((VoiceIntent, InvocationToken?) -> Unit)? = null
    private var micExposureProvider: (() -> MicExposure)? = null
    @Volatile
    private var currentMode: ListeningMode = ListeningMode.Off
    @Volatile
    private var currentRouteClass: String = AudioRoute.Unknown::class.simpleName!!
    @Volatile
    private var captureSessionIncarnation: Long = 0L
    @Volatile
    private var pendingExecutionContext: CaptureContext? = null
    data class InvocationToken(val id: Long, val captureId: java.util.UUID)
    internal class InvocationRecord(val token: InvocationToken) {
        val observations = mutableListOf<ExecutionOutcome>()
    }
    private val openInvocations = java.util.concurrent.ConcurrentHashMap<Long, InvocationRecord>()
    private val invocationLock = Any()
    private val nextInvocationId = java.util.concurrent.atomic.AtomicLong()
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        backend: AsrBackend,
        audioRoute: AudioRoute,
        listeningMode: ListeningMode,
        playbackBufferProvider: () -> PlaybackReference,
        micExposureProvider: () -> MicExposure,
        onIntent: (VoiceIntent, InvocationToken?) -> Unit,
        onTerminated: () -> Unit = {},
    ): CaptureAttachment {
        this.backend = backend
        this.currentMode = listeningMode
        this.onIntent = onIntent
        this.currentRouteClass = audioRoute::class.simpleName ?: AudioRoute.Unknown::class.simpleName!!
        captureSessionIncarnation += 1
        this.playbackBufferProvider = playbackBufferProvider
        this.micExposureProvider = micExposureProvider
        utteranceFilter.reset()
        val attached = CompletableDeferred<Boolean>()
        lateinit var started: Job
        started = scope.launch {
            Timber.i("[VoicePipeline] start route=%s bluetoothCandidate=%b", audioRoute, audioRoute is AudioRoute.BluetoothA2dpOnly)
            val backendReady = backend.ensureReady()
            if (backendReady.isFailure) {
                Timber.e(backendReady.exceptionOrNull(), "[VoicePipeline] ASR backend not ready — not listening")
                return@launch
            }
            if (!voiceAudioProcessor.attachCapture()) {
                Timber.e("[VoicePipeline] capture attach failed — not listening")
                return@launch
            }
            attached.complete(true)
            try {
                var openContext: CaptureContext? = null
                var accountedSamples = 0
                voiceAudioProcessor.startProcessing().collect { result ->
                    when (result) {
                        is VoiceSegmenterResult.SpeechStarted -> {
                            val initialSize = voiceAudioProcessor.speechPcmSize()
                            val spec = backend.requiredModel
                            val asrRef = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.Named(
                                spec.targetDir,
                                spec.files.firstOrNull { it.manifestId.isNotEmpty() }?.manifestId ?: "",
                            )
                            val admission = segmentObserver.speechStartedAdmitting(
                                captureSessionIncarnation,
                                currentRouteClass,
                                initialSize,
                                asrRef,
                                wakeRequired = !gracePeriodSignal.isActive.value,
                            )
                            openContext = admission?.context
                            if (admission != null) {
                                var transferred = false
                                try {
                                    val firstChunk = ShortArray(initialSize)
                                    val copied = if (initialSize > 0) voiceAudioProcessor.drainSpeechPcm(firstChunk) else 0
                                    transferred = segmentObserver.adoptFirstChunk(admission, firstChunk, copied)
                                    if (transferred) accountedSamples = copied
                                } finally {
                                    if (!transferred) segmentObserver.releaseInitialChunk(admission)
                                }
                            }
                        }
                        is VoiceSegmenterResult.SpeechContinuing -> {
                            if (openContext != null && segmentObserver.isRetaining(openContext!!.captureId)) {
                                val producedSize = voiceAudioProcessor.speechPcmSize()
                                val deltaSamples = (producedSize - accountedSamples).coerceAtLeast(0)
                                val token = if (deltaSamples > 0) segmentObserver.reserveDelta(openContext!!, deltaSamples) else null
                                if (token != null) {
                                    var transferred = false
                                    try {
                                        val delta = ShortArray(deltaSamples)
                                        val copied = voiceAudioProcessor.drainSpeechTail(delta, accountedSamples, producedSize)
                                        if (copied > 0) {
                                            transferred = segmentObserver.commitDelta(openContext!!, delta, copied, token)
                                            if (transferred) accountedSamples += copied
                                        }
                                    } finally {
                                        if (!transferred) segmentObserver.releaseDelta(openContext!!, token)
                                    }
                                } else if (deltaSamples > 0) {
                                }
                                accountedSamples = producedSize.coerceAtMost(accountedSamples) // never regress past drained
                            }
                        }
                        is VoiceSegmenterResult.SpeechEnded -> {
                            val totalSamples = result.frames.sumOf { it.samples.size }
                            val durationMs = totalSamples * 1000L / 16000
                            Timber.i("[VoicePipeline] vad ~%dms (%d samples)", durationMs, totalSamples)
                            if (openContext != null && totalSamples > accountedSamples &&
                                segmentObserver.isRetaining(openContext!!.captureId)
                            ) {
                                val finalToken = segmentObserver.reserveDelta(openContext!!, totalSamples - accountedSamples)
                                if (finalToken != null) {
                                    var transferred = false
                                    try {
                                        val tail = shortTailOf(result.frames, accountedSamples)
                                        transferred = segmentObserver.commitDelta(openContext!!, tail, tail.size, finalToken)
                                        if (transferred) accountedSamples = totalSamples
                                    } finally {
                                        if (!transferred) segmentObserver.releaseDelta(openContext!!, finalToken)
                                    }
                                }
                            }
                            val request = shouldTranscribe(result, openContext)
                            if (request != null) {
                                transcribeSegment(result, request)
                            }
                            openContext = null
                        }
                        is VoiceSegmenterResult.Rejected -> {
                            Timber.w("[VoicePipeline] vad rejected - %s", result.reason)
                            segmentObserver.cancelled(openContext, result.reason.name)
                            openContext = null
                        }
                        VoiceSegmenterResult.Silence -> { /* no speech */ }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[VoicePipeline] audio processing failed")
            }
        }
        processingJob = started
        started.invokeOnCompletion {
            attached.complete(false)
            onTerminated()
        }
        ownedProcessingJobs[started] = Unit
        started.invokeOnCompletion { cause -> outcomes[started] = cause ?: CLEAN_COMPLETION }
        Timber.i("[VoicePipeline] engine start requested backend=%s mode=%s", backend::class.simpleName, listeningMode)
        return CaptureAttachment(attached)
    }
    internal fun openInvocationForTest(captureId: java.util.UUID, id: Long): InvocationToken {
        val token = InvocationToken(id = id, captureId = captureId)
        synchronized(invocationLock) { openInvocations[token.id] = InvocationRecord(token) }
        return token
    }
    internal fun reportExecutionResult(token: InvocationToken, outcome: ExecutionOutcome): Boolean = synchronized(invocationLock) {
        val record = openInvocations[token.id] ?: return false
        if (record.token != token) return false
        record.observations += outcome
        true
    }
    fun settleInvocation(token: InvocationToken): Boolean = settleAndReport(token)
    private fun settleAndReport(token: InvocationToken): Boolean {
        val record: InvocationRecord? = synchronized(invocationLock) {
            val candidate = openInvocations[token.id]
            if (candidate != null && candidate.token == token) openInvocations.remove(token.id) else null
        }
        record?.let {
            finalizePublication(it.token, aggregateObservations(it.observations))
            return true
        }
        return false
    }
    private fun finalizePublication(token: InvocationToken, outcome: ExecutionOutcome?) {
        if (outcome != null) segmentObserver.noteExecutionOutcome(token.captureId, outcome)
        joinOutcome(CollectionOutcome.Executed(token.captureId, dispatchedWithoutThrowing = true))
    }
    private fun clearInvocationOwnership() {
        val abandoned: List<InvocationRecord> = synchronized(invocationLock) {
            val open = openInvocations.values.toList()
            openInvocations.clear()
            open
        }
        abandoned.forEach { finalizePublication(it.token, aggregateObservations(it.observations)) }
    }
    private fun invokeHandler(handler: ((VoiceIntent, InvocationToken?) -> Unit), intent: VoiceIntent) {
        val capture = pendingExecutionContext
        pendingExecutionContext = null
        val token = capture?.let { InvocationToken(id = nextInvocationId.incrementAndGet(), captureId = it.captureId) }
        if (token != null) synchronized(invocationLock) { openInvocations[token.id] = InvocationRecord(token) }
        try {
            handler(intent, token)
        } catch (t: Throwable) {
            if (token != null) settleInvocation(token)
            throw t
        }
    }
    fun updateListeningMode(mode: ListeningMode) {
        currentMode = mode
        Timber.i("[VoicePipeline] mode updated to %s", mode)
    }
    private data class TranscribeRequest(
        val capture: CaptureContext?,
        val noteTranslation: (au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance) -> Unit = {},
        val samples: FloatArray,
        val wakePositive: Boolean,
        val completionSample: Int = 0,
        val speechEndSample: Int = 0,
        val sampleRateHz: Int = 16000,
        val admittedId: String = java.util.UUID.randomUUID().toString(),
    )
    private fun isWakeOnly(request: TranscribeRequest): Boolean = request.speechEndSample > 0 &&
        request.completionSample + (request.sampleRateHz * WAKE_ONLY_TOLERANCE_MS / 1000) >=
        request.speechEndSample
    private fun hasLetterOrNumber(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (Character.isLetter(cp) || Character.isDigit(cp)) return true
            val cat = Character.getType(cp)
            if (cat == Character.LETTER_NUMBER.toInt() || cat == Character.OTHER_NUMBER.toInt()) return true
            i += Character.charCount(cp)
        }
        return false
    }
    private suspend fun shouldTranscribe(
        segment: VoiceSegmenterResult.SpeechEnded,
        capture: CaptureContext?,
    ): TranscribeRequest? {
        val totalSamples = segment.frames.sumOf { it.samples.size }
        val floatSamples = FloatArray(totalSamples)
        var offset = 0
        for (frame in segment.frames) {
            for (i in frame.samples.indices) {
                floatSamples[offset + i] = frame.samples[i].toFloat() / 32768f
            }
            offset += frame.samples.size
        }
        val wwResult = runCatching {
            wakeWordDetector.detect(
                segment = floatSamples,
                sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000,
                speechOnsetSample = segment.speechOnsetSample,
            )
        }.getOrElse { e ->
            Timber.w(e, "[VoicePipeline] wake detection failed, dropping segment")
            segmentObserver.wakeCompleted(
                context = capture,
                speechOnsetSample = segment.speechOnsetSample,
                speechEndSample = segment.speechEndSample,
                wakeState = SegmentObservation.WakeState.DetectorFailed,
                framesProvider = { segment.frames.map { it.samples } to (segment.frames.firstOrNull()?.sampleRateHz ?: 16000) },
            )
            return null
        }
        segmentObserver.wakeCompleted(
            context = capture,
            speechOnsetSample = segment.speechOnsetSample,
            speechEndSample = segment.speechEndSample,
            wakeState = if (wwResult.detected) SegmentObservation.WakeState.Detected else SegmentObservation.WakeState.NotDetected,
            wakeMeasurement = SegmentObservation.WakeMeasurement(
                score = wwResult.confidence.takeUnless { it.isNaN() }?.toDouble(),
                threshold = wwResult.threshold.takeUnless { it.isNaN() }?.toDouble(),
            ),
            framesProvider = { segment.frames.map { it.samples } to (segment.frames.firstOrNull()?.sampleRateHz ?: 16000) },
        )
        val mode = currentMode
        val wakeCmp = when {
            wwResult.threshold.isNaN() -> if (wwResult.detected) "hit" else "miss"
            wwResult.detected -> "%.3f >= %.3f".format(wwResult.confidence, wwResult.threshold)
            else -> "%.3f < %.3f".format(wwResult.confidence, wwResult.threshold)
        }
        if (wwResult.detected) {
            gracePeriodSignal.onWakeWordDetected()
            audioFeedbackRenderer.submitEarcon(EarconId.WAKE_WORD)
            Timber.i("[VoicePipeline] wake %s → ASR (hit, mode=%s)", wakeCmp, mode)
            return TranscribeRequest(
                capture = capture,
                noteTranslation = { provenance -> capture?.let { segmentObserver.noteTranslation(it, provenance) } },
                samples = floatSamples,
                speechEndSample = segment.speechEndSample,
                sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000,
                wakePositive = true,
                completionSample = wwResult.completionSample,
            )
        }
        return when (mode) {
            ListeningMode.Continuous -> {
                Timber.i("[VoicePipeline] wake %s → ASR (grace/continuous)", wakeCmp)
                TranscribeRequest(
                    capture = capture,
                    noteTranslation = { provenance -> capture?.let { segmentObserver.noteTranslation(it, provenance) } },
                    samples = floatSamples,
                    wakePositive = false,
                )
            }
            ListeningMode.WakeWord -> {
                capture?.let { joinOutcome(CollectionOutcome.NotCollected(it.captureId, "rejected_by_wake")) }
                    ?: Timber.i("voice-capture publish: refused reason=no-capture-context stage=wake-drop")
                Timber.i("[VoicePipeline] wake %s → drop (no grace)", wakeCmp)
                null
            }
            ListeningMode.Off -> null
        }
    }
    private suspend fun transcribeSegment(
        segment: VoiceSegmenterResult.SpeechEnded,
        request: TranscribeRequest,
    ) {
        val b = backend ?: return
        val sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000
        val floatSamples = request.samples
        val playbackReference = playbackBufferProvider?.invoke()
            ?: PlaybackReference(FloatArray(0), sampleRateHz, startPositionMs = null)
        val micPositionMs = segment.captureTimeOfSegmentStartMs()
        if (!utteranceFilter.shouldProcessReference(floatSamples, false, 0, playbackReference, micPositionMs)) {
            Timber.i("[VoicePipeline] → drop (bleed filter)")
            joinOutcome(CollectionOutcome.NotCollected(request.capture?.captureId ?: return, "bleed"))
            return
        }
        val invokedIdentity = verifiedLoadedIdentity(b)
        request.capture?.let { segmentObserver.noteAsrInvocation(it, invokedIdentity) }
        val asrStartedAt = System.currentTimeMillis()
        val asrResult = try {
            b.transcribe(floatSamples, sampleRateHz)
        } catch (ce: java.util.concurrent.CancellationException) {
            request.capture?.let { joinOutcome(CollectionOutcome.AsrCancelled(it.captureId)) }
            throw ce
        } catch (t: Throwable) {
            request.capture?.let { joinOutcome(CollectionOutcome.AsrFailed(it.captureId)) }
            Timber.w(t, "[VoicePipeline] asr provider failed")
            return
        }
        val asrMs = System.currentTimeMillis() - asrStartedAt
        val durationMs = (floatSamples.size * 1000L / sampleRateHz).toInt()
        val transcript = WakeTranscriptTrimmer.commandText(
            result = asrResult,
            wakePositive = request.wakePositive,
            completionSample = request.completionSample,
            sampleRateHz = sampleRateHz,
            utteranceDurationMs = durationMs,
        )
        val trimNote = when {
            !request.wakePositive -> null
            asrResult.text != transcript -> "trim '${asrResult.text}' → '$transcript'"
            else -> null
        }
        if (transcript.isBlank() || isWakeOnly(request)) {
            Timber.i(
                "[VoicePipeline] asr %s %dms lang=%s '%s'%s → drop (%s)",
                b::class.simpleName,
                asrMs,
                asrResult.detectedLanguage ?: "?",
                asrResult.text,
                trimNote?.let { " $it" } ?: "",
                if (request.wakePositive) "wake-only" else "empty",
            )
            if (asrResult.text.isNotBlank()) {
                request.capture?.let {
                    if (transcript.isBlank() && WakeOnlyRoutingEmission.enabled) {
                        joinOutcome(CollectionOutcome.RoutingNone(it.captureId, "wake_only"))
                    }
                    joinOutcome(CollectionOutcome.Recognized(it.captureId, asrResult.text, asrResult.detectedLanguage))
                }
            } else {
                val captureId = request.capture?.captureId ?: run {
                    Timber.i("voice-capture publish: refused reason=no-capture-context stage=asr-empty")
                    return
                }
                joinOutcome(CollectionOutcome.NotCollected(captureId, "empty", asrResult.detectedLanguage))
            }
            return
        }
        if (!hasLetterOrNumber(transcript)) {
            Timber.i(
                "[VoicePipeline] asr %s %dms lang=%s id=%s '%s'%s → drop (non-content)",
                b::class.simpleName,
                asrMs,
                asrResult.detectedLanguage ?: "?",
                request.admittedId,
                transcript,
                trimNote?.let { " $it" } ?: "",
            )
            request.capture?.let {
                joinOutcome(
                    CollectionOutcome.NotCollected(
                        it.captureId,
                        "non_content",
                        asrResult.detectedLanguage,
                        observedText = asrResult.text,
                    ),
                )
            } ?: Timber.i("voice-capture publish: refused reason=no-capture-context stage=non-content")
            return
        }
        request.capture?.let { joinOutcome(CollectionOutcome.Recognized(it.captureId, transcript, asrResult.detectedLanguage)) }
            ?: Timber.i("voice-capture publish: refused reason=no-capture-context stage=recognized")
        val trimmedResult = asrResult.copy(text = transcript)
        val routePrep = prepareRoutingInput(trimmedResult, b)
        routePrep.translation?.let(request.noteTranslation)
        routePrep.translationSkipReason?.let { reason ->
            request.capture?.let { ctx ->
                segmentObserver.noteTranslation(
                    ctx,
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                        model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
                        producedResult = false,
                        translatedText = null,
                        targetLanguage = null,
                        skipReason = reason,
                    ),
                )
            }
        }
        if (routePrep.translationFailed) {
            request.capture?.let { ctx ->
                segmentObserver.noteTranslation(
                    ctx,
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                        model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
                        producedResult = false,
                        translatedText = null,
                        targetLanguage = null,
                        failedWith = "provider_error",
                    ),
                )
            }
        }
        Timber.i(
            "[VoicePipeline] asr %s %dms lang=%s id=%s '%s'%s%s",
            b::class.simpleName,
            asrMs,
            trimmedResult.detectedLanguage ?: "?",
            request.admittedId,
            trimmedResult.text,
            trimNote?.let { " $it" } ?: "",
            routePrep.translateNote?.let { " $it" } ?: "",
        )
        if (routePrep.dropWithError) {
            Timber.i("[VoicePipeline] → drop (%s)", routePrep.translateNote)
            audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            return
        }
        processUtterance(routePrep.input!!, addressed = request.wakePositive, capture = request.capture, admittedId = request.admittedId)
    }
    private fun shortTailOf(frames: List<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame>, skip: Int): ShortArray {
        var seen = 0
        val out = ShortArray((frames.sumOf { it.samples.size } - skip).coerceAtLeast(0))
        var offset = 0
        for (frame in frames) {
            if (seen + frame.samples.size <= skip) {
                seen += frame.samples.size
                continue
            }
            val from = (skip - seen).coerceIn(0, frame.samples.size)
            val len = frame.samples.size - from
            System.arraycopy(frame.samples, from, out, offset, len)
            offset += len
            seen += frame.samples.size
        }
        return out
    }
    private fun joinOutcome(outcome: CollectionOutcome) {
        retentionSink.joinOutcome(outcome)
    }
    internal fun intentCategory(intent: VoiceIntent): String? = PredictedIntentName.of(intent)
    private suspend fun processUtterance(input: IntentRoutingInput, addressed: Boolean, capture: CaptureContext?, admittedId: String) {
        val recognizer = intentRecognizer
        val handler = onIntent ?: run {
            capture?.let { joinOutcome(CollectionOutcome.RoutingFailed(it.captureId, ROUTER_NO_HANDLER)) }
            return
        }
        fun routed(category: String?, fields: Map<String, String> = emptyMap()) {
            if (capture != null) joinOutcome(CollectionOutcome.Routed(capture.captureId, category, fields))
        }
        val ready = recognizer.ensureReady()
        if (ready.isFailure) {
            Timber.e(ready.exceptionOrNull(), "[VoicePipeline] intent not ready")
            capture?.let { joinOutcome(CollectionOutcome.RoutingFailed(it.captureId, ROUTER_UNAVAILABLE)) }
            audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            return
        }
        capture?.let { ctxCap ->
            recognizer.loadedModelRef()?.let { ref ->
                segmentObserver.noteRouterInvocation(ctxCap, ref)
            }
        }
        val t0 = System.currentTimeMillis()
        val ctx = VoiceRecognitionContext(
            listeningMode = currentMode,
            micExposure = micExposureProvider?.invoke() ?: MicExposure.Exposed,
        )
        val windowAtRequest = gracePeriodSignal.capturedGeneration()
        val outcome = recognizer.recognize(input, ctx, admittedId)
        val elapsedMs = System.currentTimeMillis() - t0
        val intent = outcome.intent
        val diagnostic = outcome.diagnostic
        if (intent != null) {
            Timber.i(
                "[VoicePipeline] intent %s %dms ← '%s'",
                intent,
                elapsedMs,
                input.routerTranscript,
            )
            routed(intentCategory(intent))
            pendingExecutionContext = capture
            invokeHandler(
                handler,
                (
                    if (intent is VoiceIntent.CloudRoute) {
                        intent.copy(
                            request = input.sourceTranscript ?: input.routerTranscript,
                            sourceLanguage = input.sourceLanguage?.takeIf { it.isNotBlank() },
                        )
                    } else {
                        intent
                    }
                    )
                    .stampedForWindow(windowAtRequest)
                    .let { it.withLogicalRequest(admittedId) },
            )
            return
        }
        val stage = diagnostic?.failedStage ?: "unknown"
        val reason = diagnostic?.reason ?: "none"
        Timber.i(
            "[VoicePipeline] intent none %dms stage=%s reason=%s ← '%s'",
            elapsedMs,
            stage,
            reason,
            input.routerTranscript,
        )
        capture?.let { joinOutcome(CollectionOutcome.RoutingNone(it.captureId, diagnostic?.reason)) }
        when (CloudEscalationPolicy.decide(reason, addressed)) {
            CloudEscalation.EARCON -> audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            CloudEscalation.SILENT -> Unit
            CloudEscalation.SPEAK_UNROUTED -> {
                val line = SpokenLine.forKey(
                    KEY_CLOUD_UNROUTED,
                    templateResolver,
                    diagnostic?.sourceLanguage,
                )
                if (line.isBlank()) {
                    audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
                } else {
                    audioFeedbackRenderer.render(VoiceResponse.Spoken(line))
                }
            }
            CloudEscalation.SPEAK_ERROR -> audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            CloudEscalation.DISPATCH -> {
                if (!gracePeriodSignal.isCurrent(windowAtRequest)) {
                    Timber.i(
                        "[VoicePipeline] cloud escalation skipped: origin %d is no longer live",
                        windowAtRequest,
                    )
                    audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
                    return
                }
                routed("cloud_escalation")
                pendingExecutionContext = capture
                Timber.i("[VoicePipeline] cloud escalation ← '%s' (reason=%s)", input.routerTranscript, reason)
                invokeHandler(
                    handler,
                    VoiceIntent.CloudRoute(
                        request = input.sourceTranscript ?: input.routerTranscript,
                        sourceLanguage = input.sourceLanguage?.takeIf { it.isNotBlank() },
                        tier = VoiceIntent.CloudTier.Unknown,
                        windowGeneration = windowAtRequest,
                        origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
                        logicalRequestId = admittedId,
                    ),
                )
            }
        }
    }
    private suspend fun prepareRoutingInput(
        trimmed: AsrResult,
        backend: AsrBackend,
    ): RoutePrep {
        val detected = trimmed.detectedLanguage?.lowercase()
        if (backend.capabilities.canTranslateToEnglish) {
            return RoutePrep(
                input = IntentRoutingInput(
                    sourceTranscript = null,
                    sourceLanguage = detected,
                    routerTranscript = trimmed.text,
                    translationKind = TranslationKind.BACKEND,
                ),
                translateNote = "translate=skip(backend)",
                dropWithError = false,
                translationSkipReason = "backend_handles_translation",
            )
        }
        if (detected == null) {
            return RoutePrep(
                input = IntentRoutingInput(
                    sourceTranscript = trimmed.text,
                    sourceLanguage = null,
                    routerTranscript = trimmed.text,
                    translationKind = TranslationKind.NONE,
                ),
                translateNote = "translate=skip(no lang)",
                dropWithError = false,
            )
        }
        if (detected == "en") {
            return RoutePrep(
                input = IntentRoutingInput(
                    sourceTranscript = trimmed.text,
                    sourceLanguage = "en",
                    routerTranscript = trimmed.text,
                    translationKind = TranslationKind.NONE,
                ),
                translateNote = null,
                dropWithError = false,
                translationSkipReason = "source_language_target_language",
            )
        }
        val ready = translationStage.ensureReady(detected)
        if (ready.isFailure) {
            return RoutePrep(
                input = null,
                translateNote = "translate=fail($detected)",
                dropWithError = true,
                translationSkipReason = "translator_unavailable",
            )
        }
        val translated = translationStage.translate(trimmed.text, detected).getOrElse {
            return RoutePrep(
                input = null,
                translateNote = "translate=fail($detected)",
                dropWithError = true,
                translationFailed = true,
            )
        }
        if (translated.isBlank()) {
            return RoutePrep(
                input = null,
                translateNote = "translate=blank($detected)",
                dropWithError = true,
                translationFailed = true,
            )
        }
        if (translated == trimmed.text) {
            return RoutePrep(
                input = null,
                translateNote = "translate=noop($detected)",
                dropWithError = true,
                translationFailed = true,
            )
        }
        return RoutePrep(
            input = IntentRoutingInput(
                sourceTranscript = trimmed.text,
                sourceLanguage = detected,
                routerTranscript = translated,
                translationKind = TranslationKind.PLATFORM,
            ),
            translateNote = "translate=$detected→en '$translated'",
            dropWithError = false,
            translation = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
                producedResult = true,
                translatedText = translated,
                targetLanguage = "en",
            ),
        )
    }
    private fun verifiedLoadedIdentity(backend: AsrBackend): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef = // P1: only the backend's own successful-load evidence counts; a
        backend.loadedModelRef() ?: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None
    private fun requestTranslationProvenance(
        backend: AsrBackend,
        translatedText: String,
    ) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
        model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.Named(
            backend.requiredModel.targetDir,
            backend.requiredModel.files.firstOrNull { it.manifestId.isNotEmpty() }?.manifestId ?: "",
        ),
        producedResult = true,
        translatedText = translatedText,
        targetLanguage = "en",
    )
    private data class RoutePrep(
        val input: IntentRoutingInput?,
        val translateNote: String?,
        val dropWithError: Boolean,
        val translation: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance? = null,
        val translationSkipReason: String? = null,
        val translationFailed: Boolean = false,
    )
    fun stop() {
        segmentObserver.listeningClosed("pipeline-stopped")
        clearInvocationOwnership()
        processingJob?.let { cancelled ->
            unfinished.removeAll { it.isCompleted }
            unfinished.add(cancelled)
            if (cancelled.isActive) {
                cancelledJobs.add(cancelled)
            }
            cancelled.invokeOnCompletion { cause ->
                outcomes[cancelled] = cause ?: CLEAN_COMPLETION
                unfinished.remove(cancelled)
            }
        }
        processingJob?.cancel()
        processingJob = null
        voiceAudioProcessor.stopProcessing()
        backend = null
        Timber.i("[VoicePipeline] engine stopped")
    }
    private fun VoiceIntent.stampedForWindow(generation: Long): VoiceIntent = if (this is VoiceIntent.CloudRoute) copy(windowGeneration = generation) else this
    companion object {
        private val CLEAN_COMPLETION: Throwable = java.util.concurrent.CancellationException("finished cleanly")
        private const val KEY_CLOUD_UNROUTED = "pipeline.unclear_command"
        private const val ROUTER_UNAVAILABLE = "router_unavailable"
        private const val ROUTER_NO_HANDLER = "no_handler"
        internal fun aggregateObservations(observations: List<ExecutionOutcome>): ExecutionOutcome? {
            val distinct = observations.toSet()
            if (distinct.size > 1) {
                Timber.w("[VoicePipeline] execution observations disagree (%s); recording unknown", distinct)
            }
            return if (distinct.size == 1) distinct.single() else null
        }
    }
}
