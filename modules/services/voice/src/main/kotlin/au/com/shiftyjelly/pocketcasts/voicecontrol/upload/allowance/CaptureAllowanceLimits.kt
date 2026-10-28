package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * Capture-admission allowance limits for the stable contributor account (spec delta b768de49).
 *
 * These bound CAPTURE admission, never measured network throughput: a capture is charged the FULL
 * reservation (one record plus the maximum encoded WAV including its 44-byte header plus
 * `max_metadata_bytes`), and that charge is never refunded for unused capacity, cancellation,
 * expiry, delivery or queue removal. Retries reuse the capture charge and stay subject to their own
 * finite body/attempt/age limits.
 *
 * No production numeric value is selected by this change: construction validates only, and the
 * caller supplies values. A missing or invalid limit refuses admission rather than defaulting.
 */
data class AllowanceLimits(
    val maxDailyRecords: Int,
    val maxDailyBytes: Long,
    /** One record's full reservation: max encoded WAV (incl. header) + canonical metadata bound. */
    val reservationBytes: Long,
) {
    init {
        require(maxDailyRecords in 1..MAX_DAILY_RECORDS_CEILING) { "max_daily_records outside ceiling" }
        require(maxDailyBytes in 1..MAX_DAILY_BYTES_CEILING) { "max_daily_bytes outside ceiling" }
        require(reservationBytes in 1..MAX_DAILY_BYTES_CEILING) { "reservation outside ceiling" }
    }

    /** True when this capture's full reservation still fits the remaining allowance. */
    fun admits(chargedRecords: Int, chargedBytes: Long): Boolean = chargedRecords < maxDailyRecords && chargedBytes + reservationBytes <= maxDailyBytes

    /** Identity of the limits this charge was made against; a change retires a prepaid handoff. */
    fun binding(): String = "$maxDailyRecords/$maxDailyBytes/$reservationBytes"

    companion object {
        const val MAX_DAILY_RECORDS_CEILING = 10_000
        const val MAX_DAILY_BYTES_CEILING = 4L * 1024 * 1024 * 1024
    }
}

/**
 * The accounting window a charge belongs to.
 *
 * Rollover requires an explicitly established authenticated time/window authority with restart,
 * forward-jump and rollback rules. Production has none in this change, so [Unestablished] is the
 * only production value and it refuses every charge: device wall time and an unqualified HTTP
 * `Date` header may never reset usage.
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
