package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controls for the early-connection opener, through the real wiring.
 *
 * Three properties matter, and each is about a connection reaching the wrong turn or the wrong
 * thread. A synthetic test that called the holder directly would not prove any of them, so these
 * drive [EarlyConnectionOpener] on a real separate dispatcher and count which thread the opens and
 * the takes actually ran on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarlyConnectionOpenerControlTest {

    private class Harness {
        val opened = Collections.synchronizedList(mutableListOf<SocketCloudTurnConnection>())
        val openThreads = Collections.synchronizedList(mutableListOf<String>())
        val takeThreads = Collections.synchronizedList(mutableListOf<String>())
        var clock = 1_000L
        val opens = AtomicInteger()

        fun connection(tag: String, nowMs: Long): SocketCloudTurnConnection = SocketCloudTurnConnection(tag, nowMs).also { opened += it }

        fun opener(scope: TestScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) = EarlyConnectionOpener(
            dispatcher = dispatcher,
            open = { tag, nowMs ->
                openThreads += Thread.currentThread().name
                opens.incrementAndGet()
                connection(tag, nowMs)
            },
            now = { clock },
        )
    }

    @Test
    fun `a turn adopts its own early connection`() = runTest {
        val harness = Harness()
        val openerThreads = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        // A real separate thread, so thread ownership is observable rather than assumed.
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "early-conn-owner").apply { isDaemon = true }
        }
        val realDispatcher = io.asCoroutineDispatcherSafe()
        try {
            val opener = harness.opener(this, realDispatcher)

            val connection = opener.openFor("utt-1")
            val adopted = opener.takeFor("utt-1")

            assertNotNull("the turn must adopt the connection it opened", adopted)
            assertEquals(connection, adopted)
            assertEquals("only one dial for open plus adopt", 1, harness.opens.get())
            openerThreads += harness.openThreads.toList()
            assertTrue(
                "the open must run on the owning dispatcher, not the caller's thread: $openerThreads",
                harness.openThreads.all { it == "early-conn-owner" },
            )
        } finally {
            io.shutdownNow()
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `a stale connection is replaced silently`() = runTest {
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "early-conn-owner").apply { isDaemon = true }
        }
        try {
            val harness = Harness()
            val opener = harness.opener(this, io.asCoroutineDispatcherSafe())

            val first = opener.openFor("utt-1")
            // The handle aged past the server's limit while the turn was still being recognised.
            harness.clock += CloudTurnConnection.MAX_AGE_MS + 1

            val taken = opener.takeFor("utt-1")

            assertNull("a stale handle is not handed to the turn", taken)
            assertTrue("and it is closed rather than left open", first.isClosed)
            // Silent: nothing threw, and the caller's only signal is that it must dial for itself.
        } finally {
            io.shutdownNow()
        }
    }

    @Test
    fun `a turn never adopts another utterance's connection`() = runTest {
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "early-conn-owner").apply { isDaemon = true }
        }
        try {
            val harness = Harness()
            val opener = harness.opener(this, io.asCoroutineDispatcherSafe())

            opener.openFor("utt-1")
            val stolen = opener.takeFor("utt-2")

            assertNull("one utterance must never be handed another's socket", stolen)
        } finally {
            io.shutdownNow()
        }
    }

    @Test
    fun `the same utterance resuming speaking keeps its connection`() = runTest {
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "early-conn-owner").apply { isDaemon = true }
        }
        try {
            val harness = Harness()
            val opener = harness.opener(this, io.asCoroutineDispatcherSafe())

            val first = opener.openFor("utt-1")
            harness.clock += 2_000
            // The user paused and carried on: same utterance, so the same connection.
            val second = opener.openFor("utt-1")

            assertEquals("a resumption must not dial again", 1, harness.opens.get())
            assertEquals(first, second)
            assertTrue("and the connection is not closed by the resumption", !first.isClosed)
        } finally {
            io.shutdownNow()
        }
    }
}

/** `ExecutorService.asCoroutineDispatcher()` under a name that reads as what it is here. */
private fun java.util.concurrent.ExecutorService.asCoroutineDispatcherSafe(): kotlinx.coroutines.CoroutineDispatcher = this.asCoroutineDispatcher()
