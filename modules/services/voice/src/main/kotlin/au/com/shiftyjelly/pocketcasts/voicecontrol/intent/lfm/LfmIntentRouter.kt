package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

import au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.LfmPrompt
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SlotRepair
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.RouterInputFormat
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class LfmIntentRouter internal constructor(
    private val dialogManager: VoiceDialogManager,
    private val modelManager: ModelManager,
    private val inference: LfmInference,
) : VoiceRecognizer {
    @Inject constructor(
        dialogManager: VoiceDialogManager,
        modelManager: ModelManager,
    ) : this(dialogManager, modelManager, LfmNativeInference)

    private val mutex = Mutex()
    private var loadedRelease: String? = null
    private var loadedFormat: RouterInputFormat? = null

    /** Optional sink for structured local diagnostics (tests / tooling). */
    @Volatile
    var diagnosticSink: ((RouterStageDiagnostic) -> Unit)? = null

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!modelManager.isLfmModelReady()) {
                    return@withContext Result.failure(IllegalStateException("LFM model is unavailable"))
                }
                val release = modelManager.lfmRelease()
                    ?: return@withContext Result.failure(IllegalStateException("LFM manifest release is unavailable"))
                if (!release.routerInputFormat.isReadyForInference) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Unsupported router_input_format: ${release.routerInputFormat.wireName}",
                        ),
                    )
                }
                if (loadedRelease == release.version && loadedFormat == release.routerInputFormat) {
                    return@withContext Result.success(Unit)
                }
                inference.release()
                val loaded = inference.load(
                    modelPath = modelManager.lfmModelFile.absolutePath,
                    classifierPath = modelManager.lfmClassifierFile.absolutePath,
                    labelMapPath = modelManager.lfmLabelMapFile.absolutePath,
                )
                if (!loaded) {
                    return@withContext Result.failure(
                        IllegalStateException(inference.lastError().ifBlank { "LFM native load failed" }),
                    )
                }
                loadedRelease = release.version
                loadedFormat = release.routerInputFormat
                Result.success(Unit)
            } catch (error: Throwable) {
                Timber.e(error, "Failed to initialize LfmIntentRouter")
                Result.failure(error)
            }
        }
    }

    override suspend fun recognize(
        input: IntentRoutingInput,
        context: VoiceRecognitionContext,
    ): VoiceRecognizeResult = withContext(Dispatchers.IO) {
        // Hold one lock across tokenize→classify→generate so KV-cache continuity
        // cannot be poisoned by a concurrent recognize/ensureReady caller.
        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            val release = modelManager.lfmRelease()
            val format = release?.routerInputFormat ?: loadedFormat
            val base = DiagnosticBuilder(
                modelRelease = release?.version ?: loadedRelease,
                quant = release?.quant,
                inputFormat = format?.wireName,
                sourceLanguage = input.sourceLanguage,
                translationKind = input.translationKind.wireName,
                startedAt = startedAt,
            )

            if (input.routerTranscript.isBlank()) {
                return@withContext finish(
                    base.fail(
                        stage = RouterStageDiagnostic.STAGE_BLANK,
                        reason = RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT,
                    ),
                )
            }
            if (loadedRelease == null || format == null || !format.isReadyForInference) {
                Timber.w("LFM router not ready — ensureReady() was not called before recognize()")
                val stage = if (format != null && !format.isReadyForInference) {
                    RouterStageDiagnostic.STAGE_UNSUPPORTED_FORMAT
                } else {
                    RouterStageDiagnostic.STAGE_NOT_READY
                }
                val reason = if (format != null && !format.isReadyForInference) {
                    RouterStageDiagnostic.REASON_UNSUPPORTED_INPUT_FORMAT
                } else {
                    RouterStageDiagnostic.REASON_MODEL_NOT_LOADED
                }
                return@withContext finish(base.fail(stage = stage, reason = reason))
            }

            // english_v1 only: prompt/token span/slot repair use routerTranscript exclusively.
            val routerText = input.routerTranscript
            var classifierLabel: String? = null
            try {
                val prompt = LfmPrompt.render(
                    transcript = routerText,
                    history = dialogManager.promptHistory(),
                )
                val promptTokenIds = inference.tokenize(prompt, addBos = false)
                    ?: return@withContext finish(
                        base.fail(
                            stage = RouterStageDiagnostic.STAGE_TOKENIZE,
                            reason = RouterStageDiagnostic.REASON_TOKENIZE_FAILED,
                        ),
                    )
                val userTokenIds = inference.tokenize(routerText, addBos = false)
                    ?: return@withContext finish(
                        base.fail(
                            stage = RouterStageDiagnostic.STAGE_TOKENIZE,
                            reason = RouterStageDiagnostic.REASON_TOKENIZE_FAILED,
                        ),
                    )
                val (poolStart, poolEnd) = LfmTokenSpan.lastUserTokenSpan(promptTokenIds, userTokenIds)
                val label = inference.classify(promptTokenIds, poolStart, poolEnd)
                    ?: return@withContext finish(
                        base.fail(
                            stage = RouterStageDiagnostic.STAGE_CLASSIFY,
                            reason = RouterStageDiagnostic.REASON_CLASSIFY_FAILED,
                        ),
                    )
                classifierLabel = label
                val (tool, action) = LfmLabel.parse(label)
                if (tool == "no_match") {
                    return@withContext finish(
                        base.fail(
                            stage = RouterStageDiagnostic.STAGE_NO_MATCH,
                            reason = RouterStageDiagnostic.REASON_NO_MATCH,
                            classifierLabel = label,
                        ),
                    )
                }

                val prefill = LfmCallPrefill.render(tool, action)
                val generated = inference.generate(prefill)
                    ?: return@withContext finish(
                        base.fail(
                            stage = RouterStageDiagnostic.STAGE_GENERATE,
                            reason = RouterStageDiagnostic.REASON_GENERATE_FAILED,
                            classifierLabel = label,
                        ),
                    )
                val repaired = SlotRepair.repair(
                    raw = generated,
                    utterance = routerText,
                    tool = tool,
                    action = action,
                ) ?: return@withContext finish(
                    base.fail(
                        stage = RouterStageDiagnostic.STAGE_PARSE_REPAIR,
                        reason = RouterStageDiagnostic.REASON_PARSE_OR_REPAIR_FAILED,
                        classifierLabel = label,
                    ),
                )

                val intent: VoiceIntent? = if (repaired.name == "dialog_control") {
                    dialogManager.resolve(
                        transcript = routerText,
                        generated = generated,
                        call = repaired,
                    )
                } else {
                    dialogManager.resolve(repaired)
                }
                if (intent == null) {
                    return@withContext finish(
                        base.fail(
                            stage = RouterStageDiagnostic.STAGE_MAPPER_DIALOG,
                            reason = RouterStageDiagnostic.REASON_MAPPER_OR_DIALOG_FAILED,
                            classifierLabel = label,
                        ),
                    )
                }
                return@withContext finish(
                    base.success(intent = intent, classifierLabel = label),
                )
            } catch (error: Throwable) {
                Timber.w(error, "LFM inference failed")
                return@withContext finish(
                    base.fail(
                        stage = RouterStageDiagnostic.STAGE_EXCEPTION,
                        reason = RouterStageDiagnostic.REASON_INFERENCE_EXCEPTION,
                        classifierLabel = classifierLabel,
                    ),
                )
            } finally {
                try {
                    inference.reset()
                } catch (resetError: Throwable) {
                    Timber.w(resetError, "LFM reset failed after recognize")
                }
            }
        }
    }

    override fun release() {
        // Same mutex as recognize/ensureReady so teardown cannot free native state mid-decode.
        runBlocking {
            mutex.withLock {
                loadedRelease = null
                loadedFormat = null
                inference.release()
            }
        }
    }

    private fun finish(result: VoiceRecognizeResult): VoiceRecognizeResult {
        result.diagnostic?.let { diagnostic ->
            try {
                diagnosticSink?.invoke(diagnostic)
            } catch (sinkError: Throwable) {
                Timber.w(sinkError, "Router diagnostic sink failed")
            }
            try {
                Timber.i(
                    "[LfmRouter] stage=%s outcome=%s reason=%s label=%s format=%s lang=%s kind=%s release=%s quant=%s %dms",
                    diagnostic.failedStage ?: "ok",
                    diagnostic.finalOutcome,
                    diagnostic.reason ?: "-",
                    diagnostic.classifierLabel ?: "-",
                    diagnostic.inputFormat ?: "-",
                    diagnostic.sourceLanguage ?: "-",
                    diagnostic.translationKind,
                    diagnostic.modelRelease ?: "-",
                    diagnostic.quant ?: "-",
                    diagnostic.totalLatencyMs,
                )
            } catch (logError: Throwable) {
                Timber.w(logError, "Router diagnostic log failed")
            }
        }
        return result
    }

    private class DiagnosticBuilder(
        private val modelRelease: String?,
        private val quant: String?,
        private val inputFormat: String?,
        private val sourceLanguage: String?,
        private val translationKind: String,
        private val startedAt: Long,
    ) {
        fun fail(
            stage: String,
            reason: String,
            classifierLabel: String? = null,
        ): VoiceRecognizeResult = VoiceRecognizeResult(
            intent = null,
            diagnostic = RouterStageDiagnostic(
                modelRelease = modelRelease,
                quant = quant,
                inputFormat = inputFormat,
                sourceLanguage = sourceLanguage,
                translationKind = translationKind,
                classifierLabel = classifierLabel,
                finalOutcome = RouterStageDiagnostic.OUTCOME_NO_INTENT,
                failedStage = stage,
                reason = reason,
                totalLatencyMs = System.currentTimeMillis() - startedAt,
            ),
        )

        fun success(
            intent: VoiceIntent,
            classifierLabel: String,
        ): VoiceRecognizeResult = VoiceRecognizeResult(
            intent = intent,
            diagnostic = RouterStageDiagnostic(
                modelRelease = modelRelease,
                quant = quant,
                inputFormat = inputFormat,
                sourceLanguage = sourceLanguage,
                translationKind = translationKind,
                classifierLabel = classifierLabel,
                finalOutcome = RouterStageDiagnostic.OUTCOME_INTENT,
                failedStage = null,
                reason = null,
                totalLatencyMs = System.currentTimeMillis() - startedAt,
            ),
        )
    }
}
