package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT CONTROL for the lifecycle seam, against the PRODUCTION adapter class.
 *
 * Reviewer a85cf8fe found the previous version proved nothing: it used a fake with an unchecked
 * counter and never modelled the readiness window or a real teardown. The harness here models the
 * TWO properties the service actually has and the seam must report:
 *
 * 1. a start is ACCEPTED before capture is up (model-readiness window), and a second start in that
 *    window must be refused;
 * 2. stop returns a job that completes only after the teardown work has finished.
 *
 * Still NOT covered: no Android `Service` is constructed (that control is blocked on the Hilt test
 * application), so `onStartCommand`/`onDestroy` wiring and the foreground path are outside this file.
 */
class VoiceServiceLifecycleContractTest {

    private class Harness {
        var captureUp = false
        var acceptedStartPending = false
        var scopeActive = true
        var startCalls = 0
        var stopCalls = 0
        var stopResult: Deferred<TeardownResult>? = null

        val lifetime: VoiceServiceLifecycle = VoiceServiceLifecycle.FromServiceState(
            // The service's authority: capture up OR an accepted start still in readiness.
            startedOrStarting = { captureUp || acceptedStartPending },
            scope = object : VoiceServiceLifecycle.LifetimeScope {
                override val isActive get() = scopeActive
            },
            startPipeline = {
                startCalls++
                acceptedStartPending = true
                true
            },
            stopPipeline = {
                stopCalls++
                checkNotNull(stopResult) { "stop requires a teardown result" }
            },
        )
    }

    @Test
    fun `a second start during the readiness window is refused`() {
        val h = Harness()
        assertTrue(h.lifetime.start())
        // Capture is NOT up yet: the start was accepted and is waiting on readiness.
        assertFalse(h.captureUp)
        assertFalse("a start already in flight must refuse a second one", h.lifetime.start())
        assertEquals("the pipeline is entered exactly once", 1, h.startCalls)
        assertTrue("the seam reports started while the start is still in flight", h.lifetime.isStarted)
    }

    @Test
    fun `a start is allowed again once the in-flight start finishes`() {
        val h = Harness()
        h.lifetime.start()
        h.acceptedStartPending = false // the start completed and did not end up capturing
        assertFalse(h.lifetime.isStarted)
        assertTrue("a finished start must not block the next one forever", h.lifetime.start())
    }

    @Test
    fun `stop returns a job that completes only after teardown really finishes`() = runBlocking {
        val h = Harness()
        // Teardown is gated so the assertion observes a genuinely incomplete job.
        val gate = CompletableDeferred<Unit>()
        h.stopResult = CoroutineScope(Dispatchers.IO).async {
            withContext(NonCancellable) { gate.await() }
            TeardownResult.Released
        }
        val returned = h.lifetime.stop()
        assertNotNull(returned)
        assertFalse("stop must NOT report completion before teardown finishes", returned.isCompleted)
        gate.complete(Unit)
        returned.join()
        assertTrue("stop completes once teardown is really done", returned.isCompleted)
    }

    @Test
    fun `a held teardown result survives the seam`() = runBlocking {
        // Reviewer 47a3a267: the seam is the ONE stop authority. If it returned a bare Job, a
        // teardown that deliberately HELD resources would be indistinguishable from a clean release
        // at every caller. The result must cross the seam intact.
        val h = Harness()
        h.stopResult = CoroutineScope(Dispatchers.IO).async {
            TeardownResult.Held(TeardownFailure(EngineQuiescence.Verdict.Residual(remaining = 2)))
        }
        val outcome = h.lifetime.stop().await()
        assertTrue("a held teardown must not reach the caller as a clean release", outcome is TeardownResult.Held)
    }

    @Test
    fun `reported state comes from the service, not from an adapter-local flag`() {
        val h = Harness()
        h.captureUp = true
        assertTrue("isStarted must read the service's authority", h.lifetime.isStarted)
        h.scopeActive = false
        assertFalse("isActive must read the service scope", h.lifetime.lifetime.isActive)
    }
}
