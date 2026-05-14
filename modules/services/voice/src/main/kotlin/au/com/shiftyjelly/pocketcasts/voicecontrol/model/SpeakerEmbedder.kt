package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.InterpreterApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeakerEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val MODEL_FILE = "speaker_embed.tflite"
        private const val MAX_SAMPLES = 80000 // 5s @ 16kHz
        private const val EMBEDDING_DIM = 192
    }

    private var interpreter: InterpreterApi? = null

    fun load(): Boolean {
        return try {
            val modelBytes = loadModelBytes()
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelBytes)
                flip()
            }
            interpreter = InterpreterApi.create(modelBuffer, InterpreterApi.Options())
            Timber.i("Speaker embedding model loaded")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load speaker embedding model")
            false
        }
    }

    private fun loadModelBytes(): ByteArray {
        // Primary: load from Android assets (production)
        try {
            return context.assets.open(MODEL_FILE).use { it.readBytes() }
        } catch (_: Exception) {
            // Fallback: load from classpath (JVM tests / Robolectric)
        }
        return javaClass.classLoader
            ?.getResourceAsStream(MODEL_FILE)
            ?.use { it.readBytes() }
            ?: throw java.io.FileNotFoundException("Model not found: $MODEL_FILE")
    }

    fun embed(audio: FloatArray): FloatArray? {
        val interp = interpreter ?: run {
            if (!load()) return null
            interpreter
        } ?: return null

        // Center-crop to MAX_SAMPLES if longer, otherwise pass as-is
        val input = if (audio.size > MAX_SAMPLES) {
            val offset = (audio.size - MAX_SAMPLES) / 2
            audio.copyOfRange(offset, offset + MAX_SAMPLES)
        } else {
            audio
        }

        // Resize input to match actual audio length (model has dynamic shape support)
        interp.resizeInput(0, intArrayOf(1, input.size))

        val inputBuffer = ByteBuffer.allocateDirect(4 * input.size).apply {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().put(input)
        }

        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interp.run(inputBuffer, output)
        return output[0]
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
