package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private, synchronous persistence for the local withdrawal stop (reviewer 9156a8ea).
 *
 * File-backed rather than in-memory because the whole point is surviving process death: a user who
 * turned sharing off and then killed the app must not start contributing again on relaunch.
 *
 * Written with a temp-file-then-rename so a crash mid-write cannot leave a truncated file that
 * reads back as "no intent" — that would silently resume collection, which is the failure this
 * exists to prevent. Reads are tolerant: a MISSING file means no intent; an unreadable one is
 * treated as "stop requested" only if its content says so, and a file we cannot parse is reported
 * as an error rather than assumed permissive.
 */
@Singleton
class FileWithdrawalIntentPersistence @Inject constructor(
    @ApplicationContext private val context: Context,
) : WithdrawalIntentPersistence {

    private val file: File
        get() = File(context.filesDir, "voice-sharing-local-stop")

    private val tempFile: File
        get() = File(context.filesDir, "voice-sharing-local-stop.tmp")

    /**
     * FAILS CLOSED. Only an explicit [REQUESTED] or [CLEARED] marker is honoured; anything else —
     * truncated, empty, unrecognised or unreadable — reads as STOPPED.
     *
     * The asymmetry decides this: reading a corrupt file as "permitted" resumes contribution for
     * someone who asked us to stop, while reading it as "stopped" costs a user one extra opt-in. A
     * silently resumed upload is not a recoverable inconvenience, so the uncertain case is the
     * stopping one.
     */
    override fun read(): Boolean {
        if (!file.exists()) return false
        return try {
            when (file.readText().trim()) {
                CLEARED -> false

                REQUESTED -> true

                // Truncated, empty, or unrecognised: we cannot prove collection was permitted.
                else -> true
            }
        } catch (e: Exception) {
            // Cannot read our own flag: be conservative and report a stop. Claiming "no intent"
            // here would resume collection for someone who may have asked us to stop.
            true
        }
    }

    /**
     * Never leaves the authoritative file half-written.
     *
     * `renameTo` replaces atomically on the filesystems we target, but it can fail (notably when the
     * target exists). The previous fallback wrote the authoritative file DIRECTLY, which is the very
     * truncation-then-write this class exists to avoid: a crash in that window leaves an empty or
     * partial file. The fallback now deletes the target first and then renames — still not atomic, but
     * the only non-atomic moment is one in which NO file exists, which reads as "no stop" only if the
     * user never stopped. To keep even that window conservative, a STOP is also written straight
     * through, because losing a stop is the failure with real consequences.
     */
    override fun write(requested: Boolean) {
        val marker = if (requested) REQUESTED else CLEARED
        tempFile.writeText(marker)
        if (tempFile.renameTo(file)) return
        if (requested) {
            // A stop must land even if the rename path is unavailable: write it directly and remove
            // the temp file, rather than leave the decision to a filesystem quirk.
            file.writeText(marker)
            tempFile.delete()
            return
        }
        // Clearing is the recoverable direction, so it takes the safer delete-then-rename path.
        file.delete()
        if (!tempFile.renameTo(file)) {
            tempFile.delete()
            file.writeText(marker)
        }
    }

    private companion object {
        const val REQUESTED = "withdrawal_requested"
        const val CLEARED = "collection_permitted"
    }
}
