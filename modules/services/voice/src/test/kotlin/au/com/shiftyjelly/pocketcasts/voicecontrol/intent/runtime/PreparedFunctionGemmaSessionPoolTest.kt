@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreparedFunctionGemmaSessionPoolTest {
    @Test
    fun `prepare creates then prefills and reports separate metrics`() = runTest {
        val runtime = FakeRuntime()
        val clock = FakeClock(100, 112, 200, 235)
        val pool = PreparedFunctionGemmaSessionPool(runtime, backgroundScope, clock::elapsed)

        val metrics = pool.prepare("STATIC")

        assertEquals(listOf("create:1", "prefill:1:STATIC"), runtime.calls)
        assertEquals(FunctionGemmaBackend.GPU, pool.backend)
        assertEquals(SessionPreparationMetrics(sessionCreateMs = 12, staticPrefillMs = 35), metrics)
    }

    @Test
    fun `consume closes prepared session then replenishes and prefills replacement`() = runTest {
        val runtime = FakeRuntime()
        val pool = PreparedFunctionGemmaSessionPool(runtime, this) { testScheduler.currentTime }
        pool.prepare("STATIC")

        val result = pool.consume { session ->
            session.prefill("REQUEST")
            session.decode()
        }
        advanceUntilIdle()

        assertEquals("generated:1", result.value)
        assertEquals(0, result.sessionWaitMs)
        assertEquals(2, runtime.createdSessionCount)
        assertEquals(listOf(1), runtime.closedSessionIds)
        assertEquals(2, runtime.staticPrefillCount)
        assertTrue(runtime.calls.indexOf("close:1") < runtime.calls.indexOf("create:2"))
    }

    @Test
    fun `second consumer waits for blocked replacement`() = runTest {
        val replacementGate = BlockingGate()
        val runtime = FakeRuntime(prefillGates = mapOf(2 to replacementGate))
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(runtime, workerScope) { testScheduler.currentTime }
        try {
            pool.prepare("STATIC")
            pool.consume { it.decode() }
            replacementGate.awaitEntered()

            val second = async { pool.consume { it.decode() } }
            yield()

            assertFalse(second.isCompleted)
            assertEquals(1, runtime.decodeCount)

            replacementGate.release()
            assertEquals("generated:2", second.await().value)
        } finally {
            replacementGate.release()
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `cancelled consumer closes its session and replenishes`() = runTest {
        val runtime = FakeRuntime()
        val pool = PreparedFunctionGemmaSessionPool(runtime, this) { testScheduler.currentTime }
        pool.prepare("STATIC")

        try {
            pool.consume<Nothing> { throw CancellationException("cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            advanceUntilIdle()
        }

        assertEquals(listOf(1), runtime.closedSessionIds)
        assertEquals(2, runtime.createdSessionCount)
        assertEquals(2, runtime.staticPrefillCount)
    }

    @Test
    fun `close during preparation cancels waiter and closes session and runtime`() = runTest {
        val preparationGate = BlockingGate()
        val runtime = FakeRuntime(prefillGates = mapOf(1 to preparationGate))
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(runtime, workerScope) { testScheduler.currentTime }
        try {
            val preparation = workerScope.async { pool.prepare("STATIC") }
            preparationGate.awaitEntered()
            val waiter = workerScope.async { pool.consume { it.decode() } }
            val closing = async(Dispatchers.Default) { pool.close() }

            awaitCondition { waiter.isCompleted }
            assertTrue(waiter.isCancelled)

            preparationGate.release()
            withTimeout(5_000) {
                try {
                    preparation.await()
                    fail("Expected closed preparation to be cancelled")
                } catch (_: Throwable) {
                    // Expected when close wins publication.
                }
                closing.await()
            }

            assertEquals(listOf(1), runtime.closedSessionIds)
            assertEquals(1, runtime.closeCount)
        } finally {
            preparationGate.release()
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `prepare does not create a session when close wins before engine lock`() = runTest {
        val prepareEngineGate = BlockingGate()
        val runtime = FakeRuntime()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(
            runtime = runtime,
            scope = workerScope,
            elapsedRealtimeMs = { testScheduler.currentTime },
            beforePrepareEngineLock = prepareEngineGate::block,
        )
        try {
            val preparation = workerScope.async { pool.prepare("STATIC") }
            prepareEngineGate.awaitEntered()

            pool.close()
            prepareEngineGate.release()

            try {
                preparation.await()
                fail("Expected preparation to fail after close")
            } catch (_: IllegalStateException) {
                // Expected because close won before session creation.
            }

            assertEquals(listOf("runtime-close"), runtime.calls)
            assertEquals(0, runtime.createdSessionCount)
            assertEquals(1, runtime.closeCount)
        } finally {
            prepareEngineGate.release()
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `close during replenishment cancels waiter without leaking replacement`() = runTest {
        val replacementGate = BlockingGate()
        val runtime = FakeRuntime(prefillGates = mapOf(2 to replacementGate))
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(runtime, workerScope) { testScheduler.currentTime }
        try {
            pool.prepare("STATIC")
            pool.consume { it.decode() }
            replacementGate.awaitEntered()
            val waiter = async { pool.consume { it.decode() } }
            val closing = async(Dispatchers.Default) { pool.close() }

            awaitCondition { waiter.isCompleted }
            assertTrue(waiter.isCancelled)

            replacementGate.release()
            withTimeout(5_000) { closing.await() }

            assertEquals(listOf(1, 2), runtime.closedSessionIds.sorted())
            assertEquals(1, runtime.closeCount)
        } finally {
            replacementGate.release()
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `preparation exception closes failed session and propagates`() = runTest {
        val failure = IllegalStateException("prefill failed")
        val runtime = FakeRuntime(prefillFailures = mapOf(1 to failure))
        val pool = PreparedFunctionGemmaSessionPool(runtime, this) { testScheduler.currentTime }

        try {
            pool.prepare("STATIC")
            fail("Expected preparation failure")
        } catch (actual: IllegalStateException) {
            assertEquals(failure, actual)
        }

        assertEquals(listOf(1), runtime.closedSessionIds)
    }

    @Test
    fun `preparation failure fails waiter and rejects repeated prepare`() = runTest {
        val failure = IllegalStateException("prefill failed")
        val runtime = FakeRuntime(prefillFailures = mapOf(1 to failure))
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(runtime, workerScope) { testScheduler.currentTime }
        try {
            val waiter = workerScope.async { pool.consume { it.decode() } }

            assertSameFailure(failure) { pool.prepare("STATIC") }
            assertSameFailure(failure) { waiter.await() }
            assertSameFailure(failure) { pool.prepare("STATIC") }

            pool.close()
            assertEquals(1, runtime.closeCount)
        } finally {
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `duplicate prepare does not poison an already prepared session`() = runTest {
        val runtime = FakeRuntime()
        val pool = PreparedFunctionGemmaSessionPool(runtime, backgroundScope) { testScheduler.currentTime }
        pool.prepare("STATIC")

        try {
            pool.prepare("OTHER")
            fail("Expected duplicate prepare rejection")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals("generated:1", pool.consume { it.decode() }.value)
        pool.close()
    }

    @Test
    fun `cancelled prepare does not start native work and fails readiness`() = runTest {
        val prepareGate = BlockingGate()
        val runtime = FakeRuntime()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(
            runtime = runtime,
            scope = workerScope,
            elapsedRealtimeMs = { testScheduler.currentTime },
            beforePrepareEngineLock = prepareGate::block,
        )
        try {
            val preparation = workerScope.async { pool.prepare("STATIC") }
            prepareGate.awaitEntered()
            preparation.cancel()
            prepareGate.release()
            preparation.join()

            assertEquals(0, runtime.createdSessionCount)
            try {
                pool.consume { it.decode() }
                fail("Expected cancelled readiness")
            } catch (_: CancellationException) {
                // Expected.
            }
        } finally {
            prepareGate.release()
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `cancelled owner scope closes replacement created by non-preemptible native work`() = runTest {
        val replacementGate = BlockingGate()
        val runtime = FakeRuntime(prefillGates = mapOf(2 to replacementGate))
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(runtime, workerScope) { testScheduler.currentTime }
        try {
            pool.prepare("STATIC")
            pool.consume { it.decode() }
            replacementGate.awaitEntered()
            val waiter = workerScope.async { pool.consume { it.decode() } }

            workerScope.cancel()
            replacementGate.release()
            awaitCondition { 2 in runtime.closedSessionIds }

            try {
                waiter.await()
                fail("Expected replacement cancellation")
            } catch (_: CancellationException) {
                // Expected.
            }
            assertEquals(listOf(1, 2), runtime.closedSessionIds.sorted())
        } finally {
            replacementGate.release()
            pool.close()
        }
    }

    @Test
    fun `already cancelled owner scope fails replacement readiness`() = runTest {
        val runtime = FakeRuntime()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(runtime, workerScope) { testScheduler.currentTime }
        try {
            pool.prepare("STATIC")
            pool.consume {
                workerScope.cancel()
                it.decode()
            }

            val failure = withTimeout(5_000) {
                runCatching { pool.consume { it.decode() } }.exceptionOrNull()
            }
            assertTrue(failure is CancellationException)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `concurrent close waits for first close to finish`() = runTest {
        val runtimeCloseGate = BlockingGate()
        val runtime = FakeRuntime(runtimeCloseGate = runtimeCloseGate)
        val pool = PreparedFunctionGemmaSessionPool(runtime, backgroundScope) { testScheduler.currentTime }
        pool.prepare("STATIC")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { pool.close() }
            runtimeCloseGate.awaitEntered()
            val second = executor.submit { pool.close() }

            assertFalse(second.isDone)
            runtimeCloseGate.release()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals(1, runtime.closeCount)
        } finally {
            runtimeCloseGate.release()
            executor.shutdownNow()
        }
    }

    @Test
    fun `reentrant close is rejected until consumer block unwinds`() = runTest {
        val runtime = FakeRuntime()
        val pool = PreparedFunctionGemmaSessionPool(runtime, backgroundScope) { testScheduler.currentTime }
        pool.prepare("STATIC")

        pool.consume {
            try {
                pool.close()
                fail("Expected reentrant close rejection")
            } catch (error: IllegalStateException) {
                assertEquals("Cannot close FunctionGemma session pool from an active consumer", error.message)
            }
            assertEquals(0, runtime.closeCount)
            it.decode()
        }

        pool.close()
        assertEquals(1, runtime.closeCount)
    }

    @Test
    fun `session wait includes consumer mutex queue and engine handoff`() = runTest {
        var now = 0L
        val firstConsumerGate = BlockingGate()
        val consumerCount = AtomicInteger()
        val secondConsumerStarted = CountDownLatch(1)
        val runtime = FakeRuntime()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(
            runtime = runtime,
            scope = workerScope,
            elapsedRealtimeMs = { now },
            beforePrepareEngineLock = {},
            beforeConsumerMutex = {
                if (consumerCount.incrementAndGet() == 2) {
                    secondConsumerStarted.countDown()
                }
            },
        )
        try {
            pool.prepare("STATIC")
            val first = workerScope.async {
                pool.consume {
                    firstConsumerGate.block()
                    it.decode()
                }
            }
            firstConsumerGate.awaitEntered()

            now = 100
            val second = workerScope.async { pool.consume { it.decode() } }
            check(secondConsumerStarted.await(5, TimeUnit.SECONDS))
            now = 175
            firstConsumerGate.release()

            first.await()
            assertEquals(75, second.await().sessionWaitMs)
        } finally {
            firstConsumerGate.release()
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `session close failure fails waiter and preserves primary block failure`() = runTest {
        val blockFailure = IllegalArgumentException("block failed")
        val closeFailure = IllegalStateException("close failed")
        val runtime = FakeRuntime(sessionCloseFailures = mapOf(1 to closeFailure))
        val consumerGate = BlockingGate()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = PreparedFunctionGemmaSessionPool(runtime, workerScope) { testScheduler.currentTime }
        try {
            pool.prepare("STATIC")
            val first = workerScope.async<Throwable> {
                var captured: Throwable? = null
                try {
                    pool.consume<Nothing> {
                        consumerGate.block()
                        throw blockFailure
                    }
                } catch (error: Throwable) {
                    captured = error
                }
                checkNotNull(captured)
            }
            consumerGate.awaitEntered()
            val waiter = workerScope.async { pool.consume { it.decode() } }
            consumerGate.release()

            val actual = first.await()
            assertTrue(actual is IllegalArgumentException)
            assertEquals(blockFailure.message, actual.message)
            assertEquals(listOf(closeFailure.message), actual.suppressed.map { it.message })
            assertSameFailure(closeFailure) { waiter.await() }

            pool.close()
            assertEquals(1, runtime.closeCount)
        } finally {
            consumerGate.release()
            pool.close()
            workerScope.cancel()
        }
    }

    @Test
    fun `close is idempotent and closes prepared session and runtime once`() = runTest {
        val runtime = FakeRuntime()
        val pool = PreparedFunctionGemmaSessionPool(runtime, this) { testScheduler.currentTime }
        pool.prepare("STATIC")

        pool.close()
        pool.close()

        assertEquals(listOf(1), runtime.closedSessionIds)
        assertEquals(1, runtime.closeCount)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                yield()
            }
        }
    }

    private suspend fun assertSameFailure(
        expected: Throwable,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected failure")
        } catch (actual: Throwable) {
            assertEquals(expected::class, actual::class)
            assertEquals(expected.message, actual.message)
        }
    }

    private class FakeClock(
        vararg values: Long,
    ) {
        private val values = ArrayDeque(values.toList())

        fun elapsed(): Long = values.removeFirst()
    }

    private class BlockingGate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun block() {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "Timed out waiting for gate release" }
        }

        suspend fun awaitEntered() {
            withTimeout(5_000) {
                while (entered.count > 0) {
                    yield()
                }
            }
        }

        fun release() {
            released.countDown()
        }
    }

    private class FakeRuntime(
        private val prefillGates: Map<Int, BlockingGate> = emptyMap(),
        private val prefillFailures: Map<Int, RuntimeException> = emptyMap(),
        private val sessionCloseFailures: Map<Int, RuntimeException> = emptyMap(),
        private val runtimeCloseGate: BlockingGate? = null,
    ) : FunctionGemmaRuntime {
        override val backend = FunctionGemmaBackend.GPU

        val calls = mutableListOf<String>()
        val closedSessionIds = mutableListOf<Int>()
        private val nextSessionId = AtomicInteger()
        private val createdSessions = AtomicInteger()
        private val staticPrefills = AtomicInteger()
        private val decodes = AtomicInteger()
        private val closes = AtomicInteger()

        val createdSessionCount get() = createdSessions.get()
        val staticPrefillCount get() = staticPrefills.get()
        val decodeCount get() = decodes.get()
        val closeCount get() = closes.get()

        override fun createSession(): FunctionGemmaSession {
            val id = nextSessionId.incrementAndGet()
            createdSessions.incrementAndGet()
            record("create:$id")
            return FakeSession(id)
        }

        override fun close() {
            closes.incrementAndGet()
            record("runtime-close")
            runtimeCloseGate?.block()
        }

        private fun record(call: String) {
            synchronized(calls) {
                calls += call
            }
        }

        private inner class FakeSession(
            private val id: Int,
        ) : FunctionGemmaSession {
            override fun prefill(text: String) {
                record("prefill:$id:$text")
                if (text == "STATIC") {
                    staticPrefills.incrementAndGet()
                    prefillGates[id]?.block()
                    prefillFailures[id]?.let { throw it }
                }
            }

            override fun decode(): String {
                decodes.incrementAndGet()
                record("decode:$id")
                return "generated:$id"
            }

            override fun close() {
                synchronized(closedSessionIds) {
                    closedSessionIds += id
                }
                record("close:$id")
                sessionCloseFailures[id]?.let { throw it }
            }
        }
    }
}
