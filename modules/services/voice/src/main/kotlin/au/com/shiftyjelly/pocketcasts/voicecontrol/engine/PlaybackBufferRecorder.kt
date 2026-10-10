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

    /** Player-timeline position (ms) of the newest timestamped sample; null until one is written. */
    private var lastPositionMs: Long? = null

    /**
     * Subscribes to the player's sink-submission tap so episode PCM is recorded once, as it is
     * accepted by the sink. Call once per capture session; the returned job is owned by [scope].
     *
     * The chunk's presentation time is carried into the write, so the reference is aligned by the
     * player's own timeline rather than by arrival order. Arrival order is adequate only while one
     * source is writing; episode and cloud answer can interleave, and without the timestamp they
     * would be concatenated in delivery order rather than placed at the position they were played.
     */
    fun start(scope: CoroutineScope): Job {
        tapJob?.cancel()
        return fingerprintPcmTap.chunks
            .onEach { chunk ->
                write(
                    pcm = chunkToFloats(chunk),
                    // The tap's position is seconds of media time; the reference tracks milliseconds.
                    positionMs = (chunk.positionSec * 1_000.0).toLong(),
                )
            }
            .launchIn(scope)
            .also { tapJob = it }
    }

    fun stop() {
        tapJob?.cancel()
        tapJob = null
    }

    /**
     * Retires the reference at a real stop or route change, keeping only the acoustic delay tail.
     *
     * Clearing the whole buffer would drop the tail that legitimately still reaches the microphone
     * just after playback stops; keeping it whole would let a stale reference match later user
     * speech and discard it as bleed. So the newest [DELAY_TAIL_SAMPLES] survive and everything
     * older is retired. The delay window is the same range the correlator searches (50–500 ms), so
     * the tail is the part that could still be heard at the microphone.
     */
    fun retire() {
        // Nothing was ever submitted: there is no tail to keep and no stale audio to retire.
        if (!filled && writePos == 0) return
        val keep = minOf(DELAY_TAIL_SAMPLES, buffer.size, if (filled) buffer.size else writePos)
        val tail = FloatArray(keep)
        // Copy the newest `keep` samples in order, wrapping as needed, then write them back so the
        // buffer contains only the still-plausible tail and the write cursor sits after it.
        for (i in 0 until keep) {
            val from = ((writePos - keep + i) % buffer.size + buffer.size) % buffer.size
            tail[i] = buffer[from]
        }
        buffer.fill(0f)
        tail.copyInto(buffer, 0)
        writePos = if (keep == buffer.size) 0 else keep
        filled = false
    }

    fun write(pcm: FloatArray, positionMs: Long? = null) {
        for (sample in pcm) {
            buffer[writePos] = sample
            writePos = (writePos + 1) % buffer.size
            if (writePos == 0) filled = true
        }
        if (positionMs != null) {
            // The position of the newest sample: the chunk's start plus its duration at 16 kHz.
            lastPositionMs = positionMs + (pcm.size * 1_000L) / SAMPLE_RATE
        }
    }

    /**
     * The player-timeline position of the newest recorded sample, or null when nothing was
     * written with a timestamp. Lets the correlation align a mic segment to a position rather than
     * to buffer offset, which is what holds when sources share the buffer.
     */
    fun lastRecordedPositionMs(): Long? = lastPositionMs

    /**
     * The reference window with the player position of its oldest retained sample.
     *
     * The oldest sample's position is derived from the newest ([lastPositionMs]) minus the window's
     * duration, which is exact for a contiguous window; sources that interleave are placed by their
     * own submission positions, so the span is the range the buffer actually covers. Null when no
     * timestamped source has contributed.
     */
    fun reference(): PlaybackReference {
        val samples = snapshot()
        val end = lastPositionMs
        val start = end?.let { it - (samples.size * 1_000L) / SAMPLE_RATE }
        return PlaybackReference(samples, SAMPLE_RATE, start)
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
         * How much of the reference survives retirement. Matches the correlator's search window
         * upper bound (500 ms): everything older than this cannot still be heard at the mic.
         */
        const val DELAY_TAIL_SAMPLES = SAMPLE_RATE / 2

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
