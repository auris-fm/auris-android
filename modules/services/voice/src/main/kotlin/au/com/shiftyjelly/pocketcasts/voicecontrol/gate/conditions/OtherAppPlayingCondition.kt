package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions

import android.media.AudioManager
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleGroup
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OtherAppPlayingCondition(
    private val audioManager: AudioManager? = null,
    private val hostIsPlaying: StateFlow<Boolean> = MutableStateFlow(false),
    // Our own earcons and speech ride the media stream, so isMusicActive is true while we are the
    // ones making the sound. Without this the app reads its own acknowledgement as a foreign app.
    private val hasEmittedAudio: StateFlow<Boolean> = MutableStateFlow(false),
    private val lastEmittedAtMs: StateFlow<Long> = MutableStateFlow(0L),
    // WHAT BECAME OF OUR LAST CUE, as the renderer could OBSERVE it.
    //
    // An observation whose deadline expired without establishing the cue's fate means we CANNOT say the
    // sound has stopped -- so the self-emission window stays open rather than closing on an assumption
    // nobody could check. Absent or observed-ended, the window behaves as it always did.
    private val lastCueObservation: StateFlow<au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer.PublishedObservation?> =
        MutableStateFlow(null),
    // Ask the renderer for measured progress now, rather than waiting for its next heartbeat.
    private val refreshSelfEmission: () -> Unit = {},
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val transitionWindowMs: Long = 5_000,
    private val debounceMs: Long = 500,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VoiceControlRule {

    override val id = ID
    override val group = VoiceControlRuleGroup.Conflicts

    private val mutableState = MutableStateFlow<VoiceControlRuleState>(VoiceControlRuleState.Unknown("initializing"))
    override val state: StateFlow<VoiceControlRuleState> = mutableState

    private var debounceJob: Job? = null

    // Last time (monotonic ms) the host was observed actively playing, used for a
    // bounded attribution window so a play/pause/route transition (where isMusicActive
    // is true but isPlaying has not yet flipped) is still attributed to the host without
    // permanently treating a long-paused loaded episode as host-owned.
    private var lastHostPlayingMs: Long = Long.MIN_VALUE

    // Our own last audible feedback, in the same monotonic clock as `nowMs`.
    private var lastSelfEmittedMs: Long = Long.MIN_VALUE

    init {
        if (audioManager != null) {
            scope.launch { pollLoop() }
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            val hasOtherApp = withContext(Dispatchers.IO) {
                val am = audioManager ?: return@withContext false
                otherAppPlaying(am.isMusicActive)
            }
            if (hostIsPlaying.value) {
                lastHostPlayingMs = nowMs()
            }
            handleStateChange(hasOtherApp, revalidate = true)
            delay(1000)
        }
    }

    /**
     * Directly updates the condition state, bypassing the AudioManager.
     * Used for testing and by external state monitors.
     */
    fun update(otherAppPlaying: Boolean) {
        handleStateChange(otherAppPlaying)
    }

    private fun handleStateChange(hasOtherApp: Boolean, revalidate: Boolean = false) {
        debounceJob?.cancel()
        if (hasOtherApp) {
            debounceJob = scope.launch {
                delay(debounceMs)
                // Output and attribution may have changed during the debounce. Publish a block only
                // if its premise is still true; update(Boolean) remains an external monitor's verdict.
                val stillOtherApp = !revalidate || withContext(Dispatchers.IO) {
                    otherAppPlaying(audioManager?.isMusicActive == true)
                }
                mutableState.value = evaluate(stillOtherApp)
            }
        } else {
            mutableState.value = VoiceControlRuleState.Allowed
        }
    }

    fun evaluate(): VoiceControlRuleState {
        val hasOtherApp = audioManager?.let { am -> otherAppPlaying(am.isMusicActive) } ?: false
        return evaluate(hasOtherApp)
    }

    /**
     * Whether an unresolved observation is still within its hold.
     *
     * MEASURED FROM THE RENDERER'S OWN PUBLICATION INSTANT, and keyed to the CUE that published it. The
     * two facts come from the published value, never from this consumer: anchoring to the first poll
     * would measure this consumer's schedule instead of the cue's age, and keying on the value alone
     * would let a successor that went straight to Unknown inherit its predecessor's elapsed time.
     *
     * A DIFFERENT CUE IS A DIFFERENT CUE even if the verdict is the same, so the identity -- not the
     * verdict -- decides whether the elapsed time carries over.
     */
    private fun unresolvedWithinHold(): Boolean {
        val published = lastCueObservation.value
            ?: return false
        if (published.observation !=
            au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer.CueObservation.Unknown
        ) {
            return false
        }
        return nowMs() - published.publishedAtMs < UNRESOLVED_HOLD_MS
    }

    /**
     * One place that assembles the attribution arguments, so the polled path and an on-demand
     * evaluation cannot disagree about them.
     */
    internal fun otherAppPlaying(isMusicActive: Boolean): Boolean {
        if (isMusicActive) refreshSelfEmission()
        if (hasEmittedAudio.value) lastSelfEmittedMs = lastEmittedAtMs.value
        return otherAppPlaying(
            isMusicActive = isMusicActive,
            hostCurrentlyPlaying = hostIsPlaying.value,
            msSinceHostPlaying = nowMs() - lastHostPlayingMs,
            msSinceSelfEmitted = if (lastSelfEmittedMs == Long.MIN_VALUE) {
                -1L
            } else {
                nowMs() - lastSelfEmittedMs
            },
            transitionWindowMs = transitionWindowMs,
            // AN UNKNOWN OBSERVATION KEEPS THE SELF-EMISSION WINDOW OPEN -- BUT ONLY FOR AS LONG AS THE
            // OBSERVATION ITSELF COULD HAVE BEEN WAITING. The deadline expiring did not establish that
            // the cue stopped sounding, so treating it as ended immediately would attribute audio we may
            // still be making to a foreign app.
            //
            // IT IS A BOUND, NOT A PERMANENT STATE. An unresolved cue is unresolved for a bounded time;
            // keeping it open indefinitely would attribute EVERY LATER active-music reading to us, long
            // after the cue or its session had ended -- the mirror of the failure it prevents, and the
            // reason an explicit stop/session end retires the observation.
            selfEmissionUnresolved = unresolvedWithinHold(),
        )
    }

    internal fun evaluate(otherAppPlaying: Boolean): VoiceControlRuleState {
        return if (otherAppPlaying) {
            VoiceControlRuleState.Blocked("other_app_playing")
        } else {
            VoiceControlRuleState.Allowed
        }
    }

    companion object {
        const val ID = "other_app_playing"
    }
}

/**
 * Decides whether "another app is playing": audio is active ([isMusicActive]) and the
 * host does not own it. The host owns audio when it is currently playing
 * ([hostCurrentlyPlaying]) or was playing within [transitionWindowMs] (i.e.
 * [msSinceHostPlaying] < window). The window covers the play/pause/route transition
 * where `AudioManager.isMusicActive()` is true while the host's `isPlaying` has not yet
 * flipped — previously that misattributed the host's own audio to "another app" and
 * blocked the mic. Bounding the window (rather than treating any loaded episode as
 * host-owned) keeps the gate blocking when a genuinely different app plays while the
 * host is long-paused.
 */
internal fun otherAppPlaying(
    isMusicActive: Boolean,
    hostCurrentlyPlaying: Boolean,
    msSinceHostPlaying: Long,
    msSinceSelfEmitted: Long,
    transitionWindowMs: Long,
    selfEmissionUnresolved: Boolean = false,
): Boolean {
    if (!isMusicActive) return false
    val hostOwnsAudio = hostCurrentlyPlaying ||
        (msSinceHostPlaying >= 0 && msSinceHostPlaying < transitionWindowMs) ||
        // Our own feedback is not a foreign app. Earcons ride the media stream and are 0.15-0.4s
        // long, so a 1 Hz poll can land inside one; without this term the app blocks its own gate
        // and stops listening in the middle of a session, having just played the acknowledgement.
        (msSinceSelfEmitted >= 0 && msSinceSelfEmitted < transitionWindowMs) ||
        // AN UNRESOLVED OBSERVATION HOLDS THE ATTRIBUTION OPEN. The window above is a BOUNDED guess
        // about how long our own sound lasts; when the observation's deadline expired, that guess is no
        // longer something we are entitled to make -- the cue may still be sounding. Reading expiry as
        // "the sound stopped" would hand audio we may still be making to a foreign app, which is the
        // mid-session self-block this whole term exists to prevent.
        selfEmissionUnresolved
    return !hostOwnsAudio
}

/**
 * How long an unresolved cue observation may hold our own audio attributed to the host.
 *
 * Generous relative to the observation's own deadline, because it exists to cover the gap between the
 * observation giving up and an explicit stop or session end retiring it -- not to bound the cue itself.
 */
internal const val UNRESOLVED_HOLD_MS: Long = 30_000
