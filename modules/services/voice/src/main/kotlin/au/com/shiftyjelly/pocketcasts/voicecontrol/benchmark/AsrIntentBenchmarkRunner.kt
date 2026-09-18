package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test seam over model-release installation and format probing. */
internal interface BenchmarkModelInstaller {
    suspend fun install(sourceDir: File)
    fun currentFormat(): String?
    fun currentRelease(): String?
}

/**
 * Device-side per-stage latency driver for the representation benchmark
 * (docs/plans/benchmark/asr-intent-benchmark.md, Item 21).
 *
 * Variant A = shipped path: translate → english_v1 router.
 * Variant B = dual_v1 native+English routing (GO'd candidate).
 *
 * Measurement discipline per spec: W warm-up iterations excluded, M measured
 * iterations, median + p95 per stage, plus a coarse heap-delta memory probe
 * per input format. Model selection is by sideloaded release directory; this
 * class copies the chosen release into the app's live LFM dir and reloads the
 * recognizer. No latest.json, upload channel, or production selector is touched.
 */
@Singleton
class AsrIntentBenchmarkRunner @Inject constructor(
    private val translationStage: TranslationStage,
    private val voiceRecognizer: VoiceRecognizer,
    modelManager: ModelManager,
) {
    internal var modelInstaller: BenchmarkModelInstaller = object : BenchmarkModelInstaller {
        override suspend fun install(sourceDir: File) = installModelRelease(modelManager, sourceDir)
        override fun currentFormat(): String? = modelManager.lfmRouterInputFormat()?.wireName
        override fun currentRelease(): String? = modelManager.lfmReleaseVersion()
    }
    data class Utterance(val caseId: String, val language: String, val text: String)

    data class CaseResult(
        val caseId: String,
        val language: String,
        val translateMs: List<Long>,
        val stageLatencyMs: Map<String, List<Long>>,
        val totalMs: List<Long>,
        val outcome: String?,
        val routerInputFormat: String?,
        val heapDeltaBytes: List<Long>,
        /** null for en cases (translation not involved). Required evidence per pack rule. */
        val translationSuccess: Boolean? = null,
        /** sha-256 of the translated text actually fed to the router (null when no translation). */
        val translatedTextSha256: String? = null,
    )

    data class VariantReport(
        val variant: String,
        val modelRelease: String?,
        val routerInputFormat: String?,
        val warmupIterations: Int,
        val measuredIterations: Int,
        val cases: List<CaseResult>,
    )

    suspend fun loadUtterances(file: File): List<Utterance> = withContext(Dispatchers.IO) {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, line ->
                val obj = org.json.JSONObject(line)
                Utterance(
                    caseId = obj.optString("case_id", "case_$index"),
                    language = obj.getString("language"),
                    text = obj.getString("text"),
                )
            }
    }

    /**
     * Runs one variant over the utterance set against the model release in
     * [modelSourceDir] (a directory with manifest.json + assets, previously
     * sideloaded onto the device). The release is copied into the app's live
     * LFM dir and the recognizer is reloaded before measurement.
     */
    suspend fun runVariant(
        variant: String,
        modelSourceDir: File,
        utterances: List<Utterance>,
        warmupIterations: Int = WARMUP_ITERATIONS,
        measuredIterations: Int = MEASURED_ITERATIONS,
        skipCaseIds: Set<String> = emptySet(),
        onCaseResult: ((CaseResult, modelRelease: String?, routerFormat: String?) -> Unit)? = null,
    ): VariantReport = withContext(Dispatchers.IO) {
        modelInstaller.install(modelSourceDir)
        val ready = voiceRecognizer.ensureReady()
        check(ready.isSuccess) { "Router not ready for variant $variant: ${ready.exceptionOrNull()}" }
        val format = modelInstaller.currentFormat()
        val release = modelInstaller.currentRelease()

        val cases = utterances.filter { it.caseId !in skipCaseIds }.map { utterance ->
            runCase(variant, utterance, warmupIterations, measuredIterations, format).also {
                onCaseResult?.invoke(it, release, format)
            }
        }
        VariantReport(
            variant = variant,
            modelRelease = release,
            routerInputFormat = format,
            warmupIterations = warmupIterations,
            measuredIterations = measuredIterations,
            cases = cases,
        )
    }

    private suspend fun runCase(
        variant: String,
        utterance: Utterance,
        warmupIterations: Int,
        measuredIterations: Int,
        format: String?,
    ): CaseResult {
        val translateMs = mutableListOf<Long>()
        val stageMs = linkedMapOf<String, MutableList<Long>>()
        val totalMs = mutableListOf<Long>()
        val heapDeltas = mutableListOf<Long>()
        var outcome: String? = null
        var translationSuccess: Boolean? = null
        var translatedHash: String? = null

        repeat(warmupIterations + measuredIterations) { iteration ->
            val measured = iteration >= warmupIterations

            // Pipeline input production: translate (timed) when non-English.
            var translated: String? = null
            val t0 = System.currentTimeMillis()
            if (utterance.language != "en") {
                translationStage.ensureReady(utterance.language)
                val translation = translationStage.translate(utterance.text, utterance.language)
                // AND across all non-en iterations: a single fallback means
                // some inputs were native text — the flag must not say true.
                translationSuccess = (translationSuccess ?: true) && translation.isSuccess
                translated = translation.getOrNull()
                translatedHash = translated?.sha256()
            }
            val translateCost = System.currentTimeMillis() - t0
            if (measured) translateMs += translateCost

            val input = buildInput(variant, utterance, translated)

            val heapBefore = currentHeap()
            val result = voiceRecognizer.recognize(input, VoiceRecognitionContext(ListeningMode.Off, MicExposure.Exposed))
            val heapAfter = currentHeap()
            if (measured) heapDeltas += (heapAfter - heapBefore).coerceAtLeast(0)

            val diagnostic = result.diagnostic
            if (diagnostic != null) {
                outcome = diagnostic.finalOutcome
                if (measured) {
                    diagnostic.stageLatencyMs.forEach { (stage, ms) ->
                        stageMs.getOrPut(stage) { mutableListOf() } += ms
                    }
                    totalMs += diagnostic.totalLatencyMs
                }
            }
        }

        return CaseResult(
            caseId = utterance.caseId,
            language = utterance.language,
            translateMs = translateMs,
            stageLatencyMs = stageMs.mapValues { it.value.toList() },
            totalMs = totalMs.toList(),
            outcome = outcome,
            routerInputFormat = format,
            heapDeltaBytes = heapDeltas.toList(),
            translationSuccess = translationSuccess,
            translatedTextSha256 = translatedHash,
        )
    }

    private fun String.sha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildInput(variant: String, utterance: Utterance, translated: String?): IntentRoutingInput {
        val english = translated ?: utterance.text
        return when (variant) {
            VARIANT_A ->
                // translate → english_v1: router sees English only.
                IntentRoutingInput(
                    sourceTranscript = utterance.text,
                    sourceLanguage = utterance.language,
                    routerTranscript = english,
                    translationKind = if (utterance.language == "en") TranslationKind.NONE else TranslationKind.PLATFORM,
                )

            VARIANT_B ->
                // dual_v1: router renders from native text + English transcript.
                IntentRoutingInput(
                    sourceTranscript = utterance.text,
                    sourceLanguage = utterance.language,
                    routerTranscript = english,
                    translationKind = if (utterance.language == "en") TranslationKind.NONE else TranslationKind.PLATFORM,
                )

            else -> error("Unknown benchmark variant: $variant")
        }
    }

    /**
     * Copies the sideloaded release (manifest + assets) over the app's live
     * LFM dir and re-inits the router. Equivalent to a model update, but
     * entirely local: no download, no latest.json touch.
     */
    private suspend fun installModelRelease(modelManager: ModelManager, sourceDir: File) {
        // Gate-open for the measured run: the benchmark app deliberately
        // allows the GO'd dual_v1 candidate (production stays fail-closed).
        modelManager.benchmarkFormatsAllowed = true
        val target = modelManager.lfmDir
        target.mkdirs()
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.copyTo(File(target, file.name), overwrite = true)
            }
        }
        // Benchmark-scoped bypass: the GO'd dual_v1 candidate is installed
        // through the sideload for the measured run only. The production
        // download path keeps dual_v1 fail-closed.
        check(modelManager.isLfmModelReady(allowBenchmarkFormats = true)) { "Sideloaded release in $sourceDir is not a complete LFM release" }
        voiceRecognizer.release()
    }

    private fun currentHeap(): Long = android.os.Debug.getNativeHeapAllocatedSize() + Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    companion object {
        const val VARIANT_A = "a_translate_english_v1"
        const val VARIANT_B = "b_dual_v1_native"
        const val WARMUP_ITERATIONS = 3
        const val MEASURED_ITERATIONS = 10

        fun median(values: List<Long>): Long {
            require(values.isNotEmpty()) { "median of empty list" }
            val sorted = values.sorted()
            return if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }
        }

        fun percentile95(values: List<Long>): Long {
            require(values.isNotEmpty()) { "p95 of empty list" }
            val sorted = values.sorted()
            val rank = (sorted.size * 95 + 99) / 100
            return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
        }
    }
}
