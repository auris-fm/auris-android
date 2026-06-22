package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime

enum class FunctionGemmaBackend {
    GPU,
    CPU,
}

fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

interface FunctionGemmaRuntimeFactory {
    /**
     * Initializes the model runtime and blocks the calling thread. Call from a worker thread.
     */
    fun create(
        modelPath: String,
        cacheDir: String,
        backend: FunctionGemmaBackend,
    ): FunctionGemmaRuntime
}

interface FunctionGemmaRuntime : AutoCloseable {
    val backend: FunctionGemmaBackend

    fun createSession(): FunctionGemmaSession
}

interface FunctionGemmaSession : AutoCloseable {
    fun prefill(text: String)

    fun decode(): String
}
