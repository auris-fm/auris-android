package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.OpenWakeWordDetector
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device wake word benchmark harness — see core repo
 * docs/specs/wakeword-device-benchmark.md. Driven by
 * training/wakeword/device_benchmark.py, which stages manifest.json plus
 * clips into filesDir/wakeword_bench/ and pulls device_result.json back.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordBenchmark {

    private lateinit var context: Context
    private lateinit var detector: OpenWakeWordDetector
    private lateinit var benchDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        benchDir = File(context.filesDir, "wakeword_bench")
        assertTrue(
            "manifest.json not staged — run device_benchmark.py from training/wakeword",
            File(benchDir, "manifest.json").exists(),
        )
        detector = OpenWakeWordDetector(context)
        assertTrue("wake word detector failed to initialize", detector.isReady)
    }

    @Test
    fun runBenchmark() {
        val manifest = JSONObject(File(benchDir, "manifest.json").readText())
        val clips = manifest.getJSONArray("clips")
        Log.i(TAG, "Scoring ${clips.length()} clips (threshold=${detector.detectionThreshold})")

        val results = JSONArray()
        for (i in 0 until clips.length()) {
            val id = clips.getJSONObject(i).getString("id")
            val result = JSONObject().put("id", id)
            try {
                val samples = readWavMono16k(File(benchDir, "clips/$id"))
                val startNs = SystemClock.elapsedRealtimeNanos()
                val detection = runBlocking { detector.detect(samples, 16000) }
                val detectMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1e6
                result.put("score", detection.confidence.toDouble())
                    .put("detected", detection.detected)
                    .put("detect_ms", detectMs)
                    .put("error", detection.error)
            } catch (e: Exception) {
                Log.w(TAG, "Clip $id failed", e)
                result.put("score", 0.0)
                    .put("detected", false)
                    .put("detect_ms", 0.0)
                    .put("error", true)
            }
            results.put(result)
            if ((i + 1) % 200 == 0) Log.i(TAG, "Scored ${i + 1}/${clips.length()}")
        }

        val output = JSONObject()
            .put("version", 1)
            .put(
                "device",
                JSONObject()
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    .put(
                        "app_version",
                        context.packageManager.getPackageInfo(context.packageName, 0)
                            .versionName ?: "unknown",
                    ),
            )
            .put("threshold", detector.detectionThreshold.toDouble())
            .put(
                "asset_hashes",
                JSONObject().apply {
                    for (asset in OWW_ASSETS) put(asset, sha256OfAsset("oww/$asset"))
                },
            )
            .put("results", results)

        File(benchDir, "device_result.json").writeText(output.toString())
        Log.i(TAG, "Wrote ${results.length()} results to $benchDir/device_result.json")
    }

    private fun sha256OfAsset(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Minimal RIFF/WAVE reader for the benchmark corpus: 16-bit PCM mono 16 kHz. */
    private fun readWavMono16k(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "${file.name}: not a RIFF/WAVE file"
        }
        var pos = 12
        var dataOffset = -1
        var dataSize = 0
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = readLeInt(bytes, pos + 4)
            when (chunkId) {
                "fmt " -> {
                    channels = readLeShort(bytes, pos + 10)
                    sampleRate = readLeInt(bytes, pos + 12)
                    bitsPerSample = readLeShort(bytes, pos + 22)
                }

                "data" -> {
                    dataOffset = pos + 8
                    dataSize = chunkSize
                }
            }
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        require(dataOffset > 0) { "${file.name}: no data chunk" }
        require(channels == 1 && sampleRate == 16000 && bitsPerSample == 16) {
            "${file.name}: expected 16-bit mono 16kHz, got ${bitsPerSample}bit ${channels}ch ${sampleRate}Hz"
        }
        val sampleCount = dataSize / 2
        val samples = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = bytes[dataOffset + 2 * i].toInt() and 0xFF
            val hi = bytes[dataOffset + 2 * i + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort().toInt() / 32768f
        }
        return samples
    }

    private fun readLeInt(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readLeShort(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val TAG = "WakeWordBenchmark"
        private val OWW_ASSETS = listOf(
            "melspectrogram.onnx",
            "embedding_model.onnx",
            "auris.onnx",
            "auris_eval.json",
        )
    }
}
