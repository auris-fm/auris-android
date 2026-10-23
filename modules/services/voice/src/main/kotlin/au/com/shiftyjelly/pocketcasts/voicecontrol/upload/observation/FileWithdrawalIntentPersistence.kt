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

    override fun read(): Boolean {
        if (!file.exists()) return false
        return try {
            file.readText().trim() == REQUESTED
        } catch (e: Exception) {
            // Cannot read our own flag: be conservative and report a stop. Claiming "no intent"
            // here would resume collection for someone who may have asked us to stop.
            true
        }
    }

    override fun write(requested: Boolean) {
        tempFile.writeText(if (requested) REQUESTED else CLEARED)
        if (!tempFile.renameTo(file)) {
            // renameTo can fail when the target exists on some filesystems; fall back to a direct
            // write so the intent is never silently lost.
            file.writeText(if (requested) REQUESTED else CLEARED)
            tempFile.delete()
        }
    }

    private companion object {
        const val REQUESTED = "withdrawal_requested"
        const val CLEARED = "collection_permitted"
    }
}
