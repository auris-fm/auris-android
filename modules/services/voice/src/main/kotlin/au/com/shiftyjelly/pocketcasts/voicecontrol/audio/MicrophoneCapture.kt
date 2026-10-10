package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
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

        /**
         * Detector aggregates for capture diagnosis, or null when unavailable.
         *
         * Returns null in a release build, so the diagnostic cannot be reached from a shipped app: the
         * boundary is a build property rather than a runtime flag a caller could flip. The values are a
         * level, a probability and the detector's own state counters — no audio.
         */
        fun diagnostics(): VadDiagnostics? {
            if (!BuildConfig.DEBUG) return null
            val values = OboeNative.nativeGetVadDiagnostics()
            if (values.size < 8) return null
            return VadDiagnostics(
                rms = values[0] / 1000.0,
                score = values[1] / 1000.0f,
                speechFrames = values[2].toInt(),
                silentFrames = values[3].toInt(),
                drainRemaining = values[4].toInt(),
                gatePassed = values[5] == 1L,
                isSpeech = values[6] == 1L,
                speechActive = values[7] == 1L,
            )
        }

        /** Starts or stops collection. Off by default, and inert in a release build. */
        fun setDiagnosticsEnabled(enabled: Boolean) {
            if (!BuildConfig.DEBUG) return
            OboeNative.nativeSetVadDiagnosticsEnabled(enabled)
        }

        /** The detector's own view of the most recent frame. A level and its decision, never audio. */
        data class VadDiagnostics(
            val rms: Double,
            val score: Float,
            val speechFrames: Int,
            val silentFrames: Int,
            val drainRemaining: Int,
            val gatePassed: Boolean,
            val isSpeech: Boolean,
            val speechActive: Boolean,
        )
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
