package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime.FunctionGemmaBackend
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

data class FunctionGemmaInferenceMetrics(
    val backend: FunctionGemmaBackend,
    val modelRelease: String,
    val sessionWaitMs: Long,
    val requestPrefillMs: Long,
    val decodeMs: Long,
    val parseResolveMs: Long,
    val totalMs: Long,
    val inputCharacters: Int,
    val outputCharacters: Int,
    val fallbackReason: String?,
)

interface FunctionGemmaMetrics {
    fun prepared(
        backend: FunctionGemmaBackend,
        modelRelease: String,
        engineInitMs: Long,
        sessionCreateMs: Long,
        staticPrefillMs: Long,
    )

    fun inference(metrics: FunctionGemmaInferenceMetrics)

    fun backendFallback(reason: String, error: Throwable)
}

@Singleton
class TimberFunctionGemmaMetrics @Inject constructor() : FunctionGemmaMetrics {
    override fun prepared(
        backend: FunctionGemmaBackend,
        modelRelease: String,
        engineInitMs: Long,
        sessionCreateMs: Long,
        staticPrefillMs: Long,
    ) {
        Timber.i(
            "FunctionGemma prepared backend=%s model=%s runtime=%s engineInitMs=%d " +
                "sessionCreateMs=%d staticPrefillMs=%d",
            backend,
            modelRelease,
            BuildConfig.LITERTLM_VERSION,
            engineInitMs,
            sessionCreateMs,
            staticPrefillMs,
        )
    }

    override fun inference(metrics: FunctionGemmaInferenceMetrics) {
        Timber.i("FunctionGemma inference metrics=%s", metrics)
    }

    override fun backendFallback(reason: String, error: Throwable) {
        Timber.w(
            "FunctionGemma fallback reason=%s error=%s message=%s",
            reason,
            error::class.java.simpleName,
            error.message.orEmpty().take(MAX_LOGGED_ERROR_CHARS),
        )
    }

    private companion object {
        const val MAX_LOGGED_ERROR_CHARS = 200
    }
}
