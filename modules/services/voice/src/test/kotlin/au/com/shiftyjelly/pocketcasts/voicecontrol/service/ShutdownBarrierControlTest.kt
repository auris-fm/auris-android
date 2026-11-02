package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controls for the shutdown barrier.
 *
 * The property under test is the one a snapshot-then-cancel cannot provide: after the barrier
 * closes, a start is REFUSED, and every job admitted before it is owned by the shutdown. The jobs
 * here are real coroutines, so "owned" means something the teardown could actually join.
 */
class ShutdownBarrierControlTest {
    @Test
    fun `closing the barrier returns every job admitted before it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val barrier = ShutdownBarrier()
            val first = scope.launch(Dispatchers.IO) { awaitCancellation() }
            val second = scope.launch(Dispatchers.IO) { awaitCancellation() }
            assertTrue(barrier.beginStart(listOf(first)))
            assertTrue(barrier.beginStart(listOf(second)))

            val owned = barrier.close()
            assertEquals("the shutdown must own all admitted work", setOf(first, second), owned.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a start arriving after the close is refused, not silently owned`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val barrier = ShutdownBarrier()
            barrier.close()
            val late = scope.launch(Dispatchers.IO) { awaitCancellation() }
            assertFalse("a start must not be admitted once the barrier is closed", barrier.beginStart(listOf(late)))
            assertTrue("nothing may be owned after the barrier closed", barrier.close().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `work admitted before the close cannot escape the returned set`() = runBlocking {
        // The defect a snapshot has: a job created between the snapshot and the cancel belongs to
        // nobody. Here it is admitted before the close, so the shutdown is responsible for it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val barrier = ShutdownBarrier()
            val entered = CompletableDeferred<Unit>()
            val outer = scope.launch(Dispatchers.IO) {
                val inner = launch(Dispatchers.IO) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                barrier.beginStart(listOf(inner))
                awaitCancellation()
            }
            barrier.beginStart(listOf(outer))
            entered.await()

            val owned = barrier.close()
            assertEquals("both the start and the work it spawned must be owned", 2, owned.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `releasing finished work does not hide outstanding work`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val barrier = ShutdownBarrier()
            val finished = scope.launch(Dispatchers.IO) { }
            val running = scope.launch(Dispatchers.IO) { awaitCancellation() }
            barrier.beginStart(listOf(finished, running))
            finished.join()
            barrier.release(listOf(finished))
            assertEquals("the running job must still be owned", listOf(running), barrier.close())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a closed barrier stays closed`() {
        val barrier = ShutdownBarrier()
        assertFalse(barrier.isClosed)
        barrier.close()
        assertTrue(barrier.isClosed)
        assertFalse("a start after a close is never admitted", barrier.beginStart(emptyList()))
    }
}
