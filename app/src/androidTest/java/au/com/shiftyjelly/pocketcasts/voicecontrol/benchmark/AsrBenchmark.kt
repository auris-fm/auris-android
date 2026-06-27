package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperCppBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsrBenchmark {

    private lateinit var modelManager: ModelManager
    private lateinit var audioFixtures: List<AudioFixture>
    private lateinit var context: Context
    private var resultFile: File? = null

    @Before
    fun setUp() {
        Log.i(TAG, "setUp: starting")
        context = ApplicationProvider.getApplicationContext<Context>()
        try {
            au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperNative.freeModel()
            Runtime.getRuntime().gc()
            Thread.sleep(500)
            Log.i(TAG, "setUp: freed whisper.cpp native memory")
        } catch (e: Exception) {
            Log.w(TAG, "setUp: could not free whisper.cpp: ${e.message}")
        }
        modelManager = ModelManager(context)
        audioFixtures = loadAudioFixtures()
        Log.i(TAG, "setUp: loaded ${audioFixtures.size} audio fixtures")
    }

    @After
    fun tearDown() {
        Log.i(TAG, "tearDown")
    }

    @Test
    fun runWhisperCpp() {
        Log.i(TAG, "runWhisperCpp: starting")
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        resultFile = File(context.filesDir, "asr_benchmark_cpp.json")
        logDeviceInfo()
        val results = runBackend("whisper-cpp", { WhisperCppBackend() }, powerManager)
        writeResultJson(results)
        Log.i(TAG, "runWhisperCpp: complete")
    }

    @Test
    fun runWhisperCppVulkan() {
        Log.i(TAG, "runWhisperCppVulkan: starting")
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        resultFile = File(context.filesDir, "asr_benchmark_cpp_vulkan.json")
        logDeviceInfo()
        val results = runBackend("whisper-cpp-vulkan", {
            WhisperCppBackend().also { it.useGpu = true }
        }, powerManager)
        writeResultJson(results)
        Log.i(TAG, "runWhisperCppVulkan: complete")
    }

    private fun runBackend(
        name: String,
        factory: () -> AsrBackend,
        powerManager: PowerManager,
    ): BackendRun {
        Log.i(TAG, "==> Backend: $name")
        val backend = factory()
        if (!downloadModel(backend)) {
            Log.e(TAG, "$name: model download failed, skipping")
            backend.release()
            return BackendRun(name = name, provider = "n/a", engineInitMs = -1, coldFirstMs = -1, requests = emptyList())
        }
        wireModel(backend)

        val initStart = SystemClock.elapsedRealtime()
        val ready = runBlocking { backend.ensureReady() }
        val engineInitMs = SystemClock.elapsedRealtime() - initStart
        if (ready.isFailure) {
            Log.e(TAG, "$name: engine init failed: ${ready.exceptionOrNull()?.message}")
            backend.release()
            return BackendRun(name = name, provider = "n/a", engineInitMs = engineInitMs, coldFirstMs = -1, requests = emptyList())
        }
        Log.i(TAG, "$name: engine ready in ${engineInitMs}ms")

        var coldFirstMs = -1L
        val firstFixture = audioFixtures.firstOrNull()
        if (firstFixture != null) {
            val t0Cold = SystemClock.elapsedRealtime()
            val coldResult = runBlocking { backend.transcribe(firstFixture.samples, firstFixture.sampleRate) }
            coldFirstMs = SystemClock.elapsedRealtime() - t0Cold
            Log.i(TAG, "$name: COLD 1st transcribe=${coldFirstMs}ms rtf=${"%.1f".format(coldFirstMs.toDouble() / firstFixture.durationMs)} text=\"${coldResult.text.take(40)}\"")
        }

        // Warmup
        val warmFixture = audioFixtures.minByOrNull { it.samples.size }
        if (warmFixture != null) {
            repeat(2) {
                runBlocking { backend.transcribe(warmFixture.samples, warmFixture.sampleRate) }
            }
            Log.i(TAG, "$name: warmup done")
        }

        val records = mutableListOf<BenchRecord>()
        for (pass in 0 until BENCHMARK_PASSES) {
            for ((idx, fixture) in audioFixtures.withIndex()) {
                val t0 = SystemClock.elapsedRealtime()
                val result = runBlocking { backend.transcribe(fixture.samples, fixture.sampleRate) }
                val transcribeMs = SystemClock.elapsedRealtime() - t0
                records += BenchRecord(
                    pass = pass,
                    fixtureName = fixture.name,
                    audioFrames = fixture.samples.size,
                    audioDurationMs = fixture.durationMs,
                    transcribeMs = transcribeMs,
                    outputText = result.text,
                    outputTextLen = result.text.length,
                    detectedLanguage = result.detectedLanguage,
                    thermalStatus = getThermalStatus(powerManager),
                )
                Log.i(TAG, "$name: pass=$pass fixture=${fixture.name} audioMs=${fixture.durationMs} transcribeMs=$transcribeMs rtf=${"%.3f".format(transcribeMs.toDouble() / fixture.durationMs)} text=\"${result.text.take(40)}\"")
            }
        }

        backend.release()
        System.gc()

        val totalMs = records.map { it.transcribeMs }.sorted()
        Log.i(TAG, "$name: done. p50=${percentile(totalMs, 50)}ms p95=${percentile(totalMs, 95)}ms min=${totalMs.firstOrNull()}ms max=${totalMs.lastOrNull()}ms mean_rtf=${"%.3f".format(records.map { it.transcribeMs.toDouble() / it.audioDurationMs }.average())}")
        return BackendRun(name = name, provider = name, engineInitMs = engineInitMs, coldFirstMs = coldFirstMs, requests = records)
    }

    private fun wireModel(backend: AsrBackend) {
        val modelDir = File(context.filesDir, backend.requiredModel.targetDir)
        if (backend is WhisperCppBackend) {
            val modelFile = backend.requiredModel.files.firstOrNull()?.let { File(modelDir, it.filename) }
            if (modelFile != null) backend.setModelFile(modelFile)
        }
    }

    private fun downloadModel(backend: AsrBackend): Boolean {
        val spec = backend.requiredModel
        val targetDir = File(context.filesDir, spec.targetDir)
        if (targetDir.exists() && spec.files.all { File(targetDir, it.filename).exists() }) {
            Log.i(TAG, "Model already downloaded: ${spec.targetDir}")
            return true
        }
        Log.i(TAG, "Downloading model: ${spec.targetDir}")
        val result = runBlocking { modelManager.ensureModel(spec) }
        if (result.isFailure) {
            Log.e(TAG, "Model download failed: ${result.exceptionOrNull()?.message}")
            return false
        }
        return true
    }

    private fun loadAudioFixtures(): List<AudioFixture> {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val assetDir = "asr_benchmark"
        val wavFiles = ctx.assets.list(assetDir)?.filter { it.endsWith(".wav") }?.sorted() ?: emptyList()
        assertFalse("No WAV fixtures found in assets/$assetDir", wavFiles.isEmpty())
        return wavFiles.map { filename ->
            val path = "$assetDir/$filename"
            val (samples, sampleRate) = readWavAsset(ctx, path)
            val durationMs = (samples.size * 1000L) / sampleRate
            AudioFixture(name = filename, samples = samples, sampleRate = sampleRate, durationMs = durationMs)
        }
    }

    private fun readWavAsset(context: Context, path: String): Pair<FloatArray, Int> {
        val data = loadAssetBytes(context, path)
        require(String(data, 0, 4) == "RIFF") { "Not a WAV file: $path" }
        require(String(data, 8, 4) == "WAVE") { "Invalid WAV: $path" }
        var pos = 12
        var sampleRate = 16000
        var channels = 1
        var bitsPerSample = 16
        var dataSize = 0
        while (pos + 8 <= data.size) {
            val chunkId = String(data, pos, 4)
            val chunkSize = (data[pos + 4].toInt() and 0xff) or ((data[pos + 5].toInt() and 0xff) shl 8) or ((data[pos + 6].toInt() and 0xff) shl 16) or ((data[pos + 7].toInt() and 0xff) shl 24)
            when (chunkId) {
                "fmt " -> {
                    channels = (data[pos + 10].toInt() and 0xff) or ((data[pos + 11].toInt() and 0xff) shl 8)
                    sampleRate = (data[pos + 12].toInt() and 0xff) or ((data[pos + 13].toInt() and 0xff) shl 8) or ((data[pos + 14].toInt() and 0xff) shl 16) or ((data[pos + 15].toInt() and 0xff) shl 24)
                    bitsPerSample = (data[pos + 22].toInt() and 0xff) or ((data[pos + 23].toInt() and 0xff) shl 8)
                }

                "data" -> {
                    dataSize = chunkSize
                    break
                }
            }
            pos += 8 + chunkSize
        }
        require(dataSize > 0) { "No data chunk: $path" }
        val dataStart = pos + 8
        val nSamples = dataSize / (bitsPerSample / 8) / channels
        val floatSamples = FloatArray(nSamples)
        for (i in 0 until nSamples) {
            val sampleIdx = dataStart + i * 2
            val s = if (sampleIdx + 1 < data.size) {
                ((data[sampleIdx].toInt() and 0xff) or ((data[sampleIdx + 1].toInt() and 0xff) shl 8)).toShort()
            } else {
                0.toShort()
            }
            floatSamples[i] = s.toFloat() / 32768f
        }
        return Pair(floatSamples, sampleRate)
    }

    private fun loadAssetBytes(context: Context, path: String): ByteArray {
        val input: InputStream = context.assets.open(path)
        return input.use { it.readBytes() }
    }

    private fun getThermalStatus(powerManager: PowerManager): Int = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) powerManager.currentThermalStatus else -1

    private fun percentile(sorted: List<Long>, p: Int): Long {
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun logDeviceInfo() {
        Log.i(TAG, "meta device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
        Log.i(TAG, "meta fixtures=${audioFixtures.size} passes=$BENCHMARK_PASSES")
    }

    private fun writeResultJson(run: BackendRun) {
        val json = JSONObject()
        json.put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        json.put("sdk", android.os.Build.VERSION.SDK_INT)
        json.put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(","))
        json.put("name", run.name)
        json.put("provider", run.provider)
        json.put("engine_init_ms", run.engineInitMs)
        json.put("cold_first_ms", run.coldFirstMs)
        json.put("request_count", run.requests.size)
        val ms = run.requests.map { it.transcribeMs }.sorted()
        if (ms.isNotEmpty()) {
            json.put("p50_ms", percentile(ms, 50))
            json.put("p95_ms", percentile(ms, 95))
            json.put("p99_ms", percentile(ms, 99))
            json.put("min_ms", ms.first())
            json.put("max_ms", ms.last())
            json.put("mean_ms", Math.round(ms.average()))
            val rtfs = run.requests.map { it.transcribeMs.toDouble() / it.audioDurationMs }
            json.put("mean_rtf", Math.round(rtfs.average() * 1000.0) / 1000.0)
        }
        val requestArr = JSONArray()
        for (r in run.requests) {
            val reqJson = JSONObject()
            reqJson.put("pass", r.pass)
            reqJson.put("fixture", r.fixtureName)
            reqJson.put("audio_ms", r.audioDurationMs)
            reqJson.put("transcribe_ms", r.transcribeMs)
            reqJson.put("rtf", Math.round(r.transcribeMs.toDouble() / r.audioDurationMs * 1000.0) / 1000.0)
            reqJson.put("output_len", r.outputTextLen)
            reqJson.put("thermal", r.thermalStatus)
            requestArr.put(reqJson)
        }
        json.put("requests", requestArr)
        val jsonStr = json.toString(2)
        Log.i(TAG, "result_json=$jsonStr")
        resultFile?.let { file ->
            try {
                file.writeText(jsonStr)
                Log.i(TAG, "result_file=${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write result file: ${e.message}")
            }
        }
    }

    data class AudioFixture(val name: String, val samples: FloatArray, val sampleRate: Int, val durationMs: Long)
    data class BenchRecord(val pass: Int, val fixtureName: String, val audioFrames: Int, val audioDurationMs: Long, val transcribeMs: Long, val outputText: String, val outputTextLen: Int, val detectedLanguage: String?, val thermalStatus: Int)
    data class BackendRun(val name: String, val provider: String, val engineInitMs: Long, val coldFirstMs: Long, val requests: List<BenchRecord>)

    companion object {
        private const val TAG = "AsrBenchmark"
        private const val BENCHMARK_PASSES = 3
    }
}
