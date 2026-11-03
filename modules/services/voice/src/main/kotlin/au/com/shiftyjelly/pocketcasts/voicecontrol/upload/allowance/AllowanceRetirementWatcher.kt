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

    /**
     * The last COMMITTED account projection, delivered by the state's post-commit hook (the
     * state fence serializes commits; this is the authoritative committed value, not a cached
     * gate decision). Reconcile re-reads this field at every wakeup — reconcile-from-authority.
     */
    @Volatile private var lastProjection: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection? = null

    /**
     * Session-tagged authoritative membership, tri-state (reviewer 0bca26d5):
     * - Unknown: no committed projection observed (never signed in / fresh fixture state).
     * - Invalidated: the store COMMITTED a projection LOSS (a NULL payload on
     *   projectionInvalidatedListener — a same-session 401 via processUnauthorized). This is
     *   authority loss: the bound allowance retires. A NON-NULL invalidation payload is a
     *   successful apply (the state fires this listener after onProjectionApplied on every
     *   applyOutcome, reviewer 414ddbe1) — it commits, it is NOT loss.
     * - Committed(epoch, ownerSession, membershipActive): a committed projection.
     *   Retirement is decided against the INSTALLED authority identity (the identity snapshot
     *   taken when the current bind completed — installs only succeed under the then-current
     *   membership, so that snapshot IS the installed identity): an inactive membership, or a
     *   committed identity (epoch or owner session) that differs from the installed one,
     *   retires the old batch. A repeated decision keeps refusing the same stale grant until
     *   it is replaced (a bind under the new identity) or retired — no history advance before
     *   the guarded retirement succeeds.
     */
    private sealed interface Membership {
        data object Unknown : Membership
        data object Invalidated : Membership
        data class Committed(
            val epoch: String?,
            val ownerSession: Long,
            /** True only when the committed projection is an ACTIVE enrolled membership. */
            val membershipActive: Boolean,
        ) : Membership
    }

    @Volatile private var membership: Membership = Membership.Unknown

    // The installed authority identity is read COHERENTLY from the lifecycle (generation +
    // immutable GrantIdentity recorded atomically at bind) — never relabeled from current
    // membership when a delayed bound callback runs (reviewer c7cdff17).

    private var priorSessionEnd: (() -> Unit)? = null
    private var installedListener: (() -> Unit)? = null
    private var priorEvidence: ((au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection, Long) -> Unit)? = null
    private var installedEvidence: ((au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection, Long) -> Unit)? = null
    private var priorInvalidated: ((au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection?, Long) -> Unit)? = null
    private var installedInvalidated: ((au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection?, Long) -> Unit)? = null
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

            // Projection changes (enrollment, withdrawal — INCLUDING a withdrawn projection that
            // retains the epoch string) are retire-authority wakeups: chain, never overwrite.
            priorEvidence = accountStartup.enrollmentEvidenceListener
            val evidence: (au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection, Long) -> Unit = { projection, ownerSession ->
                val prior = priorEvidence
                lastProjection = projection
                membership = committedFrom(projection, ownerSession)
                prior?.invoke(projection, ownerSession)
                if (!disposed) reconcile()
            }
            installedEvidence = evidence
            accountStartup.enrollmentEvidenceListener = evidence

            // PROJECTION LOSS (same-session 401): processUnauthorized clears the projection and
            // fires projectionInvalidatedListener WITHOUT any applied projection and without
            // necessarily ending the session — the chain on evidence/session alone would keep a
            // stale ACTIVE allowance alive. Chain, never overwrite.
            priorInvalidated = accountStartup.state.projectionInvalidatedListener
            val invalidated: (au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection?, Long) -> Unit = { projection, ownerSession ->
                val prior = priorInvalidated
                lastProjection = projection
                // PAYLOAD-DRIVEN (reviewer 414ddbe1): this listener also receives NON-NULL
                // projections on every successful apply (it fires after onProjectionApplied).
                // Only a NULL payload is committed loss; a non-null payload commits.
                membership = if (projection == null) {
                    Membership.Invalidated
                } else {
                    committedFrom(projection, ownerSession)
                }
                prior?.invoke(projection, ownerSession)
                if (!disposed) reconcile()
            }
            installedInvalidated = invalidated
            accountStartup.state.projectionInvalidatedListener = invalidated

            // SEED AT START (reviewer 0bca26d5): reconcile the membership ALREADY COMMITTED in
            // the store — a watcher started after an already-committed withdrawal retires at
            // start, not at some future callback that may never come.
            // ONE session-tagged read (reviewer c7cdff17): projection and owner session come
            // from a SINGLE taggedProjection.value snapshot — never paired across reads.
            val seeded = accountStartup.state.taggedProjection.value
            membership = seeded.projection?.let { committedFrom(it, seeded.ownerSession) }
                ?: Membership.Unknown

            // NEW BINDS get expiry coverage too (9d29473a #1): the lifecycle invokes this hook
            // AFTER releasing its own lock, so reconcile's watcher→lifecycle order stays
            // one-directional and deadlock-free.
            val boundHook: (Long) -> Unit = { _ ->
                // The generation argument is NOT used: re-read the coherent installed snapshot
                // inside the decision, so a delayed/reordered older callback converges on the
                // CURRENT binding (watcher→lifecycle lock order preserved).
                reconcile()
            }
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
     * Test seam (controls only): invoked between authoritative revalidation and the retirement
     * decision, so the interleaving "revalidated stale → newer live policy published + successor
     * bound → resumed" is deterministic. Null in production.
     */
    @Volatile internal var onRevalidatedForTest: (() -> Unit)? = null

    /**
     * THE retirement decision (reviewer 9da4d830): ATOMIC expected-generation retirement — no
     * check-then-act. Re-read the authoritative lease-evaluated snapshot; on expiry retire
     * through [CaptureAllowanceLifecycle.retireCurrent][retireCurrent] with the generation
     * captured at decision time, which fails atomically if ANY bind raced the revalidation; a
     * lost race re-decides against the LATEST authority by repeating the decision. This is NOT
     * a bounded loop: under arbitrary concurrent binds the re-decision can repeat, and no
     * bounded-execution claim is made. The deadline callback uses the same path, so every
     * retirement is generation-guarded.
     */
    internal fun reconcile() {
        watcherLock.lock()
        try {
            if (disposed) return
            reconcileLocked()
        } finally {
            watcherLock.unlock()
        }
    }

    private fun committedFrom(
        projection: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection,
        ownerSession: Long,
    ): Membership.Committed = Membership.Committed(
        epoch = projection.enrollmentEpoch,
        ownerSession = ownerSession,
        membershipActive = projection.voiceSharingEnrolled &&
            projection.withdrawalState == au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState.ACTIVE,
    )

    /** Caller holds [watcherLock]. */
    private fun reconcileLocked() {
        while (true) {
            // ONE coherent installed read per decision (reviewer 255866ea): the same atomic
            // record supplies BOTH the guarded generation and the installed identity. Null when
            // nothing is bound — the guarded retire is then a generation-safe no-op.
            val installed = lifecycle.installedAuthority()
            val genAtDecision = installed?.generation ?: lifecycle.currentGeneration()
            val evaluated = policyProvider.snapshot()
            onRevalidatedForTest?.invoke()
            // MEMBERSHIP AUTHORITY LOSS / REPLACEMENT (reviewer 0bca26d5 + 414ddbe1), decided
            // against the INSTALLED authority identity — never against advancing history:
            // - Invalidated (committed NULL projection — same-session 401 loss) retires;
            // - a committed projection that is not an ACTIVE enrolled membership (withdrawal
            //   RETAINING the epoch string) retires;
            // - a committed ACTIVE identity differing from the INSTALLED identity (epoch or
            //   owner session replaced) retires the OLD batch; an unchanged identity refresh
            //   does not. A repeated decision keeps refusing the same stale grant until it is
            //   replaced by a bind under the new identity (which re-snapshots onBound) or
            //   retired — the replacement signal survives a lost retirement race.
            val membershipLoss = when (val m = membership) {
                is Membership.Invalidated -> true

                // FAIL CLOSED (reviewer c7cdff17): Unknown membership with a bound grant is NOT
                // a production authorization rule — including startup after a committed 401.
                Membership.Unknown -> installed != null

                is Membership.Committed ->
                    installed != null &&
                        (
                            installed.identity == null || // bound WITHOUT install identity: fail closed
                                !m.membershipActive ||
                                m.epoch != installed.identity.enrollmentEpoch ||
                                m.ownerSession != installed.identity.ownerSession
                            )
            }
            if (!evaluated.enabled || evaluated.activatedAtMonotonicMs <= 0 || membershipLoss) {
                // Authoritative policy expired or disabled: the bound authority is governed by
                // this expired lease. The GUARDED retirement is atomic: it retires ONLY the
                // binding generation we revalidated; a bind that raced in survives this call and
                // is re-decided against the latest authority below.
                cancelDeadlineLocked()
                val retired = lifecycle.retireCurrent(genAtDecision)
                if (retired) return
                if (lifecycle.currentGeneration() == genAtDecision) {
                    return // atomic refusal because nothing (or the default) is bound — done
                }
                continue // lost race against a raced bind: re-decide from the latest authority
            }
            armLocked(evaluated)
            return
        }
    }

    /** Caller holds [watcherLock]. Arms the deadline for a LIVE policy stamp, superseding any
     *  older stamp's deadline. The firing callback RE-ENTERS the same atomic decision. */
    private fun armLocked(evaluated: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot) {
        if (evaluated.activatedAtMonotonicMs == armedFor) return // already covered exactly once
        deadlineJob?.cancel()
        armedFor = evaluated.activatedAtMonotonicMs
        deadlineJob = scope.launch {
            val remaining = (armedFor + VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1L) - nowMs()
            if (remaining > 0L) sleepMs(remaining)
            watcherLock.lock()
            try {
                if (disposed || armedFor != evaluated.activatedAtMonotonicMs) return@launch // superseded
                reconcileLocked() // same atomic revalidate-and-retire path as every wakeup
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
            val myEvidence = installedEvidence
            if (myEvidence != null && accountStartup.enrollmentEvidenceListener === myEvidence) {
                accountStartup.enrollmentEvidenceListener = priorEvidence
            }
            val myInvalidated = installedInvalidated
            if (myInvalidated != null && accountStartup.state.projectionInvalidatedListener === myInvalidated) {
                accountStartup.state.projectionInvalidatedListener = priorInvalidated
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
