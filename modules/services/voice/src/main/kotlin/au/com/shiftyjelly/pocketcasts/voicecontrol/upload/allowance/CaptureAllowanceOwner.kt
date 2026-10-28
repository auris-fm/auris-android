package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File

/**
 * The admission handoff for one owner (spec b768de49; reviewer 131e248b, 080e1f01, 82cdd583 #3).
 *
 * EXACTLY ONE prepaid, single-use [CaptureAllowanceToken] exists at a time. Only [prepareToken] does
 * work that can block — it is OFF the capture path, on a dedicated writer — and it holds [preparing]
 * only around the storage call, never [lock]. That separation is what keeps the VAD collector free:
 * [takeToken] and [retire] take [lock] for a handful of field reads and never wait behind I/O, and
 * the generation can therefore change WHILE a charge is in flight, making the stale-completion check
 * meaningful.
 *
 * A capture that finds no usable token is refused; it never waits for a refill, because the
 * collector is parked behind it. Retiring a token without refund is deliberate: the durable charge
 * stays consumed even though no audio was admitted, and process death loses the in-memory token the
 * same way.
 */
class CaptureAllowanceOwner(
    area: File,
    account: String,
    limits: AllowanceLimits,
    window: AllowanceWindow,
    provisioning: AllowanceProvisioning = AllowanceProvisioning.Unavailable,
) {
    private val ledger = CaptureAllowanceLedger(area, account, limits, window, provisioning)
    private val limitsBinding = limits.binding()

    private val windowBinding = window.binding()

    /**
     * Entered exactly when preparation reaches storage, so a control can hold it deterministically
     * instead of racing a thread that "may" be running. Off-path only.
     */
    @Volatile
    internal var onStorageEntered: (() -> Unit)? = null

    /** The admission decision's only lock: field access, never I/O. */
    private val lock = Any()

    /** Serializes off-path preparations so at most ONE refill is ever pending. */
    private val preparing = Any()

    @Volatile
    private var generation: Long = 0

    @Volatile
    private var pending: CaptureAllowanceToken? = null

    @Volatile
    private var preparedGeneration: Long = -1

    /** Mints the single token. OFF-PATH only: may block on storage, never called by admission. */
    fun prepareToken(): Boolean {
        synchronized(preparing) {
            if (pending != null) return false // at most one pending refill, never a second charge
            val mintedFrom = generation
            onStorageEntered?.invoke()
            val charged = ledger.chargeForCapture()
            if (!charged) return false
            return publish(mintedFrom)
        }
    }

    /**
     * The admission call. Non-blocking and IO-free: one atomic check-and-take. Null means the capture
     * is refused and nothing was allocated or drained.
     */
    fun takeToken(): CaptureAllowanceToken? = synchronized(lock) {
        val token = pending ?: return null
        if (token.generation != generation || token.limitsBinding != limitsBinding || token.windowBinding != windowBinding) {
            pending = null
            return null
        }
        pending = null
        token
    }

    /**
     * Retires the pending handoff without refund: a preparation in flight for an older generation will
     * no longer publish, and any token already minted becomes unusable.
     */
    fun retire() {
        synchronized(lock) {
            generation += 1
            pending = null
        }
    }

    /** True when a prepared token exists for the CURRENT generation, limits and window. */
    fun hasUsableToken(): Boolean = synchronized(lock) {
        val token = pending ?: return false
        token.generation == generation && token.limitsBinding == limitsBinding && token.windowBinding == windowBinding
    }

    private fun publish(mintedFrom: Long): Boolean = synchronized(lock) {
        // A generation change during the charge must not mint: the charge stays consumed, no refund.
        if (mintedFrom != generation) return false
        if (pending != null) return false
        pending = CaptureAllowanceToken(generation, limitsBinding, windowBinding)
        preparedGeneration = generation
        true
    }

    fun ledgerChargesForTest(): Int = when (val snap = ledger.snapshot()) {
        is AllowanceSnapshot.Entry -> snap.chargedRecords
        AllowanceSnapshot.Closed -> -1
    }
}

/** A prepaid, single-use admission. Its existence means one durable charge is already recorded. */
class CaptureAllowanceToken internal constructor(
    internal val generation: Long,
    internal val limitsBinding: String,
    internal val windowBinding: String,
)
