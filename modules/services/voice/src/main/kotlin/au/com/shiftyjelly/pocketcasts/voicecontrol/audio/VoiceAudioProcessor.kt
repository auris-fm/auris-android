package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.Manifest
import androidx.annotation.RequiresPermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Relays VAD events from the C++ processing thread to downstream consumers.
 *
 * VAD is performed natively by [OboeCaptureEngine] via Silero VAD ONNX inference.
 * The Kotlin coroutine blocks on event signals rather than polling per-frame,
 * allowing the CPU to enter deep idle states during silence.
 */
@Singleton
class VoiceAudioProcessor @Inject constructor(
    private val microphoneCapture: MicrophoneCapture,
) {
    /**
     * Attach the native capture pipeline to the confirmed stream, without opening or starting a
     * stream of its own. The caller owns the stream until this returns true.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun attachCapture(): Boolean {
        return microphoneCapture.attachCapture()
    }

    /**
     * Start processing audio from the attached microphone and emit voice segmenter results.
     *
     * @return Flow of VoiceSegmenterResult objects indicating speech activity
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startProcessing(): Flow<VoiceSegmenterResult> {
        return microphoneCapture.startCapture()
    }

    /**
     * Stop audio processing and release microphone resources.
     */
    fun stopProcessing() {
        microphoneCapture.stopCapture()
        Timber.i("Voice audio processing stopped")
    }

    /**
     * Peek the speech PCM accumulated so far in the native segment buffer: returns the ACTUAL
     * produced sample count plus the samples appended since [previousTotal]. This is the same
     * buffer the completed segment is drained from — no parallel capture, no extra copy beyond
     * the delta read. Zero-producing or unsupported states report (0, empty) honestly.
     */

    fun speechPcmSize(): Int = microphoneCapture.speechPcmSize()

    /** Tail-only drain for admitted collection copies (reserve-before-copy contract). */
    fun drainSpeechTail(out: ShortArray, previousTotal: Int, producedSize: Int): Int = microphoneCapture.drainSpeechTail(out, previousTotal, producedSize)

    /** Drains the producer's speech buffer into [out] (ownership of [out] transfers to caller). */
    fun drainSpeechPcm(out: ShortArray): Int = microphoneCapture.drainSpeechPcm(out)

    fun peekSpeechDelta(previousTotal: Int): Pair<Int, ShortArray> = microphoneCapture.peekSpeechDelta(previousTotal)

    /**
     * Check if audio processing is currently active.
     */
    val isProcessing: Boolean
        get() = microphoneCapture.isRecording
}
