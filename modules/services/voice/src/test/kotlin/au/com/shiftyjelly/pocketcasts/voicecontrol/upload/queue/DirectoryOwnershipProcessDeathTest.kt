package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACTUAL PROCESS DEATH, not a simulated one (spec ecf0df08).
 *
 * Every other restart fixture models death by calling `close()`, which is an ORDERLY shutdown: it
 * invalidates the handle and then releases. Real death is different in one way that matters - there is no
 * invalidation step at all, the kernel simply drops the lock while the dead process's objects vanish with
 * it. That difference decides whether a *fresh* owner can take the directory, so it is worth proving with a
 * real second process rather than asserting it.
 *
 * No new dependencies: a child JVM runs a tiny main that takes the lock, announces itself, and only exits
 * when killed.
 */
class DirectoryOwnershipProcessDeathTest {

    @Test
    fun `a killed process releases the directory so a fresh owner acquires it`() {
        val root = File(System.getProperty("java.io.tmpdir"), "ownership-death-${System.nanoTime()}")
        val queueRoot = File(root, "contributions").apply { mkdirs() }

        val java = File(System.getProperty("java.home"), "bin/java")
        val cp = System.getProperty("java.class.path")
        val child = ProcessBuilder(
            java.absolutePath,
            "-cp",
            cp,
            LockHolderMain::class.java.name,
            queueRoot.absolutePath,
        ).redirectErrorStream(true).start()

        try {
            // Wait for the child to actually HOLD the lock before asserting anything about it.
            val announced = readLineWithTimeout(child, 30_000)
            assertEquals("PREMISE: the child process took the directory lock (got $announced)", "HELD", announced)

            // While the child is alive, a fresh owner in THIS process must NOT acquire it.
            val blocked = FileDirectoryOwnership(queueRoot).acquire()
            assertTrue(
                "a live owner must exclude another; got reason=${blocked.unavailableReason()}",
                !blocked.isHeld(),
            )
            assertEquals("held-by-another-owner", blocked.unavailableReason())

            // KILL IT. No close(), no invalidation, no shutdown hook - the kernel drops the lock.
            child.destroyForcibly()
            assertTrue("the child process is gone", child.waitFor(30, TimeUnit.SECONDS))

            val acquired = FileDirectoryOwnership(queueRoot).acquire()
            assertTrue(
                "a fresh owner must acquire the directory after the previous process dies; got reason=${acquired.unavailableReason()}",
                acquired.isHeld(),
            )
            assertNotEquals(null, acquired)
            acquired.close()
        } finally {
            child.destroyForcibly()
        }
    }

    private fun readLineWithTimeout(process: Process, timeoutMs: Long): String? {
        val reader = process.inputStream.bufferedReader()
        val future = java.util.concurrent.CompletableFuture.supplyAsync { reader.readLine() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            null
        }
    }
}

/** Child entry point: take the directory lock, say so, then park until killed. */
object LockHolderMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val ownership = FileDirectoryOwnership(File(args[0])).acquire()
        println(if (ownership.isHeld()) "HELD" else "FAILED:${ownership.unavailableReason()}")
        System.out.flush()
        Thread.sleep(120_000)
    }
}
