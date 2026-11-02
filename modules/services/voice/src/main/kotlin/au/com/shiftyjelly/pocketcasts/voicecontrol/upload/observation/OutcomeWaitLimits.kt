package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

/**
 * THE BOUNDED OUTCOME WAIT (reviewer c5d94af4 #1 / b256b011 Batch B): the FINITE deadline after
 * which a retained capture without a terminal outcome is finalized as
 * `NotCollected("outcome_wait_expired")` instead of staying open forever.
 *
 * INDEPENDENT INPUTS (reviewer b256b011): [selectedWaitMs] (the policy/limits SELECTION) and
 * [ceilingWaitMs] (the ceiling) are two separate constructor inputs — the ceiling VALIDATES the
 * selection, it never derives it. Fail-closed construction: a selection above its ceiling, or a
 * non-positive value on either side, is a construction ERROR, not a clamp.
 *
 * PRODUCTION BINDING: no wait is selected while production limits are unavailable — the module
 * composes the finalizer with `null` (INACTIVE: no deadlines, behavior unchanged, exactly as
 * before Batch B). A future reviewed selection constructs this type explicitly; never an
 * implicit default.
 */
data class OutcomeWaitLimits(
    val selectedWaitMs: Long,
    val ceilingWaitMs: Long,
) {
    init {
        require(selectedWaitMs > 0L) { "selected outcome wait must be positive" }
        require(ceilingWaitMs > 0L) { "outcome wait ceiling must be positive" }
        require(selectedWaitMs <= ceilingWaitMs) { "selected outcome wait $selectedWaitMs exceeds its ceiling $ceilingWaitMs" }
    }
}
