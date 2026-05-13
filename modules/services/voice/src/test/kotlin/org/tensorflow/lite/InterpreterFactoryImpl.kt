package org.tensorflow.lite

import au.com.shiftyjelly.pocketcasts.voice.model.FakeInterpreterApi
import java.io.File
import java.nio.ByteBuffer
import org.tensorflow.lite.nnapi.NnApiDelegate

/**
 * Fake [InterpreterFactoryApi] implementation that [TensorFlowLite] discovers
 * via `Class.forName("org.tensorflow.lite.InterpreterFactoryImpl")` on the
 * JVM classpath. Creates a [FakeInterpreterApi] that does not require native
 * TFLite libraries (unavailable in Robolectric tests).
 */
class InterpreterFactoryImpl : InterpreterFactoryApi {
    override fun create(model: File, options: InterpreterApi.Options): InterpreterApi {
        return FakeInterpreterApi()
    }

    override fun create(model: ByteBuffer, options: InterpreterApi.Options): InterpreterApi {
        return FakeInterpreterApi()
    }

    override fun runtimeVersion(): String = "test"
    override fun schemaVersion(): String = "test"

    override fun createNnApiDelegateImpl(options: NnApiDelegate.Options): NnApiDelegate.PrivateInterface? = null
}
