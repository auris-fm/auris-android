package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.util.concurrent.locks.ReentrantLock

/**
 * The lifecycle-owned allowance authority slot (reviewer c5d94af4 #4 / b256b011): admission reads
 * THIS slot under the collection fence, and every authoritative lifecycle input (account
 * replacement/session end, policy disable, evaluated lease expiry, limits/window rebind,
 * in-process owner disposal) retires through it.
 *
 * LOCK ORDER (reviewer 3adcf0f3, reconciled): the collection fence is NOT claimed by this class;
 * the composer's [onRetired] hook enters it. The two paths therefore nest differently:
 * - ADMISSION: collection fence → `tryLock`([lock]). The lifecycle lock is only ever
 *   TRY-locked on this path: contention with an in-flight retirement REFUSES the capture, it
 *   never blocks. That try-acquisition is what makes the opposite nesting safe.
 * - RETIREMENT/BIND/DISPOSE (management paths): [lock] → the collection fence (inside the
 *   hook). No management path ever holds the fence, and admission never BLOCKS on [lock], so
 *   the cycle "admission holds fence, waits for lock; retirement holds lock, waits for fence"
 *   cannot occur: its first edge requires a blocking acquisition admission never performs.
 *
 * GENERATIONS: every successful [bind] and retirement increments a monotonic binding
 * generation. Policy/watcher callbacks capture the generation they observed BEFORE their
 * authoritative revalidation and retire only through
 * [retireCurrent][retireCurrent] with that exact generation — a successor bound in between is
 * NOT retired by a stale callback (reviewer 3adcf0f3 #1). Account/session replacement retires
 * UNCONDITIONALLY (a dead session invalidates any bound authority regardless of generation).
 * Linearization: an admission whose take returned before a retirement linearized first and may
 * complete its admission; the retirement then invalidates its outstanding capture through
 * [onRetired] (the production hook fires `observer.revoked("allowance-retired")`, which purges
 * open + retained work through the collection fence). [bind] is retire-then-set under the same
 * lock, so a limits/window rebind invalidates every token issued under the previous
 * configuration even with identical account identity.
 */
class CaptureAllowanceLifecycle : CaptureAllowanceAuthority {

    /** The single serialization primitive for take/bind/retire/dispose. Non-blocking on take. */
    private val lock = ReentrantLock()

    @Volatile private var disposed = false

    @Volatile private var current: CaptureAllowanceAuthority = UnavailableCaptureAllowance

    /** Monotonic binding generation; incremented under [lock] on every bind and retirement. */
    @Volatile private var generation = 0L

    /**
     * Invoked AFTER the slot has been replaced under [lock] for every real retirement. The
     * composition sets this to the collection-revocation hook (`observer.revoked` enters the
     * collection fence itself — see the lock-order note above). Not fired for no-ops.
     */
    @Volatile var onRetired: (() -> Unit)? = null

    /**
     * Invoked AFTER [bind] releases [lock] with the new binding generation (never under the
     * lifecycle lock, so the watcher's reconcile keeps its one-directional watcher→lifecycle
     * lock order). The retirement watcher uses this to give every NEW binding its own expiry
     * coverage — a binding made after a policy publication does not escape that lease (reviewer
     * 9d29473a #1).
     */
    @Volatile var onBound: ((Long) -> Unit)? = null

    override fun takePrepaidToken(): CaptureAllowanceToken? {
        // NON-WAITING (reviewer b256b011): contention with a retirement/rebind REFUSES the
        // capture. This try-acquisition is also half of the deadlock-freedom argument.
        if (!lock.tryLock()) return null
        return try {
            if (disposed) return null
            current.takePrepaidToken()
        } finally {
            lock.unlock()
        }
    }

    /** The binding generation observed atomically with the current slot (no ordering promise
     *  beyond the volatile read; callbacks use it only as a pre-check under their own discipline
     *  and always confirm through the guarded retire). */
    fun currentGeneration(): Long = generation

    /**
     * Binds [authority] as the current slot, FIRST retiring whatever was bound (kill any
     * prepared token + fire the revocation hook), under the same lock as take. Returns the NEW
     * binding generation. Refused after dispose.
     */
    fun bind(authority: CaptureAllowanceAuthority): Long {
        val newGeneration: Long
        val boundHook: ((Long) -> Unit)?
        lock.lock()
        try {
            checkGenerationNotOverflow()
            if (disposed) return generation
            retireLocked()
            current = authority
            generation += 1L
            newGeneration = generation
            boundHook = onBound
        } finally {
            lock.unlock()
        }
        // Post-unlock (see KDoc): the hook runs OUTSIDE the lifecycle lock.
        boundHook?.invoke(newGeneration)
        return newGeneration
    }

    /**
     * Retires the currently bound authority. When [expectedGeneration] is non-null, retires ONLY
     * if the live generation still equals it — a successor bound in the meantime survives.
     * Returns true when a REAL authority was retired (the revocation hook fired); false when the
     * generation was superseded, the slot was already the unavailable default, or disposed.
     */
    fun retireCurrent(expectedGeneration: Long? = null): Boolean {
        lock.lock()
        try {
            if (disposed) return false
            if (expectedGeneration != null && generation != expectedGeneration) return false
            return retireLocked()
        } finally {
            lock.unlock()
        }
    }

    /**
     * In-process owner disposal: cancels THIS authority's participation permanently — every later
     * take/bind/retire refuses — and retires whatever was bound. Distinct from process death,
     * where the in-memory prepared token is lost by design and no hook exists.
     */
    fun dispose() {
        lock.lock()
        try {
            if (disposed) return
            disposed = true
            retireLocked()
        } finally {
            lock.unlock()
        }
    }

    /** Test-only admission path: run [block] holding the lifecycle lock, to prove take REFUSES
     *  (tryLock) instead of blocking — the deterministic contention/no-deadlock control. */
    internal fun <R> withLifecycleLockForTest(block: () -> R): R {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    /** Caller holds [lock]. Replace the slot FIRST so no take can observe the dying authority. */
    private fun retireLocked(): Boolean {
        val previous = current
        current = UnavailableCaptureAllowance
        previous.retireForLifecycle()
        if (previous === UnavailableCaptureAllowance) return false
        generation += 1L
        onRetired?.invoke()
        return true
    }

    private fun checkGenerationNotOverflow() {
        check(generation < Long.MAX_VALUE) { "binding generation exhausted" }
    }
}
