package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

interface AsrBackend {
    suspend fun ensureReady(): Result<Unit>

    suspend fun transcribe(samples: FloatArray, sampleRateHz: Int = 16000): AsrResult

    val requiredModel: ModelSpec

    val capabilities: AsrCapabilities

    fun release()

    /**
     * P1 (reviewer be375ea1): the verified loaded model identity established by THIS backend's
     * OWN successful load of content-verified bytes — or null when no such evidence exists.
     * A configured [requiredModel] requirement alone is NOT load evidence. Callers attach the
     * ref only at the actual invocation of THIS backend.
     */
    fun loadedModelRef(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef? = null
}
