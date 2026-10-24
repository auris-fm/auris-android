package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

/**
 * THE STRUCTURED ACTION RESULT (spec 03c96924).
 *
 * A sink reports what it ACTUALLY did at the point where it decides it — the focus-grant branch, a
 * missing episode, a thrown failure — independently of what it renders afterwards. Presentation and
 * outcome are separate facts and neither implies the other:
 *
 *  - `UpNextQueueSink.clear()` mutates the queue and then returns `Earcon(CONFIRM_REQUIRED)`, so
 *    its presentation reads as a refusal while the action really happened.
 *  - `PlaybackManagerPlaybackSink.duck()` returns `Silent` on both grant and rejection.
 *  - `VoiceIntent.Playback.Resume` returns `Silent` after a real resume.
 *
 * An action a sink never reports is UNOBSERVED, never inferred from the response.
 */
sealed interface VoiceActionOutcome {
    /**
     * The sink DISPATCHED the action and nothing failed on the way out. For an asynchronous action
     * this is dispatch only: `clearUpNextAsync()` returning proves the call was made, NOT that the
     * queue was cleared. A sink that can observe a completion reports [Performed] instead.
     */
    data object Dispatched : VoiceActionOutcome

    /** The sink observed the action COMPLETE at a boundary it controls (e.g. a returned result). */
    data object Performed : VoiceActionOutcome

    /** A precondition the sink itself checks did not hold, so it did nothing. */
    data class PreconditionFailed(val reason: String) : VoiceActionOutcome

    /** The sink declined by policy (e.g. it requires explicit confirmation first). */
    data class Refused(val reason: String) : VoiceActionOutcome

    /** The sink attempted the action and it failed. */
    data class Failed(val reason: String) : VoiceActionOutcome

    /**
     * The attempt was abandoned BEFORE the sink's decision point. This deliberately does not claim
     * "the action did not happen": a cancellation can arrive after work already ran, so the honest
     * fact is that the result was not observed.
     */
    data object NotObserved : VoiceActionOutcome
}
