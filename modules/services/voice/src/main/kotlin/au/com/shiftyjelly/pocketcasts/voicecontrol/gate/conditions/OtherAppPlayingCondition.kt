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
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val transitionWindowMs: Long = 5_000,
    private val debounceMs: Long = 500,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VoiceControlRule {

    override val id = "other_app_playing"
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
            handleStateChange(hasOtherApp)
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

    private fun handleStateChange(hasOtherApp: Boolean) {
        debounceJob?.cancel()
        if (hasOtherApp) {
            debounceJob = scope.launch {
                delay(debounceMs)
                mutableState.value = VoiceControlRuleState.Blocked("other_app_playing")
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
     * One place that assembles the attribution arguments, so the polled path and an on-demand
     * evaluation cannot disagree about them.
     */
    internal fun otherAppPlaying(isMusicActive: Boolean): Boolean {
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
        )
    }

    internal fun evaluate(otherAppPlaying: Boolean): VoiceControlRuleState {
        return if (otherAppPlaying) {
            VoiceControlRuleState.Blocked("other_app_playing")
        } else {
            VoiceControlRuleState.Allowed
        }
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
): Boolean {
    if (!isMusicActive) return false
    val hostOwnsAudio = hostCurrentlyPlaying ||
        (msSinceHostPlaying >= 0 && msSinceHostPlaying < transitionWindowMs) ||
        // Our own feedback is not a foreign app. Earcons ride the media stream and are 0.15-0.4s
        // long, so a 1 Hz poll can land inside one; without this term the app blocks its own gate
        // and stops listening in the middle of a session, having just played the acknowledgement.
        (msSinceSelfEmitted >= 0 && msSinceSelfEmitted < transitionWindowMs)
    return !hostOwnsAudio
}
