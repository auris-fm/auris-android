package au.com.shiftyjelly.pocketcasts.voice.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.Tensor

/**
 * Fake [InterpreterApi] for Robolectric tests where TFLite native libraries
 * are not available on the JVM. Fills the output with zeros (192-dim embedding).
 */
class FakeInterpreterApi : InterpreterApi {
    private val embeddingDim = 192

    override fun run(input: Any, output: Any) {
        // Expected output type: Array(1) { FloatArray(embeddingDim) }
        @Suppress("UNCHECKED_CAST")
        val outputArray = output as Array<FloatArray>
        if (outputArray.size > 0 && outputArray[0].size == embeddingDim) {
            // Compute a summary of the input so the output is deterministic per-input
            // and non-zero (avoiding degenerate cosine-similarity with zero vectors).
            val sum = when (input) {
                is ByteBuffer -> {
                    input.rewind()
                    val buf = input.asFloatBuffer()
                    var s = 0.0
                    while (buf.hasRemaining()) s += buf.get().toDouble()
                    s
                }
                else -> 0.0
            }
            val base = if (sum == 0.0) 1.0 else sum
            val divisor = 80_000.0
            for (i in outputArray[0].indices) {
                outputArray[0][i] = (base / divisor).toFloat()
            }
        }
    }

    override fun resizeInput(index: Int, shape: IntArray) {
        // No-op for fake interpreter
    }

    override fun resizeInput(index: Int, shape: IntArray, allowResizing: Boolean) {
        // No-op for fake interpreter
    }

    override fun close() {
        // No-op for fake interpreter
    }

    override fun allocateTensors() = Unit
    override fun getInputTensorCount(): Int = 1
    override fun getInputIndex(name: String): Int = 0
    override fun getInputTensor(index: Int): Tensor = throw UnsupportedOperationException()
    override fun getOutputTensorCount(): Int = 1
    override fun getOutputIndex(name: String): Int = 0
    override fun getOutputTensor(index: Int): Tensor = throw UnsupportedOperationException()
    override fun runForMultipleInputsOutputs(inputs: Array<Any>, outputs: MutableMap<Int, Any>) = Unit
    override fun runSignature(
        inputs: Map<String, Any>,
        outputs: Map<String, Any>,
        signatureKey: String?,
    ) = Unit
    override fun runSignature(inputs: Map<String, Any>, outputs: Map<String, Any>) = Unit
    override fun getInputTensorFromSignature(signatureKey: String, inputName: String): Tensor =
        throw UnsupportedOperationException()
    override fun getSignatureKeys(): Array<String> = emptyArray()
    override fun getSignatureInputs(signatureKey: String): Array<String> = emptyArray()
    override fun getSignatureOutputs(signatureKey: String): Array<String> = emptyArray()
    override fun getOutputTensorFromSignature(signatureKey: String, outputName: String): Tensor =
        throw UnsupportedOperationException()
    override fun getLastNativeInferenceDurationNanoseconds(): Long? = null
}
