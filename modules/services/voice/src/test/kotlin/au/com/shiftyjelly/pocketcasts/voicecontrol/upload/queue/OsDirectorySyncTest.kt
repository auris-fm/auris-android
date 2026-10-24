package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.system.ErrnoException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * OsDirectorySync — the documented `android.system.Os` directory-fsync surface (spec 0f24636b).
 *
 * The controls bind [OsOperations] to fakes, so EVERY error and unsupported outcome is a first-class
 * JVM execution, not a hope about the platform. The contract under test: a sync that returns has a
 * completed open→fsync→close sequence; ANY failure — errno, unsupported platform, non-directory —
 * throws an [IOException] naming what happened and NEVER returns normally, because callers treat a
 * thrown sync as "not durable" and this adapter must never let that answer be a lie.
 *
 * SCOPE: the REAL `AndroidOsOperations` binding is compile-verified only; behavior against real
 * kernel calls is not exercised here (no device tests in this lane).
 */
class OsDirectorySyncTest {

    /** Records the call sequence; each hook can be made to fail with a real errno. */
    private class FakeOs(
        private val supported: Boolean = true,
        var failOpenErrno: Int? = null,
        var failFsyncErrno: Int? = null,
        var failCloseErrno: Int? = null,
    ) : OsOperations {
        val calls = mutableListOf<String>()
        val fds = mutableListOf<FileDescriptor>()
        var lastPath: String? = null

        override val isSupported: Boolean = supported

        private fun label(fd: FileDescriptor): String = (fds.indexOf(fd) + 11).toString()

        override fun openDirectory(path: String): FileDescriptor {
            calls += "open:$path"
            failOpenErrno?.let { throw ErrnoException("fake", it) }
            val fd = FileDescriptor()
            fds += fd
            lastPath = path
            return fd
        }

        override fun fsync(fd: FileDescriptor) {
            calls += "fsync:${label(fd)}"
            failFsyncErrno?.let { throw ErrnoException("fake", it) }
        }

        override fun close(fd: FileDescriptor) {
            calls += "close:${label(fd)}"
            failCloseErrno?.let { throw ErrnoException("fake", it) }
        }
    }

    private fun errno(name: String): ErrnoException = ErrnoException(name, 1)

    /** A REAL existing directory under the JVM temp root: the isDirectory precondition must hold. */
    private fun newDir(name: String): File = File(System.getProperty("java.io.tmpdir"), "os-sync-" + name + "-" + System.nanoTime()).apply { mkdirs() }

    @Test
    fun `a successful sync is exactly open - fsync - close on the directory`() {
        val ops = FakeOs()
        val dir = newDir("queue")
        OsDirectorySync(ops).sync(dir)
        assertEquals(
            "the sync must open the DIRECTORY (not the file), fsync that fd, then close it",
            listOf("open:" + dir.absolutePath, "fsync:11", "close:11"),
            ops.calls,
        )
    }

    @Test
    fun `an fsync errno is thrown as an IOException naming the errno - never a silent success`() {
        val ops = FakeOs(failFsyncErrno = 5) // EIO
        val dir = newDir("queue")
        try {
            OsDirectorySync(ops).sync(dir)
            fail("an fsync errno must propagate: an outcome-unknown sync must never be reported durable")
        } catch (e: IOException) {
            assertTrue(
                "the failure must say WHICH operation failed, got: ${e.message}",
                e.message?.contains("fsync failed") == true,
            )
            // The ErrnoException is preserved as the CAUSE so the errno remains available to a real
            // device log; the VALUE is not asserted here because this lane runs against unmocked
            // android.jar stubs that zero every field.
            assertTrue("the ErrnoException must be the cause", e.cause is ErrnoException)
        }
        // The fd is closed even on the failure path: one leaked fd per failed sync is its own defect.
        assertTrue("close must run after a failed fsync", ops.calls.last() == "close:11")
    }

    @Test
    fun `an open errno is thrown before any fsync`() {
        val ops = FakeOs(failOpenErrno = 2) // ENOENT
        val dir = newDir("queue")
        try {
            OsDirectorySync(ops).sync(dir)
            fail("an open errno must propagate")
        } catch (e: IOException) {
            assertTrue("must say which operation failed, got: ${e.message}", e.message?.contains("open failed") == true)
            assertTrue("the ErrnoException must be the cause", e.cause is ErrnoException)
        }
        assertTrue(
            "no fsync may run on an fd that was never opened",
            ops.calls.none { it.startsWith("fsync") },
        )
    }

    @Test
    fun `an unsupported platform is a THROWN failure, never a reported durable clear`() {
        // The reason this adapter exists: a platform that cannot sync a directory must produce a
        // failure the callers convert to "not durable", not a silent success that lets a failed-stop
        // clear or a publication be claimed as durable.
        val ops = FakeOs(supported = false)
        val dir = newDir("queue")
        try {
            OsDirectorySync(ops).sync(dir)
            fail("an unsupported platform must throw, never return normally")
        } catch (e: IOException) {
            assertTrue(
                "must say the platform does not support it, got: ${e.message}",
                e.message?.contains("unsupported") == true,
            )
        }
        assertTrue("no syscall may be attempted when unsupported", ops.calls.isEmpty())
    }

    @Test
    fun `a non-directory target is refused before any syscall`() {
        val ops = FakeOs()
        try {
            OsDirectorySync(ops).sync(newDir("file").resolve("entry.wav"))
            fail("syncing a non-directory must throw")
        } catch (_: IOException) {
        }
        assertTrue("no syscall may run against a non-directory", ops.calls.isEmpty())
    }
}
