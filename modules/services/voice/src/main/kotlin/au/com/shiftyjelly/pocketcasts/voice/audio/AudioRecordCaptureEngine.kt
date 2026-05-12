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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

/**
 * Fallback capture engine using Android AudioRecord directly.
 *
 * Used when Oboe is unavailable or fails to initialize. Preserves the original
 * UNPROCESSED audio source workaround for the AAudio releaseBuffer bug.
 */
internal class AudioRecordCaptureEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : CaptureEngine {

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioSource: Int by lazy {
        if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != null) {
            Timber.i("Using UNPROCESSED audio source (AudioRecord fallback)")
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            Timber.i("UNPROCESSED not supported, falling back to VOICE_RECOGNITION")
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private var audioRecord: AudioRecord? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startCapture(): Flow<PcmAudioFrame> = callbackFlow {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
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
            Timber.i("AudioRecord fallback capture started")

            val audioBuffer = ShortArray(bufferSize / BYTES_PER_SAMPLE)

            while (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size)
                when {
                    readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                        throw MicrophoneCaptureException.ReadFailed("Invalid operation")
                    }
                    readResult == AudioRecord.ERROR_BAD_VALUE -> {
                        throw MicrophoneCaptureException.ReadFailed("Bad value")
                    }
                    readResult != null && readResult > 0 -> {
                        trySend(PcmAudioFrame(audioBuffer.copyOf(readResult), SAMPLE_RATE_HZ))
                    }
                    else -> { /* no data */ }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "AudioRecord fallback capture error")
            throw MicrophoneCaptureException.CaptureFailed(e.message ?: "Unknown error")
        } finally {
            stopCapture()
        }
    }.flowOn(Dispatchers.IO)

    override fun stopCapture() {
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping AudioRecord capture")
        } finally {
            audioRecord = null
        }
    }

    override val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
}
