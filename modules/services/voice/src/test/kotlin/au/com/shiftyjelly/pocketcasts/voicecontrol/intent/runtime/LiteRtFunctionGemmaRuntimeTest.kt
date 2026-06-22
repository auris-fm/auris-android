package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime

import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiteRtFunctionGemmaRuntimeTest {
    @Test
    fun `build exposes the pinned LiteRT-LM version`() {
        assertEquals("0.13.1", BuildConfig.LITERTLM_VERSION)
    }

    @Test
    fun `factory maps GPU engine configuration and restores benchmark flag`() {
        val benchmarkFlags = FakeLiteRtBenchmarkFlags(enabled = false)
        val engine = FakeLiteRtEngine(isBenchmarkEnabled = { benchmarkFlags.enabled })
        val engineFactory = CapturingLiteRtEngineFactory(engine)
        val factory = LiteRtFunctionGemmaRuntimeFactory(engineFactory, benchmarkFlags)

        val runtime = factory.create(
            modelPath = "/models/function-gemma.litertlm",
            cacheDir = "/models/cache",
            backend = FunctionGemmaBackend.GPU,
        )

        assertEquals(FunctionGemmaBackend.GPU, runtime.backend)
        assertEquals("/models/function-gemma.litertlm", engineFactory.config?.modelPath)
        assertEquals("/models/cache", engineFactory.config?.cacheDir)
        assertEquals(2048, engineFactory.config?.maxNumTokens)
        assertEquals(LiteRtBackend.GPU, engineFactory.config?.backend)
        assertTrue(engine.benchmarkEnabledDuringInitialize)
        assertFalse(benchmarkFlags.enabled)
    }

    @Test
    fun `factory maps CPU backend`() {
        val engineFactory = CapturingLiteRtEngineFactory(FakeLiteRtEngine())
        val factory = LiteRtFunctionGemmaRuntimeFactory(
            engineFactory = engineFactory,
            benchmarkFlags = FakeLiteRtBenchmarkFlags(),
        )

        factory.create("model", "cache", FunctionGemmaBackend.CPU)

        assertEquals(LiteRtBackend.CPU, engineFactory.config?.backend)
    }

    @Test
    fun `factory restores benchmark flag when initialization fails`() {
        val benchmarkFlags = FakeLiteRtBenchmarkFlags(enabled = false)
        val engine = FakeLiteRtEngine(
            isBenchmarkEnabled = { benchmarkFlags.enabled },
            initializeFailure = IllegalStateException("initialization failed"),
        )
        val factory = LiteRtFunctionGemmaRuntimeFactory(
            engineFactory = CapturingLiteRtEngineFactory(engine),
            benchmarkFlags = benchmarkFlags,
        )

        try {
            factory.create("model", "cache", FunctionGemmaBackend.GPU)
            fail("Expected initialization to fail")
        } catch (_: IllegalStateException) {
            assertTrue(engine.benchmarkEnabledDuringInitialize)
            assertFalse(benchmarkFlags.enabled)
        }
    }

    @Test
    fun `session uses deterministic sampler and delegates prefill and decode`() {
        val nativeSession = FakeLiteRtSession(decodeResult = "decoded")
        val engine = FakeLiteRtEngine(session = nativeSession)
        val runtime = LiteRtFunctionGemmaRuntimeFactory(
            engineFactory = CapturingLiteRtEngineFactory(engine),
            benchmarkFlags = FakeLiteRtBenchmarkFlags(),
        ).create("model", "cache", FunctionGemmaBackend.CPU)

        val session = runtime.createSession()
        session.prefill("prompt")

        assertEquals("decoded", session.decode())
        assertEquals("prompt", nativeSession.prefilledText)
        assertEquals(1, engine.sessionConfig?.topK)
        assertEquals(1.0, engine.sessionConfig?.topP)
        assertEquals(0.0, engine.sessionConfig?.temperature)
        assertEquals(0, engine.sessionConfig?.seed)
    }

    @Test
    fun `runtime close is idempotent`() {
        val engine = FakeLiteRtEngine()
        val runtime = LiteRtFunctionGemmaRuntimeFactory(
            engineFactory = CapturingLiteRtEngineFactory(engine),
            benchmarkFlags = FakeLiteRtBenchmarkFlags(),
        ).create("model", "cache", FunctionGemmaBackend.CPU)

        runtime.close()
        runtime.close()

        assertEquals(1, engine.closeCount)
    }

    @Test
    fun `session close is idempotent`() {
        val nativeSession = FakeLiteRtSession()
        val runtime = LiteRtFunctionGemmaRuntimeFactory(
            engineFactory = CapturingLiteRtEngineFactory(FakeLiteRtEngine(session = nativeSession)),
            benchmarkFlags = FakeLiteRtBenchmarkFlags(),
        ).create("model", "cache", FunctionGemmaBackend.CPU)

        val session = runtime.createSession()
        session.close()
        session.close()

        assertEquals(1, nativeSession.closeCount)
    }

    private class CapturingLiteRtEngineFactory(
        private val engine: FakeLiteRtEngine,
    ) : LiteRtEngineFactory {
        var config: LiteRtEngineConfig? = null

        override fun create(config: LiteRtEngineConfig): LiteRtEngine {
            this.config = config
            return engine
        }
    }

    private class FakeLiteRtBenchmarkFlags(
        override var enabled: Boolean = false,
    ) : LiteRtBenchmarkFlags

    private class FakeLiteRtEngine(
        private val session: FakeLiteRtSession = FakeLiteRtSession(),
        private val isBenchmarkEnabled: () -> Boolean = { false },
        private val initializeFailure: RuntimeException? = null,
    ) : LiteRtEngine {
        var benchmarkEnabledDuringInitialize = false
        var sessionConfig: LiteRtSessionConfig? = null
        var closeCount = 0

        override fun initialize() {
            benchmarkEnabledDuringInitialize = isBenchmarkEnabled()
            initializeFailure?.let { throw it }
        }

        override fun createSession(config: LiteRtSessionConfig): LiteRtSession {
            sessionConfig = config
            return session
        }

        override fun close() {
            closeCount++
        }
    }

    private class FakeLiteRtSession(
        private val decodeResult: String = "",
    ) : LiteRtSession {
        var prefilledText: String? = null
        var closeCount = 0

        override fun prefill(text: String) {
            prefilledText = text
        }

        override fun decode(): String = decodeResult

        override fun close() {
            closeCount++
        }
    }
}
