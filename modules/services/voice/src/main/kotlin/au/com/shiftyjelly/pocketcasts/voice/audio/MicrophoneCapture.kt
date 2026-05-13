package au.com.shiftyjelly.pocketcasts.voice.audio

import android.Manifest
import androidx.annotation.RequiresPermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Microphone capture using Oboe (native C++ via JNI) with callback mode
 * to avoid the AAudio releaseBuffer assertion (Oboe issue #535).
 *
 * Emits [Flow]<[PcmAudioFrame]> at 16kHz / 16-bit PCM / mono.
 */
@Singleton
class MicrophoneCapture @Inject constructor() {
    companion object {
        internal const val SAMPLE_RATE_HZ = 16_000
        internal const val CHANNELS = 1
        internal const val BYTES_PER_SAMPLE = 2
    }

    private var activeEngine: OboeCaptureEngine? = null

    /**
     * Start capturing audio using Oboe native capture engine.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<PcmAudioFrame> {
        val engine = OboeCaptureEngine()
        Timber.i("Using OboeCaptureEngine")
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
}

sealed class MicrophoneCaptureException(message: String) : Exception(message) {
    data class InitializationFailed(override val message: String) : MicrophoneCaptureException(message)
    data class ReadFailed(override val message: String) : MicrophoneCaptureException(message)
    data class CaptureFailed(override val message: String) : MicrophoneCaptureException(message)
}
