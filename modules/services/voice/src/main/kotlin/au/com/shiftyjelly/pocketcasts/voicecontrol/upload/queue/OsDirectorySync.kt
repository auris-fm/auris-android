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

    override fun openDirectory(path: String): FileDescriptor = Os.open(path, DIRECTORY_OPEN_FLAGS, 0)

    override fun fsync(fd: FileDescriptor) {
        Os.fsync(fd)
    }

    override fun close(fd: FileDescriptor) {
        Os.close(fd)
    }

    internal companion object {
        // O_DIRECTORY is absent from this SDK's android.jar stub of OsConstants, so the VALUE is
        // pinned from bionic's per-architecture kernel uapi headers (asm-arm and asm-arm64
        // fcntl.h): O_DIRECTORY is 040000 octal = 0x4000, O_RDONLY is 0. The earlier 0x10000 pin
        // was O_DIRECT on ARM/ARM64, which would make every directory open fail on the target
        // platform (reviewer 87e2e450). If OsConstants gains the constant, this moves back to it.
        // Internal so the JVM controls can pin the value the production binding opens with; the
        // static Os.open call itself cannot be intercepted on the JVM.
        internal const val O_DIRECTORY = 0x4000
        internal val DIRECTORY_OPEN_FLAGS: Int = O_DIRECTORY or OsConstants.O_RDONLY
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
 * `close` runs even when `fsync` failed: an fd leaked per failed sync is its own defect. A close
 * failure is itself propagated: close-only failure throws; fsync+close failure throws the ORIGINAL
 * fsync error with the close failure suppressed.
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
        // close ALWAYS runs, and its failure is a REAL failure, not a detail: the declared contract
        // is "any call may fail loudly", so a close-only failure propagates, and when fsync AND
        // close both fail the ORIGINAL fsync error is thrown with the close failure attached as a
        // suppressed exception (reviewer 87e2e450 - the previous finally swallowed close entirely).
        var fsyncError: IOException? = null
        try {
            ops.fsync(fd)
        } catch (e: ErrnoException) {
            fsyncError = IOException("directory fsync failed (errno=${e.errno})", e)
        }
        try {
            ops.close(fd)
        } catch (e: ErrnoException) {
            val closeError = IOException("directory close failed (errno=${e.errno})", e)
            val original = fsyncError
            if (original != null) {
                original.addSuppressed(closeError)
                throw original
            }
            throw closeError
        }
        fsyncError?.let { throw it }
    }

    companion object {
        /** The previous on-device-verified FileChannel path, kept as a selectable alternative. */
        val FILECHANNEL_FALLBACK: DirectorySync = DirectorySync.REAL
    }
}
