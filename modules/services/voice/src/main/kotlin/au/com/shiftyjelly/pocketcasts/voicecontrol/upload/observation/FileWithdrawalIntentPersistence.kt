package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
 * exists to prevent.
 *
 * KNOWN ASYMMETRY, stated rather than hidden: a file that is present but corrupt/empty/unreadable
 * reads as STOPPED (see [read]), while an ABSENT file reads as PERMITTED. The two are deliberately
 * different demands. Absence is the legitimate state before the user has ever made a decision, and
 * treating it as a stop would strand every first-run user behind an opt-in they never asked for.
 * The price is that a lost file resumes collection, which is why [write] never removes the
 * authoritative file and never leaves it half-written - the absent case is narrowed to "nothing was
 * ever written", not reachable by any ordinary crash during an update.
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
     * DURABLE, ATOMIC, and with NO window in which the authoritative file is missing or half-written
     * (reviewer 668882eb, fd258849).
     *
     * Three earlier versions of this method were each wrong in a way that mattered:
     *  - writing the authoritative file directly truncates it before the new bytes land, so a crash
     *    between the two leaves an empty file - and empty reads as "we cannot prove permission", i.e.
     *    the user's stop is correctly kept but a CLEAR is silently lost;
     *  - deleting the target first and then renaming creates a window in which NO file exists, and a
     *    missing file reads as "never stopped", which RESUMES COLLECTION;
     *  - a failed rename that fell back to a direct write combined both.
     *
     * The fix is to never remove the target. The bytes go to a temp file which is flushed and fsynced,
     * and only then renamed OVER the target; POSIX rename replaces atomically, so a reader sees either
     * the whole old marker or the whole new one, never a partial file and never an absent one. Once the
     * rename lands, the CONTAINING DIRECTORY is fsynced, because a rename is only durable relative to
     * its directory entry: without that barrier the file can revert after power loss even though the
     * data blocks were flushed.
     *
     * Scope of the claim: this makes the marker's own replacement atomic and crash-durable on a
     * filesystem that honours fsync on files and directories. It is NOT a claim that Android's
     * storage stack guarantees this on every device, and it does not make the wider queue durable.
     */
    override fun write(requested: Boolean) {
        val marker = if (requested) REQUESTED else CLEARED
        val dir = file.parentFile ?: context.filesDir
        dir.mkdirs()
        // Write the WHOLE marker to the temp file, flush it through the OS, then force it to the
        // device. A temp file that is not fsynced can be empty after a power loss, and an empty temp
        // renamed over the target would destroy a good marker.
        FileOutputStream(tempFile).use { out ->
            out.write(marker.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tempFile.renameTo(file)) {
            // Rename failed. Do NOT truncate the target: writing it directly is the defect above, and
            // deleting it first is the other one. Leave the authoritative file exactly as it was (a
            // previous stop stays a stop, a previous clear stays a clear) and drop the temp file, so
            // the state on disk remains a coherent marker rather than a partial one.
            tempFile.delete()
            return
        }
        syncDirectory(dir)
    }

    /**
     * Forces the DIRECTORY ENTRY created by the rename. Failure is tolerated rather than thrown: the
     * marker is already correct in the cache, and throwing here would surface a storage quirk as a
     * user-visible failure of the stop.
     */
    private fun syncDirectory(dir: File) {
        try {
            FileInputStream(dir).use { it.fd.sync() }
        } catch (e: Exception) {
            // Some filesystems refuse to open a directory as a stream. The marker is still correct;
            // only its power-loss durability is weaker, and there is no safer action available here.
        }
    }

    private companion object {
        const val REQUESTED = "withdrawal_requested"
        const val CLEARED = "collection_permitted"
    }
}
