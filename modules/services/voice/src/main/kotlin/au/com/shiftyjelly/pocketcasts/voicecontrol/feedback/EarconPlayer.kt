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

    /** The worker thread THIS player started, for a test to observe its liveness directly. */
    @Volatile
    private var workerForTest: Thread? = null

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
                    // THIS player's worker, retained so a test can observe THAT thread rather than any
                    // thread that happens to share the name. A name scan sees other players' workers
                    // still winding down and reads a released worker as live; observing the instance
                    // this player started is both deterministic and about the right subject.
                    .also { workerForTest = it }
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
    internal fun workerIsShutdownForTest(): Boolean {
        // Both halves of "released": the executor is shut down AND this player's own thread has ENDED.
        //
        // `shutdown()` is ASYNCHRONOUS -- it accepts the request and the thread finishes its queue
        // afterwards -- so reading liveness immediately would report a released worker as live. The
        // observation therefore WAITS for the thread to end, bounded, which is what "released" means:
        // an unbounded wait would hang on a worker that never stops, and no wait would report a
        // stopped worker as running.
        val thread = workerForTest
        val shutDown = synchronized(executorLock) { executor?.isShutdown ?: true }
        if (thread == null) return shutDown
        thread.join(WORKER_RELEASE_TIMEOUT_MS)
        return shutDown && !thread.isAlive
    }

    /** Runs [block] on the player's own thread and returns its result, so call order is preserved. */
    // DRIVES THE PRODUCTION PATH. This used to re-implement the locking (`synchronized { ... submit }`),
    // so a test could not observe the discipline at all: mutating the REAL path left every case passing,
    // because the cases ran THIS copy. A test seam must route through the mechanism, not restate it.
    internal fun <T> submitForTest(block: () -> T): T = onPlayerThread(block)

    // Replaces the worker with [service], so a test controls when it is shut down.

    internal fun installExecutorForTest(service: java.util.concurrent.ExecutorService) {
        synchronized(executorLock) { executor = service }
    }

    /**
     * Shuts the worker down the way [release] does: UNDER [executorLock].
     *
     * Shutting down outside the lock opens a window production does not have -- the executor can be
     * disabled between the lock being taken and `submit` running, and the submission is then REJECTED.
     * A test that shuts down directly therefore reports a failure the real discipline cannot produce,
     * which makes it a false alarm rather than a control.
     */
    internal fun shutdownExecutorForTest() {
        synchronized(executorLock) {
            executor?.shutdown()
            executor = null
        }
    }

    /** The worker, created on demand. Caller holds [executorLock]. */
    private fun executorForLocked(): java.util.concurrent.ExecutorService {
        val existing = executor?.takeIf { !it.isShutdown }
        if (existing != null) return existing
        return java.util.concurrent.Executors
            .newSingleThreadExecutor { runnable ->
                Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
                    .also { workerForTest = it }
            }
            .also { executor = it }
    }

    private fun <T> onPlayerThread(block: () -> T): T {
        // SUBMIT UNDER THE SAME LOCK THAT SHUTS THE EXECUTOR DOWN. `executorFor()` can hand back a
        // service that `release()` shuts down before `submit` runs, and the submit is then REJECTED --
        // a throw under contention, which is exactly what the serialisation exists to prevent. The
        // window is between choosing the executor and using it.
        // SELECTION **AND SUBMISSION** SHARE THE LOCK `release()` TAKES. Selecting inside the lock and
        // submitting outside it leaves the same window: `release()` can shut the executor down between
        // the two, and the submission is then REJECTED -- the throw under contention the serialisation
        // exists to prevent. Only the WAIT is outside, because holding the lock across it would block
        // the very release that is allowed to run.
        val future = synchronized(executorLock) {
            executorForLocked().submit(block)
        }
        return future.get()
    }

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
                    // THE FRAMES ALREADY ACCEPTED STILL COUNT. Returning here without folding `offset`
                    // into the frontier forgets audio the track will really play, so the NEXT cue's
                    // target excludes it and the head -- which does include it -- passes that target
                    // early: the next cue reads Consumed while half of it is still sounding.
                    //
                    // The frontier is frames the track ACCEPTED, and a failed cue accepted `offset` of
                    // them. The cue itself FAILED, which is reported to the caller, and that is a
                    // separate fact from how much audio the track now owes.
                    acceptedFramesTotal += offset
                    return false
                }
                clip.recordAccepted(offset, written, playbackBufferRecorder)
                offset += written
            }
            lastPlayedId = id
            // THE HEAD IS CUMULATIVE ACROSS CUES ON ONE TRACK, so completion must compare it against a
            // cumulative target. Comparing it against this cue's own frame COUNT made a second cue
            // read Consumed immediately: the head already stood at the first cue's total, which
            // exceeds the second cue's count before any of its frames had sounded.
            //
            // The origin is where the head stood when this cue began, and the target is that origin
            // plus the frames this cue submitted -- so the comparison is about THIS cue's audio.
            // THE TARGET IS THE ACCEPTED-FRAME FRONTIER, not the head at submission.
            //
            // The head cannot be observed reliably while frames are still being written -- the platform
            // advances it as the track drains, and a mock or a real device may move it DURING the write
            // loop. Taking "origin + this cue's frames" therefore understates whenever earlier audio is
            // still queued (a second cue reported Consumed at the first cue's total) and misstates a
            // single cue whose head advanced while its frames were accepted.
            //
            // The frontier is the total frames this track has ACCEPTED, which is exactly what must
            // sound before this cue is done: every frame submitted before it, plus its own.
            acceptedFramesTotal += offset
            cueHeadTarget = acceptedFramesTotal
            cueHeadOrigin = acceptedFramesTotal - offset
            emittedFrames = offset
            emittedRateHz = clip.sampleRateHz
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

    @Volatile
    private var emittedRateHz: Int = 0

    /** Total frames this TRACK has accepted, across every cue played on it. */
    @Volatile
    private var acceptedFramesTotal: Int = 0

    /** Where the track's cumulative head stood when the most recent cue started. */
    @Volatile
    private var cueHeadOrigin: Int = 0

    /** The cumulative head position this cue reaches when all its accepted frames have sounded. */
    @Volatile
    private var cueHeadTarget: Int = 0

    /**
     * The duration of the cue the sink ACCEPTED, in milliseconds, from the frames accepted and the
     * rate they were accepted at.
     *
     * This is an estimate of QUEUED audio, NOT a completion signal: it says how much audio was handed
     * over, not when it started sounding or when it finished. It is useful for bounding a coverage
     * window; it must not be reported as completion. Completion is [completion], which reads the
     * output.
     */
    fun emittedDurationMs(): Long {
        val rate = emittedRateHz
        if (rate <= 0) return 0L
        return emittedFrames * 1_000L / rate
    }

    /**
     * The playback head of the cue's own track, in frames, or null when nothing is playing.
     *
     * Test seam, mirroring the cloud player's: the platform's `playbackHeadPosition` is the only
     * authority on what has actually SOUNDED.
     */
    var readPlaybackHeadFrames: ((track: AudioTrack) -> Int?)? = null

    /**
     * Whether the cue has been observed to have played, or why it cannot be said.
     *
     * The distinction that matters for attribution: a cue's attribution must END when the output has
     * consumed it, not when the estimated duration elapses, and a cue that is cancelled or fails ends
     * its attribution immediately rather than being covered to its nominal length.
     */
    fun completion(): EarconCompletion {
        val current = track ?: return EarconCompletion.Unknown
        if (emittedFrames <= 0) return EarconCompletion.Unknown
        // An installed observer is AUTHORITATIVE: its null means "not observable", and falling back
        // to the platform head would fabricate an observation the caller said does not exist.
        val head = if (readPlaybackHeadFrames != null) {
            readPlaybackHeadFrames!!.invoke(current) ?: return EarconCompletion.Unknown
        } else {
            current.playbackHeadPosition
        }
        // CUMULATIVE: the head counts every frame the track has ever played, so the comparison is
        // against the frontier this cue's accepted frames reach.
        return if (head >= cueHeadTarget) EarconCompletion.Consumed else EarconCompletion.Playing
    }

    /** What the output has been observed to have done with the accepted cue. */
    enum class EarconCompletion { Playing, Consumed, Unknown }

    /**
     * Stops the cue's output and clears the recorded acceptance, so attribution ends NOW rather than
     * at an estimated duration.
     *
     * Called when the session ends or the cue is cancelled or superseded: the cue is not going to
     * finish, and covering it for its nominal length would keep the gate attributing a sound the user
     * can no longer hear.
     */
    fun stopNow() {
        onPlayerThread {
            track?.runCatching { stop() }
            emittedFrames = 0
            emittedRateHz = 0
            cueHeadOrigin = 0
            cueHeadTarget = 0
            lastPlayedId = null
        }
    }

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
            // The frontier belongs to the track that was just released, so it must not outlive it: the
            // NEXT track's head starts at zero, and carrying the old total forward would make its first
            // cue report Consumed before any of its frames sounded.
            acceptedFramesTotal = 0
            cueHeadTarget = 0
            cueHeadOrigin = 0
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
            // A replacement track's head starts at zero, exactly as a fresh one's does.
            acceptedFramesTotal = 0
            cueHeadTarget = 0
            cueHeadOrigin = 0
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
        // A NEW track's head starts at zero, so the frontier restarts with it. Carrying the previous
        // track's total forward would make the first cue on the new track unreachable.
        acceptedFramesTotal = 0
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

        /** How long a test waits for a released worker to end before reporting it as still running. */
        private const val WORKER_RELEASE_TIMEOUT_MS = 2_000L

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
