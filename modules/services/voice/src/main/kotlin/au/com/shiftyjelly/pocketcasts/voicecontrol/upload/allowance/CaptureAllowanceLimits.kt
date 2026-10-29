package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * Capture-admission allowance limits for ONE contributor account (spec b768de49).
 *
 * These bound CAPTURE admission, never measured network throughput. A capture is charged the FULL
 * reservation - one record plus [wavesReservationBytes] - and that charge is never refunded for
 * unused capacity, cancellation, expiry, delivery or queue removal. Retries reuse the capture charge
 * and stay subject to their own body/attempt/age limits.
 *
 * NO CEILING AND NO PRODUCTION VALUE IS CHOSEN HERE (reviewer 82cdd583 #5, 8b5fc0c9 #4, f64bd7ad #2,
 * spec dc4c1105). Two KINDS of number are separately supplied and must not be confused:
 *
 *  - [Ceilings] are the resource-limit paragraph's independent upper bounds on this client: the most
 *    a day may ever admit, and the largest clip and metadata envelope policy permits. They are
 *    supplied by policy and cannot be derived from anything here.
 *  - The SELECTED values describe this installation's operational limits. Each of the four must be
 *    A POLICY VALUE VALIDATED AGAINST ITS OWN CEILING - a daily byte total that merely fits one clip
 *    is not a maximum clip size, so the two kinds of number are checked pairwise.
 *
 * This type performs no defaulting - no constants, no copy-defaults, no "reasonable" fallback - so a
 * caller without real ceilings cannot construct limits, and [ProductionLimits] stays unavailable.
 */
data class AllowanceLimits(
    /** SELECTED ceiling on captures charged per accounting window. */
    val maxDailyRecords: Int,
    /** SELECTED ceiling on charged bytes per accounting window. */
    val maxDailyBytes: Long,
    /** SELECTED maximum encoded WAV clip size INCLUDING its 44-byte header. */
    val maxClipBytes: Long,
    /** SELECTED complete canonical record/digest envelope bound. */
    val maxMetadataBytes: Int,
    /** Independent policy upper bounds; each selected value must not exceed its counterpart. */
    val ceilings: Ceilings,
) {

    private val reservation: Long = checkedReservation(maxClipBytes, maxMetadataBytes)

    init {
        require(maxDailyRecords >= 1) { "max_daily_records must be positive" }
        require(maxMetadataBytes >= 1) { "max_metadata_bytes must be positive" }
        require(maxClipBytes > WAV_HEADER_BYTES) { "max_clip_bytes must exceed the WAV header" }
        // Each SELECTED value is validated against ITS OWN ceiling. Checking only that a day fits one
        // reservation would let an over-policy clip or metadata bound through.
        require(maxDailyRecords <= ceilings.maxDailyRecords) { "max_daily_records exceeds its ceiling" }
        require(maxDailyBytes <= ceilings.maxDailyBytes) { "max_daily_bytes exceeds its ceiling" }
        require(maxClipBytes <= ceilings.maxClipBytes) { "max_clip_bytes exceeds its ceiling" }
        require(maxMetadataBytes <= ceilings.maxMetadataBytes) { "max_metadata_bytes exceeds its ceiling" }
        // Structural checks still apply in addition to the policy ones.
        require(maxDailyBytes >= reservation) { "max_daily_bytes must admit one reservation" }
    }

    /** One record's FULL reservation: max encoded WAV including its header + metadata bound. */
    fun wavesReservationBytes(): Long = reservation

    /** True when a capture's full reservation still fits the remaining allowance. */
    fun admits(chargedRecords: Int, chargedBytes: Long): Boolean {
        if (chargedRecords < 0 || chargedBytes < 0) return false
        if (chargedRecords >= maxDailyRecords) return false
        // Subtraction avoids the wrap that `chargedBytes + reservation` can produce.
        return chargedBytes <= maxDailyBytes - reservation
    }

    /** Identity of the limits a charge was made against; a change retires a prepaid handoff. */
    fun binding(): String = "$maxDailyRecords/$maxDailyBytes/$maxClipBytes/$maxMetadataBytes"

    companion object {
        /** RIFF header + fmt + data chunk headers: the fixed part of the encoded clip. */
        const val WAV_HEADER_BYTES = 44L

        private fun checkedReservation(maxClipBytes: Long, maxMetadataBytes: Int): Long {
            val reservation = Math.addExact(maxClipBytes, maxMetadataBytes.toLong())
            require(reservation > WAV_HEADER_BYTES) { "reservation must exceed the WAV header" }
            return reservation
        }
    }
}

/**
 * The independent resource-limit ceilings for one contributor account (spec dc4c1105).
 *
 * These are policy inputs: an upper bound on how many captures and how many bytes a day may admit.
 * They are NOT derivable from the clip or metadata bounds, so they must be supplied explicitly and
 * are validated only for internal consistency. No production value exists in this change.
 */
data class Ceilings(
    /** Upper bound on captures charged per accounting window. */
    val maxDailyRecords: Int,
    /** Upper bound on charged bytes per accounting window. */
    val maxDailyBytes: Long,
    /** Upper bound on the selected maximum encoded clip size. */
    val maxClipBytes: Long,
    /** Upper bound on the selected canonical metadata envelope bound. */
    val maxMetadataBytes: Int,
) {
    init {
        require(maxDailyRecords >= 1) { "max_daily_records ceiling must be positive" }
        require(maxDailyBytes >= 1) { "max_daily_bytes ceiling must be positive" }
        require(maxClipBytes > AllowanceLimits.WAV_HEADER_BYTES) { "max_clip_bytes ceiling must exceed the WAV header" }
        require(maxMetadataBytes >= 1) { "max_metadata_bytes ceiling must be positive" }
    }

    fun binding(): String = "$maxDailyRecords/$maxDailyBytes/$maxClipBytes/$maxMetadataBytes"
}

/**
 * Where production limits would come from. UNAVAILABLE by construction in this change: no daily
 * ceiling has been selected, so there is no production value to hand out and no default to fall back
 * on. A caller must handle unavailability rather than receiving an invented allowance.
 *
 * Nothing in this change constructs [Selected]: no production caller supplies policy values yet, and
 * the constructor is merely `internal`, so this is an UNAVAILABLE-BY-CONVENTION state rather than a
 * type-level guarantee. Explicitly: the compiler does not prevent a same-module caller from building
 * one, and no such caller exists.
 */
sealed interface ProductionLimits {
    data object Unavailable : ProductionLimits

    /** Represents selected policy values; no caller in this change constructs it. */
    class Selected internal constructor(internal val limits: AllowanceLimits) : ProductionLimits
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
