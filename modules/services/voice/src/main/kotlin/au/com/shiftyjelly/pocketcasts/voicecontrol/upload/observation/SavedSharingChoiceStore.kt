package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.DirectorySync
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
    /** The stored choice, or null when nothing has ever been saved or the file is unusable. */
    fun read(): SavedSharingChoice? = persistence.read()

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
        return persistence.write(
            SavedSharingChoice(
                account = account,
                state = SharingChoiceState.ENROLLED,
                epoch = epoch,
                acceptedDisclosureVersion = acceptedDisclosureVersion,
            ),
        )
    }

    /**
     * Record an EXPLICIT stop. The epoch is RETAINED: a retired epoch is a server-refusal matter
     * under ordinary record construction, not grounds for this client to erase provenance. Only the
     * consent state changes, which is the single fact the user actually decided.
     */
    fun recordWithdrawn(account: String): Boolean {
        if (account.isBlank()) return false
        val previous = persistence.read()
        return persistence.write(
            SavedSharingChoice(
                account = account,
                state = SharingChoiceState.WITHDRAWN,
                // Carry the epoch over only when it belongs to the SAME account; never migrate one
                // account's provenance onto another.
                epoch = previous?.takeIf { it.account == account }?.epoch,
                acceptedDisclosureVersion = previous?.takeIf { it.account == account }?.acceptedDisclosureVersion,
            ),
        )
    }

    /**
     * The choice for [account], or null unless this account is enrolled with a real epoch and an
     * accepted disclosure. This is the ONE question admission asks.
     */
    fun enrolledChoiceFor(account: String?): SavedSharingChoice? {
        if (account.isNullOrBlank()) return null
        val choice = persistence.read() ?: return null
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

enum class SharingChoiceState { ENROLLED, WITHDRAWN }

/** Durable single-record storage for [SavedSharingChoice]. */
interface SharingChoicePersistence {
    fun read(): SavedSharingChoice?

    /** Returns TRUE only when the record is durably in place. */
    fun write(choice: SavedSharingChoice): Boolean
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
    private val file: File
        get() = File(context.filesDir, "voice-sharing-choice")

    private val tempFile: File
        get() = File(context.filesDir, "voice-sharing-choice.tmp")

    override fun read(): SavedSharingChoice? {
        if (!file.exists()) return null
        return try {
            decode(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // Cannot read our own record: report "nothing saved" and let admission refuse.
            null
        }
    }

    override fun write(choice: SavedSharingChoice): Boolean {
        val dir = file.parentFile ?: return false
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
                false
            } else {
                // The rename is only durable relative to its directory entry. A sync FAILURE throws
                // and is reported as a failed write, never swallowed as success.
                directorySync.sync(dir)
                true
            }
        } catch (e: Exception) {
            tempFile.delete()
            false
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
