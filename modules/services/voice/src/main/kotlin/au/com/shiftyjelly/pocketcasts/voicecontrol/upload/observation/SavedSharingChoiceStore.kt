package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.fs.DirectorySync
import java.io.File
import java.io.FileOutputStream

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
    /**
     * FAILED-PERSIST LATCH (@spec 3f1e939a, per-account @spec 57cef12c). Once an account's write
     * reports failure, THAT account's consent reads refuse until THAT account's write SUCCEEDS:
     * the file may still hold a stale ENROLLED record while the user's latest instruction for the
     * account (withdrawn / not enrolled) did not land. Logging the failure is not enough — a log
     * cannot stop a capture.
     *
     * Scope is deliberately PER ACCOUNT: a global latch let a successful write for account B clear
     * a failed stop for account A, after which A read its stale enrolled file. A's latch is A's.
     */
    private val persistFailedAccounts = java.util.Collections.synchronizedSet(HashSet<String>())

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
        if (account.isBlank() || epoch.isBlank() || acceptedDisclosureVersion.isBlank()) {
            lastFailureReason = "blank field: account=${account.isBlank()} epoch=${epoch.isBlank()} disclosure=${acceptedDisclosureVersion.isBlank()}"
            return false
        }
        val written = persistence.write(
            SavedSharingChoice(
                account = account,
                state = SharingChoiceState.ENROLLED,
                epoch = epoch,
                acceptedDisclosureVersion = acceptedDisclosureVersion,
            ),
        )
        if (!written) persistFailedAccounts.add(account) else persistFailedAccounts.remove(account)
        lastFailureReason = if (written) null else persistence.lastFailureReason()
        return written
    }

    /**
     * WHY the last write failed, for the latch log (owner Pixel test): a latch that only says
     * "failed" cannot be diagnosed from a device. Set on every failed record write and cleared by
     * the next successful one. Observability only - it never feeds a consent decision.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var lastFailureReason: String? = null
        private set

    /**
     * Record a server answer of NOT ENROLLED. Nothing the user decided at a surface produced
     * this, but it IS the account's actual current state: an old ENROLLED record left on disk
     * would admit captures the server has already closed (@spec 3f1e939a). No epoch or disclosure
     * is fabricated — the record simply fails every consent read.
     */
    fun recordNotEnrolled(account: String): Boolean {
        if (account.isBlank()) {
            lastFailureReason = "blank account"
            return false
        }
        val written = persistence.write(
            SavedSharingChoice(
                account = account,
                state = SharingChoiceState.NOT_ENROLLED,
                epoch = null,
                acceptedDisclosureVersion = null,
            ),
        )
        if (!written) persistFailedAccounts.add(account) else persistFailedAccounts.remove(account)
        lastFailureReason = if (written) null else persistence.lastFailureReason()
        return written
    }

    /**
     * Record an EXPLICIT stop. The epoch is RETAINED: a retired epoch is a server-refusal matter
     * under ordinary record construction, not grounds for this client to erase provenance. Only the
     * consent state changes, which is the single fact the user actually decided.
     */
    fun recordWithdrawn(account: String): Boolean {
        if (account.isBlank()) {
            lastFailureReason = "blank account"
            return false
        }
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
        if (!written) persistFailedAccounts.add(account) else persistFailedAccounts.remove(account)
        lastFailureReason = if (written) null else persistence.lastFailureReason()
        return written
    }

    /**
     * The choice for [account], or null unless this account is enrolled with a real epoch and an
     * accepted disclosure. This is the ONE question admission asks.
     */
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

    fun enrolledChoiceFor(account: String?): SavedSharingChoice? {
        if (account.isNullOrBlank()) return null
        // A persist that failed for THIS account leaves the file's meaning unknown relative to the
        // user's latest instruction for it: refuse until a write for the account succeeds.
        if (account in persistFailedAccounts) return null
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

    /** Returns TRUE only when the record is durably in place. */
    fun write(choice: SavedSharingChoice): Boolean

    /** Why the most recent [write] on this instance failed, or null after a success. Diagnostics. */
    fun lastFailureReason(): String? = null
}

/**
 * File-backed record with the same atomic-replace discipline the withdrawal marker already uses:
 * write the whole record to a temp file, flush and fsync it, rename OVER the target (POSIX rename is
 * atomic, so a reader sees the old or the new record and never a partial or absent one), then fsync
 * the containing directory so the rename itself survives power loss.
 *
 * Format is one line, tab-separated, deliberately tiny. Field order is fixed:
 * `account \t state \t epoch \t disclosure`. Blank/absent values are written as `-`.
 *
 * FAILS CLOSED. Anything unparseable, truncated, or unrecognised reads as null - never as consent.
 */
class FileSharingChoicePersistence(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val directorySync: DirectorySync = DirectorySync.PRODUCTION,
) : SharingChoicePersistence {
    /**
     * ONE FILE PER ACCOUNT (@spec 2e87df12, finding 3). A single global record made account A's
     * consent depend on whatever account B did last: switching A→B→A would leave A needing another
     * server roundtrip before ordinary collection worked again. With per-account files, A's record
     * is untouched while B is live, and A returns to exactly the choice A itself made.
     */
    private fun fileFor(account: String): File = File(context.filesDir, "voice-sharing-choice-" + sha256(account))

    /**
     * A UNIQUE temp path per write (@owner Pixel test, nine `persist: failed latched` lines during
     * fast taps). The previous fixed `<record>.tmp` was shared by every concurrent writer: writer B
     * truncated A's in-flight temp, so A renamed a partial record or failed with ENOENT after B had
     * already renamed it away. Each write now owns its temp file, so overlapping writes each publish
     * one whole record and the last rename wins.
     */
    private fun tempFileFor(account: String): File = File(
        context.filesDir,
        "voice-sharing-choice-" + sha256(account) + ".tmp." + java.util.UUID.randomUUID(),
    )

    private fun sha256(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    override fun read(account: String): SavedSharingChoice? {
        val file = fileFor(account)
        if (!file.exists()) return null
        return try {
            decode(file.readText(Charsets.UTF_8))?.takeIf { it.account == account }
        } catch (e: Exception) {
            // Cannot read our own record: report "nothing saved" and let admission refuse.
            null
        }
    }

    @Volatile
    private var failureReason: String? = null

    override fun lastFailureReason(): String? = failureReason

    private fun failed(reason: String): Boolean {
        failureReason = reason
        return false
    }

    override fun write(choice: SavedSharingChoice): Boolean {
        val file = fileFor(choice.account)
        val tempFile = tempFileFor(choice.account)
        val dir = file.parentFile ?: return failed("no parent directory for ${file.name}")
        return try {
            dir.mkdirs()
            FileOutputStream(tempFile).use { out ->
                out.write(encode(choice).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tempFile.renameTo(file)) {
                tempFile.delete()
                // The rename failed, so the record on disk is whatever it was before this call.
                failed("rename failed: ${tempFile.name} -> ${file.name}")
            } else {
                // The rename is only durable relative to its directory entry. A sync FAILURE throws
                // and is reported as a failed write, never swallowed as success.
                directorySync.sync(dir)
                failureReason = null
                true
            }
        } catch (e: Exception) {
            tempFile.delete()
            failed("exception ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun encode(choice: SavedSharingChoice): String = listOf(
        choice.account,
        choice.state.name,
        choice.epoch ?: ABSENT,
        choice.acceptedDisclosureVersion ?: ABSENT,
    ).joinToString(SEPARATOR) + "\n"

    private fun decode(text: String): SavedSharingChoice? {
        val fields = text.trim().split(SEPARATOR)
        if (fields.size != 4) return null
        val account = fields[0].takeIf { it.isNotBlank() && it != ABSENT } ?: return null
        val state = SharingChoiceState.entries.firstOrNull { it.name == fields[1] } ?: return null
        return SavedSharingChoice(
            account = account,
            state = state,
            epoch = fields[2].takeIf { it != ABSENT },
            acceptedDisclosureVersion = fields[3].takeIf { it != ABSENT },
        )
    }

    private companion object {
        const val SEPARATOR = "\t"
        const val ABSENT = "-"
    }
}
