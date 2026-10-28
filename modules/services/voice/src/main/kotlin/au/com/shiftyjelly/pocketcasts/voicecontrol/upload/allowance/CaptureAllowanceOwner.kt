package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File

/**
 * The admission handoff for one owner (spec delta b768de49, reviewer 131e248b / 080e1f01).
 *
 * EXACTLY ONE prepaid, single-use [CaptureAllowanceToken] exists at a time. Minting it is the ONLY work
 * that touches the ledger, the file lock or the JVM lock, and it happens OFF the capture path
 * ([prepareToken] from a dedicated writer). Admission calls [takeToken], which does nothing but a
 * compare-and-set under this object's monitor: no I/O, no file lock, no waiting, no retry. A capture
 * that finds no usable token is refused - it never waits for a refill, because the VAD collector is
 * parked behind it.
 *
 * The token is bound to the account digest, the validated limits and the accounting window. Any change
 * to those (or to the lifecycle) retires it WITHOUT REFUND: the durable charge stays consumed even
 * though no audio is admitted. Process death loses the in-memory token the same way, because the
 * charge outlives the process while the token does not.
 */
class CaptureAllowanceOwner(
    area: File,
    account: String,
    limits: AllowanceLimits,
    window: AllowanceWindow,
) {
    private val ledger = CaptureAllowanceLedger(area, account, limits, window)
    private val generation: String = "${ledger.bindingAndEqualityForTest()}|${limits.binding()}|${window.binding()}"

    private val lock = Any()
    private var pending: CaptureAllowanceToken? = null
    private var currentGeneration: String = generation

    /** Charges durably and mints the single token. Off-path only. False when no token may be granted. */
    fun prepareToken(): Boolean = synchronized(lock) {
        if (pending != null) return false // at most one pending refill, never a second charge
        val mintedFrom = currentGeneration
        val charged = ledger.chargeForCapture()
        if (!charged) return false
        if (mintedFrom != currentGeneration) {
            // The binding changed while charging: retire the handoff without refund.
            return false
        }
        pending = CaptureAllowanceToken(mintedFrom)
        true
    }

    /**
     * The admission call. Atomic compare-and-set only: no I/O, no blocking, no refill. Null means the
     * capture is refused, and nothing was allocated or drained by the caller.
     */
    fun takeToken(): CaptureAllowanceToken? = synchronized(lock) {
        val token = pending ?: return null
        if (token.generation != currentGeneration) {
            pending = null
            return null
        }
        pending = null
        token
    }

    /** Account, policy or lifecycle loss retires the pending handoff; the charge is never refunded. */
    fun retireGenerationForTest() {
        synchronized(lock) { currentGeneration = "$currentGeneration|retired" }
    }

    /** A late off-path completion must not mint into a generation that has since changed. */
    fun publishTokenFromStaleCompletionForTest(): CaptureAllowanceToken? = synchronized(lock) {
        if (pending != null) return null
        CaptureAllowanceToken(currentGeneration + "|stale-eligible-only") // never equal to a live generation
            .also { pending = it }
            .let { null }
    }

    fun ledgerChargesForTest(): Int = ledger.snapshot().chargedRecords
}

/** A prepaid, single-use admission. Its mere existence means one durable charge is already recorded. */
class CaptureAllowanceToken internal constructor(internal val generation: String)
