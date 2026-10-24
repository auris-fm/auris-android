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
    // Supported ONLY when the running ABI's O_DIRECTORY value is known: an unknown ABI must fail,
    // never open with a guessed flag (a wrong flag is O_DIRECT, not O_DIRECTORY, and the open fails
    // or - worse - is simply not a directory handle).
    override val isSupported: Boolean = directoryOpenFlags(android.os.Build.SUPPORTED_ABIS.toList()) != null

    override fun openDirectory(path: String): FileDescriptor = Os.open(path, directoryOpenFlags(android.os.Build.SUPPORTED_ABIS.toList())!! or OsConstants.O_RDONLY, 0)

    override fun fsync(fd: FileDescriptor) {
        Os.fsync(fd)
    }

    override fun close(fd: FileDescriptor) {
        Os.close(fd)
    }

    internal companion object {
        // O_DIRECTORY is absent from this SDK's android.jar stub of OsConstants AND its value is
        // architecture-dependent in bionic's kernel uapi headers, so the value is selected per
        // RUNNING ABI (reviewer 87e2e450, second round):
        //   - asm-arm / asm-arm64 fcntl.h: O_DIRECTORY = 040000 octal = 0x4000 (O_DIRECT = 0x10000)
        //   - asm-generic fcntl.h (x86, x86_64): O_DIRECTORY = 0200000 octal = 0x10000 (O_DIRECT = 0x4000)
        // A universal literal is wrong on one family whichever value it picks - the first pin
        // (0x10000) broke ARM, a flat 0x4000 breaks x86_64, which voice/build.gradle.kts also
        // builds. An UNKNOWN ABI returns null, which makes the platform report unsupported and
        // fail loudly instead of opening with a guessed flag. Internal so the JVM controls can pin
        // BOTH architectures; the static Os.open call itself cannot be intercepted on the JVM.
        internal const val O_DIRECTORY_ARM = 0x4000
        internal const val O_DIRECTORY_X86 = 0x10000

        internal fun directoryOpenFlags(abis: List<String>): Int? = when {
            abis.any { it.startsWith("arm") || it.startsWith("aarch64") } -> O_DIRECTORY_ARM
            abis.any { it.startsWith("x86") } -> O_DIRECTORY_X86
            else -> null
        }
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
