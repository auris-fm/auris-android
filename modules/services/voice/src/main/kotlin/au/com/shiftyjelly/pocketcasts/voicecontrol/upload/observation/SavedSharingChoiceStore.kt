package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation


/**
 * The signed-in account's ORDINARY sharing choice and real enrollment provenance, held durably.
 *
 * WHY THIS EXISTS (spec 1af5e705, reviewer evidence): admission used to read the in-memory membership
 * projection, which only a server refresh in the CURRENT process can supply. Saved consent therefore
 * could not authorize a capture, and a user who had opted in was refused every time after a restart -
 * the ordinary path the spec mandates ("sharing on -> completed speech segment -> private file ->
 * existing authenticated upload") did not work without a fresh round trip. The projection also had no
 * durable counterpart at all, so nothing on disk could answer "is sharing on?".
 *
 * WHAT THIS IS NOT. It is not a readiness protocol, a poller, an evidence cache with freshness rules,
 * or an allowance/ledger inference. It stores exactly what the existing enrollment/withdrawal
 * operations already obtain, and nothing is derived from it beyond the plain question of consent.
 *
 * THE THREE RULES THIS CLASS ENFORCES:
 *  1. An explicit stop wins IMMEDIATELY and survives restart: [withdraw] replaces the record with a
 *     WITHDRAWN choice before/independently of any network outcome, and a WITHDRAWN record never
 *     admits, whatever the server later says.
 *  2. An account NEVER inherits another account's choice or epoch: every record carries its account
 *     and any read for a different account is a refusal.
 *  3. Only a real record admits. A missing, unreadable, corrupt or account-mismatched file refuses -
 *     "no stop file present" is never treated as consent.
 */
class SavedSharingChoiceStore(
    private val persistence: SharingChoicePersistence,
) {
    /** The stored choice for [account], or null when nothing is saved or the file is unusable. */
    fun read(account: String): SavedSharingChoice? = persistence.read(account)

    /**
     * Record an EXPLICIT opt-in with the server's real epoch and accepted disclosure version.
     *
     * Returns false when the record could not be made durable. Callers must treat false as "the
     * choice is not saved" and must NOT report sharing as on: a remembered consent that is not on
     * disk is precisely the defect this store exists to remove.
     */
    fun recordEnrolled(
        account: String,
        epoch: String,
        acceptedDisclosureVersion: String,
    ): Boolean {
        if (account.isBlank() || epoch.isBlank() || acceptedDisclosureVersion.isBlank()) return false
        val written = persistence.write(
            SavedSharingChoice(
                account = account,
                state = SharingChoiceState.ENROLLED,
                epoch = epoch,
                acceptedDisclosureVersion = acceptedDisclosureVersion,
            ),
        )
        return written
    }

    /**
     * Record a server answer of NOT ENROLLED. Nothing the user decided at a surface produced
     * this, but it IS the account's actual current state: an old ENROLLED record left on disk
     * would admit captures the server has already closed (@spec 3f1e939a). No epoch or disclosure
     * is fabricated — the record simply fails every consent read.
     */
    fun recordNotEnrolled(account: String): Boolean {
        if (account.isBlank()) return false
        val written = persistence.write(
            SavedSharingChoice(
                account = account,
                state = SharingChoiceState.NOT_ENROLLED,
                epoch = null,
                acceptedDisclosureVersion = null,
            ),
        )
        return written
    }

    /**
     * Record an EXPLICIT stop. The epoch is RETAINED: a retired epoch is a server-refusal matter
     * under ordinary record construction, not grounds for this client to erase provenance. Only the
     * consent state changes, which is the single fact the user actually decided.
     */
    fun recordWithdrawn(account: String): Boolean {
        if (account.isBlank()) return false
        val previous = persistence.read(account)
        val written = persistence.write(
            SavedSharingChoice(
                account = account,
                state = SharingChoiceState.WITHDRAWN,
                // Carry the epoch over only when it belongs to the SAME account; never migrate one
                // account's provenance onto another.
                epoch = previous?.takeIf { it.account == account }?.epoch,
                acceptedDisclosureVersion = previous?.takeIf { it.account == account }?.acceptedDisclosureVersion,
            ),
        )
        return written
    }

    /**
     * Whether the account has ANY durable record, whatever its state. VISIBILITY ONLY (Settings row
     * shown/hidden): "sharing is off" is a known durable state, so an offline start with a WITHDRAWN
     * record must still show the row. The switch's VALUE comes from [enrolledChoiceFor], which keeps
     * its stricter consent semantics.
     */
    fun knownChoiceFor(account: String?): SavedSharingChoice? {
        if (account.isNullOrBlank()) return null
        return persistence.read(account)?.takeIf { it.account == account }
    }

    /**
     * The choice for [account], or null unless this account is enrolled with a real epoch and an
     * accepted disclosure. This is the ONE question admission asks.
     */
    fun enrolledChoiceFor(account: String?): SavedSharingChoice? {
        if (account.isNullOrBlank()) return null
        val choice = persistence.read(account) ?: return null
        if (choice.account != account) return null
        if (choice.state != SharingChoiceState.ENROLLED) return null
        if (choice.epoch.isNullOrBlank() || choice.acceptedDisclosureVersion.isNullOrBlank()) return null
        return choice
    }
}

/** The user's own statement of consent, and the provenance that came with it. */
data class SavedSharingChoice(
    val account: String,
    val state: SharingChoiceState,
    val epoch: String?,
    val acceptedDisclosureVersion: String?,
)

enum class SharingChoiceState { ENROLLED, WITHDRAWN, NOT_ENROLLED }

/** Durable single-record storage for [SavedSharingChoice]. */
interface SharingChoicePersistence {
    /** The saved record for [account], or null. Per-account: one account's record is never read for another. */
    fun read(account: String): SavedSharingChoice?

    /** Returns TRUE only when the record is durably in place. A false result is only logged. */
    fun write(choice: SavedSharingChoice): Boolean
}
