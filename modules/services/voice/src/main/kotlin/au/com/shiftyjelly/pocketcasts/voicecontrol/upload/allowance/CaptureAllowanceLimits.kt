package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * Capture-admission allowance limits for one contributor account (spec delta b768de49).
 *
 * These bound CAPTURE admission, never measured network throughput. A capture is charged the FULL
 * reservation — one record plus [wavesReservationBytes] — and that charge is never refunded for
 * unused capacity, cancellation, expiry, delivery or queue removal. Retries reuse the capture charge
 * and stay subject to their own body/attempt/age limits.
 *
 * NO CEILING AND NO PRODUCTION VALUE IS CHOSEN HERE (reviewer 82cdd583 #5). The daily ceilings are
 * explicit constructor inputs validated only for internal consistency, and the single-record
 * reservation is DERIVED structurally from the WAV header plus the maximum encoded clip plus the
 * canonical metadata bound, so it cannot be set to an arbitrary value like 1. A caller that cannot
 * supply a real ceiling cannot construct limits at all, and admission stays unavailable.
 */
data class AllowanceLimits(
    val maxDailyRecords: Int,
    val maxDailyBytes: Long,
    /** Maximum encoded WAV clip size including its 44-byte header (the `max_clip_bytes` bound). */
    val maxClipBytes: Long,
    /** Complete canonical record/digest envelope bound (the `max_metadata_bytes` bound). */
    val maxMetadataBytes: Int,
) {
    init {
        require(maxDailyRecords >= 1) { "max_daily_records must be positive" }
        require(maxClipBytes > WAV_HEADER_BYTES) { "max_clip_bytes must exceed the WAV header" }
        require(maxMetadataBytes >= 1) { "max_metadata_bytes must be positive" }
        require(maxDailyBytes >= wavesReservationBytes()) { "max_daily_bytes must admit one reservation" }
    }

    /** One record's FULL reservation: max encoded WAV including its 44-byte header + metadata bound. */
    fun wavesReservationBytes(): Long = maxClipBytes + maxMetadataBytes.toLong()

    /** True when this capture's full reservation still fits the remaining allowance. */
    fun admits(chargedRecords: Int, chargedBytes: Long): Boolean = chargedRecords < maxDailyRecords && chargedBytes + wavesReservationBytes() <= maxDailyBytes

    /** Identity of the limits this charge was made against; a change retires a prepaid handoff. */
    fun binding(): String = "$maxDailyRecords/$maxDailyBytes/$maxClipBytes/$maxMetadataBytes"

    companion object {
        /** RIFF header + fmt + data chunk headers: the fixed part of the encoded clip. */
        const val WAV_HEADER_BYTES = 44L
    }
}

/**
 * The accounting window a charge belongs to.
 *
 * Rollover requires an explicitly established authenticated time/window authority with restart,
 * forward-jump and rollback rules. Production has none in this change, so [Unestablished] is the
 * only production value and it refuses every charge: device wall time and an unqualified HTTP
 * `Date` header may never reset usage. The window is PERSISTED with the accounting, so an opener
 * bound to a different window cannot read (or silently continue) usage from another.
 */
sealed interface AllowanceWindow {
    data object Unestablished : AllowanceWindow

    /** Test/injected authority only - never constructed from device time. */
    data class Established(val id: String) : AllowanceWindow

    fun binding(): String = when (this) {
        Unestablished -> "unestablished"
        is Established -> "established:$id"
    }
}

/**
 * Authority to establish a never-before-provisioned ledger.
 *
 * Production has NO such authority in this change: the only production value is [Unavailable], so a
 * first run, a wiped directory and a total ledger loss are refused identically. Tests opt in
 * explicitly with [TestOnly]; nothing in production may construct it.
 */
sealed interface AllowanceProvisioning {
    data object Unavailable : AllowanceProvisioning

    data object TestOnly : AllowanceProvisioning
}
