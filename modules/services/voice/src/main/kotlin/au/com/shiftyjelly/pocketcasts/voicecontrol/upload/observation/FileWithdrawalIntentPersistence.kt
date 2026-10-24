package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.DirectorySync
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
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
 * reads as STOPPED (see [read]), while an ABSENT file reads as PERMITTED. A missing marker therefore
 * does NOT stop collection - the [LocalWithdrawalIntentStore] failure latch covers the case where a
 * stop could not be written at all, since that is the only way absence can appear after a stop. The two are deliberately
 * different demands. Absence is the legitimate state before the user has ever made a decision, and
 * treating it as a stop would strand every first-run user behind an opt-in they never asked for.
 * The price is that a lost file resumes collection, which is why [write] never removes the
 * authoritative file and never leaves it half-written - the absent case is narrowed to "nothing was
 * ever written", not reachable by any ordinary crash during an update.
 */
@Singleton
class FileWithdrawalIntentPersistence constructor(
    @ApplicationContext private val context: Context,
    /**
     * The DIRECTORY BARRIER for the marker's entry. Injected so tests can refuse it, and so the
     * production binding is the SAME documented `android.system.Os` adapter the queue uses.
     *
     * This replaced `FileInputStream(dir).fd.sync()`, which is not a supported directory barrier on
     * Android (reviewer 60647af3) - the file descriptor was opened on a DIRECTORY through the FILE
     * api, and whether that fsyncs the entry is unspecified. No unsupported-runtime success fallback:
     * a failure here reports false, and the store keeps the admission latch set.
     */
    directorySyncOverride: DirectorySync? = null,
) : WithdrawalIntentPersistence {

    /**
     * The barrier actually used. The DEFAULT is the JVM-runnable FileChannel barrier so Robolectric
     * tests construct this directly without touching android.system.Os; production binds the
     * android.system.Os adapter at the Hilt boundary (VoiceObservationModule), per spec 64ce2de6.
     */
    private val directorySync: DirectorySync = directorySyncOverride ?: DirectorySync.REAL

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
    override fun write(requested: Boolean): Boolean {
        val marker = if (requested) REQUESTED else CLEARED
        val dir = file.parentFile ?: context.filesDir
        try {
            dir.mkdirs()
            // Write the WHOLE marker to the temp file, flush it through the OS, then force it to the
            // device. A temp file that is not fsynced can be empty after a power loss, and an empty
            // temp renamed over the target would destroy a good marker.
            FileOutputStream(tempFile).use { out ->
                out.write(marker.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tempFile.renameTo(file)) {
                // THE RENAME FAILED, so the marker on disk is whatever it was BEFORE this call.
                // Discarding that outcome is how a first withdrawal could silently keep the old
                // PERMITTED marker and resume collection on restart (reviewer 60647af3). Report it.
                tempFile.delete()
                // Still fail closed (clear a possibly-permitted marker), but REPORT the failure: the
                // marker did not take the atomic path, so its durability is not what was promised.
                failClosedInPlace(file)
                return false
            }
        } catch (e: Exception) {
            try {
                tempFile.delete()
            } catch (ignored: Exception) {
                // Nothing further to do; the return below is what matters.
            }
            failClosedInPlace(file)
            return false
        }
        // The rename landed. The directory entry is what makes it durable, so force that too; if the
        // barrier fails the marker is present but its power-loss durability is unproven, which must
        // not be reported as a durable stop.
        return syncDirectory(dir)
    }

    /**
     * Last resort when the atomic path did not complete and the caller asked for a STOP.
     *
     * Leaving the previous PERMITTED marker in place would resume collection for someone who asked us
     * to stop, which is the failure this whole class exists to prevent. Truncating the file is not
     * atomic, but for a STOP the possible outcomes are "no marker" and "REQUESTED" - both of which
     * keep collection closed the next time this file is read.
     */
    private fun failClosedInPlace(target: File): Boolean = try {
        // ALWAYS writes the STOP marker, never the one the caller asked for: this path only runs
        // because the write already failed, and the only unknown that matters is whether the user is
        // stopped. Writing CLEARED here would mean a failed clear and a failed stop both leave
        // collection open, which is the direction with real consequences.
        target.writeText(REQUESTED)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Forces the DIRECTORY ENTRY created by the rename.
     *
     * A rename is durable only relative to its parent directory. The barrier is [DirectorySync],
     * which in production is the documented `android.system.Os` open(O_DIRECTORY)/fsync/close surface.
     * A thrown sync (errno OR unsupported platform) is reported as false - never as a durable clear -
     * and the caller decides what that means; for a failed CLEAR the admission latch stays set.
     */
    private fun syncDirectory(dir: File): Boolean = try {
        directorySync.sync(dir)
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val REQUESTED = "withdrawal_requested"
        const val CLEARED = "collection_permitted"
    }
}
