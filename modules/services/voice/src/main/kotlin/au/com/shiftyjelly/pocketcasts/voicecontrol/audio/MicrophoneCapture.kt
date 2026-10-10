package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.Manifest
import android.content.Context
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
 * Emits [Flow]<[VoiceSegmenterResult]> — VAD is now performed in C++
 * and events are signaled directly from the native processing thread.
 */
@Singleton
class MicrophoneCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        internal const val SAMPLE_RATE_HZ = 16_000
        internal const val CHANNELS = 1
        internal const val BYTES_PER_SAMPLE = 2

        /**
         * Whether libonnxruntime.so is already loaded in this process. Exposed so a test can assert
         * that capture succeeds without wake/embedding/transcriber setup having loaded it first.
         */
        fun isOnnxRuntimeLoaded(): Boolean = OboeNative.nativeIsOrtLoaded()

        /**
         * Frames the native VAD has consumed from capture since it started. Lets a test assert that
         * captured audio actually reaches the VAD, which a liveness check cannot show in silence.
         */
        fun framesConsumed(): Long = OboeNative.nativeGetFramesConsumed()
    }

    private var activeEngine: OboeCaptureEngine? = null

    /**
     * Start capturing audio using Oboe native capture engine.
     * VAD is performed in C++ and emitted as VoiceSegmenterResult events.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<VoiceSegmenterResult> {
        val engine = OboeCaptureEngine(context.assets)
        Timber.i("Using OboeCaptureEngine (event-driven VAD)")
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
