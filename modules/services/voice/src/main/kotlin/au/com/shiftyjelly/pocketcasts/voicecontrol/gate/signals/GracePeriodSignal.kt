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
     * Which window is current. A generation begins whenever a *new act* opens
     * one — a wake, or a command the app handled locally — and identifies the
     * window for anything that outlives it, such as a cloud turn still in
     * flight. A completion that carries a stale generation extends nothing: a
     * count of what has been spent in a window cannot tell one window from the
     * next, so an old request could otherwise re-arm a new session's allowance.
     */
    private var generation = 0L

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

    /** The window the allowance was spent in, so it is spent once per generation. */
    private var escalatedGeneration = -1L

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
     * [fromGeneration] is the window the handled command was issued under, or
     * null when the app handled a command locally as a fresh act.
     *
     * [restoresAllowance] applies only to a dispatched turn: true for one the
     * router chose, false for the routing-failure fallback.
     */
    fun onCommandRecognized(fromGeneration: Long? = null, restoresAllowance: Boolean = true) {
        // A completion that names its window may extend that window only while
        // it is still the current one and still open. It never opens one and
        // never resurrects a window a privacy event ended — including when a
        // later wake has opened a new one.
        if (fromGeneration != null) {
            if (fromGeneration != generation || !_isActive.value) return
            startOrReset()
            // Restoring is a separate question from touching the window: a
            // deliberate route earns the next dispatch, an escalation does not.
            if (restoresAllowance) escalatedGeneration = -1L
            return
        }
        if (closedByPrivacy) return
        openWindow()
    }

    /**
     * Called when the wake word is detected — opens a window, including after a
     * privacy close, since a fresh wake is a new user act.
     */
    fun onWakeWordDetected() {
        closedByPrivacy = false
        openWindow()
    }

    /**
     * Why a dispatch would be refused right now, for the log line: the two
     * causes are different problems (nothing is listening vs this window has
     * already spent its one dispatch) and conflating them made a real run hard
     * to read.
     */
    fun escalationRefusal(): String = when {
        closedByPrivacy -> "no open window (closed by a privacy event)"
        !_isActive.value -> "no open window"
        escalationUsed && escalatedGeneration == generation -> "allowance spent in this window"
        else -> "available"
    }

    /** The window a dispatch issued now would belong to. */
    val currentGeneration: Long get() = generation

    /**
     * Issues the window's single escalation, returning the generation it was
     * issued under, or null when it is refused.
     *
     * Refused when no window is open — an utterance with no act behind it has
     * nothing to spend against — and when this generation has already spent its
     * one dispatch.
     *
     * This does **not** constrain a false wake. A false wake is a wake
     * detection, so it opens a window and its one utterance is the residual we
     * accepted knowingly; what this refuses is an utterance arriving with no
     * window at all.
     */
    fun issueEscalation(): Long? {
        if (!_isActive.value || closedByPrivacy) return null
        if (escalationUsed && escalatedGeneration == generation) return null
        escalationUsed = true
        escalatedGeneration = generation
        return generation
    }

    /** A new act opens a window and begins its generation. */
    private fun openWindow() {
        generation += 1
        startOrReset()
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
