package au.com.shiftyjelly.pocketcasts.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark.AsrIntentBenchmarkRunner
import au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark.BenchmarkRequest
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Debug-only foreground service that executes the representation benchmark
 * (docs/plans/benchmark/asr-intent-benchmark.md, Item 21).
 *
 * Durability model: this device kills long-running background work, so the
 * run persists EVERY case result as its own flushed JSONL line the moment it
 * is measured, and on restart already-recorded case IDs are skipped. A kill
 * therefore costs at most the in-flight case, not hours.
 *
 * Writes `files/benchmark/results/benchmark_results.jsonl` (one line per
 * case, plus the variant metadata on every line so any subset is
 * self-describing; aggregation happens host-side).
 */
@AndroidEntryPoint
class AsrIntentBenchmarkService : Service() {

    @Inject lateinit var runner: AsrIntentBenchmarkRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.let { runCatching { BenchmarkRequest.fromIntent(it) }.getOrNull() }
        if (request == null) {
            // Sticky restart after a kill carries a null intent: keep the
            // last request so the run resumes where it left off.
            val last = lastRequest
            if (last == null) {
                Timber.e("[AsrIntentBenchmark] service started without a parsable request; stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            return runWith(last)
        }
        return runWith(request)
    }

    private fun runWith(request: BenchmarkRequest): Int {
        lastRequest = request
        startForeground(NOTIFICATION_ID, buildNotification("starting: ${request.variants.joinToString(",")}"))

        scope.launch {
            try {
                runBenchmark(request)
            } catch (e: Exception) {
                Timber.e(e, "[AsrIntentBenchmark] failed")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun runBenchmark(request: BenchmarkRequest) {
        val utterances = runner.loadUtterances(File(request.utterancesPath))
        val resultsFile = resultsFile()
        val doneIds = loadDoneCaseIds(resultsFile)
        Timber.i(
            "[AsrIntentBenchmark] %d utterances, variants=%s warmup=%d measured=%d, resuming %d done case(s)",
            utterances.size,
            request.variants,
            request.warmup,
            request.measured,
            doneIds.size,
        )

        for (variantKey in request.variants) {
            val modelDirName = MODEL_DIRS[variantKey] ?: error("Unknown variant key: $variantKey")
            val variant = when (variantKey) {
                "a" -> AsrIntentBenchmarkRunner.VARIANT_A
                "b" -> AsrIntentBenchmarkRunner.VARIANT_B
                else -> error("Unknown variant key: $variantKey")
            }
            updateNotification("variant $variantKey ($modelDirName): ${utterances.size} utterances")
            val report = runner.runVariant(
                variant = variant,
                modelSourceDir = File(request.modelsDir, modelDirName),
                utterances = utterances,
                warmupIterations = request.warmup,
                measuredIterations = request.measured,
                skipCaseIds = doneIds,
                onCaseResult = { case, release, format ->
                    resultsFile.appendText(
                        caseLine(
                            variantKey = variantKey,
                            variant = variant,
                            modelRelease = release,
                            routerFormat = format,
                            warmupIterations = request.warmup,
                            measuredIterations = request.measured,
                            case = case,
                        ) + "\n",
                    )
                    updateNotification(
                        "variant $variantKey: ${case.caseId} (${case.routerInputFormat})",
                    )
                },
            )
            Timber.i(
                "[AsrIntentBenchmark] variant %s done: %d new case(s), release=%s format=%s",
                variant,
                report.cases.size,
                report.modelRelease,
                report.routerInputFormat,
            )
        }
        Timber.i("[AsrIntentBenchmark] results at %s", resultsFile.absolutePath)
    }

    internal fun resultsFile(): File = File(File(filesDir, "benchmark/results").apply { mkdirs() }, "benchmark_results.jsonl")

    /** Case IDs already persisted, so a restart resumes instead of re-measuring. */
    internal fun loadDoneCaseIds(resultsFile: File): Set<String> = buildSet {
        if (!resultsFile.exists()) return@buildSet
        resultsFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                add(org.json.JSONObject(line).getJSONObject("case").getString("case_id"))
            }
        }
    }

    private fun caseLine(
        variantKey: String,
        variant: String,
        modelRelease: String?,
        routerFormat: String?,
        warmupIterations: Int,
        measuredIterations: Int,
        case: AsrIntentBenchmarkRunner.CaseResult,
    ): String {
        val obj = org.json.JSONObject()
        obj.put("variant_key", variantKey)
        obj.put("variant", variant)
        obj.put("model_release", modelRelease ?: org.json.JSONObject.NULL)
        obj.put("router_input_format", case.routerInputFormat ?: routerFormat ?: org.json.JSONObject.NULL)
        obj.put("warmup_iterations", warmupIterations)
        obj.put("measured_iterations", measuredIterations)
        obj.put("measured_at_epoch_ms", System.currentTimeMillis())
        val c = org.json.JSONObject()
        c.put("case_id", case.caseId)
        c.put("language", case.language)
        c.put("outcome", case.outcome ?: org.json.JSONObject.NULL)
        c.put("router_input_format", case.routerInputFormat ?: org.json.JSONObject.NULL)
        c.put("translate_median_ms", AsrIntentBenchmarkRunner.median(case.translateMs))
        c.put("translate_p95_ms", AsrIntentBenchmarkRunner.percentile95(case.translateMs))
        val stages = org.json.JSONObject()
        case.stageLatencyMs.forEach { (stage, values) ->
            val s = org.json.JSONObject()
            s.put("median_ms", AsrIntentBenchmarkRunner.median(values))
            s.put("p95_ms", AsrIntentBenchmarkRunner.percentile95(values))
            stages.put(stage, s)
        }
        c.put("router_stages", stages)
        c.put("router_total_median_ms", AsrIntentBenchmarkRunner.median(case.totalMs))
        c.put("router_total_p95_ms", AsrIntentBenchmarkRunner.percentile95(case.totalMs))
        c.put(
            "e2e_translate_plus_router_median_ms",
            AsrIntentBenchmarkRunner.median(case.translateMs) + AsrIntentBenchmarkRunner.median(case.totalMs),
        )
        c.put("heap_delta_median_bytes", AsrIntentBenchmarkRunner.median(case.heapDeltaBytes))
        obj.put("case", c)
        return obj.toString()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ASR benchmark", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ASR intent benchmark")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "asr_intent_benchmark"
        private const val NOTIFICATION_ID = 0x2110

        /** Last accepted request, so a sticky restart resumes the same run. */
        @Volatile
        var lastRequest: BenchmarkRequest? = null

        val MODEL_DIRS = mapOf(
            "a" to "english_v1",
            "b" to "dual_v1",
        )
    }
}
