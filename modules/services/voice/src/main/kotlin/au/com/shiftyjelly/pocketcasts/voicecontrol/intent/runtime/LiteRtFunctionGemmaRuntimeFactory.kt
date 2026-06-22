package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtFunctionGemmaRuntimeFactory internal constructor(
    private val engineFactory: LiteRtEngineFactory,
    private val benchmarkFlags: LiteRtBenchmarkFlags,
) : FunctionGemmaRuntimeFactory {
    @Inject
    constructor() : this(
        engineFactory = ProductionLiteRtEngineFactory,
        benchmarkFlags = ProductionLiteRtBenchmarkFlags,
    )

    override fun create(
        modelPath: String,
        cacheDir: String,
        backend: FunctionGemmaBackend,
    ): FunctionGemmaRuntime {
        val engine = synchronized(BENCHMARK_FLAG_LOCK) {
            val previousBenchmarkFlag = benchmarkFlags.enabled
            try {
                // LiteRT captures this process-global flag during engine initialization.
                benchmarkFlags.enabled = true
                engineFactory.create(
                    LiteRtEngineConfig(
                        modelPath = modelPath,
                        cacheDir = cacheDir,
                        backend = when (backend) {
                            FunctionGemmaBackend.GPU -> LiteRtBackend.GPU
                            FunctionGemmaBackend.CPU -> LiteRtBackend.CPU
                        },
                        maxNumTokens = MAX_CONTEXT_TOKENS,
                    ),
                ).also(LiteRtEngine::initialize)
            } finally {
                benchmarkFlags.enabled = previousBenchmarkFlag
            }
        }
        return LiteRtFunctionGemmaRuntime(engine, backend)
    }

    private companion object {
        const val MAX_CONTEXT_TOKENS = 2048
        val BENCHMARK_FLAG_LOCK = Any()
    }
}

internal enum class LiteRtBackend {
    GPU,
    CPU,
}

internal data class LiteRtEngineConfig(
    val modelPath: String,
    val cacheDir: String,
    val backend: LiteRtBackend,
    val maxNumTokens: Int,
)

internal data class LiteRtSessionConfig(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int,
)

internal interface LiteRtBenchmarkFlags {
    var enabled: Boolean
}

internal fun interface LiteRtEngineFactory {
    fun create(config: LiteRtEngineConfig): LiteRtEngine
}

internal interface LiteRtEngine : AutoCloseable {
    fun initialize()

    fun createSession(config: LiteRtSessionConfig): LiteRtSession
}

internal interface LiteRtSession : AutoCloseable {
    fun prefill(text: String)

    fun decode(): String
}

@OptIn(ExperimentalApi::class)
private object ProductionLiteRtBenchmarkFlags : LiteRtBenchmarkFlags {
    override var enabled: Boolean
        get() = ExperimentalFlags.enableBenchmark
        set(value) {
            ExperimentalFlags.enableBenchmark = value
        }
}

private object ProductionLiteRtEngineFactory : LiteRtEngineFactory {
    override fun create(config: LiteRtEngineConfig): LiteRtEngine {
        val engine = Engine(
            EngineConfig(
                modelPath = config.modelPath,
                backend = when (config.backend) {
                    LiteRtBackend.GPU -> Backend.GPU()
                    LiteRtBackend.CPU -> Backend.CPU()
                },
                maxNumTokens = config.maxNumTokens,
                cacheDir = config.cacheDir,
            ),
        )
        return ProductionLiteRtEngine(engine)
    }
}

private class ProductionLiteRtEngine(
    private val engine: Engine,
) : LiteRtEngine {
    override fun initialize() = engine.initialize()

    override fun createSession(config: LiteRtSessionConfig): LiteRtSession {
        val sampler = SamplerConfig(
            topK = config.topK,
            topP = config.topP,
            temperature = config.temperature,
            seed = config.seed,
        )
        return ProductionLiteRtSession(
            engine.createSession(SessionConfig(samplerConfig = sampler)),
        )
    }

    override fun close() = engine.close()
}

private class ProductionLiteRtSession(
    private val session: Session,
) : LiteRtSession {
    override fun prefill(text: String) {
        session.runPrefill(listOf(InputData.Text(text)))
    }

    override fun decode(): String = session.runDecode()

    override fun close() = session.close()
}

private class LiteRtFunctionGemmaRuntime(
    private val engine: LiteRtEngine,
    override val backend: FunctionGemmaBackend,
) : FunctionGemmaRuntime {
    private val isClosed = AtomicBoolean()

    override fun createSession(): FunctionGemmaSession {
        val config = LiteRtSessionConfig(
            topK = 1,
            topP = 1.0,
            temperature = 0.0,
            seed = 0,
        )
        return LiteRtFunctionGemmaSession(engine.createSession(config))
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            engine.close()
        }
    }
}

private class LiteRtFunctionGemmaSession(
    private val session: LiteRtSession,
) : FunctionGemmaSession {
    private val isClosed = AtomicBoolean()

    override fun prefill(text: String) = session.prefill(text)

    override fun decode(): String = session.decode()

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            session.close()
        }
    }
}
