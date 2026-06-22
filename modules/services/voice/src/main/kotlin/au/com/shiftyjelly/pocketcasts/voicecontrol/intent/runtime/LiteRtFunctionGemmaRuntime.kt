@file:Suppress("ktlint:standard:filename")

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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalApi::class)
class LiteRtFunctionGemmaRuntimeFactory @Inject constructor() : FunctionGemmaRuntimeFactory {
    override fun create(
        modelPath: String,
        cacheDir: String,
        backend: FunctionGemmaBackend,
    ): FunctionGemmaRuntime {
        ExperimentalFlags.enableBenchmark = true
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = when (backend) {
                    FunctionGemmaBackend.GPU -> Backend.GPU()
                    FunctionGemmaBackend.CPU -> Backend.CPU()
                },
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDir,
            ),
        )
        engine.initialize()
        return LiteRtFunctionGemmaRuntime(engine, backend)
    }

    private companion object {
        const val MAX_CONTEXT_TOKENS = 2048
    }
}

private class LiteRtFunctionGemmaRuntime(
    private val engine: Engine,
    override val backend: FunctionGemmaBackend,
) : FunctionGemmaRuntime {
    override fun createSession(): FunctionGemmaSession {
        val sampler = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = 0.0,
            seed = 0,
        )
        return LiteRtFunctionGemmaSession(
            engine.createSession(SessionConfig(samplerConfig = sampler)),
        )
    }

    override fun close() = engine.close()
}

private class LiteRtFunctionGemmaSession(
    private val session: Session,
) : FunctionGemmaSession {
    override fun prefill(text: String) {
        session.runPrefill(listOf(InputData.Text(text)))
    }

    override fun decode(): String = session.runDecode()

    override fun close() = session.close()
}
