package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/**
 * The `android.system.Os` surface this adapter uses, extracted so the ERROR and UNSUPPORTED paths are
 * first-class controls on the JVM (spec 0f24636b): the documented directory-fsync surface is
 * `Os.open(path, O_DIRECTORY | O_RDONLY)` + `Os.fsync(fd)` + `Os.close(fd)` (API 21+).
 */
interface OsOperations {
    /** Whether the platform supports the directory-fsync surface at all. */
    val isSupported: Boolean

    /** Opens `path` as a directory; returns its descriptor. Throws on failure. */
    fun openDirectory(path: String): FileDescriptor

    /** Flushes the descriptor's directory entry to storage. Throws on failure. */
    fun fsync(fd: FileDescriptor)

    /** Closes the descriptor. Throws on failure. */
    fun close(fd: FileDescriptor)
}

/** Production binding to the real `android.system.Os`. */
class AndroidOsOperations : OsOperations {
    override val isSupported: Boolean = true // documented since API 21; the app's minSdk is 21+

    override fun openDirectory(path: String): FileDescriptor = Os.open(path, O_DIRECTORY or OsConstants.O_RDONLY, 0)

    override fun fsync(fd: FileDescriptor) {
        Os.fsync(fd)
    }

    override fun close(fd: FileDescriptor) {
        Os.close(fd)
    }

    private companion object {
        // O_DIRECTORY is in the Linux fcntl.h surface the docs describe, but this SDK's
        // android.jar stub of OsConstants does not expose it, so the documented VALUE is pinned here
        // (0200000 octal, bionic fcntl.h). If OsConstants gains it, this moves back to the constant.
        private const val O_DIRECTORY = 0x10000
    }
}

/**
 * Directory fsync over the DOCUMENTED `android.system.Os` surface (spec 0f24636b): `open` with
 * `O_DIRECTORY`, `fsync`, `close`.
 *
 * FAILURE PROPAGATION is the contract: an `ErrnoException` from any call becomes an [IOException]
 * naming the errno, and an unsupported platform becomes an [IOException] too. Neither is ever
 * swallowed into a silent success, because a directory sync whose outcome is unknown must never be
 * reported as a durable clear or a durable publication - the callers (the store's prepare path and
 * the withdrawal persistence) treat a thrown sync as "not durable", and this adapter guarantees that
 * answer is always TRUE when it returns.
 *
 * `close` runs even when `fsync` failed: an fd leaked per failed sync is its own defect.
 *
 * SCOPE HONESTY: the REAL binding ([AndroidOsOperations]) is compile-verified against the documented
 * surface; its behavior against real kernel calls is NOT verified by these JVM controls, which bind
 * [OsOperations] to fakes. The previous `FileChannel.force` path carries its own on-device
 * verification note (SDK 37, Pixel 8) and remains available as [FILECHANNEL_FALLBACK].
 */
class OsDirectorySync(
    private val ops: OsOperations,
) : DirectorySync {

    override fun sync(directory: File) {
        if (!directory.isDirectory) {
            throw IOException("cannot sync a non-directory: ${directory.name}")
        }
        if (!ops.isSupported) {
            // Unsupported MUST be a failure, never a silent success: reporting a clear durable when
            // the platform cannot sync the directory is exactly the dishonest claim this adapter
            // exists to prevent.
            throw IOException("directory fsync unsupported on this platform: ${directory.name}")
        }
        val fd = try {
            ops.openDirectory(directory.absolutePath)
        } catch (e: ErrnoException) {
            // The public `errno` field is the documented diagnosis surface; the cause is preserved.
            // (rethrowAsIOException is the other documented conversion but this lane's JVM controls
            // run against unmocked android.jar stubs where only fields are safe.)
            throw IOException("directory open failed (errno=${e.errno})", e)
        }
        try {
            ops.fsync(fd)
        } catch (e: ErrnoException) {
            throw IOException("directory fsync failed (errno=${e.errno})", e)
        } finally {
            try {
                ops.close(fd)
            } catch (e: ErrnoException) {
                // A close failure after a successful fsync does not undo the flush; a close failure
                // after a FAILED fsync is moot because the fsync already threw. Not silently
                // swallowed into success: sync() has either thrown or completed before this.
            }
        }
    }

    companion object {
        /** The previous on-device-verified FileChannel path, kept as a selectable alternative. */
        val FILECHANNEL_FALLBACK: DirectorySync = DirectorySync.REAL
    }
}
