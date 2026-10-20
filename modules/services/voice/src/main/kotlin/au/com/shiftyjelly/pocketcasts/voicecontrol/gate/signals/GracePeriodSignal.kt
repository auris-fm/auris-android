package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Event
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Kind
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Reason
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Source
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics as Trace

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

    /** The most recent generation opened BY A WAKE; a turn captured under it starts a conversation. */
    private var wakeGeneration = 0L

    /** The generation whose first request has already been prepared, so later ones continue. */
    private var requestPreparedForGeneration = -1L

    /**
     * The window's identity, for a caller that must CAPTURE it at registration and act on it later.
     *
     * A dialog request is prepared at one moment and executed at another; the window it belongs to is
     * the one that was live when the REQUEST was registered, not whichever is live when it finally
     * runs. Reading `isActive` at execution time cannot express that -- a fresh wake would look like a
     * continuation of the previous conversation.
     */
    fun capturedGeneration(): Long = synchronized(windowLock) { generation }

    /** The timer job, so a case can assert the pointer was preserved across a delayed arm. */
    internal fun timerJobForTest(): Job? = synchronized(windowLock) { timerJob }

    /**
     * Invokes the protected arm for [ownedGeneration], as a delayed completion would.
     *
     * Exposed so a case can reach the assignment AFTER the caller has released the monitor -- which is
     * the seam the liveness check exists for, and which no ordinary call can reproduce.
     */
    internal fun armTimerForLockedForTest(ownedGeneration: Long) = armTimerForLocked(ownedGeneration)

    /** The wake-originated generation, so a case can see what the marker names right now. */
    internal fun wakeOriginatedGenerationForTest(): Long = synchronized(windowLock) { wakeGeneration }

    /**
     * Advances the window WITHOUT cancelling the outstanding timer.
     *
     * This exists so a case can put a timer in the position it must refuse from: still scheduled, no
     * longer owning the window. Normal paths cancel the timer under the same monitor, so they never
     * exercise the ownership check on its own -- and without a way to reach that state, the check has no
     * case that can fail for it.
     */
    internal fun replaceWindowWithoutCancellingTimer() {
        synchronized(windowLock) {
            generation += 1
            _isActive.value = true
        }
    }

    /**
     * Whether [captured] names the window that is live right now.
     *
     * A request whose captured window is no longer current has been superseded, and must not act on
     * the current one -- including when a newer window is open and active.
     */
    fun isCurrent(captured: Long): Boolean = synchronized(windowLock) {
        // EQUAL GENERATION IS NOT ENOUGH: a window closed by a privacy event must reject even an origin
        // that still matches numerically, because the whole point of that close is that nothing keeps
        // listening after the app was backgrounded. Comparing numbers alone would admit a request whose
        // window has been invalidated.
        captured == generation && _isActive.value && !closedByPrivacy
    }

    /**
     * Whether the request being prepared is the FIRST of its window, so it begins a conversation.
     *
     * PER REQUEST, not a property of the window: a window is opened by a wake, but the wake's own
     * request is only the first one. A wake-free follow-up inside the same window is a CONTINUATION,
     * and reading "this window was opened by a wake" for it would make every follow-up look like a
     * fresh user act.
     *
     * [isFirstRequestOfWindow] is therefore what the caller passes, and this only checks that the
     * window it belongs to is still the current one.
     */
    fun isWakeOriginated(captured: Long, isFirstRequestOfWindow: Boolean): Boolean = synchronized(windowLock) { isFirstRequestOfWindow && captured == wakeGeneration }

    /**
     * Whether [captured]'s window has ALREADY had a request prepared under it.
     *
     * The caller asks this to decide whether the request it is preparing is the first of the window:
     * the first one begins a conversation, later ones continue it. Kept here rather than derived from
     * `isActive`, because the window being open says nothing about whether anything has been sent in it.
     */
    fun hasPreparedRequest(captured: Long): Boolean = synchronized(windowLock) { requestPreparedForGeneration == captured }

    /**
     * Records that a request has been prepared under [captured], so a later one knows it is a
     * continuation rather than the wake's own request.
     */
    fun notePreparedRequest(captured: Long) {
        // UNDER THE SAME MONITOR as every other writer. `@Synchronized` takes the INSTANCE monitor, which
        // is a DIFFERENT lock from [windowLock] -- so it serialized this write against other
        // `@Synchronized` members and against nothing that matters, while the transitions it must be
        // ordered with took [windowLock]. Two locks for one state is not synchronization.
        synchronized(windowLock) { requestPreparedForGeneration = captured }
    }

    /**
     * True once a privacy event has closed the window and no wake has opened a
     * new one. A close is final: neither a completion that lands afterwards nor
     * a command can reopen it, because the whole point of the close is that
     * nothing keeps listening after the app was backgrounded or the audio route
     * changed. Only a new wake opens a window again.
     */
    private var closedByPrivacy = false

    /**
     * True when the window is closed because a privacy event ended it and no wake has reopened it.
     *
     * Exposed because a privacy closure does not pass through the playback layer, so code that
     * restores playback state cannot detect it via the playback command revision.
     */
    fun isClosedByPrivacy(): Boolean = synchronized(windowLock) { closedByPrivacy }

    /**
     * How many times a privacy event has closed the window. Monotonic and never reset by a wake, so a
     * caller that read it when it took an obligation can tell that a privacy event happened since —
     * a live boolean cannot answer that, because a wake clears it before a suspended caller resumes.
     */
    fun privacyClosureCount(): Long = privacyClosures.get()

    private val privacyClosures = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * How many closures coincided with the host's audio actually stopping.
     *
     * Distinct from [privacyClosureCount]: backgrounding and route changes close the window without
     * touching playback, so a caller that holds a playback obligation must not treat those as a
     * fact about playback. Incremented by [noteHostPlaybackStopped], called from where the host's
     * own state is observable, at the boundary that owns the distinction.
     */
    private val playbackStopClosures = java.util.concurrent.atomic.AtomicLong(0)

    fun playbackStopClosureCount(): Long = playbackStopClosures.get()

    /**
     * Records that the host's playback stopped while the window was closed by a privacy event.
     *
     * Called by the playback-context layer when it observes the stop, so the closure kinds are
     * separated at the event boundary rather than inferred from a count that increments on
     * visibility changes alone.
     */

    // Records that the host's playback stopped while the window was closed by a privacy event.
    // Called by the playback-context layer when it observes the stop, so the closure kinds are
    // separated at the event boundary rather than inferred from a count that increments on
    // visibility changes alone.
    fun noteHostPlaybackStopped() {
        playbackStopClosures.incrementAndGet()
    }

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
     * THE WINDOW-OWNER MONITOR. Every transition and the allowance claim take it.
     *
     * `@Synchronized` on `issueEscalation` alone excludes NOTHING, because the writers -- wake/open,
     * privacy close, timeout -- never took it: a reader and a writer on different locks is no
     * synchronization at all. This is the one boundary all of them share, so a claim cannot interleave
     * with the transition that changes what it is claiming against.
     */
    private val windowLock = Any()

    /** Opaque ownership for work that must finish before the inactivity countdown resumes. */
    class CloudTurnHold internal constructor(internal val generation: Long, internal val owner: Long?)

    private val cloudTurns = mutableSetOf<CloudTurnHold>()

    /** Holds only an already-live window; an old request cannot borrow its successor's grace. */
    fun holdCloudTurn(fromGeneration: Long? = null, owner: Long? = null): CloudTurnHold? = synchronized(windowLock) {
        val refusal = when {
            closedByPrivacy -> Reason.HoldRejectedPrivacy
            !_isActive.value -> Reason.HoldRejectedInactive
            fromGeneration != null && fromGeneration != generation -> Reason.HoldRejectedGeneration
            else -> null
        }
        if (refusal != null) {
            Trace.record(
                Event(
                    Kind.Hold, refusal, Source.Grace, generation = generation, active = _isActive.value,
                    holdsBefore = cloudTurns.size, holdsAfter = cloudTurns.size, owner = owner, requestedGeneration = fromGeneration,
                ),
            )
            return@synchronized null
        }
        val before = cloudTurns.size
        CloudTurnHold(generation, owner).also {
            cloudTurns += it
            timerJob?.cancel()
            timerJob = null
            Trace.record(
                Event(
                    Kind.Hold, Reason.HoldAcquired, Source.Grace, generation = generation, active = true,
                    holdsBefore = before, holdsAfter = cloudTurns.size, owner = owner, requestedGeneration = fromGeneration,
                ),
            )
        }
    }

    /** Called after turn termination and local playback drain/clear, including failure cleanup. */
    fun finishCloudTurn(hold: CloudTurnHold, cancelled: Boolean = false) {
        val armFor = synchronized(windowLock) {
            val before = cloudTurns.size
            val removed = cloudTurns.remove(hold)
            Trace.record(
                Event(
                    Kind.Hold,
                    if (!removed) {
                        Reason.HoldReleaseIgnored
                    } else if (cancelled) {
                        Reason.HoldCancelled
                    } else {
                        Reason.HoldReleased
                    },
                    Source.Grace, generation = generation, active = _isActive.value, holdsBefore = before, holdsAfter = cloudTurns.size,
                    owner = hold.owner, requestedGeneration = hold.generation,
                ),
            )
            if (!removed) return@synchronized null
            if (hold.generation != generation || closedByPrivacy || !_isActive.value || cloudTurns.isNotEmpty()) {
                return@synchronized null
            }
            generation
        } ?: return
        armTimerForLocked(armFor)
    }

    // The monitor itself, so a test can hold it and observe whether a transition waits for it.
    internal fun windowMonitorForTest(): Any = windowLock

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
            // VALIDATE AND EXTEND IN ONE TRANSACTION. Checking the origin and liveness BEFORE taking the
            // lock let a completion that arrived while the window was still open be PARKED at the lock,
            // then resume after a privacy close or a replacement and EXTEND THE WRONG WINDOW -- measured
            // by @reviewer as listening reopening after privacy closed it. The check an earlier moment
            // authorises is not authorisation at the moment of the act, so both happen under the monitor
            // or the check does not constrain the act at all.
            val armFor = synchronized(windowLock) {
                if (fromGeneration != generation || !_isActive.value) return@synchronized null
                Trace.record(
                    Event(
                        Kind.Window,
                        Reason.Extended,
                        Source.Grace,
                        generation = generation,
                        previousActive = _isActive.value,
                        active = true,
                        holdsBefore = cloudTurns.size,
                        holdsAfter = cloudTurns.size,
                    ),
                )
                _isActive.value = true
                // Restoring is a separate question from touching the window: a deliberate route earns
                // the next dispatch, an escalation does not.
                if (restoresAllowance) escalatedGeneration = -1L
                generation
            } ?: return
            // ARMED OUTSIDE the monitor: `armTimer` launches and reads the coroutine context, and a
            // suspension point inside a critical section is the interleaving the lock exists to prevent.
            // Arming takes the monitor itself and re-checks ownership, so a transition landing between
            // the two steps cannot leave an unowned timer behind.
            armTimerForLocked(armFor)
            return
        }
        // The privacy refusal and the open are one decision, so they take the monitor together: a
        // privacy close landing between them would otherwise open a window the close had just ended.
        val ended = synchronized(windowLock) {
            if (closedByPrivacy) return@synchronized null
            openWindowLocked(Reason.RecognizedCommand)
        }
        // THE REPLACED WINDOW IS REPORTED HERE TOO. This branch was discarding the return, so an
        // unstamped command that REPLACED a window retired nothing -- the same "a replacement is an
        // ending" rule as every other open, reached by the one path that ignored it.
        ended?.let { notifyIfEnded(it) }
    }

    /**
     * Called when the wake word is detected — opens a window, including after a
     * privacy close, since a fresh wake is a new user act.
     */
    fun onWakeWordDetected() {
        // ONE TRANSITION. The markers and the opening are the SAME act, so they must not be separable:
        // setting them around `openWindow()` left a moment where the window was open with the previous
        // marker, and a concurrent reader could observe it. A FRESH WAKE BEGINS A NEW USER ACT and
        // therefore a new conversation -- the window it opens is marked wake-originated so a turn
        // captured under it prepares CREATE even if an older conversation handle exists.
        val ended = synchronized(windowLock) {
            closedByPrivacy = false
            val previous = openWindowLocked(Reason.Wake)
            wakeGeneration = generation
            previous
        }
        notifyIfEnded(ended)
    }

    /**
     * Why a dispatch would be refused right now, for the log line: the two
     * causes are different problems (nothing is listening vs this window has
     * already spent its one dispatch) and conflating them made a real run hard
     * to read.
     */
    fun escalationRefusal(): String = synchronized(windowLock) {
        when {
            closedByPrivacy -> "no open window (closed by a privacy event)"
            !_isActive.value -> "no open window"
            escalationUsed && escalatedGeneration == generation -> "allowance spent in this window"
            else -> "available"
        }
    }

    /** The window a dispatch issued now would belong to. */
    val currentGeneration: Long get() = synchronized(windowLock) { generation }

    // Issues the window's single escalation, returning the generation it was issued under, or null
    // when it is refused.
    //
    // Refused when no window is open -- an utterance with no act behind it has nothing to spend
    // against -- and when this generation has already spent its one dispatch.
    //
    // THE CLAIM IS AGAINST THE REQUEST'S OWN WINDOW, not whichever is current. Recognition takes time,
    // and a wake arriving meanwhile opens a new window: claiming against the current generation would
    // let a request issued under the OLD window spend the SUCCESSOR's allowance, leaving the user's
    // newer act unable to escalate at all. A request whose window is no longer live claims nothing.
    //
    // This does **not** constrain a false wake. A false wake is a wake detection, so it opens a window
    // and its one utterance is the residual we accept knowingly; what this refuses is an utterance
    // arriving with no window at all.

    fun issueEscalation(origin: Long = generation): Long? = synchronized(windowLock) {
        if (origin != generation) return@synchronized null
        if (!_isActive.value || closedByPrivacy) return@synchronized null
        if (escalationUsed && escalatedGeneration == origin) return@synchronized null
        escalationUsed = true
        escalatedGeneration = origin
        origin
    }

    /** A new act opens a window and begins its generation. */
    private fun openWindow() {
        val ending = synchronized(windowLock) { openWindowLocked() }
        notifyIfEnded(ending)
    }

    /**
     * [openWindow]'s body, for a caller already holding the monitor.
     *
     * Returns the generation that ENDED, which the caller reports OUTSIDE the monitor: a callback that
     * runs other components' code inside the lock could deadlock against a component calling back in.
     */
    private fun openWindowLocked(reason: Reason = Reason.RecognizedCommand): Long {
        // OPENING A WINDOW ENDS THE PREVIOUS ONE. A wake replaces the window in force, so whatever that
        // window was holding stops being retryable at THIS moment -- not when some later request happens
        // to be admitted. Without it, a replaced window whose successor never runs a request kept its
        // prepared turn indefinitely: the ordinary ending, reached by replacement.
        //
        // THE TRANSITION IS UNDER THE MONITOR AND THE CALLBACK IS NOT: a notification delivered while
        // holding the lock would run other components' code inside it, and a component that calls back
        // in would deadlock. The ENDED generation is READ under the monitor and the callback then runs
        // AFTER the counter has advanced -- the notification reports the window that ended, which is the
        // previous generation, not the one now current.
        val previous = generation
        val wasActive = _isActive.value
        val held = cloudTurns.size
        cloudTurns.clear()
        generation += 1
        // EACH WINDOW HAS ITS OWN ALLOWANCE, so opening one CLEARS the spent marker: otherwise a claim
        // made under a previous window made every later window read as spent.
        escalationUsed = false
        escalatedGeneration = -1L
        _isActive.value = true
        armTimerForLocked(ownedGeneration = generation)
        val transitionReason = if (wasActive) {
            if (reason == Reason.Wake) Reason.SuccessorWake else Reason.SuccessorCommand
        } else {
            reason
        }
        Trace.record(
            Event(
                Kind.Window, transitionReason, Source.Grace, generation = generation, previousGeneration = previous,
                previousActive = wasActive, active = true, holdsBefore = held, holdsAfter = 0,
            ),
        )
        return previous
    }

    /** Reports [ended] to the window-ended observer, unless no window ended. */
    private fun notifyIfEnded(ended: Long) {
        if (ended != 0L) onWindowEnded?.invoke(ended)
    }

    // Arms this window's timer, OWNED by [ownedGeneration].
    //
    // THE TIMER MUST KNOW WHICH WINDOW IT BELONGS TO. Reading the live generation at expiry would let a
    // timer that was CANCELLED (because its window was extended or replaced) close its successor: by the
    // time it fires, `generation` names a different window, and closing on that is closing the wrong one.
    // The ownership is checked UNDER the monitor together with the close, so the check and the close are
    // one step and a successor cannot slip between them.
    //
    // It also CLEARS `isActive` when it owns the window: an expired window is not open. The reset path
    // relied on this too, and a version that only notified left an expired extension reading as open.

    /**
     * Arms the timer for [ownedGeneration] under the monitor.
     *
     * The launch itself happens outside it -- `armTimer` reads the coroutine context, which suspends --
     * but the ASSIGNMENT takes the monitor and re-checks that the window is still [ownedGeneration], so a
     * transition landing in between cannot leave a timer owned by a window that has already ended.
     */
    private fun armTimerForLocked(ownedGeneration: Long) {
        val job = armTimer(ownedGeneration)
        synchronized(windowLock) {
            // OWNERSHIP IS GENERATION **AND** LIVENESS. Generation alone is not enough: a PRIVACY CLOSE
            // preserves the generation -- it ends the window without advancing it -- so a completion that
            // was valid when it started could still arm this window's timer AFTER the window was closed.
            // The closed window then holds a live timer owned by nobody, and the timer reports an ENDING
            // for a window that already ended: two endings for one window.
            //
            // The check runs here, in the protected assignment, because the caller releases windowLock
            // before arming -- so this really is the last point at which ownership can be established.
            if (ownedGeneration == generation && _isActive.value && cloudTurns.isEmpty()) {
                timerJob?.cancel()
                timerJob = job
            } else {
                // A REFUSED TIMER IS CANCELLED WITHOUT EVER RUNNING. Its coroutine is launched and then
                // immediately cancelled, so its completion handler fires on the scope's dispatcher -- and
                // an unobserved cancellation there surfaces as an exception in a LATER test, not this one.
                // Cancelling the whole child chain makes the cancellation self-contained.
                job.cancel(kotlinx.coroutines.CancellationException("window no longer owns this timer"))
            }
        }
    }

    private fun armTimer(ownedGeneration: Long): Job = scope.launch {
        // THIS job, captured OUTSIDE the critical section: `currentCoroutineContext()` suspends, and a
        // suspension point inside a lock is the very interleaving the lock exists to prevent.
        val thisTimer = currentCoroutineContext()[Job]
        delay(timeoutMs)
        val ended = synchronized(windowLock) {
            if (ownedGeneration != generation) return@synchronized null
            if (timerJob !== thisTimer) return@synchronized null
            if (cloudTurns.isNotEmpty()) return@synchronized null
            Trace.record(
                Event(
                    Kind.Window,
                    Reason.Timeout,
                    Source.Grace,
                    generation = generation,
                    previousActive = _isActive.value,
                    active = false,
                    holdsBefore = cloudTurns.size,
                    holdsAfter = 0,
                ),
            )
            _isActive.value = false
            ownedGeneration
        } ?: return@launch
        // DELIVERED OUTSIDE the monitor: a callback running other components' code inside the lock could
        // deadlock against a component that calls back in.
        onWindowEnded?.invoke(ended)
    }

    private fun startOrReset() {
        // UNDER THE MONITOR: extending a window is a transition of the same state the claim reads, so it
        // must not interleave with one. The timer is ARMED with this window's generation, so an expired
        // extension closes the window it belongs to and cannot touch a successor.
        synchronized(windowLock) {
            _isActive.value = true
            // ARMED THROUGH armTimerForLocked, like every other open: arming directly here skipped the
            // ownership re-check, so this one path could install a timer owned by a window that a
            // concurrent transition had already replaced.
            armTimerForLocked(ownedGeneration = generation)
        }
    }

    /**
     * Told which window ended, whenever it ends: a privacy close, a route change, or the timeout.
     *
     * ONE notification for EVERY ending, rather than a branch per cause, because the retryability rule
     * is about the window rather than about why it closed.
     */
    var onWindowEnded: ((generation: Long) -> Unit)? = null

    /** Called when audio route changes — immediately ends grace period. */
    fun onAudioRouteChanged(source: Source = Source.Route) = closeByPrivacy(Reason.RouteChanged, source)

    /** Called when app goes to background — immediately ends grace period. */
    fun onAppBackgrounded() = closeByPrivacy(Reason.Backgrounded, Source.Foreground)

    private fun closeByPrivacy(reason: Reason, source: Source) {
        val ended = synchronized(windowLock) {
            Trace.record(
                Event(
                    Kind.Window,
                    reason,
                    source,
                    generation = generation,
                    previousActive = _isActive.value,
                    active = false,
                    holdsBefore = cloudTurns.size,
                    holdsAfter = 0,
                ),
            )
            privacyClosures.incrementAndGet()
            timerJob?.cancel()
            cloudTurns.clear()
            closedByPrivacy = true
            _isActive.value = false
            generation
        }
        onWindowEnded?.invoke(ended)
    }
}
