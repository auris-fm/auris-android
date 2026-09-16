package au.com.shiftyjelly.pocketcasts.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark.BenchmarkRequest
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/**
 * Debug-only adb trigger for the representation benchmark
 * (docs/plans/benchmark/asr-intent-benchmark.md, Item 21).
 *
 * Usage:
 *   adb shell am broadcast \
 *     -a fm.auris.debug.ASR_INTENT_BENCHMARK \
 *     -n au.com.shiftyjelly.pocketcasts/au.com.shiftyjelly.pocketcasts.benchmark.AsrIntentBenchmarkReceiver \
 *     --es utterances <app-accessible>/utterances.jsonl \
 *     --es models_dir <app-accessible>/models \
 *     --es variants a,b \
 *     --es warmup 3 --es measured 10
 *
 * Execution lives in [AsrIntentBenchmarkService]: the official run is
 * multi-hour and a broadcast `goAsync` window ANRs the app, so this receiver
 * only parses the request and hands it to the foreground service.
 *
 * Expects `<models_dir>/<variant-dir>/manifest.json` + assets per variant
 * (sideloaded releases; no network, no latest.json). Writes
 * `files/benchmark/results/benchmark_results.jsonl` and logs the path.
 */
@AndroidEntryPoint
class AsrIntentBenchmarkReceiver @Inject constructor() : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BenchmarkRequest.ACTION) return
        val request = try {
            BenchmarkRequest.fromIntent(intent)
        } catch (e: IllegalStateException) {
            Timber.e(e, "[AsrIntentBenchmark] bad trigger request")
            return
        }
        val service = Intent(context, AsrIntentBenchmarkService::class.java).putExtras(intent.extras ?: android.os.Bundle())
        context.startForegroundService(service)
        Timber.i(
            "[AsrIntentBenchmark] trigger accepted: %d variants, warmup=%d measured=%d",
            request.variants.size,
            request.warmup,
            request.measured,
        )
    }
}
