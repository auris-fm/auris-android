package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Arg parsing for the Item 21 driver entry points (receiver → service).
 * The measured run outgrew the broadcast `goAsync` window (multi-hour run
 * ANR'd the app), so execution moves to a foreground service; both entry
 * points must parse identical extras with identical defaults.
 */
@RunWith(RobolectricTestRunner::class)
class BenchmarkRequestTest {

    private fun intent(block: Intent.() -> Unit = {}): Intent = Intent("fm.auris.debug.ASR_INTENT_BENCHMARK").apply(block)

    @Test
    fun `parses required paths and optional disciplines`() {
        val i = intent {
            putExtra("utterances", "/data/u.jsonl")
            putExtra("models_dir", "/data/models")
            putExtra("variants", "a,b")
            putExtra("warmup", "5")
            putExtra("measured", "7")
        }
        val r = BenchmarkRequest.fromIntent(i)
        assertEquals("/data/u.jsonl", r.utterancesPath)
        assertEquals("/data/models", r.modelsDir)
        assertEquals(listOf("a", "b"), r.variants)
        assertEquals(5, r.warmup)
        assertEquals(7, r.measured)
    }

    @Test
    fun `defaults match the runner disciplines when extras omitted`() {
        val r = BenchmarkRequest.fromIntent(
            intent {
                putExtra("utterances", "/data/u.jsonl")
                putExtra("models_dir", "/data/models")
            },
        )
        assertEquals(listOf("a", "b"), r.variants)
        assertEquals(AsrIntentBenchmarkRunner.WARMUP_ITERATIONS, r.warmup)
        assertEquals(AsrIntentBenchmarkRunner.MEASURED_ITERATIONS, r.measured)
    }

    @Test
    fun `missing required extra fails loudly`() {
        assertThrows(IllegalStateException::class.java) {
            BenchmarkRequest.fromIntent(intent { putExtra("utterances", "/data/u.jsonl") })
        }
    }

    @Test
    fun `blank variants string falls back to both variants`() {
        val r = BenchmarkRequest.fromIntent(
            intent {
                putExtra("utterances", "/u")
                putExtra("models_dir", "/m")
                putExtra("variants", " ")
            },
        )
        assertEquals(listOf("a", "b"), r.variants)
    }

    @Test
    fun `non numeric discipline strings fall back to defaults`() {
        val r = BenchmarkRequest.fromIntent(
            intent {
                putExtra("utterances", "/u")
                putExtra("models_dir", "/m")
                putExtra("warmup", "abc")
                putExtra("measured", "")
            },
        )
        assertNull(null)
        assertEquals(AsrIntentBenchmarkRunner.WARMUP_ITERATIONS, r.warmup)
        assertEquals(AsrIntentBenchmarkRunner.MEASURED_ITERATIONS, r.measured)
    }
}
