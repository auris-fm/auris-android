package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import timber.log.Timber

/**
 * Microphone capture using Oboe (native C++ via JNI) with callback mode
 * to avoid the AAudio releaseBuffer assertion (Oboe issue #535).
 *
 * Emits [Flow]<[VoiceSegmenterResult]> — VAD is now performed in C++
 * and events are signaled directly from the native processing thread.
 *
 * This is the single owner of the native capture stream and serializes every open/attach/stop
 * under one lock. Native capture is keyed to a generation that changes whenever a stream is
 * opened, so a teardown from a collector that lost ownership (its stream was replaced or
 * released) is a no-op instead of stopping the capture that replaced it.
 */
@Singleton
class MicrophoneCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardware: CaptureHardware,
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
    }

    private val lock = Any()

    /** Monotonic; each successfully opened stream gets the next value. */
    private var nextGeneration = 0L

    /** The generation of the stream that is currently open, or null when none is. */
    private var currentGeneration: Long? = null

    private var activeEngine: OboeCaptureEngine? = null

    /**
     * Attaches the native VAD pipeline to the stream the confirmation path has already opened
     * and started, without opening or starting a stream of its own. Returns false when the
     * attach fails; the caller must then leave the confirmed stream to be closed.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun attachCapture(): Boolean = synchronized(lock) {
        val generation = currentGeneration ?: return false
        val engine = OboeCaptureEngine(
            hardware = hardware,
            assetManager = context.assets,
            generation = generation,
            onFinished = ::onEngineFinished,
        )
        val attached = engine.attachCapture()
        if (attached) {
            activeEngine = engine
            Timber.i("Using OboeCaptureEngine (event-driven VAD) generation=%d", generation)
        } else {
            activeEngine = null
            Timber.w("Oboe capture attach failed")
        }
        attached
    }

    /**
     * Consume VAD events from the capture attached by [attachCapture]. VAD is performed in C++
     * and emitted as VoiceSegmenterResult events.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<VoiceSegmenterResult> {
        val engine = synchronized(lock) { activeEngine } ?: return emptyFlow()
        return engine.captureEvents()
    }

    /**
     * Opens the input stream without starting it, returning the AAudio device id it selected
     * (kUnspecified = 0, or -1 on failure). No audio flows until [startAcquiredStream] is called,
     * so the device can be confirmed before capture begins.
     */
    fun openStreamForAcquisition(): Int = synchronized(lock) {
        val deviceId = hardware.openInputCapture(SAMPLE_RATE_HZ, CHANNELS)
        currentGeneration = if (deviceId >= 0) {
            nextGeneration += 1
            nextGeneration
        } else {
            null
        }
        deviceId
    }

    /** Starts the stream opened by [openStreamForAcquisition]. */
    fun startAcquiredStream(): Boolean = synchronized(lock) { hardware.startInputStream() }

    /** Closes any stream opened by [openStreamForAcquisition]. Idempotent. */
    fun closeAcquiredStream() = synchronized(lock) {
        hardware.closeInputCapture()
        currentGeneration = null
        activeEngine = null
    }

    /**
     * Stop audio capture and release resources.
     */
    fun stopCapture() = synchronized(lock) {
        activeEngine?.dispose()
    }

    /**
     * Check if microphone capture is currently active.
     */
    val isRecording: Boolean
        get() = synchronized(lock) { activeEngine?.isRecording == true }

    /** The engine currently attached; exposed so a test can drive a stale collector's teardown. */
    internal fun currentEngineForTest(): OboeCaptureEngine? = synchronized(lock) { activeEngine }

    /**
     * Reports that an engine's flow finished (or was stopped). Native capture is stopped only
     * while that engine's generation still owns the current stream: a stale collector whose
     * stream was replaced or released must not stop the replacement capture.
     */
    private fun onEngineFinished(engine: OboeCaptureEngine) {
        synchronized(lock) {
            if (activeEngine === engine) {
                activeEngine = null
            }
            if (currentGeneration == engine.generation) {
                currentGeneration = null
                hardware.stopCaptureAndVad()
            }
        }
    }
}

sealed class MicrophoneCaptureException(message: String) : Exception(message) {
    data class InitializationFailed(override val message: String) : MicrophoneCaptureException(message)
    data class ReadFailed(override val message: String) : MicrophoneCaptureException(message)
    data class CaptureFailed(override val message: String) : MicrophoneCaptureException(message)
}
