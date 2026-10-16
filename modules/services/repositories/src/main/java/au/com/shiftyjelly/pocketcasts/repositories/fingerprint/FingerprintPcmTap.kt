package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Publishes the PCM the player is already decoding, stamped with its media-time position, so
 * fingerprinting can reuse it instead of downloading and decoding the episode a second time.
 * Producer callbacks are all invoked from the playback thread.
 */
@OptIn(UnstableApi::class)
@Singleton
class FingerprintPcmTap @Inject constructor() {

    class PcmChunk(
        val data: ByteArray,
        val encoding: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val positionSec: Double,
        /** [streamEpoch] at emission: identity of the media stream these chunks belong to. */
        val streamEpoch: Long,
        /**
         * Opaque admission ticket captured at EMISSION from the registered stamper (null when none).
         * A queued chunk carries the ticket its stream held WHEN THE CHUNK WAS CREATED -- a consumer
         * must never mint or renew admission at delivery time (@spec/@reviewer): work that was already
         * in flight across a wholesale retirement stays dead, and only FUTURE emissions from a renewed
         * live owner carry fresh tickets.
         */
        val admissionTicket: Long?,
    )

    private val chunkFlow = MutableSharedFlow<PcmChunk>(
        extraBufferCapacity = CHUNK_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val chunks: SharedFlow<PcmChunk> = chunkFlow.asSharedFlow()

    /**
     * Stamps each emission with the current admission ticket for [streamEpoch]. Invoked on the playback
     * thread AT CHUNK CREATION. The recorder supplies this through its own ownership state; returning
     * null means "unowned" and such chunks are refused by an admission-enforcing recorder.
     */
    var admissionStamper: ((epoch: Long, previousTicket: Long?) -> Long?)? = null

    /**
     * Called on the playback thread at every stream-close event -- [onSinkFlush] (media3 sink flush()
     * AND reset(), which covers pause/seek/stop in this renderer) and a foreground transition to
     * background ([handlePlaybackState]). A subscriber retires its held admission AT THE EVENT; the
     * epoch bump alone only ever superseded admission when a SUCCESSOR later emitted, which left a
     * paused-then-idle episode holding live admission with no owner event closing it (@reviewer,
     * thread 4235702596).
     */
    private val streamClosedCallbacks = mutableListOf<() -> Unit>()

    /** Idempotent: registering the same callback twice fires it once per stream-close event. */
    fun registerStreamClosed(callback: () -> Unit) {
        synchronized(streamClosedCallbacks) { if (callback !in streamClosedCallbacks) streamClosedCallbacks += callback }
    }

    fun unregisterStreamClosed(callback: () -> Unit) {
        synchronized(streamClosedCallbacks) { streamClosedCallbacks -= callback }
    }

    private var lastEmittedEpoch = -1L
    private var lastEmittedTicket: Long? = null

    private var pendingAnchorUs = C.TIME_UNSET
    private var anchorUs = C.TIME_UNSET
    private var framesSinceAnchor = 0L

    /**
     * Identifies the media stream the anchor describes. Increments every time the anchor is (re)set to
     * a new presentation position -- a seek, a flush, or a new episode -- so consumers can tell two
     * same-rate streams apart instead of inheriting each other's position-derived state.
     */
    private var streamEpoch = 0L

    fun onSinkBuffer(presentationTimeUs: Long) {
        pendingAnchorUs = presentationTimeUs
    }

    fun onSinkFlush() {
        // THE STOP/DRAIN EVENT ITSELF, not a later successor emission: an owner that wants to close
        // admission at the real boundary subscribes here (streamClosedCallbacks). Runs BEFORE the
        // epoch bump so retirement is complete before any new-stream stamping can happen.
        // SNAPSHOT UNDER THE REGISTRY MONITOR, INVOKE OUTSIDE IT (@reviewer): toList() racing an
        // unsynchronized register/unregister on another thread is undefined on a MutableList; and
        // invoking under the monitor would run arbitrary retirement code while a concurrent
        // register/unregister blocks the playback thread.
        val callbacks = synchronized(streamClosedCallbacks) { streamClosedCallbacks.toList() }
        callbacks.forEach { it() }
        pendingAnchorUs = C.TIME_UNSET
        anchorUs = C.TIME_UNSET
        framesSinceAnchor = 0
        streamEpoch++
    }

    fun onPcm(buffer: ByteBuffer, format: AudioProcessor.AudioFormat) {
        val frames = buffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        val pending = pendingAnchorUs
        pendingAnchorUs = C.TIME_UNSET
        if (anchorUs == C.TIME_UNSET) {
            if (pending == C.TIME_UNSET) return
            anchorUs = pending
            framesSinceAnchor = 0
            streamEpoch++
        } else if (pending != C.TIME_UNSET) {
            if (abs(pending - positionUs(format.sampleRate)) > REANCHOR_TOLERANCE_US) {
                anchorUs = pending
                framesSinceAnchor = 0
                streamEpoch++
            }
        }
        val positionSec = positionUs(format.sampleRate) / 1_000_000.0
        framesSinceAnchor += frames
        if (chunkFlow.subscriptionCount.value == 0) return
        val data = ByteArray(buffer.remaining())
        buffer.duplicate().get(data)
        // STAMP AT CREATION, not at consumption: the ticket names the admission this audio was emitted
        // under. Epoch changes ask the stamper for a fresh ticket (a new incarnation); same epoch reuses
        // the epoch's current ticket UNLESS the stamper reports one changed after wholesale retirement --
        // renewal covers only FUTURE emissions because it happens here, before the next chunk is created.
        val epoch = streamEpoch
        val stamper = admissionStamper
        val ticket: Long? = if (stamper == null) {
            null
        } else if (epoch != lastEmittedEpoch) {
            stamper(epoch, null).also {
                lastEmittedEpoch = epoch
                lastEmittedTicket = it
            }
        } else {
            val carried = lastEmittedTicket
            // The stamper decides whether the carried ticket is still live for future emissions; a dead
            // carried ticket is replaced by the renewed one it mints now.
            stamper(epoch, carried).also { lastEmittedTicket = it }
        }
        chunkFlow.tryEmit(
            PcmChunk(
                data = data,
                encoding = format.encoding,
                sampleRate = format.sampleRate,
                channelCount = format.channelCount,
                positionSec = positionSec,
                streamEpoch = streamEpoch,
                admissionTicket = ticket,
            ),
        )
    }

    private fun positionUs(sampleRate: Int): Long = anchorUs + framesSinceAnchor * 1_000_000 / sampleRate

    companion object {
        private const val CHUNK_BUFFER_CAPACITY = 64
        private const val REANCHOR_TOLERANCE_US = 200_000L
    }
}
