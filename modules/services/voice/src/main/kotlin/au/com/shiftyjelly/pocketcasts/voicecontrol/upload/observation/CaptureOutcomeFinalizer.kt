package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BOUNDED OUTCOME FINALIZATION (Batch B, reviewer c5d94af4 #1 / be6bb62e / 62023896): every
 * retained capture carries a FINITE outcome wait whose ABSOLUTE deadline is frozen at REAL
 * completion time — the SINK captures the monotonic completion timestamp under its lock and
 * carries it in the retained callback, so a delayed callback or dispatcher can never extend
 * the wait (62023896 #1). At expiry the capture is SEALED — an immutable [SealedCapture]
 * preserving every known fact, unresolved stages typed `unknown/outcome_wait_expired` — and
 * dispatched through the REAL publication path. The deadline never deletes a capture.
 *
 * Timer lifecycle, owned per capture:
 *  - ONE [Registration] per capture; entry inserted BEFORE the coroutine launches; removal is
 *    identity-safe (`remove(id, reg)`) and [runDeadline] cleans up in `finally` on any
 *    exception/cancellation path (62023896 #2/#3);
 *  - REGISTRATION is validated against the sink ([canAcceptOutcomeWaitRegistration]:
 *    generation + presence + unsealed + no publishable terminal) and promptly dropped when a
 *    callback arrives for an already-sealed/released/purged capture; ACTUAL SEALING cancels
 *    the timer through the sink's [onCaptureSealed] hook, alongside release, revocation, and
 *    disposal (62023896 #2);
 *  - the FIRING decision is atomic: [ContributionRetentionSink.sealExpiredOutcome]`(id, gen)`
 *    validates generation + presence + not-sealed and seals under ONE sink-lock boundary;
 *  - a purge notification cancels only registrations from BEFORE that purge (generation
 *    filter), so a capture registered after the purge keeps its valid timer.
 *
 * [waitLimits] carries the INDEPENDENT selected and ceiling inputs. No limits selected →
 * INACTIVE, and that state CLOSES contribution admission (spec cf1b9809).
 *
 * Process death loses the in-memory timers by design (same scope as the sink itself); scope
 * cancellation disposes via [Job.invokeOnCompletion].
 */
class CaptureOutcomeFinalizer(
    private val sink: ContributionRetentionSink,
    private val waitLimits: OutcomeWaitLimits?,
    private val scope: CoroutineScope,
    /** Injectable pause; production uses the coroutine delay. Tests drive a virtual clock. */
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
    /** Injectable monotonic clock; MUST be the SAME domain as the sink's [nowMs] so the
     *  completion-frozen deadline and the remaining-duration computation agree. */
    private val nowMs: () -> Long,
) {
    val expiredReason: String get() = SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED

    /** True when a bounded wait is selected. FALSE CLOSES ADMISSION (fail-closed wiring). */
    val limitsSelected: Boolean get() = waitLimits != null

    /** ONE owned registration per capture; removal is identity-safe by (id, registration). */
    private class Registration(val captureId: UUID, val generation: Long, val deadlineAtMs: Long) {
        @Volatile
        var cancelled = false

        var job: Job? = null
    }

    private val lock = ReentrantLock()
    private val timers = ConcurrentHashMap<UUID, Registration>()
    private var disposed = false
    private var installedRetainedHook: ((UUID, Long, Long) -> Unit)? = null
    private var installedReleasedHook: ((UUID) -> Unit)? = null
    private var installedSealedHook: ((UUID) -> Unit)? = null
    private var installedPurgedHook: (() -> Unit)? = null

    /** Test visibility: registrations neither completed nor cancelled (must drain to zero). */
    fun activeTimerCount(): Int = timers.size

    /** Attaches this finalizer's hooks to the sink and arms the owning-scope teardown. */
    fun start() {
        lock.withLock {
            if (disposed || installedRetainedHook != null) return
            val retained: (UUID, Long, Long) -> Unit = { id, gen, completedAtMs -> noteRetained(id, gen, completedAtMs) }
            val released: (UUID) -> Unit = { cancelTimer(it) }
            val sealedHook: (UUID) -> Unit = { cancelTimer(it) }
            val purged: () -> Unit = { onPurgedNotification() }
            installedRetainedHook = retained
            installedReleasedHook = released
            installedSealedHook = sealedHook
            installedPurgedHook = purged
            sink.onCandidateRetained = retained
            sink.onCaptureReleased = released
            sink.onCaptureSealed = sealedHook
            sink.onPurged = purged
            scope.coroutineContext[Job]?.invokeOnCompletion { dispose() }
        }
    }

    /** Arms the capture's deadline from its COMPLETION-FROZEN timestamp (never from delivery
     *  time). The registration is inserted before the coroutine launches. */
    private fun noteRetained(captureId: UUID, atGeneration: Long, completedAtMs: Long) {
        val limits = waitLimits ?: return
        lock.withLock {
            if (disposed) return
            timers.remove(captureId)?.let {
                it.cancelled = true
                it.job?.cancel()
            }
            val reg = Registration(captureId, atGeneration, completedAtMs + limits.selectedWaitMs)
            timers[captureId] = reg
            reg.job = scope.launch { runDeadline(reg) }
        }
        // PROMPT BOOKKEEPING CANCELLATION (not the firing decision): a callback delivered for
        // an already-sealed/released capture or an already-superseded purge generation drops
        // its timer at once. The FIRING decision itself remains the sink's atomic
        // generation-checked seal; a later purge is covered by the generation-filtered hook.
        if (!sink.canAcceptOutcomeWaitRegistration(captureId, atGeneration)) cancelTimer(captureId)
    }

    private suspend fun runDeadline(reg: Registration) {
        try {
            val remaining = reg.deadlineAtMs - nowMs()
            if (remaining > 0) sleepMs(remaining)
            if (reg.cancelled) return
            // ATOMIC: the sink validates generation + presence + not-sealed and seals in ONE
            // lock acquisition; the snapshot is dispatched through the real publication path.
            sink.sealExpiredOutcome(reg.captureId, reg.generation)
        } finally {
            // Identity-safe, exception/cancellation-safe cleanup: only OUR registration.
            timers.remove(reg.captureId, reg)
        }
    }

    private fun cancelTimer(captureId: UUID) {
        timers.remove(captureId)?.let {
            it.cancelled = true
            it.job?.cancel()
        }
    }

    /** A purge notification cancels only registrations OLDER than that purge; a capture
     *  registered after the purge keeps its valid timer. */
    private fun onPurgedNotification() {
        val currentGeneration = sink.currentPurgeGeneration()
        timers.values.filter { it.generation < currentGeneration }.forEach { reg ->
            timers.remove(reg.captureId, reg)
            reg.cancelled = true
            reg.job?.cancel()
        }
    }

    /** Disposes: cancels every deadline, detaches ONLY its own hooks, refuses later arming. */
    fun dispose() {
        lock.withLock {
            if (disposed) return
            disposed = true
            timers.values.forEach { reg ->
                timers.remove(reg.captureId, reg)
                reg.cancelled = true
                reg.job?.cancel()
            }
            if (sink.onCandidateRetained === installedRetainedHook) sink.onCandidateRetained = null
            if (sink.onCaptureReleased === installedReleasedHook) sink.onCaptureReleased = null
            if (sink.onCaptureSealed === installedSealedHook) sink.onCaptureSealed = null
            if (sink.onPurged === installedPurgedHook) sink.onPurged = null
            installedRetainedHook = null
            installedReleasedHook = null
            installedSealedHook = null
            installedPurgedHook = null
        }
    }
}
