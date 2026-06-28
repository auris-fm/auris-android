package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * Namespacing object for JNI native function declarations.
 *
 * Loads the pocketcasts_voice_capture native library at class initialization time.
 */
internal object OboeNative {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
        Timber.i("Oboe native library loaded")
    }

    external fun nativeStartCapture(sampleRate: Int, channels: Int): Boolean
    external fun nativeReadAudioData(buffer: ShortArray): Int
    external fun nativeAudioWaitForData(timeoutMs: Int): Boolean
    external fun nativeStopCapture()
    external fun nativeCloseCapture()
    external fun nativeIsCapturing(): Boolean
}

/** Configuration constants shared between Kotlin polling loop and native code. */
internal object OboeConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    const val FRAMES_PER_POLL = 1024 // 64ms at 16kHz
}

/**
 * Capture engine using Oboe native library via JNI.
 *
 * The Oboe audio callback writes samples into a native lock-free ring buffer.
 * A Kotlin coroutine polls this buffer and emits [Flow]<[PcmAudioFrame]>.
 *
 * All JNI calls are made from the single collector coroutine context.
 */
internal class OboeCaptureEngine {

    @Volatile
    private var disposed = false

    fun startCapture(): Flow<PcmAudioFrame> = flow {
        if (!OboeNative.nativeStartCapture(OboeConfig.SAMPLE_RATE_HZ, OboeConfig.CHANNELS)) {
            throw MicrophoneCaptureException.InitializationFailed("Oboe stream creation failed")
        }

        val buffer = ShortArray(OboeConfig.FRAMES_PER_POLL * OboeConfig.CHANNELS)
        val channels = OboeConfig.CHANNELS

        try {
            while (currentCoroutineContext().isActive && !disposed) {
                if (OboeNative.nativeAudioWaitForData(100)) {
                    val framesRead = OboeNative.nativeReadAudioData(buffer)
                    if (framesRead > 0) {
                        val samples = buffer.copyOf(framesRead * channels)
                        emit(PcmAudioFrame(samples, OboeConfig.SAMPLE_RATE_HZ))
                    }
                } else if (!OboeNative.nativeIsCapturing()) {
                    break
                }
            }
        } finally {
            OboeNative.nativeStopCapture()
            OboeNative.nativeCloseCapture()
            disposed = true
        }
    }.flowOn(Dispatchers.IO)

    fun stopCapture() {
        disposed = true
    }

    val isRecording: Boolean
        get() = !disposed && OboeNative.nativeIsCapturing()
}
