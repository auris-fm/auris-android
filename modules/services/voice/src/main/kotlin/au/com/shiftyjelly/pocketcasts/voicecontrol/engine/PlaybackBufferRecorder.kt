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
 * THE POSITION EACH SOURCE SUPPLIES, because they are not all the same quantity and the difference
 * decides whether two of them can be compared:
 *
 * ```
 * episode        PcmChunk.positionSec          MEDIA position      (PositionAxis.Media)
 * cloud answer   AudioTrack.getTimestamp        a MONOTONIC TIME    (PositionAxis.Monotonic)
 * earcon         none                           no position at all
 * TTS            none                           no position at all
 * capture (mic)  getTimestamp(CLOCK_MONOTONIC)   a TIME (see OboeAudioCapture.captureClock)
 * ```
 *
 * Two consequences a reader should not have to rediscover:
 *  - SUBMISSION ORDER SUFFICES FOR WHAT THIS CLASS CONTAINS. Its contract is "the PCM the client
 *    actually submitted for playback", and the filter's first question is what the reference holds,
 *    which submission order answers exactly. A wrong submission order corrupts WHAT is held.
 *  - IT PLACES A WINDOW IN TIME ONLY WHEN ONE AXIS IS PRESENT. The cloud answer now supplies a
 *    render clock, so a window of ONLY cloud audio can be related to wall time and the gap between it
 *    and a microphone segment is the player's buffer latency -- the quantity an echo-bleed filter is
 *    deciding. The episode tap supplies a MEDIA position, which is not a time, so those two cannot be
 *    combined: a window containing both has NO position and is reported as such rather than as a
 *    subtraction across two origins.
 *
 * The render clock changed what a consumer can ASK of the stored samples, not what this class stores.
 * That distinction is why the two consequences above are separate claims rather than one bug, and it
 * is also why adding the clock introduced a new failure mode -- a mixed window -- rather than only
 * removing an old gap. A new source of time is a new way for two clocks to disagree.
 *
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

    /**
     * Guards [buffer], [writePos], [filled] and [lastPositionMs].
     *
     * The class doc's "single writer" is true of the SUBMISSION ORDER, not of the thread: the tap
     * collector runs on Main, [CloudAudioPlayer] writes from the sink's IO coroutine, the route
     * observer retires on Main, and [snapshot] is read from the ASR coroutine. [write] advances one
     * field at a time, so a reader sees a consistent cursor; [retire] is a MULTI-FIELD transition
     * (fill + copy + writePos + filled), so a reader interleaving it can size the window from one
     * field and populate it from another.
     */
    private val lock = Any()

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
        synchronized(lock) {
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
    }

    /** No axis recorded yet, or no timestamped contribution has arrived. */
    private var positionAxis: PositionAxis? = null

    /**
     * @param positionMs the position of the chunk's FIRST sample, or null when the contributing
     * renderer supplies no timestamp (earcons and TTS do not). An untimed chunk contributes SAMPLES
     * and NOT a position: it must not inherit another producer's axis, because doing so would make a
     * window look like it has a valid mapping to a clock nothing measured for this audio.
     * @param axis which clock [positionMs] is on. Ignored when [positionMs] is null.
     */
    fun write(pcm: FloatArray, positionMs: Long? = null, axis: PositionAxis? = null) {
        val axisClaimed = axis != null
        synchronized(lock) {
            for (sample in pcm) {
                buffer[writePos] = sample
                writePos = (writePos + 1) % buffer.size
                if (writePos == 0) filled = true
            }
            if (positionMs != null) {
                // The position of the newest sample: the chunk's start plus its duration at 16 kHz.
                lastPositionMs = positionMs + (pcm.size * 1_000L) / SAMPLE_RATE
                // WHICH CLOCK that number belongs to. A window holding two contributions from
                // different axes has no single position, and subtracting across them yields a
                // number that is neither a media position nor a time -- the difference between two
                // unrelated origins, reported as a delay.
                if (positionAxis == PositionAxis.Unanchored) {
                    // A window cannot come back to being anchored once an untimed contribution has
                    // joined it. The retained samples now include audio whose sounding time nothing
                    // measured, so the span's start no longer describes when the window began.
                    positionAxis = PositionAxis.Mixed
                } else if (positionAxis == null) {
                    // An unlabelled position is producer placement, not a render observation, so it
                    // leaves the window unanchored rather than claiming the Monotonic default.
                    positionAxis = if (axisClaimed) axis else PositionAxis.Unanchored
                } else if (positionAxis != axis) {
                    positionAxis = PositionAxis.Mixed
                }
            } else {
                // An UNTIMED contribution contributes samples and NO position. It does not inherit
                // the axis of whatever came before, and -- the part that is easy to miss -- it
                // INVALIDATES any anchor already established: the anchor describes the start of the
                // retained window, and this audio has just joined that window with no known time.
                // Keeping the old anchor would place untimed samples at a moment derived from
                // another producer's clock.
                positionAxis = PositionAxis.Unanchored
            }
        }
    }

    /**
     * The player-timeline position of the newest recorded sample, or null when nothing was
     * written with a timestamp. Lets the correlation align a mic segment to a position rather than
     * to buffer offset, which is what holds when sources share the buffer.
     */
    fun lastRecordedPositionMs(): Long? = synchronized(lock) { lastPositionMs }

    /**
     * The reference window with the player position of its oldest retained sample.
     *
     * The oldest sample's position is derived from the newest ([lastPositionMs]) minus the window's
     * duration, which is exact for a contiguous window; sources that interleave are placed by their
     * own submission positions, so the span is the range the buffer actually covers. Null when no
     * timestamped source has contributed.
     */
    fun reference(): PlaybackReference {
        // One acquisition, not two. `snapshot()` and the position are the two halves of ONE answer --
        // the samples and the timeline position they occupy -- so reading them under separate locks
        // can pair `start` derived from a newer position with samples from an older state, giving a
        // span that never existed. `write()` updates both, and `retire()` changes the sample count
        // without touching the position.
        synchronized(lock) {
            val samples = snapshotLocked()
            val end = lastPositionMs
            // A window spanning two clocks reports NO position. The start would otherwise be computed
            // by subtracting the window's duration from a value on one axis and then compared against
            // an instant on the other, producing a plausible delay that is really the distance between
            // two unrelated origins. Absent is the honest answer, and the correlator already falls
            // back to offset matching when a position is absent.
            // A position is offered ONLY when the window's samples carry a monotonically-clocked
            // mapping, because that is the only axis the microphone instant can be compared against.
            // Two exclusions, and the second is the one a mixed-axis guard alone misses:
            //
            //   Mixed     the window spans two origins, so subtraction crosses them
            //   Media     a SINGLE-domain window on the wrong domain -- a media position is seconds
            //             into an episode and a microphone instant is a monotonic time, so pairing
            //             them reports the distance between two unrelated origins as a delay
            //
            // Both report no position. Absent is the honest answer and the correlator falls back to
            // offset matching, whereas a media position would align confidently against a moment that
            // has nothing to do with when the audio was captured.
            val anchored = positionAxis == PositionAxis.Monotonic
            val start = if (anchored) {
                end?.let { it - (samples.size * 1_000L) / SAMPLE_RATE }
            } else {
                null
            }
            return PlaybackReference(samples, SAMPLE_RATE, start)
        }
    }

    fun snapshot(): FloatArray = synchronized(lock) { snapshotLocked() }

    /** The window's samples. Callers must hold [lock] so the read is one coherent step. */
    private fun snapshotLocked(): FloatArray {
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

/**
 * Which clock a contribution's position is measured on. Two sources in different domains cannot be
 * paired, so the recorder records which one it is holding rather than assuming they agree.
 */
enum class PositionAxis {
    /** Seconds into the media being played (the episode renderer's tap). */
    Media,

    /** A monotonic time on `CLOCK_MONOTONIC` (the cloud answer's render clock). */
    Monotonic,

    /** Both have contributed: the window has no single position. */
    Mixed,

    /**
     * An untimed contribution has joined the window, so the span's start is no longer known even if
     * every timed contribution agreed. Distinguishing this from [Mixed] keeps the reason accurate:
     * one is two clocks disagreeing, the other is audio whose time nothing measured.
     */
    Unanchored,
}
