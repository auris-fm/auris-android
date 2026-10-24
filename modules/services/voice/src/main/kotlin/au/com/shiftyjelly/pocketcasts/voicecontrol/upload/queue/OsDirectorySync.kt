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
class AndroidOsOperations(
    // The O_DIRECTORY AUTHORITY (reviewer 87e2e450, round 4; spec 220cdb5f): the platform's OWN
    // OsConstants.O_DIRECTORY when the running android exposes it, else the value COMPILED INTO
    // the native helper library for the running ABI (DirectoryFlagJni.cpp reads <fcntl.h> from the
    // NDK, so it is arch-correct by construction). There is deliberately NO device-capability
    // inference: Build.SUPPORTED_ABIS describes what a device CAN run, not the flag this process's
    // syscalls expect, and a wrong guess is O_DIRECT - the open simply fails or is not a directory
    // handle. Null (neither authority resolvable) makes the platform report UNSUPPORTED and fail
    // loudly; there is no unsupported-runtime success fallback.
    private val oDirectoryAuthority: () -> Int? = DEFAULT_O_DIRECTORY_AUTHORITY,
) : OsOperations {
    // Resolved ONCE (reviewer dffa4872): isSupported and openDirectory must reason about the SAME
    // constant, and the production authorities are stable - a per-call resolution would let the two
    // reads disagree. The injected authority is still the seam tests use.
    private val resolvedFlag: Int? by lazy { oDirectoryAuthority() }

    override val isSupported: Boolean = resolvedFlag != null

    override fun openDirectory(path: String): FileDescriptor = Os.open(path, resolvedFlag!! or OsConstants.O_RDONLY, 0)

    override fun fsync(fd: FileDescriptor) {
        Os.fsync(fd)
    }

    override fun close(fd: FileDescriptor) {
        Os.close(fd)
    }

    internal companion object {
        // Order: the platform's own constant would outrank the compiled one (same surface Os.open
        // documents) - but as of NDK/platform 34 and 37, NO android.jar platform exposes the field
        // (javap-verified), so on every shipping device this tier is inert and the flag in
        // production rests SOLELY on the compiled native authority from the .so. The reflection
        // tier is kept for forward compatibility (a future platform that adds the field becomes
        // authority automatically). Missing field OR missing native library => null = unsupported.
        internal val DEFAULT_O_DIRECTORY_AUTHORITY: () -> Int? = {
            runCatching { OsConstants::class.java.getField("O_DIRECTORY").getInt(null) }.getOrNull()
                ?: DirectoryFlagJni.oDirectoryOrNull()
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
        // ANY fsync throwable (not only ErrnoException - the OsOperations seam is documented for
        // arbitrary injected implementations) still runs close: an fd leaked per failed sync is its
        // own defect (claude review finding, restoring the guarantee the old finally-shaped code
        // had for this case).
        var fsyncError: IOException? = null
        var fsyncUnexpected: Throwable? = null
        try {
            ops.fsync(fd)
        } catch (e: ErrnoException) {
            fsyncError = IOException("directory fsync failed (errno=${e.errno})", e)
        } catch (t: Throwable) {
            fsyncUnexpected = t
        }
        try {
            ops.close(fd)
        } catch (e: ErrnoException) {
            val closeError = IOException("directory close failed (errno=${e.errno})", e)
            val original = fsyncError
            if (original != null) {
                original.addSuppressed(closeError)
            } else if (fsyncUnexpected != null) {
                fsyncUnexpected.addSuppressed(closeError)
            } else {
                throw closeError
            }
        }
        fsyncUnexpected?.let { throw it }
        fsyncError?.let { throw it }
    }

    companion object {
        /** The previous on-device-verified FileChannel path, kept as a selectable alternative. */
        val FILECHANNEL_FALLBACK: DirectorySync = DirectorySync.REAL
    }
}
