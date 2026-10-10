package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.res.AssetManager
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

    // Atomic capture + VAD start (single mutex scope — prevents race with close).
    // Passes AssetManager so the Silero VAD ONNX session can be initialized.
    external fun nativeStartCaptureAndVad(sampleRate: Int, channels: Int, assetManager: AssetManager): Boolean

    // Atomic capture + VAD stop
    external fun nativeStopCaptureAndVad()

    external fun nativeIsCapturing(): Boolean

    /**
     * Whether libonnxruntime.so is already loaded in this process. Lets a test assert that capture
     * succeeds without wake/embedding/transcriber setup having run first.
     */
    external fun nativeIsOrtLoaded(): Boolean

    /** Frames the VAD has consumed from capture since start, for reachability diagnostics. */
    external fun nativeGetFramesConsumed(): Long

    /**
     * Aggregate detector diagnosis, or null when diagnostics are off. Order matches the native
     * contract: rms x1000, score x1000, speechFrames, silentFrames, drainRemaining, then gatePassed,
     * isSpeech and speechActive as 0/1. Aggregates only; no audio crosses this boundary.
     */
    external fun nativeGetVadDiagnostics(): LongArray
    external fun nativeSetVadDiagnosticsEnabled(enabled: Boolean)
    external fun nativeWaitForVadEvent(timeoutMs: Int): Int
    external fun nativeGetSpeechPcm(buffer: ShortArray): Int
    external fun nativeGetSpeechPcmSize(): Int
    external fun nativeGetSpeechOnsetSample(): Int
    external fun nativeGetSpeechEndSample(): Int
}

/** Configuration constants shared between Kotlin capture loop and native code. */
internal object OboeConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    const val FRAMES_PER_POLL = 1024 // 64ms at 16kHz
}

/**
 * Capture engine using Oboe native library via JNI.
 *
 * The Oboe audio callback writes samples into a native lock-free ring buffer.
 * A C++ VAD processing thread consumes that buffer, runs Silero VAD inference,
 * and signals speech events. A Kotlin coroutine blocks on these events and
 * emits [VoiceSegmenterResult] to downstream consumers.
 *
 * All JNI calls are made from the single collector coroutine context.
 */
internal class OboeCaptureEngine(
    private val assetManager: AssetManager,
) {

    @Volatile
    private var disposed = false

    fun startCapture(): Flow<VoiceSegmenterResult> = flow {
        if (!OboeNative.nativeStartCaptureAndVad(OboeConfig.SAMPLE_RATE_HZ, OboeConfig.CHANNELS, assetManager)) {
            throw MicrophoneCaptureException.InitializationFailed("Oboe stream creation failed")
        }

        try {
            while (currentCoroutineContext().isActive && !disposed) {
                when (val event = OboeNative.nativeWaitForVadEvent(500)) {
                    1 -> emit(VoiceSegmenterResult.SpeechStarted)

                    2 -> {
                        val totalSamples = OboeNative.nativeGetSpeechPcmSize()
                        if (totalSamples > 0) {
                            val buffer = ShortArray(totalSamples)
                            val copied = OboeNative.nativeGetSpeechPcm(buffer)
                            if (copied > 0) {
                                val speechOnsetSample = OboeNative.nativeGetSpeechOnsetSample()
                                // 0 means the segmenter does not report it, which the engine reads as
                                // unknown — so unlike onset, 0 is a legal value here and must not throw.
                                val speechEndSample = OboeNative.nativeGetSpeechEndSample()
                                require(speechEndSample in 0..copied) {
                                    "Native VAD speech end $speechEndSample outside 0..$copied"
                                }
                                require(speechOnsetSample in 0 until copied) {
                                    "Native VAD speech onset $speechOnsetSample outside 0 until $copied"
                                }
                                val frames = mutableListOf<PcmAudioFrame>()
                                var offset = 0
                                while (offset < copied) {
                                    val frameSize = minOf(OboeConfig.FRAMES_PER_POLL, copied - offset)
                                    val frameSamples = buffer.copyOfRange(offset, offset + frameSize)
                                    frames.add(PcmAudioFrame(frameSamples, OboeConfig.SAMPLE_RATE_HZ))
                                    offset += frameSize
                                }
                                emit(
                                    VoiceSegmenterResult.SpeechEnded(
                                        frames = frames,
                                        speechOnsetSample = speechOnsetSample,
                                        speechEndSample = speechEndSample,
                                    ),
                                )
                            }
                        }
                    }

                    0 -> { /* timeout */ }

                    -1 -> break
                }
            }
        } finally {
            OboeNative.nativeStopCaptureAndVad()
            disposed = true
        }
    }.flowOn(Dispatchers.IO)

    fun stopCapture() {
        disposed = true
    }

    val isRecording: Boolean
        get() = !disposed && OboeNative.nativeIsCapturing()
}
