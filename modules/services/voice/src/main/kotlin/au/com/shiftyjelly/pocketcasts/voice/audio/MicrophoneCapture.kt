@file:Suppress("DelicateCoroutinesApi")

package au.com.shiftyjelly.pocketcasts.voice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

@Singleton
class MicrophoneCapture @Inject constructor() {
    companion object {
        internal const val SAMPLE_RATE_HZ = 16_000
        internal const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        internal const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        internal const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes
        internal val CHANNELS = 1 // Mono
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int = 0

    /**
     * Start capturing audio from the microphone and emit PCM audio frames as a Flow.
     * Each frame contains the raw audio samples that can be processed by the voice segmenter.
     *
     * @return Flow of PcmAudioFrame objects containing audio samples
     * @throws MicrophoneCaptureException if audio capture initialization fails
     */
    fun startCapture(): Flow<PcmAudioFrame> = callbackFlow {
        try {
            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                throw MicrophoneCaptureException.InitializationFailed("Invalid buffer size: $bufferSize")
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2, // Double buffer for smoother capture
            ).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) {
                    throw MicrophoneCaptureException.InitializationFailed("AudioRecord not initialized")
                }
                it.startRecording()
                Timber.i("Microphone capture started")
            }

            val audioBuffer = ShortArray(bufferSize / BYTES_PER_SAMPLE)

            run loop@{
                while (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size)
                    when {
                        readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                            Timber.e("Invalid operation during audio capture")
                            throw MicrophoneCaptureException.ReadFailed("Invalid operation")
                        }

                        readResult == AudioRecord.ERROR_BAD_VALUE -> {
                            Timber.e("Bad value during audio capture")
                            throw MicrophoneCaptureException.ReadFailed("Bad value")
                        }

                        readResult != null && readResult > 0 -> {
                            val samples = audioBuffer.copyOf(readResult)
                            val frame = PcmAudioFrame(
                                samples = samples,
                                sampleRateHz = SAMPLE_RATE_HZ,
                            )
                            trySend(frame)
                        }

                        else -> {
                            Timber.w("No audio data read: $readResult")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during microphone capture")
            throw MicrophoneCaptureException.CaptureFailed(e.message ?: "Unknown error")
        } finally {
            stopCapture()
            close()
            Timber.i("Microphone capture stopped")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stop audio capture and release AudioRecord resources.
     */
    fun stopCapture() {
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
            Timber.i("AudioRecord released")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping microphone capture")
        } finally {
            audioRecord = null
        }
    }

    /**
     * Check if microphone capture is currently active.
     */
    val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
}

sealed class MicrophoneCaptureException(message: String) : Exception(message) {
    data class InitializationFailed(override val message: String) : MicrophoneCaptureException(message)
    data class ReadFailed(override val message: String) : MicrophoneCaptureException(message)
    data class CaptureFailed(override val message: String) : MicrophoneCaptureException(message)
}
