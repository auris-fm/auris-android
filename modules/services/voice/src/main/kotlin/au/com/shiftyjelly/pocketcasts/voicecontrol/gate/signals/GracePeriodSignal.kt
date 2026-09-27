package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Tracks the conversation grace period that starts/resets when a command is recognized
 * or the wake word is detected (in WakeWord mode, the wake word triggers the grace period
 * so follow-up commands flow in Continuous mode).
 *
 * While the timer is active the microphone stays in Continuous mode. The grace period
 * ends when: the 30-second timer expires, the audio route changes, or the app is
 * switched away from.
 *
 * Spec: Every recognized command or wake-word detection starts/resets a 30-second
 * conversation grace period.
 */
@Singleton
class GracePeriodSignal @Inject constructor() {
    /** Internal constructor for testing with custom timeout. */
    internal constructor(timeoutMs: Long) : this() {
        this.timeoutMs = timeoutMs
    }

    private var timeoutMs: Long = 30_000L
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    /**
     * True once a privacy event has closed the window and no wake has opened a
     * new one. A close is final: neither a completion that lands afterwards nor
     * a command can reopen it, because the whole point of the close is that
     * nothing keeps listening after the app was backgrounded or the audio route
     * changed. Only a new wake opens a window again.
     */
    private var closedByPrivacy = false

    /**
     * One escalation to the cloud service is allowed per grace window: the
     * window is the user-initiated act, and this bounds a routing failure (or a
     * deliberate `no_match`) to a single dispatch per act rather than a stream.
     * Reset by anything that opens or resets the window — a wake or a
     * recognised command — never by the failure itself.
     */
    private var escalationUsed = false

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    /**
     * Called when a command is recognized — starts/resets the grace period.
     *
     * Refuses in two cases, and they live here rather than at each call site so
     * no path — model-chosen route, fallback, ordinary command, or a wrapper
     * added later — can express "start a window after a privacy close":
     * - the window was closed by a privacy event and no wake has reopened it;
     * - this is an escalation (a network turn), which extends a window that is
     *   still open but never resurrects one that expired while it was in flight.
     *
     * [fromEscalation] must be true when the command being handled *is* the
     * cloud escalation, so that handling it does not refresh the escalation
     * budget and fund the fallback's own next attempt.
     */
    fun onCommandRecognized(fromEscalation: Boolean = false) {
        if (closedByPrivacy) return
        if (fromEscalation && !_isActive.value) return
        startOrReset()
        if (!fromEscalation) escalationUsed = false
    }

    /**
     * Called when the wake word is detected — opens a window, including after a
     * privacy close, since a fresh wake is a new user act.
     */
    fun onWakeWordDetected() {
        closedByPrivacy = false
        startOrReset()
        escalationUsed = false
    }

    /**
     * Consumes the window's single escalation.
     *
     * False when the budget is already spent, and false when no window is open:
     * the bound is "one utterance per user-initiated act", so an utterance with
     * no window has no act to spend against.
     *
     * This does **not** constrain a false wake. A false wake is a wake
     * detection, so it opens a window and its one utterance is the residual we
     * accepted knowingly; what this refuses is an utterance arriving with no
     * window at all.
     */
    fun tryConsumeEscalation(): Boolean {
        if (!_isActive.value) return false
        if (escalationUsed) return false
        escalationUsed = true
        return true
    }

    private fun startOrReset() {
        _isActive.value = true
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(timeoutMs)
            _isActive.value = false
        }
    }

    /** Called when audio route changes — immediately ends grace period. */
    fun onAudioRouteChanged() = closeByPrivacy()

    /** Called when app goes to background — immediately ends grace period. */
    fun onAppBackgrounded() = closeByPrivacy()

    private fun closeByPrivacy() {
        timerJob?.cancel()
        _isActive.value = false
        closedByPrivacy = true
    }
}
