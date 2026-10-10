package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import androidx.media3.common.C
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Rolling buffer of recently-played audio for cross-correlation.
 *
 * It holds the PCm the client actually submitted for playback, so the signal filter can reject
 * playback bleed at the microphone. Three renderers contribute, and this class records each where
 * the client owns the buffer:
 *
 *  - episode playback, via the existing [FingerprintPcmTap] sink-submission tap (see [start]);
 *  - cloud answer audio, written at its `AudioTrack` submission point (`CloudAudioPlayer`);
 *  - local feedback (earcon/TTS), pending a later increment because neither exposes PCM today.
 *
 * Timestamps stay aligned because every source feeds the same single writer; the correlation only
 * needs a bounded alignment window, so a submission reference is sufficient — it does not need to
 * track samples already physically played.
 */
@Singleton
class PlaybackBufferRecorder @Inject constructor(
    private val fingerprintPcmTap: FingerprintPcmTap,
) {
    private val buffer: FloatArray = FloatArray(SAMPLE_RATE * BUFFER_DURATION_SECONDS)
    private var writePos = 0
    private var filled = false

    private var tapJob: Job? = null

    /**
     * Subscribes to the player's sink-submission tap so episode PCM is recorded once, as it is
     * accepted by the sink. Call once per capture session; the returned job is owned by [scope].
     */
    fun start(scope: CoroutineScope): Job {
        tapJob?.cancel()
        return fingerprintPcmTap.chunks
            .onEach { chunk -> write(chunkToFloats(chunk)) }
            .launchIn(scope)
            .also { tapJob = it }
    }

    fun stop() {
        tapJob?.cancel()
        tapJob = null
    }

    fun write(pcm: FloatArray) {
        for (sample in pcm) {
            buffer[writePos] = sample
            writePos = (writePos + 1) % buffer.size
            if (writePos == 0) filled = true
        }
    }

    fun snapshot(): FloatArray {
        if (!filled && writePos == 0) return FloatArray(0)
        val size = if (filled) buffer.size else writePos
        val result = FloatArray(size)
        if (filled) {
            // Wrap: copy from writePos to end, then start to writePos
            val tail = buffer.size - writePos
            buffer.copyInto(result, 0, writePos, buffer.size)
            buffer.copyInto(result, tail, 0, writePos)
        } else {
            buffer.copyInto(result, 0, 0, writePos)
        }
        return result
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val BUFFER_DURATION_SECONDS = 2

        /**
         * Converts a tap chunk to the float samples the reference stores. Mirrors the existing
         * consumer's conversion so both readers of the tap agree on the encoding.
         */
        internal fun chunkToFloats(chunk: FingerprintPcmTap.PcmChunk): FloatArray {
            val buffer = ByteBuffer.wrap(chunk.data).order(ByteOrder.nativeOrder())
            return if (chunk.encoding == C.ENCODING_PCM_FLOAT) {
                val floatBuffer = buffer.asFloatBuffer()
                val out = FloatArray(floatBuffer.remaining())
                floatBuffer.get(out)
                out
            } else {
                val shortBuffer = buffer.asShortBuffer()
                val out = FloatArray(shortBuffer.remaining())
                for (i in out.indices) out[i] = shortBuffer.get(i) / 32768f
                out
            }
        }
    }
}
