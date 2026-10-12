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
        val played = earconPlayer.play(id)
        if (played) {
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
            coverEarconUntilConsumed()
        }
        return played
    }

    /** The in-flight earcon coverage, so a session end can end it rather than let it run on. */
    private var earconCoverageJob: Job? = null

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
    private fun coverEarconUntilConsumed() {
        earconCoverageJob?.cancel()
        earconCoverageJob = scope.launch {
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
            var renderingDeadlineMs: Long? = null
            while (!disposed) {
                val limit = renderingDeadlineMs ?: (QUEUED_OBSERVATION_MS + elapsed)
                if (observed && elapsed + EMISSION_HEARTBEAT_MS > limit) return@launch
                delay(if (observed) EMISSION_HEARTBEAT_MS else firstWaitMs)
                elapsed += if (observed) EMISSION_HEARTBEAT_MS else firstWaitMs
                observed = true
                when (earconPlayer.completion()) {
                    // CONSUMED IS MEASURED RENDERING, so it NOTES BEFORE ENDING. The head has passed
                    // every accepted frame, which is the STRONGEST evidence the cue sounded -- and a cue
                    // that finished BETWEEN observations would otherwise never be attributed at all.
                    // Returning here without noting treated completion as if it proved nothing.
                    EarconPlayer.EarconCompletion.Consumed -> {
                        noteEmitted()
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
    }

    /**
     * Ends the earcon's attribution now: the cue stopped, failed, or the session ended.
     *
     * Deferred to the player so the acceptance it reports and the acceptance this reads are the same
     * state, and attribution stops at the moment the sound does rather than at its estimate.
     */
    fun stopEarconNow() {
        earconPlayer.stopNow()
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
        earconCoverageJob?.cancel()
        earconPlayer.stopNow()
        earconPlayer.release()
    }

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
