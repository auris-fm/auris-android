package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import org.json.JSONObject

/** Whether a queued recording may be sent right now. */
sealed interface GateVerdict {
    data object Send : GateVerdict

    /** The recording must be deleted, and [reason] says why in the log. */
    data class Refuse(val reason: String) : GateVerdict
}

/**
 * Decides whether a recording is still worth sending.
 *
 * A queued recording is a claim about the past: it was captured while signed in to some account,
 * with sharing on, under some enrollment epoch. Every one of those can change before the queue
 * drains, and sending a recording whose premise no longer holds would either upload data the user
 * withdrew or upload it to an account it does not belong to. So the facts are re-read here, on
 * every pass, instead of being trusted from publish time.
 */
fun interface PreSendGate {
    fun verdictFor(recording: FolderQueue.Recording): GateVerdict

    companion object {
        /** Sends everything. For a loop built without an account, as in a control. */
        val OPEN = PreSendGate { GateVerdict.Send }
    }
}

/**
 * The facts this gate needs, as functions so each is read fresh rather than snapshot once.
 *
 * [currentAccount] and [currentEpoch] are read at decision time because both can change under a
 * queue that already has entries.
 */
class AccountPreSendGate(
    private val currentAccount: () -> String?,
    private val currentEpoch: () -> String?,
    private val sharingEnabled: () -> Boolean,
    private val maxAgeMs: Long = FolderQueue.MAX_AGE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : PreSendGate {
    override fun verdictFor(recording: FolderQueue.Recording): GateVerdict {
        // The user's current answer outranks anything queued. Off means off, now, for everything
        // already waiting - waiting cannot change it back.
        if (!sharingEnabled()) return GateVerdict.Refuse("recording sharing is off")

        val account = currentAccount()
            ?: return GateVerdict.Refuse("nobody is signed in")

        if (now() - recording.publishedAtMs > maxAgeMs) {
            return GateVerdict.Refuse("older than the retention limit")
        }

        // Unreadable before send is the same fact as unreadable after: nothing to send.
        val stamped = runCatching { JSONObject(recording.metadata.readText()) }.getOrNull()
            ?: return GateVerdict.Refuse("its record could not be read")

        val epoch = stamped.optString("enrollment_epoch", "")
        if (epoch.isBlank()) return GateVerdict.Refuse("its record has no enrollment epoch")

        // A recording made under a previous enrollment is not this enrollment's data. The server
        // would answer 410 anyway; deleting here keeps it from being uploaded at all.
        val live = currentEpoch()
        if (live.isNullOrBlank() || epoch != live) {
            return GateVerdict.Refuse("its enrollment epoch is no longer current")
        }

        // Ownership. The account is carried in the record so a cross-account queue is detectable
        // rather than silently uploaded to whoever is signed in now.
        val owner = stamped.optString("account", "")
        if (owner.isNotBlank() && owner != account) {
            return GateVerdict.Refuse("it belongs to another account")
        }

        if (!recording.audio.isFile || recording.audio.length() <= 0L) {
            return GateVerdict.Refuse("its audio could not be read")
        }

        return GateVerdict.Send
    }
}
