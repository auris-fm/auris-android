package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * The prepaid-admission dependency the capture path consumes at speech start (reviewer 2164aee3).
 *
 * FAIL-CLOSED: null REFUSES the capture BEFORE the sink reservation and BEFORE the engine's
 * ShortArray/drain. There is no "admit without an authority" path - a missing allowance authority
 * is unavailability, never a bypass. [CaptureAllowanceOwner] is the real implementation; the
 * production composition currently binds [UnavailableCaptureAllowance] because no production
 * limits, window or provisioning authority are selected (spec dc4c1105 lineage).
 */
fun interface CaptureAllowanceAuthority {
    /**
     * Returns EXACTLY ONE prepaid, single-use token, or null to refuse this capture. Non-blocking
     * and IO-free: called under the collection fence, before the observer monitor is entered, and
     * must never open the ledger or touch storage. A token returned here may be BURNED without
     * refund if the sink subsequently refuses - a durable charge may consume allowance without
     * admitting audio.
     */
    fun takePrepaidToken(): CaptureAllowanceToken?

    /**
     * Lifecycle retirement (reviewer c5d94af4 #4): invoked when the owning composition retires
     * this authority because an authoritative lifecycle input changed (account replacement,
     * policy disable/lease expiry, limits/window rebind, in-process owner disposal). Kills any
     * prepared-but-untaken token WITHOUT refund; a token already taken linearized before this
     * call and is invalidated through the retirement revocation, not here. Default no-op so
     * stateless authorities (UnavailableCaptureAllowance) need no handling.
     */
    fun retireForLifecycle() {}
}

/** Production binding while no authority is selected: every capture is refused. */
object UnavailableCaptureAllowance : CaptureAllowanceAuthority {
    override fun takePrepaidToken(): CaptureAllowanceToken? = null
}
