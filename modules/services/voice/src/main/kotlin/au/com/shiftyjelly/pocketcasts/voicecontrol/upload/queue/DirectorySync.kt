package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Makes a DIRECTORY ENTRY durable — not the file contents, the name.
 *
 * `FileDescriptor.sync()` on a file flushes its bytes; it does nothing for the directory that names
 * it, so after a crash a file can be fully written and still have no entry. On this codebase that
 * would mean `prepare()` returning a durable entry whose ready marker vanished, which is precisely
 * what the marker exists to prevent. The distinction matters because the ready marker is the ONLY
 * readiness authority: claiming it is durable when it might not be is worse than not claiming it.
 *
 * VERIFIED ON DEVICE, not assumed: `FileChannel.force(true)` on a directory succeeds on Android SDK
 * 37 (Pixel 8). `FileInputStream(dir).fd.sync()` throws EISDIR there, so the FileChannel form is the
 * one implemented rather than the more commonly suggested one.
 *
 * A sync FAILURE is thrown, never swallowed. Callers treat it as a failed preparation: the entry is
 * not durable, so it must not be reported as durable.
 */
fun interface DirectorySync {
    @Throws(IOException::class)
    fun sync(directory: File)

    companion object {
        /** Production implementation: a real directory fsync. */
        val REAL: DirectorySync = DirectorySync { directory ->
            if (!directory.isDirectory) {
                throw IOException("cannot sync a non-directory: ${directory.name}")
            }
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

/**
 * Makes a FILE's contents durable — the counterpart to [DirectorySync]'s name durability.
 *
 * Both are needed and neither substitutes for the other: `force` on a directory persists the entry
 * that names a file, while `force` on the file persists what that file contains. Writing a sidecar
 * with `writeText` and then syncing only the directory makes its NAME durable while its CONTENTS may
 * still be lost, which for `created.at` and `attempts` means losing age and attempt authority.
 */
fun interface FileSync {
    @Throws(IOException::class)
    fun sync(file: File)

    companion object {
        val REAL: FileSync = FileSync { file ->
            java.io.FileOutputStream(file, true).use { it.fd.sync() }
        }
    }
}

/**
 * The ready transition MAY have occurred, but its final synchronization could not be confirmed.
 *
 * This is a third outcome, not a failure. The spec (voice-recording-uploads, `ecbe84dd`) requires it:
 * a failure known to precede the ready transition is uncommitted, but once the marker may exist the
 * store cannot claim either success or definite rejection. Collapsing this into a thrown error is the
 * mistake the spec names explicitly, because a generic error invites a caller to delete files or mint
 * a new capture id for an entry that a restart may find intact.
 *
 * Callers must treat this as "unknown, reconcile later under the serialized owner" — never as
 * "not published".
 */
class PublicationUncertain(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
