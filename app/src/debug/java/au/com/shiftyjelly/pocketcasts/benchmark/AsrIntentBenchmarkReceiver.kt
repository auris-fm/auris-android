package au.com.shiftyjelly.pocketcasts.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark.AsrIntentBenchmarkRunner
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Debug-only adb-triggered driver for the representation benchmark
 * (docs/plans/benchmark/asr-intent-benchmark.md, Item 21).
 *
 * Usage:
 *   adb shell am broadcast \
 *     -a fm.auris.debug.ASR_INTENT_BENCHMARK \
 *     -n au.com.shiftyjelly.pocketcasts/au.com.shiftyjelly.pocketcasts.benchmark.AsrIntentBenchmarkReceiver \
 *     --es utterances /sdcard/benchmark/utterances.jsonl \
 *     --es models_dir /sdcard/benchmark/models \
 *     --es variants a,b \
 *     --es warmup 3 --es measured 10
 *
 * Expects `<models_dir>/<variant-dir>/manifest.json` + assets per variant
 * (sideloaded releases; no network, no latest.json). Writes
 * `files/benchmark/results/<timestamp>_<variants>.jsonl` and logs the path.
 */
@AndroidEntryPoint
class AsrIntentBenchmarkReceiver @Inject constructor() : BroadcastReceiver() {

    @Inject lateinit var runner: AsrIntentBenchmarkRunner

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                run(appContext, intent)
            } catch (e: Exception) {
                Timber.e(e, "[AsrIntentBenchmark] failed")
            } finally {
                pending.finish()
            }
        }
    }

    private fun run(context: Context, intent: Intent) = runBlocking {
        val utterancesFile = File(requireArg(intent, EXTRA_UTTERANCES))
        val modelsDir = File(requireArg(intent, EXTRA_MODELS_DIR))
        val variants = (intent.getStringExtra(EXTRA_VARIANTS) ?: "a,b").split(",").map { it.trim() }
        val warmup = intent.getIntExtra(EXTRA_WARMUP, AsrIntentBenchmarkRunner.WARMUP_ITERATIONS)
        val measured = intent.getIntExtra(EXTRA_MEASURED, AsrIntentBenchmarkRunner.MEASURED_ITERATIONS)

        val utterances = runner.loadUtterances(utterancesFile)
        Timber.i("[AsrIntentBenchmark] %d utterances, variants=%s warmup=%d measured=%d", utterances.size, variants, warmup, measured)

        val resultsDir = File(context.filesDir, "benchmark/results").apply { mkdirs() }
        val resultsFile = File(resultsDir, "benchmark_results.jsonl")

        val variantDirs = mapOf(
            "a" to "english_v1",
            "b" to "dual_v1",
        )
        for (variantKey in variants) {
            val modelDirName = variantDirs[variantKey] ?: error("Unknown variant key: $variantKey")
            val variant = when (variantKey) {
                "a" -> AsrIntentBenchmarkRunner.VARIANT_A
                "b" -> AsrIntentBenchmarkRunner.VARIANT_B
                else -> error("Unknown variant key: $variantKey")
            }
            val report = runner.runVariant(
                variant = variant,
                modelSourceDir = File(modelsDir, modelDirName),
                utterances = utterances,
                warmupIterations = warmup,
                measuredIterations = measured,
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

    private fun requireArg(intent: Intent, key: String): String = intent.getStringExtra(key)?.takeIf { it.isNotBlank() } ?: error("Missing required extra $key")

    companion object {
        const val ACTION = "fm.auris.debug.ASR_INTENT_BENCHMARK"
        const val EXTRA_UTTERANCES = "utterances"
        const val EXTRA_MODELS_DIR = "models_dir"
        const val EXTRA_VARIANTS = "variants"
        const val EXTRA_WARMUP = "warmup"
        const val EXTRA_MEASURED = "measured"
    }
}
