package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import androidx.annotation.VisibleForTesting
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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

@Singleton
open class WhisperRecognizer @Inject constructor(
    private val modelFile: File,
) {
    // Override in tests to avoid calling real native code
    @VisibleForTesting
    internal var nativeImpl: ((modelPath: String, pcmData: ShortArray, sampleRate: Int) -> String)? = null

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    suspend fun transcribe(clip: VoiceUtteranceClip): String = withContext(Dispatchers.IO) {
        val totalSamples = clip.frames.sumOf { it.samples.size }
        val allSamples = ShortArray(totalSamples)
        var offset = 0
        for (frame in clip.frames) {
            frame.samples.copyInto(allSamples, offset)
            offset += frame.samples.size
        }
        try {
            val text = nativeImpl?.invoke(modelFile.absolutePath, allSamples, clip.sampleRateHz)
                ?: WhisperNative.transcribe(modelFile.absolutePath, allSamples, clip.sampleRateHz)
            Timber.i("Whisper: '%s'", text)
            text.trim()
        } catch (e: Exception) {
            Timber.e(e, "Whisper transcription failed")
            ""
        }
    }
}
