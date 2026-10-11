package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Plays the bundled earcons through an [AudioTrack] this app owns, so the samples sent to the output
 * are known and can be recorded into the shared echo reference.
 *
 * Audible fidelity is preserved: the clip is played at its own rate. Only the *copy* that enters the
 * reference is normalized to the reference's 16 kHz, so the output sink and the reference are
 * allowed different rates and the cue is not band-limited just to simplify the correlator.
 */
class EarconPlayer(
    context: Context,
    private val playbackBufferRecorder: PlaybackBufferRecorder? = null,
) {
    private val appContext = context.applicationContext
    private val clips: Map<EarconId, EarconClip>
    private var track: AudioTrack? = null

    /**
     * Serialises play and release onto one thread.
     *
     * `track` is shared mutable state and was written from both Main (`VoiceControlService`'s
     * listening cue) and IO (`VoiceAsrEngine`'s collect coroutine): two overlapping cues could each
     * build a track and orphan the first -- still playing, never released -- and a `release()` landing
     * mid-write left the newly created track playing forever. A single-threaded executor makes
     * play/release one-at-a-time in call order without changing the synchronous result callers use.
     */
    private val executorLock = Any()
    private var executor: java.util.concurrent.ExecutorService? = null

    /**
     * The player's own single thread, created on demand.
     *
     * Lazy and re-creatable on purpose, which is what lets two requirements hold together: [release]
     * must RELEASE the worker rather than retain it for the process, and the player is a Hilt
     * `@Singleton` that a later service session still needs to use. A permanently-held executor would
     * leak the thread; a permanently-shut-down one would refuse the next session with
     * `RejectedExecutionException`. Creating it here gives both -- disposal shuts this one down, and
     * the next play starts a fresh one.
     */
    private fun executorFor(): java.util.concurrent.ExecutorService = synchronized(executorLock) {
        executor?.takeIf { !it.isShutdown } ?: java.util.concurrent.Executors
            .newSingleThreadExecutor { runnable ->
                Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
            }
            .also { executor = it }
    }

    /** The worker's thread, for a test to observe the lifetime. Null before first use. */
    internal fun workerThreadForTest(): String? = synchronized(executorLock) {
        executor?.let { "earcon-player" }
    }

    /**
     * Whether the worker has been RELEASED, for a test to observe the lifetime.
     *
     * Null alone does not mean shut down: dropping the reference while the thread is alive is precisely
     * the leak the case exists to catch, and `executor?.isShutdown ?: true` reported that as shut down.
     * The worker is released only when the reference is gone AND no `earcon-player` thread is still
     * alive, so both are checked.
     */
    internal fun workerIsShutdownForTest(): Boolean = synchronized(executorLock) {
        val service = executor
        val shutDown = service?.isShutdown ?: true
        shutDown && !anyWorkerThreadAlive()
    }

    /** Whether any worker thread is still running, so a released-but-live worker is not read as gone. */
    private fun anyWorkerThreadAlive(): Boolean = Thread.getAllStackTraces().keys.any {
        it.name == WORKER_THREAD_NAME && it.isAlive
    }

    /** Runs [block] on the player's own thread and returns its result, so call order is preserved. */
    private fun <T> onPlayerThread(block: () -> T): T = executorFor().submit(block).get()

    init {
        clips = EarconId.entries.associateWithNotNull { id ->
            val resId = appContext.resources.getIdentifier(
                "earcon_${id.name.lowercase()}",
                "raw",
                appContext.packageName,
            )
            if (resId == 0) {
                null
            } else {
                runCatching {
                    appContext.resources.openRawResource(resId).use { decodeWavToMono(it) }
                }.onFailure { Timber.w(it, "[Earcon] failed to decode %s", id) }.getOrNull()
            }
        }
    }

    /** The last earcon this player started, so a caller or test can observe that feedback sounded. */
    @Volatile
    var lastPlayedId: EarconId? = null
        private set

    /**
     * Starts [id] and reports whether the SINK ACCEPTED the whole clip.
     *
     * The boolean answers "did submission succeed", which is a START acknowledgement -- **it does not
     * claim the audio has been emitted**, and treating it as completion is the conflation @spec
     * ruled out. The drain runs on this player's own thread: the caller (the ASR collect coroutine,
     * and Main) is released as soon as submission finishes rather than after the clip has played.
     *
     * Only PCM the sink ACCEPTED feeds the reference, so a partial write cannot leave the reference
     * claiming audio that was never submitted.
     */
    fun play(id: EarconId): Boolean = onPlayerThread {
        startOnPlayerThread(id)
    }

    private fun startOnPlayerThread(id: EarconId): Boolean {
        val clip = clips[id] ?: return false
        if (clip.samples.isEmpty()) return false
        val audioTrack = try {
            ensureTrack(clip.sampleRateHz)
        } catch (e: Exception) {
            Timber.w(e, "[Earcon] play failed")
            return false
        }
        return try {
            var offset = 0
            while (offset < clip.samples.size) {
                val written = audioTrack.write(clip.samples, offset, clip.samples.size - offset)
                if (written <= 0) {
                    Timber.w("[Earcon] AudioTrack.write returned $written")
                    return false
                }
                clip.recordAccepted(offset, written, playbackBufferRecorder)
                offset += written
            }
            lastPlayedId = id
            emittedFrames = offset
            true
        } catch (e: Exception) {
            Timber.w(e, "[Earcon] play failed")
            false
        }
    }

    /**
     * The frames the sink accepted for the most recent clip.
     *
     * Read alongside [play]'s result: a caller that needs to distinguish "accepted" from "finished"
     * must not infer completion from a successful submission. `AudioTrack.playbackHeadPosition` is the
     * only authority on completion, and it is not sampled here -- the heartbeat and the gate's own
     * attribution window are what cover the span between the two.
     */
    @Volatile
    var emittedFrames: Int = 0
        private set

    /**
     * Frees this session's audio resources.
     *
     * Deliberately NOT a terminal state, and the distinction is a user-facing one: this player is a
     * Hilt `@Singleton` and `VoiceControlService.stopVoiceControl()` releases it on every stop, so a
     * one-way flag here silenced all feedback for the rest of the PROCESS -- the user stops voice
     * control once and never hears a cue again, with nothing logged. Releasing frees the track and
     * makes an in-flight play stop; the next play re-creates its track, which is what the singleton's
     * lifetime requires.
     *
     * The executor is not shut down either: a shut-down executor rejects the next submit with
     * `RejectedExecutionException`, and the thread is a daemon, so an idle one costs nothing.
     */
    fun release() {
        onPlayerThread {
            track?.runCatching {
                stop()
                release()
            }
            track = null
        }
        // Release the worker itself, last: the teardown above runs ON this thread, and the next session
        // creates a fresh one rather than finding a shut-down executor.
        synchronized(executorLock) {
            executor?.shutdown()
            executor = null
        }
    }

    private fun ensureTrack(sampleRateHz: Int): AudioTrack {
        val current = track
        if (current != null) {
            if (current.sampleRate == sampleRateHz) return current
            // The bundled clips share a rate; if one ever differs, recreate rather than play it at
            // the wrong rate, which would change its pitch and duration.
            current.runCatching {
                stop()
                release()
            }
            track = null
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val created = AudioTrack.Builder()
            .setAudioAttributes(earconAudioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()
        created.play()
        track = created
        return created
    }

    /**
     * Decodes a PCM WAV resource to mono 16 kHz 16-bit samples.
     *
     * Handles the format the bundles actually use (16-bit PCM, possibly stereo); anything else is
     * rejected rather than silently mis-read, since a wrong interpretation would put wrong audio in
     * the reference.
     */
    private fun decodeWavToMono(input: InputStream): EarconClip {
        val clip = EarconAudio.decodeWavToMono(input)
        return EarconClip(clip.samples, clip.sampleRateHz)
    }

    /**
     * A decoded earcon: the samples to PLAY at their own [sampleRateHz], plus the normalized copy
     * that goes into the reference. The two are deliberately separate — playback keeps its full
     * bandwidth, and only the correlation works at the reference's rate.
     */
    private inner class EarconClip(
        val samples: ShortArray,
        val sampleRateHz: Int,
    ) {
        /**
         * Records the portion of this clip the sink accepted, on the reference's timeline.
         *
         * The accepted slice is converted to the reference rate, so a partial write leaves the
         * reference holding exactly the audio that was submitted, not the whole clip.
         */
        fun recordAccepted(startSample: Int, count: Int, recorder: PlaybackBufferRecorder?) {
            if (recorder == null || count <= 0) return
            val forReference = EarconAudio.resampleRange(
                samples = samples,
                fromRate = sampleRateHz,
                toRate = REFERENCE_RATE_HZ,
                startSample = startSample,
                count = count,
            )
            recorder.write(FloatArray(forReference.size) { forReference[it] / 32768f })
        }
    }

    private inline fun <K, V : Any> Iterable<K>.associateWithNotNull(valueSelector: (K) -> V?): Map<K, V> {
        val result = LinkedHashMap<K, V>()
        for (key in this) {
            valueSelector(key)?.let { result[key] = it }
        }
        return result
    }

    internal companion object {
        /** The name the worker thread carries, so the test observer can find it by name. */
        const val WORKER_THREAD_NAME = "earcon-player"

        /** The rate the shared echo reference and the correlator work at. */
        const val REFERENCE_RATE_HZ = 16_000

        /**
         * The stream earcons ride.
         *
         * Deliberately **media** rather than sonification. Sonification maps to the system stream, which
         * this family of devices aliases to the ring stream, so Do Not Disturb silenced every earcon
         * while ordinary playback was perfectly audible — a user speaking to an app they deliberately
         * opened heard no confirmation. Media follows the audio the user is already listening to, which
         * is also where the spoken responses go through platform TTS, so the two feedback channels agree.
         *
         * Reported from a device where `STREAM_SYSTEM` was `Muted: true / streamVolume: 0` under
         * `zen_mode: 1` while every earcon play attempt was logged `usage:13 muted`. If this is ever
         * changed back, the reason it must not be is that sentence.
         */
        internal fun earconAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
