package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.system.ErrnoException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        var failFsyncUnexpected: IllegalStateException? = null,
        var failCloseErrno: Int? = null,
        var failCloseUnexpected: IllegalStateException? = null,
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
            failFsyncUnexpected?.let { throw it }
        }

        override fun close(fd: FileDescriptor) {
            calls += "close:${label(fd)}"
            failCloseErrno?.let { throw ErrnoException("fake", it) }
            failCloseUnexpected?.let { throw it }
        }
    }

    private fun errno(name: String): ErrnoException = ErrnoException(name, 1)

    /** A REAL existing directory under the JVM temp root: the isDirectory precondition must hold. */
    private fun newDir(name: String): File = File(System.getProperty("java.io.tmpdir"), "os-sync-" + name + "-" + System.nanoTime()).apply { mkdirs() }

    @Test
    fun `the default flag authority resolves the PLATFORM constant or the COMPILED native one - never a device capability list`() {
        // Round 4 (reviewer 87e2e450; spec 220cdb5f): Build.SUPPORTED_ABIS describes what a device
        // CAN run, not the flag this process's syscalls expect, so NO code path may consult it.
        // On this plain JVM: the android.jar stub has no OsConstants.O_DIRECTORY field and no
        // native helper .so exists, so the default authority MUST be null (=> unsupported), even
        // though any SUPPORTED_ABIS-based selector would have "known" an ARM value here.
        // Robolectric-side mixed discriminator lives in OsDirectorySyncWiringTest, where
        // Build.SUPPORTED_ABIS is populated.
        assertNull(
            "a non-null default on the JVM means a device-capability or guessed fallback exists",
            AndroidOsOperations.DEFAULT_O_DIRECTORY_AUTHORITY(),
        )
        assertFalse("no authority => unsupported", AndroidOsOperations(oDirectoryAuthority = { null }).isSupported)
        assertTrue("a resolvable authority => supported", AndroidOsOperations(oDirectoryAuthority = { 0x4000 }).isSupported)
    }

    @Test
    fun `a close-only failure after a successful fsync PROPAGATES`() {
        // The declared contract is "any call may fail loudly"; the previous finally swallowed a
        // close failure entirely, reporting a sync whose outcome was partly unknown as complete.
        val os = FakeOs(failCloseErrno = 9) // EBADF
        try {
            OsDirectorySync(os).sync(newDir("close-only"))
            fail("a failed close must throw, never return normally")
        } catch (e: IOException) {
            assertTrue("close failure must be named: ${e.message}", e.message!!.contains("close"))
            assertTrue("the errno cause must be preserved", e.cause is ErrnoException)
        }
        assertTrue("close must still have been reached: ${os.calls}", os.calls.last().startsWith("close"))
    }

    @Test
    fun `a NON-errno CLOSE failure does not replace an earlier fsync failure - and the double-unexpected case preserves the fsync original`() {
        // reviewer f52f1209: close's catch must cover arbitrary throwables too, or a misbehaving
        // injected close implementation would REPLACE the fsync primary.
        val os = FakeOs(failFsyncErrno = 5, failCloseUnexpected = IllegalStateException("close misbehaved"))
        try {
            OsDirectorySync(os).sync(newDir("errno-fsync-unexpected-close"))
            fail("the fsync primary must surface")
        } catch (e: IOException) {
            assertTrue("ORIGINAL errno fsync error must be thrown: ${e.message}", e.message!!.contains("fsync"))
            assertEquals(
                "both the close failure kinds must ride along suppressed",
                1,
                e.suppressed.size,
            )
        }
        val os2 = FakeOs(failCloseUnexpected = IllegalStateException("close misbehaved"))
        try {
            OsDirectorySync(os2).sync(newDir("unexpected-close-only"))
            fail("a close-only non-errno failure must still propagate")
        } catch (e: IllegalStateException) {
            assertEquals("close misbehaved", e.message)
        }
    }

    @Test
    fun `a NON-errno fsync failure still runs close and propagates the original throwable`() {
        // claude review minor 4: the seam is documented for arbitrary injected implementations, so
        // an fsync implementation may throw something that is not an ErrnoException; the fd must
        // still be closed and the ORIGINAL throwable must surface, with the close failure riding
        // along as suppressed if close also fails.
        val os = FakeOs()
        os.failFsyncUnexpected = IllegalStateException("injected implementation misbehaved")
        try {
            OsDirectorySync(os).sync(newDir("unexpected-fsync"))
            fail("the original non-errno throwable must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("injected implementation misbehaved", e.message)
            assertTrue("close must have run: ${os.calls}", os.calls.last().startsWith("close"))
        }
        // And when close ALSO fails, the close failure rides on the original throwable.
        val os2 = FakeOs(failCloseErrno = 9)
        os2.failFsyncUnexpected = IllegalStateException("injected implementation misbehaved")
        try {
            OsDirectorySync(os2).sync(newDir("unexpected-fsync-and-close"))
            fail("the original non-errno throwable must propagate")
        } catch (e: IllegalStateException) {
            assertEquals(1, e.suppressed.size)
            assertTrue(e.suppressed[0].message!!.contains("close"))
        }
    }

    @Test
    fun `when fsync AND close both fail the ORIGINAL fsync error is thrown with the close failure suppressed`() {
        val os = FakeOs(failFsyncErrno = 5, failCloseErrno = 9)
        try {
            OsDirectorySync(os).sync(newDir("close-only"))
            fail("the fsync failure must surface, never be replaced or swallowed")
        } catch (e: IOException) {
            assertTrue("the ORIGINAL fsync error must be thrown: ${e.message}", e.message!!.contains("fsync"))
            assertTrue("the errno cause must be preserved", e.cause is ErrnoException)
            assertEquals(
                "the close failure must ride along as suppressed, not vanish",
                1,
                e.suppressed.size,
            )
            assertTrue(e.suppressed[0].message!!.contains("close"))
        }
    }

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
