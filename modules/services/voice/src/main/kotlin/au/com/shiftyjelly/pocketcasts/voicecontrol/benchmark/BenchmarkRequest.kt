package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import android.content.Intent

/**
 * Parsed trigger arguments for the Item 21 device latency driver, shared by
 * the adb broadcast receiver and the foreground service that executes the
 * run. The measured run is multi-hour, so the receiver only forwards the
 * request; a foreground service owns execution (a broadcast `goAsync` window
 * cannot legally span the run — it ANRs the app).
 */
data class BenchmarkRequest(
    val utterancesPath: String,
    val modelsDir: String,
    val variants: List<String>,
    val warmup: Int,
    val measured: Int,
) {
    companion object {
        const val ACTION = "fm.auris.debug.ASR_INTENT_BENCHMARK"
        const val EXTRA_UTTERANCES = "utterances"
        const val EXTRA_MODELS_DIR = "models_dir"
        const val EXTRA_VARIANTS = "variants"
        const val EXTRA_WARMUP = "warmup"
        const val EXTRA_MEASURED = "measured"

        fun fromIntent(intent: Intent): BenchmarkRequest {
            val utterances = intent.getStringExtra(EXTRA_UTTERANCES)?.takeIf { it.isNotBlank() }
                ?: error("Missing required extra $EXTRA_UTTERANCES")
            val models = intent.getStringExtra(EXTRA_MODELS_DIR)?.takeIf { it.isNotBlank() }
                ?: error("Missing required extra $EXTRA_MODELS_DIR")
            val variants = intent.getStringExtra(EXTRA_VARIANTS)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("a", "b")
            return BenchmarkRequest(
                utterancesPath = utterances,
                modelsDir = models,
                variants = variants,
                warmup = intent.getStringExtra(EXTRA_WARMUP)?.toIntOrNull()
                    ?: AsrIntentBenchmarkRunner.WARMUP_ITERATIONS,
                measured = intent.getStringExtra(EXTRA_MEASURED)?.toIntOrNull()
                    ?: AsrIntentBenchmarkRunner.MEASURED_ITERATIONS,
            )
        }
    }
}
