package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime.FunctionGemmaBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime.FunctionGemmaRuntimeFactory
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime.PreparedFunctionGemmaSessionPool
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class FunctionGemmaIntentRouter @Inject constructor(
    private val dialogManager: VoiceDialogManager,
    private val modelManager: ModelManager,
    private val runtimeFactory: FunctionGemmaRuntimeFactory,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : VoiceRecognizer {
    private val transitionMutex = Mutex()
    private val stateMutex = Mutex()
    private var activeState: ActiveState? = null

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            transitionMutex.withLock {
                prepareCurrentRelease()
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.e(error, "Failed to initialize FunctionGemmaIntentRouter")
            Result.failure(error)
        }
    }

    override suspend fun recognize(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoiceIntent? = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) return@withContext null
        val state = stateMutex.withLock { activeState } ?: return@withContext null

        try {
            consumeAndResolve(state.pool, transcript)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (state.pool.backend != FunctionGemmaBackend.GPU) {
                invalidatePool(state)
                logRuntimeWarning("FunctionGemma CPU inference failed", error)
                return@withContext null
            }
            recoverOnCpuOnce(state, transcript, error)
        }
    }

    private suspend fun prepareCurrentRelease() {
        check(modelManager.isFunctionGemmaModelReady()) {
            "FunctionGemma model or manifest is unavailable"
        }
        val release = requireNotNull(modelManager.functionGemmaReleaseVersion()) {
            "FunctionGemma manifest release is unavailable"
        }
        val current = stateMutex.withLock { activeState }
        if (current?.release == release) return

        if (current != null) {
            stateMutex.withLock {
                if (activeState === current) activeState = null
            }
            current.pool.close()
        }

        val prepared = createGpuFirstPool()
        stateMutex.withLock {
            activeState = ActiveState(release, prepared)
        }
    }

    private suspend fun createGpuFirstPool(): PreparedFunctionGemmaSessionPool {
        return try {
            createPreparedPool(FunctionGemmaBackend.GPU)
        } catch (error: CancellationException) {
            throw error
        } catch (gpuFailure: Exception) {
            logRuntimeWarning("FunctionGemma GPU initialization failed; using CPU", gpuFailure)
            createPreparedPool(FunctionGemmaBackend.CPU)
        }
    }

    private suspend fun createPreparedPool(
        backend: FunctionGemmaBackend,
    ): PreparedFunctionGemmaSessionPool {
        val runtime = runtimeFactory.create(
            modelPath = modelManager.functionGemmaModelFile.absolutePath,
            cacheDir = modelManager.functionGemmaDir.absolutePath,
            backend = backend,
        )
        val pool = PreparedFunctionGemmaSessionPool(runtime, applicationScope)
        try {
            pool.prepare(FunctionGemmaPrompt.staticPrefix)
            return pool
        } catch (error: Throwable) {
            try {
                pool.close()
            } catch (closeFailure: Throwable) {
                error.addSuppressed(closeFailure)
            }
            throw error
        }
    }

    private suspend fun consumeAndResolve(
        pool: PreparedFunctionGemmaSessionPool,
        transcript: String,
    ): VoiceIntent? {
        return pool.consume { session ->
            val suffix = FunctionGemmaPrompt.requestSuffix(
                transcript = transcript,
                history = dialogManager.promptHistory(),
            )
            check(!suffix.contains("<start_function_declaration>")) {
                "FunctionGemma request suffix contains static declarations"
            }
            session.prefill(suffix)
            val generated = session.decode().trim { it <= ' ' }
            val call = ToolCall.parse(generated) ?: return@consume null
            dialogManager.resolve(transcript, generated, call)
        }.value
    }

    private suspend fun recoverOnCpuOnce(
        failedGpuState: ActiveState,
        transcript: String,
        gpuFailure: Exception,
    ): VoiceIntent? = transitionMutex.withLock {
        logRuntimeWarning("FunctionGemma GPU inference failed; retrying on CPU", gpuFailure)
        val current = stateMutex.withLock { activeState }
        val cpuState = when {
            current?.pool?.backend == FunctionGemmaBackend.CPU -> current

            current !== failedGpuState -> return@withLock null

            else -> {
                stateMutex.withLock {
                    if (activeState === failedGpuState) activeState = null
                }
                failedGpuState.pool.close()
                val cpuPool = try {
                    createPreparedPool(FunctionGemmaBackend.CPU)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logRuntimeWarning("FunctionGemma CPU fallback initialization failed", error)
                    return@withLock null
                }
                ActiveState(failedGpuState.release, cpuPool).also { replacement ->
                    stateMutex.withLock {
                        activeState = replacement
                    }
                }
            }
        }

        try {
            consumeAndResolve(cpuState.pool, transcript)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            invalidatePool(cpuState)
            logRuntimeWarning("FunctionGemma CPU retry failed", error)
            null
        }
    }

    private suspend fun invalidatePool(state: ActiveState) {
        val shouldClose = stateMutex.withLock {
            if (activeState === state) {
                activeState = null
                true
            } else {
                false
            }
        }
        if (shouldClose) state.pool.close()
    }

    private fun logRuntimeWarning(
        message: String,
        error: Throwable,
    ) {
        Timber.w(
            "%s (%s: %s)",
            message,
            error::class.java.simpleName,
            error.message.orEmpty().take(MAX_LOGGED_ERROR_CHARS),
        )
    }

    private data class ActiveState(
        val release: String,
        val pool: PreparedFunctionGemmaSessionPool,
    )

    private companion object {
        const val MAX_LOGGED_ERROR_CHARS = 200
    }
}
