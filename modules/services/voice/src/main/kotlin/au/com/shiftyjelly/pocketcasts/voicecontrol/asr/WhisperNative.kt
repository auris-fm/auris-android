package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

object WhisperNative {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }

    external fun transcribe(
        modelPath: String,
        pcmData: ShortArray,
        sampleRate: Int,
    ): String
}
