package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The OS lock must exclude a real SECOND PROCESS (reviewer 8b5fc0c9). A second descriptor in this JVM
 * proves only `OverlappingFileLockException`, which a JVM monitor also produces; only another process
 * exercises the `tryLock` returning null path that a refused owner actually hits.
 */
class CaptureAllowanceProcessLockTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(10, 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024, ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192))

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun owner() = CaptureAllowanceOwner(area(), account, limits, window, AllowanceProvisioning.TestOnly)

    private fun artifacts(): Map<String, String> = area().walkTopDown()
        .filter { it.isFile }
        .associate { it.name to it.readText() }

    private fun lockPath(): File = area().walkTopDown().first { it.isFile && it.name == "usage.lock" }

    @Test fun `a lock held by another process refuses the charge and leaves every artifact unchanged`() {
        assertTrue(owner().prepareToken())
        val before = artifacts()
        val java = File(System.getProperty("java.home"), "bin/java")
        assumeTrue("a real JVM is required to hold the lock from another process", java.canExecute())

        // A SEPARATE process holds the OS lock on the very same file for a bounded time.
        val holder = ProcessBuilder(
            java.absolutePath,
            "-cp",
            System.getProperty("java.class.path"),
            LockHolder::class.java.name,
            lockPath().absolutePath,
        ).redirectErrorStream(true).start()
        try {
            // Bounded read: a helper that fails to start must fail the test, never hang it.
            val reader = holder.inputStream.bufferedReader()
            val confirmation = boundedRead(reader, 30_000)
            assertEquals("the helper process must confirm it holds the lock (got: $confirmation)", "HELD", confirmation)

            val contender = owner()
            assertFalse("a lock held by another process must refuse the charge", contender.prepareToken())
            assertEquals("no artifact may change while another process holds the lock", before, artifacts())
        } finally {
            holder.destroyForcibly()
            // Join AFTER forced termination so no helper outlives teardown.
            holder.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /** Reads one line with a deadline, so startup failure cannot block the test indefinitely. */
    private fun boundedRead(reader: java.io.BufferedReader, ms: Long): String? {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = pool.submit<String?> { reader.readLine() }
        return try {
            future.get(ms, TimeUnit.MILLISECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            "TIMEOUT after ${ms}ms"
        } finally {
            pool.shutdownNow()
        }
    }

    /** Holds an OS file lock and announces it, so the test never races the helper's startup. */
    class LockHolder {
        companion object {
            @JvmStatic
            fun main(args: Array<String>) {
                java.io.RandomAccessFile(args[0], "rw").use { raf ->
                    val lock = raf.channel.lock()
                    println("HELD")
                    System.out.flush()
                    Thread.sleep(20_000)
                    lock.release()
                }
            }
        }
    }
}
