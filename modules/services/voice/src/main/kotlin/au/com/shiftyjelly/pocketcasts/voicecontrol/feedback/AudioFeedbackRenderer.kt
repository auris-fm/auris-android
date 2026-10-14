package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class AudioFeedbackRenderer(
    private val earconPlayer: EarconPlayer,
    private val ttsEngine: TtsEngine,
) {
    private val _hasEmittedAudio = MutableStateFlow(false)
    private val _lastEmittedAtMs = MutableStateFlow(SystemClock.elapsedRealtime())

    /**
     * Whether this app has ever emitted at least one audible response, and when it last did.
     *
     * The gate needs this because earcons and speech ride the media stream, so
     * `AudioManager.isMusicActive` is true while *we* are the ones making the sound. Without it the
     * app reads its own acknowledgement as a foreign app playing, blocks its own gate and closes the
     * microphone mid-conversation: the user hears the beep and the command they said next is
     * discarded, with the log blaming another app.
     */
    val hasEmittedAudio: StateFlow<Boolean> = _hasEmittedAudio
    val lastEmittedAtMs: StateFlow<Long> = _lastEmittedAtMs

    /**
     * Note that this app is making sound *now*, from a path that is not this renderer.
     *
     * [noteEmitted] is private because every caller used to be in this class — but the cloud
     * player is a second producer of audible output, and it was the one the gate did not know
     * about: a cloud answer played for seconds while the gate saw a stale self-emission, read
     * `isMusicActive` as a foreign app, and cut the microphone mid-answer. Rather than teach the
     * gate about cloud turns, the cloud path joins the signal that already answers this question.
     *
     * This is the same shape as [speakWithHeartbeat]: longer-than-the-window audio refreshes the
     * stamp for its own duration, because speech outlasting the window reads as a foreign app for
     * the rest of the answer.
     */
    fun noteAudibleNow() = noteEmitted()

    /**
     * How many times the gate's attribution has been refreshed. Test visibility for the coverage
     * contract: a cue longer than the heartbeat window needs more than its submission note.
     */
    private val emissionNotes = java.util.concurrent.atomic.AtomicInteger(0)

    /** The attribution notes taken so far. See [emissionNotes]. */
    fun emissionNotesForTest(): Int = emissionNotes.get()

    private fun noteEmitted() {
        emissionNotes.incrementAndGet()
        _hasEmittedAudio.value = true
        _lastEmittedAtMs.value = SystemClock.elapsedRealtime()
    }

    /**
     * Where the heartbeat and the earcon coverage run.
     *
     * Main by default, because this renderer is driven from the service's lifecycle and the gate's
     * state is read on Main. A test may substitute a dispatcher it controls, which is the only way to
     * observe a heartbeat deterministically -- real time plus a real dispatcher makes the assertion
     * depend on scheduling rather than on the code.
     */
    var scopeDispatcherForTest: kotlinx.coroutines.CoroutineDispatcher? = null

    /** The renderer's scope, created once so coverage is not orphaned by a later cancellation. */
    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + (scopeDispatcherForTest ?: Dispatchers.Main.immediate))
    }
    private var currentJob: Job? = null

    /**
     * Whether this renderer is DISPOSED for good, as opposed to between service sessions.
     *
     * The two are different and the distinction is user-facing: `VoiceControlService` is released on
     * every stop and this type is a `@Singleton`, so a single flag made one stop disable all feedback
     * for the rest of the process. [endSession] handles the stop; this handles disposal.
     */
    private var disposed = false

    /**
     * Called when a spoken reply did not finish playing. Defaulted to the structured log so an
     * incomplete answer is never silent; a host can replace it to surface the failure upward.
     */
    var onPlaybackFailure: (Throwable) -> Unit = { error ->
        Timber.e(error, "[VoiceResponse] spoken reply did not finish playing")
    }

    fun render(response: VoiceResponse, language: String = "en") {
        if (disposed) return
        // Debug-only diagnostic: what the app is about to say, before TTS. This is the one
        // place every spoken response passes through, so it is where a response that is
        // identical regardless of the prompt can be told from one that differs. Debug-gated
        // because the standing rule for this pipeline is that server-derived text does not
        // reach device logs.
        if (BuildConfig.DEBUG) {
            Timber.d("[VoiceResponse] %s", describeForLog(response, language))
        }
        currentJob?.cancel()
        // Launched coroutine inherits the Job cancellation: when we cancel currentJob
        // above, any child suspend calls (like TtsEngine.speak) will be cancelled.
        currentJob = scope.launch {
            try {
                when (response) {
                    is VoiceResponse.Silent -> { /* no-op */ }

                    is VoiceResponse.Earcon -> playEarcon(response.id)

                    is VoiceResponse.Spoken -> speakWithHeartbeat(response.text, language)

                    is VoiceResponse.Combined -> {
                        playEarcon(response.earcon)
                        speakWithHeartbeat(response.spokenText, language)
                    }
                }
            } catch (e: TtsPlaybackIncompleteException) {
                // The reply did not finish playing. Report it as a delivery failure rather than a
                // completed reply, and let the caller own whatever cleanup that implies.
                Timber.e(
                    e,
                    "[VoiceResponse] reply did not finish playing: played %d of %d frames",
                    e.framesPlayed,
                    e.framesWritten,
                )
                onPlaybackFailure(e)
            }
        }
    }

    /**
     * Plays an earcon directly, outside a rendered response.
     *
     * This is the path the wake and listening cues take, and they are the two earcons that sound
     * while the microphone is open — so this is where the gate has to know the sound is ours.
     */
    fun playEarcon(id: EarconId): Boolean {
        if (disposed) return false
        // THE ADMISSION COMES BACK FROM THE PLAY, so the cue this renderer covers is the cue it
        // admitted. Reading "the current admission" afterwards would answer a different question: a
        // successor could take the track in between, and this would then hold the SUCCESSOR'S token
        // while believing it held its own.
        val admitted = earconPlayer.play(id)
        if (admitted != null) {
            // NO UNCONDITIONAL ATTRIBUTION. `play` reporting true means the sink ACCEPTED the cue, which
            // is not the same as its being audible: the cue may sit queued behind earlier audio, and the
            // gate would then read the microphone as ours before our own cue began. Attribution is the
            // heartbeat's job, and it fires only on OBSERVED progress past the cue's first frame.
            // The coverage still starts here, because the queued delay is part of the span to cover.
            // The cue has been ACCEPTED by the sink, which is not the same as its having finished
            // sounding. A cue longer than the gate's attribution window would leave the microphone
            // read as a foreign app part-way through our own cue -- the same failure the speak path
            // covers with a heartbeat, and the reason this cannot rest on the submission note alone.
            //
            // The span is the clip's OWN duration, from the frames the sink accepted and the rate it
            // accepted them at, so a shorter cue is not covered longer than it sounds and a longer one
            // is not cut short by an assumed length.
            coverEarconUntilConsumed(id, admitted)
        }
        // THE PUBLIC RESULT STILL REPORTS ACCEPTANCE. Returning true unconditionally made a REFUSED cue
        // look accepted to every caller, so a caller could not tell a cue that never sounded from one
        // that did -- the same conflation the null result exists to express.
        return admitted != null
    }

    /** The in-flight earcon coverage, so a session end can end it rather than let it run on. */
    private var earconCoverageJob: Job? = null

    /** Serializes ownership validation with an observation's publication. */
    private val coverageLock = Any()

    /**
     * Runs INSIDE an observer, after it starts and before it observes, so a case can hold it in flight.
     *
     * Null in production -- an already-completed deferred -- so the await costs a fast path and changes
     * no behaviour. Test-only, and it exists because a real delayed continuation CANNOT be simulated by
     * finishing the observer and then calling a retirement by hand.
     */
    internal var holdObserverForTest: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /**
     * Completed by the observer that TAKES the hold, and carrying that observer's OWN job.
     *
     * `coverageIsRunningForTest` reports only whether the CURRENT job is active, which is not the same as
     * "A arrived at the hold" -- a predecessor is already replaced by the time we look, so that probe could
     * be true for the successor and the case would assert a precondition it never established. This
     * acknowledgement is the arrival proof, and the job it carries is the one whose cancellation and
     * eventual refusal the case is about.
     */
    internal var holdArrivalForTest: kotlinx.coroutines.CompletableDeferred<Job>? = null

    /**
     * Whether the coverage observation is STILL RUNNING, so a case can assert that a deadline BOUNDS it.
     *
     * A note count cannot show this: a queued cue emits no notes either way, so a deadline that never
     * expires and one that does look identical from the count alone. The observation's OWN lifetime is
     * the thing the bound is about -- and a deadline computed from `elapsed` each pass can never be
     * reached, which only its lifetime reveals.
     */
    internal fun coverageIsRunningForTest(): Boolean = earconCoverageJob?.isActive == true

    /**
     * What became of a cue, as the renderer was able to OBSERVE it.
     *
     * A per-cue PRODUCTION outcome, not a test readout. [Unknown] exists because the observation's own
     * deadline can expire without the cue's fate ever being established: the cue may still be queued, may
     * have stalled, or may have finished sounding with nobody looking. A caller that collapsed that into
     * "consumed" would claim audio nobody observed, and one that collapsed it into "stopped" would claim
     * a stop nobody observed either.
     *
     * DISTINCT FROM AN ACTION OUTCOME. That the observation expired says nothing about whether an action
     * ran, and it does not authorize a replay.
     */
    enum class CueObservation {
        /** The cue's frames were OBSERVED consumed -- the head passed every accepted frame. */
        Consumed,

        /** The cue stopped being consumable; its attribution was ended deliberately. */
        Ended,

        /** The deadline expired with the cue's fate unestablished. */
        Unknown,
    }

    /**
     * The last per-cue observation, so the owning consumer can act on it.
     *
     * Exposed as a StateFlow, not a plain field: the consumer EVALUATES asynchronously from the
     * publication, so a value read once at construction would be the value at that moment forever.
     */
    private val _lastCueObservation = kotlinx.coroutines.flow.MutableStateFlow<PublishedObservation?>(null)
    val lastCueObservation: kotlinx.coroutines.flow.StateFlow<PublishedObservation?> = _lastCueObservation

    /**
     * A published verdict WITH the cue it is about and the instant it was published.
     *
     * ALL THREE TRAVEL TOGETHER, because the consumer needs each of them and can derive none:
     *
     *  - [cueId] makes a verdict attributable to ONE cue. Without it a successor's verdict is
     *    indistinguishable from its predecessor's, so a consumer that missed the intermediate clear
     *    would apply the old cue's elapsed time to the new one.
     *  - [publishedAtMs] is the RENDERER'S instant, not the consumer's first sight of it. A bound
     *    anchored to when the consumer happened to poll starts later for a cue published earlier, and
     *    would measure the consumer's schedule rather than the cue's age.
     *  - [observation] is the verdict itself.
     */
    data class PublishedObservation(
        val cueId: Long,
        val observation: CueObservation,
        val publishedAtMs: Long,
    )

    /**
     * Whether the last observation ended because its DEADLINE expired without an observed ending.
     *
     * DEADLINE EXPIRY IS UNKNOWN, NOT CONSUMED: the cue's fate was not established, so a caller must not
     * read the ending as evidence that the audio finished sounding.
     */
    @Volatile
    private var coverageExpiredUnknown: Boolean = false

    internal fun coverageExpiredUnknownForTest(): Boolean = coverageExpiredUnknown

    /**
     * One writer for the observation and its typed form, so they cannot disagree.
     *
     * The two travel together because the consumer reads the TYPED value while the timing controls read
     * the flag; publishing them separately would let one be updated and the other not, which is the
     * disagreement a single write prevents.
     */
    private fun publishCueObservation(cueId: Long, observation: CueObservation) {
        coverageExpiredUnknown = observation == CueObservation.Unknown
        _lastCueObservation.value = PublishedObservation(
            // THE CUE'S OWN IDENTITY, allocated once when the cue was admitted. Allocating here instead
            // would give ONE cue a new id for every verdict it publishes -- and a consumer comparing ids
            // would read its own cue's change of verdict as a DIFFERENT cue, discarding the elapsed time
            // it should keep. An identity is a property of the cue, not of the publication.
            cueId = cueId,
            observation = observation,
            // THE PUBLICATION TIMESTAMP IS per-observation, unlike the identity: each verdict is a
            // separate event and the consumer measures its own hold from the verdict it is acting on.
            publishedAtMs = SystemClock.elapsedRealtime(),
        )
    }

    /** Distinguishes one cue's verdict from the next cue's, so a consumer cannot conflate them. */
    private val cueCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** The identity of the cue currently being observed, allocated when it was admitted. */
    private var currentCueId: Long = 0L

    /**
     * The PLAYER admission the renderer currently holds, so a case can assert WHICH cue's audio it is
     * observing instead of inferring it from the verdict.
     */
    internal var currentCueAdmission: EarconPlayer.AdmissionToken? = null
        private set

    /**
     * Publishes an observation ONLY while [observer] is still the cue's current observer.
     *
     * `coverageExpiredUnknown` is written by a NON-SUSPENDING assignment, so cancellation cannot protect
     * it: an old observer resuming after a successor has published would overwrite the successor's
     * result with its own. THE VALIDATION AND THE WRITE ARE ONE STEP, under the same monitor that
     * installs a successor, so the two cannot interleave.
     */
    private fun publishObservationIfOwning(observer: Job?, write: () -> Unit) {
        synchronized(coverageLock) {
            if (observer == null || earconCoverageJob !== observer) return
            write()
        }
    }

    /** The deadline-expiry form of [publishObservationIfOwning]. */
    private fun publishExpiryIfOwning(observer: Job?) = publishObservationIfOwning(observer) { publishCueObservation(currentCueId, CueObservation.Unknown) }

    /** The observed-completion form: the cue's fate IS established. */
    private fun publishConsumedIfOwning(observer: Job?) = publishObservationIfOwning(observer) { publishCueObservation(currentCueId, CueObservation.Consumed) }

    /**
     * Attempts a retirement for [owner], and reports whether that owner still spoke for the cue.
     *
     * TEST VISIBILITY for the REAL stale-retirement path: a retirement is only published while its owner
     * still owns the cue, which is what makes a stale continuation unable to overwrite its successor. The
     * production caller is the observation loop's own ending; passing a substituted owner lets a case
     * exercise the refusal without standing up a second concurrent observer.
     */
    internal fun retireObservationForTest(owner: kotlinx.coroutines.Job?) = publishEndedIfOwning(owner)

    /** The deliberately-ended form: attribution was stopped at the moment the sound stopped. */
    private fun publishEndedIfOwning(observer: Job?) = publishObservationIfOwning(observer) { publishCueObservation(currentCueId, CueObservation.Ended) }

    /**
     * Keeps the gate's attribution fresh until the cue is OBSERVED to have been consumed.
     *
     * The estimated duration is a BOUND, not the ending condition: the loop ends when the output
     * reports it has played the accepted frames, which also covers the queued delay before the cue
     * starts sounding. It ends early on a cue that stops being consumable -- a failure, a stop, or a
     * supersession clears the acceptance, so the loop exits instead of attributing audio the user can
     * no longer hear.
     *
     * Runs on the renderer's own scope so a synchronous caller is not blocked.
     */
    private fun coverEarconUntilConsumed(startedEarcon: EarconId, admitted: EarconPlayer.AdmissionToken) {
        // THE OBSERVER KNOWS ITS OWN CUE. `coverageExpiredUnknown` is a plain field written by a
        // NON-SUSPENDING assignment after `completion()` returns, so cancellation alone cannot protect it:
        // an OLD observer can resume after a SUCCESSOR has published its own result and overwrite it.
        // Each observer therefore carries the job that owns it and validates ownership in the same step
        // as the write -- if the current job is not this one, this observer no longer speaks for the cue.
        // THE IDENTITY IS THE JOB ITSELF, CAPTURED BEFORE IT STARTS. Reading it from inside the
        // coroutine would race the assignment below -- `launch` can begin before `earconCoverageJob` is
        // set, so an inner capture would compare against `null` and return immediately, ending every
        // observation before it ran. `CoroutineStart.LAZY` lets the identity be published first, and the
        // coroutine is then started explicitly.
        synchronized(coverageLock) {
            earconCoverageJob?.cancel()
            // A NEW CUE'S OBSERVATION STARTS WITHOUT A VERDICT. Clearing the flag is not a claim that the
            // cue ended; it is the absence of any observation yet -- the typed value is cleared too, so a
            // consumer cannot read the PREVIOUS cue's result as this one's.
            coverageExpiredUnknown = false
            // THE NEW CUE'S IDENTITY IS ALLOCATED AT ADMISSION, so every verdict this cue publishes
            // carries the SAME id and a consumer can tell "my cue changed" from "a different cue".
            currentCueId = cueCounter.incrementAndGet()
            currentCueAdmission = admitted
            // THE ADMISSION THE PLAY RETURNED, not one re-read afterwards: the token the renderer holds
            // is then the one the player granted for THIS play.
            _lastCueObservation.value = null
        }
        // A REFERENCE THE COROUTINE CAN READ FROM INSIDE ITS OWN INITIALIZER. Kotlin does not allow a
        // `val` to be referenced within its own initialiser, and the observer needs its own job identity
        // for the ownership check -- so the identity is published through a holder that is set before the
        // observer ever runs.
        val observerRef = kotlinx.coroutines.CompletableDeferred<Job>()
        val observer = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // THE HOLD SEAM. Reached after this observer has started but BEFORE it observes anything, so a
            // case can keep THIS observer in flight while a successor takes the cue. That is the only way
            // to exercise a genuinely delayed continuation -- a case that lets the observer finish and
            // then calls a retirement by hand tests the hand, not the continuation.
            // A cue that is never observed to progress must not be covered forever: the estimate
            // bounds the wait, and the bound is only a ceiling.
            val boundMs = earconPlayer.emittedDurationMs()
            var elapsed = 0L
            // THE CUE IS OBSERVED AT LEAST ONCE, whatever its length. A cue SHORTER than the heartbeat
            // (100 frames is about 6 ms) has a bound below one tick, so a loop that required the tick to
            // fit inside the bound NEVER RAN -- and a cue that was never observed was never attributed,
            // however audibly it sounded. The bound is a CEILING on how long to wait for the cue to
            // finish, not a reason to skip looking.
            // ONE OBSERVATION IS ALWAYS MADE, AND THE BOUND IS A SINGLE CEILING ON THE WHOLE WAIT.
            //
            // A cue SHORTER than the heartbeat (100 frames is about 6 ms) has a bound below one tick, so
            // a loop that required a whole tick to fit inside the bound NEVER RAN -- and a cue never
            // observed was never attributed, however audibly it sounded. So the wait is scaled to the
            // cue: the first observation happens inside its span, and the loop then continues on the
            // regular heartbeat only while the cue's own span has not been crossed.
            val firstWaitMs = minOf(EMISSION_HEARTBEAT_MS, maxOf(boundMs, 1L))
            var observed = false
            // A BOUNDED OBSERVATION POLICY, INDEPENDENT OF THE PCM SPAN.
            //
            // The PCM duration is what the client SUBMITTED; it is not a claim about when hardware
            // finishes, and it is wrong in both directions here -- a long-queued cue has a span far
            // smaller than the sound still to come, and a cue that stopped early must not be covered past
            // its end. So the observation is bounded by ITS OWN limits:
            //
            //   QUEUED     at most QUEUED_OBSERVATION_MS from submission; a cue that never begins
            //              sounding must not be awaited forever.
            //   RENDERING  after the FIRST measured rendering, at most max(QUEUED_OBSERVATION_MS,
            //              accepted PCM duration + RENDERING_GRACE_MS) from that observation.
            //
            // DEADLINE EXPIRY IS AN UNKNOWN/STALLED OBSERVATION, not evidence the cue was consumed. These
            // are conservative observation limits, not claims about hardware completion.
            var stillQueued = true
            // A FIXED DEADLINE, NOT ONE MEASURED FROM `elapsed` EACH PASS. `QUEUED_OBSERVATION_MS +
            // elapsed` grows with the loop, so the limit can never be reached and a permanently queued
            // cue is observed forever -- the deadline would be a formality rather than a bound. The
            // queued limit is absolute from submission; the rendering limit is set ONCE, at the first
            // measured rendering, and is likewise absolute from there.
            val queuedDeadlineMs = QUEUED_OBSERVATION_MS
            var renderingDeadlineMs: Long? = null
            while (!disposed) {
                val limit = renderingDeadlineMs ?: queuedDeadlineMs
                // WAIT THE REMAINING INTERVAL, do not give up a whole heartbeat short. Returning as soon
                // as the NEXT full heartbeat would overshoot the deadline ends the observation at
                // 1_999 ms for a 2_000 ms limit -- one tick early, so the cue is never observed AT the
                // boundary. The loop sleeps exactly as far as the deadline and observes there.
                val remainingMs = limit - elapsed
                if (observed && remainingMs <= 0) {
                    // THE DEADLINE EXPIRED: the observation gives up WITHOUT having established the
                    // cue's fate, so the ending is UNKNOWN rather than a claim that it completed.
                    // OWNERSHIP AND THE WRITE ARE ONE STEP: a superseding observation has already
                    // published its own result, and this one must not overwrite it.
                    publishExpiryIfOwning(observerRef.await())
                    return@launch
                }
                val waitMs = if (!observed) {
                    minOf(firstWaitMs, maxOf(remainingMs, 1L))
                } else {
                    minOf(EMISSION_HEARTBEAT_MS, maxOf(remainingMs, 1L))
                }
                delay(waitMs)
                elapsed += waitMs
                observed = true
                // THE HOLD SITS AFTER THE WAIT, IMMEDIATELY BEFORE THE OBSERVATION IS READ. This placement
                // is the whole distinction: a hold BEFORE `delay` releases into a CANCELLABLE suspension,
                // so a cancelled continuation throws there and never reaches the observation at all --
                // which made the earlier fixture unobservable rather than discriminating. Here the
                // continuation has no further suspension before it reads the output and publishes, so a
                // cancelled continuation reaches the publish path exactly as production's does whenever a
                // successor is admitted during this window.
                // ONE-SHOT: the observer that finds a hold takes it, so a SUCCESSOR runs normally.
                synchronized(coverageLock) {
                    val pending = holdObserverForTest
                    holdObserverForTest = null
                    pending
                }?.let { barrier ->
                    // ARRIVAL IS ACKNOWLEDGED BEFORE WAITING, and it carries THIS observer's identity, so
                    // the case knows A genuinely reached the hold rather than inferring it from a probe
                    // about the current job.
                    holdArrivalForTest?.complete(observerRef.await())
                    barrier.await()
                }
                when (earconPlayer.completion()) {
                    // CONSUMED IS MEASURED RENDERING, so it NOTES BEFORE ENDING. The head has passed
                    // every accepted frame, which is the STRONGEST evidence the cue sounded -- and a cue
                    // that finished BETWEEN observations would otherwise never be attributed at all.
                    // Returning here without noting treated completion as if it proved nothing.
                    EarconPlayer.EarconCompletion.Consumed -> {
                        noteEmitted()
                        // AN OBSERVED ENDING IS NOT AN EXPIRY: the cue's fate IS established -- and it
                        // is only published if this observer still owns the cue.
                        publishConsumedIfOwning(observerRef.await())
                        return@launch
                    }

                    EarconPlayer.EarconCompletion.Unknown -> return@launch

                    // STILL QUEUED: accepted and not yet sounding. It must NOT set the attribution --
                    // an emitted sound is one the listener could hear, and this one has not started.
                    // The loop keeps waiting, which is what makes the queued delay covered rather than
                    // mistaken for silence.
                    EarconPlayer.EarconCompletion.Queued -> {
                        // STILL QUEUED: keep waiting past the estimate, because the sound has not begun.
                        stillQueued = true
                    }

                    // Observed sounding: refresh the attribution so the gate knows the sound is ours.
                    EarconPlayer.EarconCompletion.Playing -> {
                        // Observed sounding, so the cue is no longer merely waiting -- and the observation
                        // now runs to the POST-START limit, measured from THIS observation.
                        if (stillQueued || renderingDeadlineMs == null) {
                            renderingDeadlineMs = elapsed +
                                maxOf(QUEUED_OBSERVATION_MS, boundMs + RENDERING_GRACE_MS)
                        }
                        stillQueued = false
                        noteEmitted()
                    }
                }
            }
        }
        synchronized(coverageLock) {
            earconCoverageJob = observer
            observerRef.complete(observer)
        }
        observer.start()
    }

    /**
     * Speaks while refreshing what we last emitted, because speech outlasts the gate's attribution
     * window: a reply longer than it would otherwise read as a foreign app for the rest of its own
     * duration, and the microphone would be closed mid-answer.
     */
    private suspend fun speakWithHeartbeat(text: String, language: String) = coroutineScope {
        noteEmitted()
        // The utterance runs in a child so the heartbeat can tick alongside it, but its failure must
        // propagate OUT of this function: catching it inside the launch would let the child finish
        // normally and this call return as though the reply had been delivered.
        val utterance = launch { ttsEngine.speak(text, language) }
        while (utterance.isActive) {
            delay(EMISSION_HEARTBEAT_MS)
            if (utterance.isActive) noteEmitted()
        }
        // Surfaces TtsPlaybackIncompleteException (or any other failure) to the caller.
        utterance.join()
    }

    /**
     * Ends a service session: frees audio resources but leaves the renderer usable.
     *
     * Called from `stopVoiceControl()`, which runs on EVERY stop. Because this type is a `@Singleton`,
     * treating that as disposal meant the next start had no feedback at all -- silently.
     */
    fun endSession() {
        currentJob?.cancel()
        // End the cue's attribution HERE rather than letting an estimate cover it: the session is
        // stopping, so whatever the output was doing, the user is no longer hearing it and the gate
        // must stop treating it as ours.
        // THE SESSION'S END RETIRES ITS CUE. Cancelling the observer alone leaves whatever it last
        // published standing, and after a session ends that verdict is about a cue nobody can hear any
        // more -- an Unknown left published would attribute all later music to us.
        publishEndedIfOwning(currentObserver())
        earconCoverageJob?.cancel()
        earconPlayer.stopNow()
        earconPlayer.release()
    }

    /** The observer that currently speaks for the cue, read under the monitor that installs one. */
    private fun currentObserver(): Job? = synchronized(coverageLock) { earconCoverageJob }

    /**
     * What an owner's lifecycle event should do to this renderer.
     *
     * Named rather than left to two call sites, because the CHOICE is the defect this exists to
     * prevent: `stopVoiceControl` runs on EVERY stop while this type is a `@Singleton`, so releasing
     * there silenced all feedback for the process. A caller selects a constant, and the service's two
     * call sites are pinned by a test without standing the Service up.
     */
    enum class Lifecycle {
        /** The owning session ended; the renderer stays usable for the next one. */
        SessionEnded,

        /** The owner is gone for good; a later emit is refused. */
        Disposed,
    }

    /** Applies [event], so the choice between ending and disposing has one home. */
    fun applyLifecycle(event: Lifecycle) = when (event) {
        Lifecycle.SessionEnded -> endSession()
        Lifecycle.Disposed -> release()
    }

    /** Disposes the renderer for good. A later [render] or [playEarcon] is refused. */
    fun release() {
        disposed = true
        scope.cancel()
        earconPlayer.stopNow()
        earconPlayer.release()
        ttsEngine.release()
    }

    internal companion object {
        /** Shorter than the gate's attribution window, so a long reply never falls out of it. */
        private const val EMISSION_HEARTBEAT_MS = 1_000L

        /** The most a QUEUED cue is observed from submission before the observation gives up. */
        private const val QUEUED_OBSERVATION_MS = 2_000L

        /** How much longer than the accepted PCM duration a RENDERING cue is observed. */
        private const val RENDERING_GRACE_MS = 1_000L

        /**
         * One line describing what is about to be spoken, including the text itself, its length
         * and a digest. The length and digest answer "is this the same response" without having
         * to compare prose by eye, and the digest is stable for a given text within a build.
         */
        internal fun describeForLog(response: VoiceResponse, language: String): String = when (response) {
            is VoiceResponse.Silent -> "kind=silent nothing_spoken"

            is VoiceResponse.Earcon -> "kind=earcon earcon=${response.id} nothing_spoken"

            is VoiceResponse.Spoken -> spokenForLog("spoken", response.text, language)

            is VoiceResponse.Combined -> spokenForLog(
                "combined earcon=${response.earcon}",
                response.spokenText,
                language,
            )
        }

        private fun spokenForLog(prefix: String, text: String, language: String): String = "kind=$prefix lang=$language len=${text.length} digest=${digest(text)} text=\"${escapeForLog(text)}\""

        internal fun digest(text: String): String = text.hashCode().toUInt().toString(16)

        /**
         * Keeps one response on one line. An answer containing a newline would otherwise push the
         * rest of itself onto continuation lines that carry no prefix, so grepping responses would
         * compare only the first line of each and two different answers could look identical.
         */
        internal fun escapeForLog(text: String): String = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
