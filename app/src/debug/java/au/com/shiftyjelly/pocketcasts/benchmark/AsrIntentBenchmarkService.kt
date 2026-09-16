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
 * The official measured run is multi-hour, which exceeds the broadcast
 * `goAsync` window (receiver ANRs → process killed). The receiver therefore
 * only forwards the parsed [BenchmarkRequest]; this service owns execution
 * under a dataSync foreground notification and stops itself when done.
 *
 * Writes `files/benchmark/results/benchmark_results.jsonl` (one line per
 * variant report; chunked runs simply append).
 */
@AndroidEntryPoint
class AsrIntentBenchmarkService : Service() {

    @Inject lateinit var runner: AsrIntentBenchmarkRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.let { runCatching { BenchmarkRequest.fromIntent(it) }.getOrNull() }
        if (request == null) {
            Timber.e("[AsrIntentBenchmark] service started without a parsable request; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

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
        return START_NOT_STICKY
    }

    private suspend fun runBenchmark(request: BenchmarkRequest) {
        val utterances = runner.loadUtterances(File(request.utterancesPath))
        Timber.i(
            "[AsrIntentBenchmark] %d utterances, variants=%s warmup=%d measured=%d",
            utterances.size,
            request.variants,
            request.warmup,
            request.measured,
        )

        val resultsDir = File(filesDir, "benchmark/results").apply { mkdirs() }
        val resultsFile = File(resultsDir, "benchmark_results.jsonl")

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
            )
            resultsFile.appendText(reportToJsonl(report) + "\n")
            Timber.i("[AsrIntentBenchmark] variant %s done: release=%s format=%s", variant, report.modelRelease, report.routerInputFormat)
        }
        Timber.i("[AsrIntentBenchmark] results at %s", resultsFile.absolutePath)
    }

    private fun reportToJsonl(report: AsrIntentBenchmarkRunner.VariantReport): String {
        val obj = org.json.JSONObject()
        obj.put("variant", report.variant)
        obj.put("model_release", report.modelRelease ?: org.json.JSONObject.NULL)
        obj.put("router_input_format", report.routerInputFormat ?: org.json.JSONObject.NULL)
        obj.put("warmup_iterations", report.warmupIterations)
        obj.put("measured_iterations", report.measuredIterations)
        val cases = org.json.JSONArray()
        report.cases.forEach { case ->
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
            cases.put(c)
        }
        obj.put("cases", cases)
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

        val MODEL_DIRS = mapOf(
            "a" to "english_v1",
            "b" to "dual_v1",
        )
    }
}
