package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

object WhisperNative {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }

    external fun init(modelPath: String, useGpu: Boolean = false): Boolean

    external fun transcribe(
        modelPath: String,
        pcmData: ShortArray,
        sampleRate: Int,
        useGpu: Boolean = false,
    ): String

    external fun setPipelineCachePath(cachePath: String)

    external fun freeModel()
}
