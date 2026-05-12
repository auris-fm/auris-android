@file:Suppress("DelicateCoroutinesApi")

package au.com.shiftyjelly.pocketcasts.voice.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

@Singleton
class MicrophoneCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        internal const val SAMPLE_RATE_HZ = 16_000
        internal const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        internal const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        internal const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes
        internal val CHANNELS = 1 // Mono
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Use UNPROCESSED source to bypass Android 16 AAudio framework bug where
     * capture+playback streams cause releaseBuffer assertion (mUnreleased out of range).
     * When the AAudio layer applies AEC/AGC/NS to processed sources, it creates additional
     * internal bookkeeping during co-existent capture-playback streams. UNPROCESSED skips
     * all audio processing — fewer AAudio pipeline paths, reducing exposure to the bug.
     * Falls back to VOICE_RECOGNITION (no AGC/noise suppression) if UNPROCESSED unavailable.
     */
    private val audioSource: Int by lazy {
        if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != null) {
            Timber.i("Using UNPROCESSED audio source")
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            Timber.i("UNPROCESSED not supported, falling back to VOICE_RECOGNITION")
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
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
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<PcmAudioFrame> = callbackFlow {
        try {
            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                throw MicrophoneCaptureException.InitializationFailed("Invalid buffer size: $bufferSize")
            }

            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 3,
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw MicrophoneCaptureException.InitializationFailed("AudioRecord not initialized")
            }

            audioRecord?.startRecording()
            Timber.i("Microphone capture started")

            val audioBuffer = ShortArray(bufferSize / BYTES_PER_SAMPLE)

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
