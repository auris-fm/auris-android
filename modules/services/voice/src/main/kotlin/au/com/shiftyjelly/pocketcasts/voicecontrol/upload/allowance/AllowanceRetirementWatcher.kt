package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The subscription/deadline OWNER for allowance retirement (reviewer c5d94af4 #4, b256b011,
 * corrections 3adcf0f3 + 9d29473a). Subscribes the authoritative lifecycle inputs and drives
 * [CaptureAllowanceLifecycle.retireCurrent] on each. Owns EVERY job it creates; [dispose] is the
 * composition-owned teardown AND is wired to the OWNING SCOPE's completion — when the injected
 * scope completes or is cancelled, the watcher disposes itself (cancels jobs, detaches only its
 * own chain link, retires the bound authority, refuses everything after). In-process owner
 * disposal; process death has no hook and loses the in-memory prepared token by design.
 *
 * RECONCILE-FROM-AUTHORITY (9d29473a #1): policy emissions are merely WAKEUPS. Every wakeup —
 * a published snapshot, a NEW BIND ([CaptureAllowanceLifecycle.onBound], invoked post-unlock),
 * or start — runs [reconcile], which re-reads the AUTHORITATIVE lease-evaluated `snapshot()`:
 * - authoritative policy expired/disabled → the currently bound authority is governed by that
 *   expired lease and is retired (a binding that arrived AFTER the publication does NOT escape:
 *   publish-before-bind retires at the deadline; a same-lease rebind retires; bind-under-expired
 *   policy retires immediately via the bind reconciliation);
 * - authoritative policy live → the deadline is (re)armed at the provider's OWN boundary
 *   (`activatedAtMonotonicMs + DEFAULT_LEASE_MS + 1`), superseding any older stamp's deadline;
 *   a stale disabled emission therefore cannot cancel the only deadline of a newer live policy —
 *   the next/authoritative state decides, never the emission's payload alone.
 *
 * The firing deadline re-validates and retires ONLY when the authoritative policy is still
 * expired AND the binding generation has not changed during revalidation (a bind that raced in
 * triggers a fresh reconcile instead — a binding governed by a newer valid policy is spared).
 *
 * DEADLINE EXACTNESS: ONE sleep computed at arm time; the injected [sleepMs] (tests) advances a
 * virtual clock deterministically across that same span. Production is
 * [SystemClock.elapsedRealtime] + [kotlinx.coroutines.delay].
 *
 * NOTE FOR COMPOSITION: NOT an @Inject constructor — the clock/sleep seams are function types
 * without Dagger bindings (9d29473a #3); VoiceObservationModule provides this singleton
 * explicitly with the production clock.
 */
class AllowanceRetirementWatcher(
    private val lifecycle: CaptureAllowanceLifecycle,
    private val policyProvider: VoiceSharingPolicyProvider,
    private val accountStartup: VoiceSharingAccountStartup,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val sleepMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    /** Serializes start/dispose/reconcile/arm (3adcf0f3 #2). */
    private val watcherLock = ReentrantLock()

    @Volatile private var disposed = false
    private var started = false
    private var collectJob: Job? = null
    private var deadlineJob: Job? = null

    @Volatile private var armedFor = -1L

    private var priorSessionEnd: (() -> Unit)? = null
    private var installedListener: (() -> Unit)? = null
    private var installedBoundHook: ((Long) -> Unit)? = null
    private var scopeCompletion: kotlinx.coroutines.DisposableHandle? = null

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

            // NEW BINDS get expiry coverage too (9d29473a #1): the lifecycle invokes this hook
            // AFTER releasing its own lock, so reconcile's watcher→lifecycle order stays
            // one-directional and deadlock-free.
            val boundHook: (Long) -> Unit = { reconcile() }
            installedBoundHook = boundHook
            lifecycle.onBound = boundHook

            // The collection job is OWNED: dispose cancels it.
            collectJob = scope.launch {
                policyProvider.publishedPolicy.collect { reconcile() }
            }

            // TEARDOWN WIRING (9d29473a #2): the OWNING SCOPE's completion/cancellation disposes
            // this watcher — cancelling the application scope retires the bound authority,
            // detaches the listener, and refuses later use. Not merely "a method exists".
            scopeCompletion = scope.coroutineContext[Job]?.invokeOnCompletion {
                dispose()
            }

            // Cover a policy that was already live before start().
            reconcile()
        } finally {
            watcherLock.unlock()
        }
    }

    /**
     * THE retirement decision, always derived from the authoritative lease-evaluated snapshot —
     * emissions and binds are only wakeups. See the class KDoc.
     */
    internal fun reconcile() {
        watcherLock.lock()
        try {
            if (disposed) return
            val genBeforeRevalidation = lifecycle.currentGeneration()
            val evaluated = policyProvider.snapshot()
            if (!evaluated.enabled || evaluated.activatedAtMonotonicMs <= 0) {
                // Authoritative policy expired or disabled: cancel any deadline and retire the
                // bound authority (it is governed by this expired lease). A bind that raced the
                // revalidation is re-reconciled instead of blindly retired.
                cancelDeadlineLocked()
                if (lifecycle.currentGeneration() == genBeforeRevalidation) {
                    lifecycle.retireCurrent()
                } else {
                    armOrRetireLocked(evaluated = policyProvider.snapshot(), wakeStamp = evaluated.activatedAtMonotonicMs)
                }
                return
            }
            armOrRetireLocked(evaluated = evaluated, wakeStamp = evaluated.activatedAtMonotonicMs)
        } finally {
            watcherLock.unlock()
        }
    }

    /** Caller holds [watcherLock]. Arms the deadline for a LIVE policy stamp (superseding any
     *  older stamp's deadline); an expired snapshot here retires the bound authority. */
    private fun armOrRetireLocked(evaluated: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot, wakeStamp: Long) {
        if (!evaluated.enabled || evaluated.activatedAtMonotonicMs <= 0) {
            lifecycle.retireCurrent()
            return
        }
        if (evaluated.activatedAtMonotonicMs == armedFor) return // already covered exactly once
        deadlineJob?.cancel()
        armedFor = evaluated.activatedAtMonotonicMs
        deadlineJob = scope.launch {
            val remaining = (armedFor + VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1L) - nowMs()
            if (remaining > 0L) sleepMs(remaining)
            watcherLock.lock()
            try {
                if (disposed || armedFor != evaluated.activatedAtMonotonicMs) return@launch // superseded
                val genBeforeRevalidation = lifecycle.currentGeneration()
                val authoritative = policyProvider.snapshot()
                if (!authoritative.enabled || authoritative.activatedAtMonotonicMs <= 0) {
                    if (lifecycle.currentGeneration() == genBeforeRevalidation) {
                        lifecycle.retireCurrent()
                    } else {
                        // A bind raced the revalidation: reconcile for the NEW binding instead.
                        reconcile()
                    }
                }
            } finally {
                watcherLock.unlock()
            }
        }
    }

    /**
     * In-process owner disposal AND the scope-completion teardown: cancels everything this
     * watcher owns, detaches only its own chain link and bound hook, retires the lifecycle, and
     * refuses everything afterwards.
     */
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
            scopeCompletion?.dispose()
            scopeCompletion = null
            // Detach ONLY if the chain still starts with our listener (a later composition may
            // have chained onto us; then leave the chain intact — `disposed` stops our reactions).
            val mine = installedListener
            if (mine != null && accountStartup.sessionEndedListener === mine) {
                accountStartup.sessionEndedListener = priorSessionEnd
            }
            if (lifecycle.onBound === installedBoundHook) {
                lifecycle.onBound = null
            }
            lifecycle.dispose()
        } finally {
            watcherLock.unlock()
        }
    }

    private fun cancelDeadlineLocked() {
        deadlineJob?.cancel()
        deadlineJob = null
        armedFor = -1L
    }
}
