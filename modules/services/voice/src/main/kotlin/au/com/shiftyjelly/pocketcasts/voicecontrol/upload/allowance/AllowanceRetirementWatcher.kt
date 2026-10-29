package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The subscription/deadline OWNER for allowance retirement (reviewer c5d94af4 #4, b256b011,
 * corrections 3adcf0f3): subscribes the authoritative lifecycle inputs and drives
 * [CaptureAllowanceLifecycle.retireCurrent] on each. Owns EVERY job it creates —
 * [dispose] cancels the policy collection AND the deadline under one lock, detaches ONLY its own
 * chained listener (restoring the prior one), retires the authority, and refuses all later
 * callbacks/rebinds/re-arms. In-process owner disposal; process death has no hook and loses the
 * in-memory prepared token by design.
 *
 * STALE-CALLBACK SAFETY (3adcf0f3 #1): every policy-driven retirement is GENERATION-BOUND. The
 * callback captures the lifecycle generation observed when it was queued, re-reads the
 * authoritative `snapshot()` at firing, and retires ONLY if the generation still matches — a
 * successor bound between queueing and firing is never retired by a stale disable emission or a
 * stale lease deadline. Timestamps arm the deadline; the ACTIVATION STAMP + generation guard,
 * not the timestamp, decide the retirement.
 *
 * DEADLINE EXACTNESS: armed at the provider's OWN boundary
 * (`activatedAtMonotonicMs + DEFAULT_LEASE_MS + 1`, the same monotonic calculation snapshot()
 * enforces). Production sleeps ONE exact [kotlinx.coroutines.delay] computed at arm time; the
 * injected [sleepMs] (tests) advances a virtual clock deterministically across that same span.
 *
 * @param nowMs monotonic clock; production is SystemClock::elapsedRealtime.
 * @param sleepMs suspend-until advance; production is kotlinx.coroutines.delay.
 */
@javax.inject.Singleton
class AllowanceRetirementWatcher @javax.inject.Inject constructor(
    private val lifecycle: CaptureAllowanceLifecycle,
    private val policyProvider: VoiceSharingPolicyProvider,
    private val accountStartup: VoiceSharingAccountStartup,
    @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val sleepMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    /** Serializes start/dispose/armDeadline/deadline-replacement (3adcf0f3 #2). */
    private val watcherLock = ReentrantLock()

    @Volatile private var disposed = false
    private var started = false
    private var collectJob: Job? = null
    private var deadlineJob: Job? = null

    private var priorSessionEnd: (() -> Unit)? = null
    private var installedListener: (() -> Unit)? = null

    /** The activation stamp the live deadline is armed for; superseded stamps ignore their fire. */
    @Volatile private var armedFor = -1L

    /** Idempotent; refused after [dispose]. */
    fun start() {
        watcherLock.lock()
        try {
            if (started || disposed) return
            started = true

            // Account replacement/session end — CHAIN, never overwrite (CollectionLossWatcher may
            // already sit on this listener; it must keep firing). A session change retires
            // UNCONDITIONALLY: the dead session invalidates ANY bound authority.
            priorSessionEnd = accountStartup.sessionEndedListener
            val mine: () -> Unit = {
                val prior = priorSessionEnd
                prior?.invoke()
                if (!disposed) lifecycle.retireCurrent()
            }
            installedListener = mine
            accountStartup.sessionEndedListener = mine

            // The collection job is OWNED: dispose cancels it (3adcf0f3 #2).
            collectJob = scope.launch {
                policyProvider.publishedPolicy.collect { snap ->
                    onPublication(snap.enabled, snap.activatedAtMonotonicMs)
                }
            }
        } finally {
            watcherLock.unlock()
        }
    }

    /** In-process owner disposal: cancel everything this watcher owns, detach, retire, refuse. */
    fun dispose() {
        watcherLock.lock()
        try {
            if (disposed) return
            disposed = true
            deadlineJob?.cancel()
            deadlineJob = null
            armedFor = -1L
            collectJob?.cancel()
            collectJob = null
            // Detach ONLY if the chain still starts with our listener (a later composition may
            // have chained onto us; then leave the chain intact — `disposed` stops our reactions).
            val mine = installedListener
            if (mine != null && accountStartup.sessionEndedListener === mine) {
                accountStartup.sessionEndedListener = priorSessionEnd
            }
            lifecycle.dispose()
        } finally {
            watcherLock.unlock()
        }
    }

    /** Called on each published policy snapshot. */
    private fun onPublication(enabled: Boolean, activatedAtMonotonicMs: Long) {
        watcherLock.lock()
        val genAtPublication: Long
        try {
            if (disposed) return
            genAtPublication = lifecycle.currentGeneration()
            if (!enabled || activatedAtMonotonicMs <= 0) {
                // Disable (or a non-activation publication): the deadline is moot. Re-read the
                // AUTHORITATIVE state and retire only the generation observed at queueing, so a
                // queued stale disable never kills a successor bound to a newer activation.
                deadlineJob?.cancel()
                deadlineJob = null
                armedFor = -1L
                val evaluated = policyProvider.snapshot()
                if (!evaluated.enabled) lifecycle.retireCurrent(genAtPublication)
                return
            }
            if (activatedAtMonotonicMs == armedFor) return
            armDeadlineLocked(activatedAtMonotonicMs, genAtPublication)
        } finally {
            watcherLock.unlock()
        }
    }

    /** Caller holds [watcherLock]. The recompute loop is exact: production's single sleep spans
     *  the FULL remaining time; a truncated sleep only happens with an injected test clock. */
    private fun armDeadlineLocked(activatedAtMonotonicMs: Long, genAtArm: Long) {
        deadlineJob?.cancel()
        armedFor = activatedAtMonotonicMs
        deadlineJob = scope.launch {
            // ONE exact sleep computed at arm time (production: the full remaining span; the
            // injected test clock resumes it by advancing). No recompute loop: a re-read after
            // resumption would re-park instead of firing.
            val remaining = (activatedAtMonotonicMs + VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1L) - nowMs()
            if (remaining > 0L) sleepMs(remaining)
            // GENERATION GUARD: only the activation alive when we armed may be retired.
            if (disposed || armedFor != activatedAtMonotonicMs) return@launch
            val evaluated = policyProvider.snapshot()
            if (!evaluated.enabled || evaluated.activatedAtMonotonicMs <= 0) {
                lifecycle.retireCurrent(genAtArm)
            }
        }
    }
}
