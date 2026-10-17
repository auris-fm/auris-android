package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import androidx.annotation.VisibleForTesting
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

    /** Test seam (4d1e5946): install a real clip so submit()/play() take an actual accepted write. */
    internal fun installClipForTest(id: EarconId, samples: ShortArray, sampleRateHz: Int) {
        val ctor = EarconClip::class.java.getDeclaredConstructor(
            EarconPlayer::class.java,
            ShortArray::class.java,
            Int::class.java,
        )
        ctor.isAccessible = true
        val field = EarconPlayer::class.java.getDeclaredField("clips")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(this) as MutableMap<EarconId, Any>
        map[id] = ctor.newInstance(this, samples, sampleRateHz)
    }

    // Replaces the worker with [service], so a test controls when it is shut down.

    /** Test seam: the production accepted-frames frontier, to prove bookkeeping ran (35f7e2d8). */
    internal fun acceptedFrontierForTest(): Int = synchronized(executorLock) { acceptedFramesTotal }

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

    // Plays `id` and returns THE ADMISSION IT TOOK, or null when the cue was not admitted.
    //
    // A null result is a START acknowledgement that did NOT happen -- it does not claim the audio has
    // been emitted, and treating acceptance as completion is the conflation @spec ruled out. The drain
    // runs on this player's own thread, so the caller is released when submission finishes rather than
    // after the clip has played. Only PCM the sink ACCEPTED feeds the reference, so a partial write
    // cannot leave the reference claiming audio that was never submitted.
    //
    // THE TOKEN IS RETURNED RATHER THAN RE-READ. A caller that played the cue and then asked for "the
    // current admission" would be asking a DIFFERENT question: between its play and its read a successor
    // can take the track, and the caller would hold the SUCCESSOR'S token while believing it held its
    // own -- so its later stop would release an admission it never made. The admission and the play are
    // one act, so the result of the act is what carries it.
    fun play(id: EarconId): AdmissionToken? = onPlayerThread {
        startOnPlayerThread(id)
    }

    /**
     * Fire-and-forget submission for latency-sensitive callers (4236341555): the clip write runs on
     * the player executor -- serialised with every other cue, and with the SAME admission-token
     * minting, recorder-admission ownership and completion bookkeeping as [play] -- but the CALLER
     * does not wait for the write. Acceptance is observable through the coverage heartbeat, not
     * through a return value, so this is only for callers who cue and move on (the ASR collect path),
     * not for callers that must gate on acceptance.
     */
    fun submit(id: EarconId, onAdmitted: ((AdmissionToken?) -> Unit)? = null) {
        val task = {
            // The callback runs ON THE PLAYER THREAD, in task order: a successor's coverage cannot
            // start while an older cue is still writing, and a refusal (null) never starts coverage
            // at all. Admission-token ownership is unchanged -- the token is minted here, by the
            // same bookkeeping play() uses.
            val token = startOnPlayerThread(id)
            onAdmitted?.invoke(token)
        }
        synchronized(executorLock) {
            executorForLocked().submit(task)
        }
    }

    private fun startOnPlayerThread(id: EarconId): AdmissionToken? {
        val clip = clips[id] ?: return null
        if (clip.samples.isEmpty()) return null
        val audioTrack = try {
            ensureTrack(clip.sampleRateHz)
        } catch (e: Exception) {
            Timber.w(e, "[Earcon] play failed")
            return null
        }
        return try {
            // A new cue supersedes the previous one's recorder ownership; the old cue's retained tail
            // keeps its expiry deadline and stays matchable until elapsed time retires it.
            closeRecorderAdmission()
            currentRecorderAdmission = openRecorderAdmission(playbackBufferRecorder)
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
                    closeRecorderAdmission()
                    return null
                }
                currentRecorderAdmission?.let { clip.recordAccepted(offset, written, playbackBufferRecorder, it) }
                offset += written
            }
            lastPlayedId = id
            // THE ADMISSION TOKEN IS MINTED WHEN THE CUE TAKES THE TRACK, so it identifies THIS playing
            // of the cue. A caller holds it to stop precisely this admission later.
            val admitted = AdmissionToken(admissionCounter.incrementAndGet())
            currentAdmission = admitted
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
            // THE ADMISSION'S OWN FRONTIER, PUBLISHED AS ONE IMMUTABLE SNAPSHOT. The shared slots
            // above serve the legacy/inspection readers; the SCOPED reader (completionFor) reads only
            // this reference. One @Volatile reference write is atomic: there is no intermediate state
            // in which the bounds belong to one admission and the marker to another -- the defect the
            // writer-between-fields-and-marker control (ddaf3aa7b) demonstrated for marker-last
            // ordering, where B's fields could land while B's marker had not and A read the mixture
            // as its own. BOUNDS AND ADMISSION MARKER MOVE TOGETHER, IN THE SAME SNAPSHOT.
            currentFrontier = Frontier(
                admissionRaw = admitted.raw,
                origin = acceptedFramesTotal - offset,
                target = acceptedFramesTotal,
            )
            // THE RAW MARKER STAYS IN STEP for the stop-scoping path (stopAdmissionNow compares
            // currentAdmission); completion ownership checks read the snapshot, not this.
            currentAdmissionRaw = admitted.raw
            admitted
        } catch (e: Exception) {
            Timber.w(e, "[Earcon] play failed")
            closeRecorderAdmission()
            null
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
        return when {
            head >= cueHeadTarget -> EarconCompletion.Consumed

            // SOUNDING IS OBSERVED PROGRESS, NOT ACCEPTANCE. The head standing at or before this cue's
            // own first frame means the cue is still QUEUED -- accepted by the track and not yet
            // audible. Reporting that as Playing attributes an emitted sound for a cue nobody has heard,
            // and the gate then reads the microphone as ours before our cue has begun.
            head > cueHeadOrigin -> EarconCompletion.Playing

            else -> EarconCompletion.Queued
        }
    }

    /** What the output has been observed to have done with the accepted cue. */
    enum class EarconCompletion {
        /**
         * Accepted, and its frames have not begun sounding: still queued behind earlier audio.
         *
         * DISTINCT FROM [Playing] on purpose. Acceptance is the sink taking the frames; sounding is the
         * track having progressed through this cue's first frame. Only the latter means the sound is
         * audible, and only that may be attributed.
         */
        Queued,

        /** Observed to be sounding: the track has progressed past this cue's first frame. */
        Playing,

        /** Observed to have played every accepted frame. */
        Consumed,

        /** Not observable: no track, no accepted frames, or the observer reported unavailable. */
        Unknown,
    }

    /**
     * Stops the cue's output and clears the recorded acceptance, so attribution ends NOW rather than
     * at an estimated duration.
     *
     * Called when the session ends or the cue is cancelled or superseded: the cue is not going to
     * finish, and covering it for its nominal length would keep the gate attributing a sound the user
     * can no longer hear.
     */
    fun stopNow() {
        stopAdmissionNow(expectedAdmission = null)
    }

    /**
     * A UNIQUE TOKEN FOR ONE ADMISSION OF A CUE.
     *
     * The earcon ID is the KIND of cue, not this playing of it: two consecutive wake cues share an id, so
     * an id comparison cannot tell a held stop aimed at the first from a stop aimed at the second. An
     * incarnation can, and the difference is the whole question -- a stale stop must not release a cue it
     * never admitted.
     */
    @JvmInline
    value class AdmissionToken(val raw: Long)

    /**
     * Stops the ADMISSION this call was issued for, and only that one.
     *
     * [stopNow] stops whatever `track` is current, and the block runs LATER on the player thread, so a
     * successor admitted in between plays on that same track and would be stopped too. Scoping by
     * [EarconId] only reaches the KIND: a second cue of the same kind looks identical, so a held old stop
     * would still release it. The token is per admission, which is what a stop is actually about.
     *
     * [expectedAdmission] null means "stop whatever is playing", the session-teardown shape, where the
     * caller really does mean the track itself.
     */
    fun stopAdmissionNow(expectedAdmission: AdmissionToken?) {
        onPlayerThread {
            // A DIFFERENT ADMISSION IS NOT THIS STOP'S SUBJECT, even when it is the SAME earcon: the
            // successor's audio is not what this stop was issued for.
            if (expectedAdmission != null && currentAdmission != expectedAdmission) return@onPlayerThread
            // The discarded cue's recorder stream ends with it: close its admission and owe expiry to
            // whatever tail the hardware still plays.
            closeRecorderAdmission()
            // DISCARDING A TRACK MEANS DISCARDING EVERYTHING IT OWNS, in one step. Nulling the reference
            // after `stop()` alone LEFT THE RESOURCE UNRELEASED -- nothing could release it afterwards,
            // because the only reference was gone -- AND LEFT the track's cumulative frontier standing:
            // the next cue runs on a NEW track whose head starts at zero, while its completion target
            // still counted the retired track's frames, so it could report Consumed before sounding.
            // RELEASE IS ATTEMPTED INDEPENDENTLY OF STOP. In one `runCatching` block a THROWING `stop()`
            // skips the release, and the reference is dropped anyway -- so the resource is leaked by
            // exactly the case that most needs releasing. ONE RULE, EVERY CALL SITE.
            track?.runCatching { stop() }
            track?.runCatching { release() }
            track = null
            emittedFrames = 0
            emittedRateHz = 0
            // THE FRONTIER BELONGS TO THE TRACK, so it goes with it -- the same reset `release()` does,
            // for the same reason.
            acceptedFramesTotal = 0
            cueHeadOrigin = 0
            cueHeadTarget = 0
            lastPlayedId = null
            currentAdmission = null
            // THE SNAPSHOT CLEARS WITH THE ADMISSION, in one reference write: nothing is admitted, so
            // no scoped read can vouch stale bounds to a live caller.
            currentFrontier = null
            currentAdmissionRaw = 0L
        }
    }

    /** The admission currently owning the track, or null when nothing has been admitted. */
    // The recorder-side admission for the cue currently holding the track. Minted with the cue, closed
    // when the cue finishes or is stopped -- an earcon is a short-lived producer identity.
    @VisibleForTesting
    internal var currentRecorderAdmission: PlaybackBufferRecorder.Admission? = null
    private var earconProducerSequence = 0L

    private fun openRecorderAdmission(recorder: PlaybackBufferRecorder?): PlaybackBufferRecorder.Admission? {
        if (recorder == null) return null
        earconProducerSequence += 1L
        return recorder.openAdmission(EARCON_PRODUCER_BASE + earconProducerSequence)
    }

    private fun closeRecorderAdmission() {
        val admission = currentRecorderAdmission ?: return
        currentRecorderAdmission = null
        playbackBufferRecorder?.retireProducer(admission)
    }

    private var currentAdmission: AdmissionToken? = null

    /**
     * ONE ADMISSION'S FRONTIER AS AN IMMUTABLE VALUE.
     *
     * Bounds are computed once, at admission, and never mutated: a successor does not overwrite them,
     * it publishes a NEW snapshot. A scoped reader therefore always sees bounds and admission marker
     * from the SAME admission -- the mixture state (B's bounds, A's marker) that marker-last ordering
     * still admitted is unrepresentable.
     */
    private data class Frontier(
        val admissionRaw: Long,
        val origin: Int,
        val target: Int,
    )

    /**
     * The CURRENT admission's frontier, or null when nothing is admitted. Single @Volatile reference:
     * readers get one consistent snapshot, writers swap in a fresh one per admission.
     */
    @Volatile
    private var currentFrontier: Frontier? = null

    /**
     * The raw admission identity that owns the track, published for cross-thread ownership checks.
     *
     * EVERY writer sets this LAST, after the frontier fields it vouches for (admission above, the
     * zeroing in [stopAdmissionNow]), and readers rely on exactly that order: seeing raw R means the
     * fields R's admission wrote are visible, and NOT seeing R after a read means the read raced a
     * successor and its verdict is not the caller's to publish. 0 means nothing is admitted.
     */
    @Volatile
    private var currentAdmissionRaw: Long = 0L

    /**
     * Completion SCOPED TO ONE ADMISSION, not to whatever cue currently owns the track.
     *
     * The verdict is computed from THE CALLER'S OWN immutable frontier snapshot: the current snapshot
     * is read once (one atomic reference read), and anything that is not the caller's admission yields
     * [EarconCompletion.Unknown] -- which the renderer's observer treats as "nothing established", so
     * a superseded cue publishes nothing and the successor's own observer, once its callback runs,
     * speaks for the cue. Covers BOTH known races:
     *
     * - the CALLBACK GAP (63f99bed, control 2034cd5d4): A reads after B's whole publication -- B's
     *   snapshot is current, A bails.
     * - the WRITER-INTERNAL WINDOW (52b786fe, control ddaf3aa7b): A reads between B's frontier
     *   mutation and B's marker. Marker-last alone let A accept B's bounds as its own; with the
     *   snapshot there are no shared bounds to half-write -- A reads either its OWN immutable bounds
     *   (its verdict, correct) or B's whole snapshot (not its admission, bails).
     *
     * The playback head is read AFTER the snapshot: the head is cumulative track state, not admission
     * state, and a head sampled slightly later only ever makes a verdict MORE progressed, never
     * attributes a different cue.
     */
    fun completionFor(admissionRaw: Long?): EarconCompletion {
        if (admissionRaw == null) return EarconCompletion.Unknown
        val current = track ?: return EarconCompletion.Unknown
        val frontier = currentFrontier ?: return EarconCompletion.Unknown
        if (frontier.admissionRaw != admissionRaw) return EarconCompletion.Unknown
        if (emittedFrames <= 0) return EarconCompletion.Unknown
        val head = if (readPlaybackHeadFrames != null) {
            readPlaybackHeadFrames!!.invoke(current) ?: return EarconCompletion.Unknown
        } else {
            current.playbackHeadPosition
        }
        return when {
            head >= frontier.target -> EarconCompletion.Consumed
            head > frontier.origin -> EarconCompletion.Playing
            else -> EarconCompletion.Queued
        }
    }

    /**
     * The admission currently owning the track, so a caller can tell TWO admissions of the SAME sound
     * apart -- their label is identical, so only the token distinguishes them.
     */
    fun currentAdmissionForTest(): AdmissionToken? = currentAdmission

    /**
     * The admission that currently owns the track, for a caller that will later stop THAT admission.
     *
     * Read on the player thread, so it is the admission owning the track at the moment of the read rather
     * than one that a later admission may have replaced.
     */
    fun currentAdmissionToken(): AdmissionToken? = onPlayerThread { currentAdmission }

    private val admissionCounter = java.util.concurrent.atomic.AtomicLong(0)

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
            closeRecorderAdmission() // RELEASE IS ATTEMPTED INDEPENDENTLY OF STOP. In one `runCatching` block a THROWING `stop()`
            // skips the release, and the reference is dropped anyway -- so the resource is leaked by
            // exactly the case that most needs releasing. ONE RULE, EVERY CALL SITE.
            track?.runCatching { stop() }
            track?.runCatching { release() }
            track = null
            // The frontier belongs to the track that was just released, so it must not outlive it: the
            // NEXT track's head starts at zero, and carrying the old total forward would make its first
            // cue report Consumed before any of its frames sounded.
            acceptedFramesTotal = 0
            cueHeadTarget = 0
            cueHeadOrigin = 0
            // Same rule as [stopAdmissionNow]: the snapshot and marker clear with the admission.
            currentFrontier = null
            currentAdmissionRaw = 0L
        }
        // Release the worker itself, last: the teardown above runs ON this thread, and the next session
        // creates a fresh one rather than finding a shut-down executor.
        synchronized(executorLock) {
            executor?.shutdown()
            executor = null
        }
    }

    /**
     * Test seam (4d1e5946; 35f7e2d8 correction): substitutes only the AudioTrack CONSTRUCTION.
     * The returned instance goes through the SAME lifecycle as a platform track -- rate reuse,
     * discard-and-recreate, `track = created`, play(), and the acceptedFramesTotal frontier reset --
     * so completion/release bookkeeping is exercised, not bypassed. Null in production.
     */
    @VisibleForTesting
    internal var trackFactoryForTest: ((sampleRateHz: Int) -> AudioTrack)? = null

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
        val created = trackFactoryForTest?.invoke(sampleRateHz) ?: run {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            AudioTrack.Builder()
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
        }
        created.play()
        track = created
        // A NEW track's head starts at zero, so the frontier restarts with it. Carrying the previous
        // track's total forward would make the first cue on the new track unreachable.
        acceptedFramesTotal = 0
        return created
    }

    /**
     * Decodes a PCM WAV resource to mono 16-BIT samples AT THE CLIP'S OWN RATE.
     *
     * The rate is read from the file header and preserved (the track is created at it), per @spec's
     * rate policy: playback keeps original fidelity and only the reference copy is normalized.
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
        fun recordAccepted(
            startSample: Int,
            count: Int,
            recorder: PlaybackBufferRecorder?,
            admission: PlaybackBufferRecorder.Admission,
        ) {
            if (recorder == null || count <= 0) return
            writeCueReference(recorder, samples, sampleRateHz, startSample, count, admission)
        }
    }

    /**
     * THE ACTUAL CUE WRITER the accepted-submission path uses, extracted so a control can drive it
     * directly (queued and partial acceptances included) without clip assets. POSITION ATTRIBUTION
     * (4236341498, conservative per @spec 6e52d610): cue timing is explicitly UNAVAILABLE --
     * recordAccepted observes accepted/submitted samples, not render instants, and a write-instant
     * timestamp would be submission time relabeled as sounding time. The UNTIMED write invalidates
     * any window anchor the cue's unknown sounding time cannot cover. OWNED like every other
     * reference writer (@spec): the samples carry the admission minted when the cue took the track.
     */
    internal fun writeCueReference(
        recorder: PlaybackBufferRecorder,
        samples: ShortArray,
        fromRateHz: Int,
        startSample: Int,
        count: Int,
        admission: PlaybackBufferRecorder.Admission,
    ) {
        val forReference = EarconAudio.resampleRange(
            samples = samples,
            fromRate = fromRateHz,
            toRate = REFERENCE_RATE_HZ,
            startSample = startSample,
            count = count,
        )
        recorder.write(FloatArray(forReference.size) { forReference[it] / 32768f }, admission = admission)
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

        // Distinct producer-id namespace for earcon cues, disjoint from cloud answers
        // (CLOUD_ANSWER_IDENTITY_BASE) and media tap epochs.
        const val EARCON_PRODUCER_BASE: Long = Long.MIN_VALUE / 4

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
