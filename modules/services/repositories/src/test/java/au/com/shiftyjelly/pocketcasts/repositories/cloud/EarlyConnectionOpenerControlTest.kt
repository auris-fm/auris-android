package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a dead connection is refused so the turn dials fresh`() = runTest {
        val harness = Harness()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opener = harness.opener(this, dispatcher)

        opener.openFor("utt-1")
        testScheduler.advanceUntilIdle()
        val dead = harness.opened.single()
        dead.markDead()

        val adopted = opener.takeFor("utt-1")
        testScheduler.advanceUntilIdle()

        assertNull("a connection whose socket already failed must never be adopted", adopted)
        assertTrue("a refused dead connection must be closed, not left open", dead.isClosed)
        assertEquals("a refused connection must not count as spent-adopted", false, dead.isSpent)
    }

    @Test
    fun `a connection gone stale before its utterance resumes is replaced, not resumed`() = runTest {
        // The keep condition checks liveness and spend but not age, so an utterance that pauses
        // past MAX_AGE_MS resumed onto a socket `takeFor` then threw away at the staleness check:
        // the early open existed to spare the dial, and the resume forfeited it anyway. The KDoc
        // already promises "a stale one is closed and replaced" -- this pins it.
        val harness = Harness()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opener = harness.opener(this, dispatcher)

        val first = opener.openFor("utt-1")
        testScheduler.advanceUntilIdle()
        // Age past the server limit while the SAME utterance was still unspent and alive.
        harness.clock += CloudTurnConnection.MAX_AGE_MS + 1

        val second = opener.openFor("utt-1")
        testScheduler.advanceUntilIdle()

        assertEquals("a stale held connection must be replaced, not resumed", 2, harness.opens.get())
        assertTrue("the replacement must be a different connection", second !== first)
        assertTrue("the stale connection must be closed on replacement", first.isClosed)
    }

    @Test
    fun `a stop closes the held connection and leaves nothing held`() = runTest {
        val harness = Harness()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opener = harness.opener(this, dispatcher)

        val held = opener.openFor("utt-1")
        testScheduler.advanceUntilIdle()
        assertFalse("the fixture holds an open connection", held.isClosed)

        opener.shutdown()
        testScheduler.advanceUntilIdle()

        assertTrue("a stopped session must not keep a socket open", held.isClosed)
        assertNull("nothing is held after the stop", opener.takeFor("utt-1"))
    }

    @Test
    fun `after a shutdown the opener still pre-opens for the next session`() = runTest {
        // shutdown() closes what one session held; it must not cancel the opener itself. The
        // opener is a @Singleton: the next service session still pre-opens through the same
        // instance, and a "tidier" shutdown that cancelled the open scope would quietly remove
        // the early connection from every session after the first.
        val harness = Harness()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opener = harness.opener(this, dispatcher)

        val first = opener.openFor("utt-1")
        testScheduler.advanceUntilIdle()
        opener.shutdown()
        testScheduler.advanceUntilIdle()
        assertTrue(first.isClosed)

        opener.openForLater("utt-2")
        testScheduler.advanceUntilIdle()
        val second = opener.takeFor("utt-2")
        testScheduler.advanceUntilIdle()

        assertEquals("the next session still gets its pre-opened connection", 2, harness.opens.get())
        assertNotNull("the pre-opened connection of the next session must be adoptable", second)
        assertFalse("the new connection must be open", (second as SocketCloudTurnConnection).isClosed)
    }

    @Test
    fun `a dead held connection is replaced on the next open rather than resumed`() = runTest {
        // The same utterance speaking again normally keeps its connection. A socket that died in
        // between must not be handed back as a resumption: the early open exists to spare the dial,
        // and resuming a dead handle forfeits it while logging "resume" about a dead socket.
        val harness = Harness()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opener = harness.opener(this, dispatcher)

        val first = opener.openFor("utt-1")
        testScheduler.advanceUntilIdle()
        // markDead alone: nothing was adopted, so isSpent stays false and the tag still matches --
        // liveness is the ONLY thing that can trigger the replacement here.
        (first as SocketCloudTurnConnection).markDead()
        // Advance a little, but far below MAX_AGE_MS, so staleness cannot be the cause either.
        harness.clock += 10L

        val second = opener.openFor("utt-1")
        testScheduler.advanceUntilIdle()

        assertEquals("a dead held connection must be replaced, not resumed", 2, harness.opens.get())
        assertTrue("the replacement must be a different connection", second !== first)
        assertTrue("the dead connection must be closed, not leaked open", first.isClosed)
        assertTrue(
            "the replacement must be alive",
            !(second as SocketCloudTurnConnection).isDead,
        )
    }

/** `ExecutorService.asCoroutineDispatcher()` under a name that reads as what it is here. */
    private fun java.util.concurrent.ExecutorService.asCoroutineDispatcherSafe(): kotlinx.coroutines.CoroutineDispatcher = this.asCoroutineDispatcher()

    /**
     * A single-threaded owner dispatcher whose queued tasks run only when the test releases them,
     * and in an order the test chooses. A real multi-threaded dispatcher can run a shutdown that
     * was requested second before a dial that was requested first; this models exactly that
     * reordering so the interleaving is pinned instead of hoped for.
     */
    private class GatedOwnerDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val size: Int get() = queue.size

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun runTask(index: Int) {
            val tasks = queue.toList()
            assertTrue("expected a queued task at $index, have ${tasks.size}", index < tasks.size)
            queue.remove(tasks[index])
            tasks[index].run()
        }
    }

    @Test
    fun `a dial queued before a shutdown does not hold a socket after it`() = runTest {
        // openForLater launches the dial onto the opener's owner; shutdown() may be requested a
        // moment later and, on a multi-threaded dispatcher, finish while that dial is still queued.
        // The dial then starts after the shutdown: if it still dials, it writes `held` after the
        // close and the shutdown leaves a socket open. A dial queued before a shutdown is
        // superseded by it and must be dropped.
        val harness = Harness()
        val dispatcher = GatedOwnerDispatcher()
        val opener = harness.opener(this, dispatcher)

        opener.openForLater("utt-1")
        launch { opener.shutdown() }
        runCurrent() // the shutdown coroutine hops to the owner dispatcher, queued behind the dial
        assertEquals(2, dispatcher.size)

        dispatcher.runTask(1) // the shutdown runs to completion before the dial starts
        assertNull("nothing is held right after the shutdown", opener.peek())

        dispatcher.runTask(0) // the queued dial now runs, after the shutdown
        assertNull(
            "a dial queued before a shutdown must not hold a socket after it",
            opener.peek(),
        )
        assertTrue("no dial may run for a superseded request", harness.opens.get() == 0)
    }
}
