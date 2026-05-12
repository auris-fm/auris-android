package au.com.shiftyjelly.pocketcasts.voice.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Microphone capture using Oboe (native C++ via JNI) with callback mode
 * to avoid the AAudio releaseBuffer assertion (Oboe issue #535).
 *
 * Falls back to Android AudioRecord if Oboe is unavailable or fails.
 *
 * Emits [Flow]<[PcmAudioFrame]> at 16kHz / 16-bit PCM / mono — identical output
 * format to the original AudioRecord-based implementation.
 */
@Singleton
class MicrophoneCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        internal const val SAMPLE_RATE_HZ = 16_000
        internal const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        internal const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        internal const val CHANNELS = 1
        internal const val BYTES_PER_SAMPLE = 2
    }

    private var activeEngine: CaptureEngine? = null

    /**
     * Start capturing audio. Tries Oboe first; falls back to AudioRecord.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<PcmAudioFrame> {
        val engine = createEngine()
        activeEngine = engine
        return engine.startCapture()
    }

    /**
     * Stop audio capture and release resources.
     */
    fun stopCapture() {
        activeEngine?.stopCapture()
        activeEngine = null
    }

    /**
     * Check if microphone capture is currently active.
     */
    val isRecording: Boolean
        get() = activeEngine?.isRecording == true

    private fun createEngine(): CaptureEngine {
        return try {
            val engine = OboeCaptureEngine()
            Timber.i("Using OboeCaptureEngine")
            engine
        } catch (e: Exception) {
            Timber.w(e, "Oboe unavailable, falling back to AudioRecord")
            AudioRecordCaptureEngine(context)
        }
    }
}

sealed class MicrophoneCaptureException(message: String) : Exception(message) {
    data class InitializationFailed(override val message: String) : MicrophoneCaptureException(message)
    data class ReadFailed(override val message: String) : MicrophoneCaptureException(message)
    data class CaptureFailed(override val message: String) : MicrophoneCaptureException(message)
}
