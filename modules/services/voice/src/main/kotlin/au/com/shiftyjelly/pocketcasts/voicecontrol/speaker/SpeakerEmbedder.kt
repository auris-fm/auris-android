package au.com.shiftyjelly.pocketcasts.voicecontrol.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * ONNX speaker embedding model wrapper.
 *
 * 16kHz mono PCM → fbank → ONNX inference → 256-dim L2-normalized embedding.
 * Uses the ONNX Runtime instance already loaded for wake word detection.
 */
@Singleton
class SpeakerEmbedder @Inject constructor() {

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    var threshold: Float = 0.6f
        private set
    private var embeddingDim: Int = 256

    fun load(modelBytes: ByteArray, configJson: String): Boolean {
        return try {
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(modelBytes)

            val config = org.json.JSONObject(configJson)
            threshold = config.getDouble("optimal_threshold").toFloat()
            embeddingDim = config.optInt("embedding_dim", 256)
            Timber.i("Speaker embedding model loaded (dim=%d, threshold=%.3f)", embeddingDim, threshold)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load speaker embedding model")
            false
        }
    }

    val isLoaded: Boolean get() = session != null

    fun embed(audio: FloatArray): FloatArray? {
        val sess = session ?: return null
        val ortEnv = env ?: return null

        return try {
            val fbank = computeFbank(audio)
            val inputName = sess.inputNames.iterator().next()
            val shape = longArrayOf(1, fbank.size.toLong(), fbank[0].size.toLong())
            val tensor = OnnxTensor.createTensor(ortEnv, fbank, shape)
            val result = sess.run(mapOf(inputName to tensor))
            val embedding = (result.first().value as Array<FloatArray>)[0]
            tensor.close()
            result.forEach { it.close() }
            l2Normalize(embedding)
        } catch (e: Exception) {
            Timber.e(e, "Speaker embedding inference failed")
            null
        }
    }

    fun release() {
        session?.close()
        session = null
        env?.close()
        env = null
    }

    private fun computeFbank(audio: FloatArray): Array<FloatArray> {
        val sampleRate = 16000
        val nMel = 80
        val frameLenMs = 25.0f
        val frameShiftMs = 10.0f
        val preemph = 0.97f

        val frameLen = (frameLenMs * sampleRate / 1000).toInt()
        val frameShift = (frameShiftMs * sampleRate / 1000).toInt()

        // Pre-emphasis
        val preemphasized = FloatArray(audio.size)
        preemphasized[0] = audio[0]
        for (i in 1 until audio.size) {
            preemphasized[i] = audio[i] - preemph * audio[i - 1]
        }

        // Frame
        val nFrames = maxOf(0, (preemphasized.size - frameLen) / frameShift + 1)
        val frames = Array(nFrames) {
            val start = it * frameShift
            preemphasized.copyOfRange(start, start + frameLen)
        }

        // Hamming window + FFT magnitude
        val nFft = 1 shl (32 - Integer.numberOfLeadingZeros(frameLen - 1))
        val spec = Array(nFrames) { frame ->
            val windowed = FloatArray(nFft)
            for (i in frame.indices) {
                val w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (frameLen - 1))
                windowed[i] = (frame[i] * w).toFloat()
            }
            fftMagnitude(windowed).sliceArray(0 until nFft / 2 + 1)
        }

        // Mel filterbank
        val melBasis = melFilterbank(nMel, nFft / 2 + 1, sampleRate)
        val fbank = Array(nFrames) { FloatArray(nMel) }
        for (f in spec.indices) {
            for (m in 0 until nMel) {
                var sum = 0.0
                for (k in spec[f].indices) {
                    sum += spec[f][k] * melBasis[m][k]
                }
                fbank[f][m] = Math.log(maxOf(sum, 1e-10)).toFloat()
            }
        }
        return fbank
    }

    private fun fftMagnitude(real: FloatArray): FloatArray {
        val n = real.size
        val result = FloatArray(n)
        // Simple DFT for portability — short frames (400-512 samples) make this fast enough.
        // Replace with FFTJni if profiling shows this is a bottleneck.
        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * Math.PI * t * k / n
                re += real[t] * Math.cos(angle)
                im += real[t] * Math.sin(angle)
            }
            result[k] = Math.sqrt(re * re + im * im).toFloat()
        }
        return result
    }

    private fun melFilterbank(nMels: Int, nFftBins: Int, sampleRate: Int): Array<FloatArray> {
        val lowMel = hzToMel(20.0)
        val highMel = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(nMels + 2) { lowMel + (highMel - lowMel) * it / (nMels + 1) }
        val hzPoints = melPoints.map { melToHz(it) }
        val bins = hzPoints.map { ((nFftBins - 1) * it / (sampleRate / 2.0)).toInt() }

        val filt = Array(nMels) { FloatArray(nFftBins) }
        for (m in 1..nMels) {
            for (k in bins[m - 1] until bins[m]) {
                filt[m - 1][k] = ((k - bins[m - 1]).toFloat() / maxOf(1, bins[m] - bins[m - 1]))
            }
            for (k in bins[m] until bins[m + 1]) {
                filt[m - 1][k] = ((bins[m + 1] - k).toFloat() / maxOf(1, bins[m + 1] - bins[m]))
            }
        }
        return filt
    }

    private fun hzToMel(hz: Double): Double = 1127.0 * Math.log(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.exp(mel / 1127.0) - 1.0)

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x.toDouble()
        norm = Math.sqrt(norm)
        if (norm > 0) {
            for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        }
        return v
    }
}
