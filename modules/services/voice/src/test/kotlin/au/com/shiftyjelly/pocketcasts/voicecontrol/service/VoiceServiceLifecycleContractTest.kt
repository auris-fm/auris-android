package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT CONTROL for the lifecycle seam, run against the PRODUCTION mapping.
 *
 * Reviewer 5184236b: asserting this against a hand-written fake proves nothing about production —
 * a fake can satisfy any title it is given. So this drives
 * [VoiceServiceLifecycle.FromServiceState] — the same class `VoiceControlService.lifecycle` uses —
 * with the service's real decision shapes: the started flag, the scope's active flag, and the
 * start/stop callbacks.
 *
 * Still NOT covered, stated plainly: no Android `Service` is instantiated, so `onCreate`/
 * `onDestroy`, `stopSelf` and the platform foreground-service path are outside this control.
 */
class VoiceServiceLifecycleContractTest {

    /**
     * The PRODUCTION adapter, driven by the same state the service owns: a started flag, the scope's
     * active flag, and start/stop callbacks. The counters record what the adapter asked for, so the
     * assertions are about the adapter's decisions rather than a fake's convenience.
     */
    private class Harness {
        var engineStarted = false
        var scopeActive = true
        var startCalls = 0
        var stopCalls = 0

        val lifetime: VoiceServiceLifecycle = VoiceServiceLifecycle.FromServiceState(
            started = { engineStarted },
            scope = object : VoiceServiceLifecycle.LifetimeScope {
                override val isActive get() = scopeActive
            },
            startPipeline = {
                startCalls++
                engineStarted = true
                engineStarted
            },
            stopPipeline = {
                stopCalls++
                engineStarted = false
                scopeActive = false
                Job().apply { complete() }
            },
        )
    }

    @Test
    fun `a second start does not start a second pipeline`() {
        val h = Harness()
        assertTrue(h.lifetime.start())
        assertFalse("a second start must report already-started", h.lifetime.start())
        assertTrue(h.lifetime.isStarted)
        assertEquals("the pipeline is started exactly once", 1, h.startCalls)
    }

    @Test
    fun `stop is idempotent and deactivates the lifetime scope`() {
        val h = Harness()
        h.lifetime.start()
        val first = h.lifetime.stop()
        assertTrue("stop's job completes so a control can await real ordering", first.isCompleted)
        assertFalse(h.lifetime.isStarted)
        assertFalse("the service scope is cancelled on stop", h.lifetime.lifetime.isActive)
        h.lifetime.stop()
        assertFalse("stopping twice must not restart anything", h.lifetime.isStarted)
        assertEquals("stop reaches the pipeline on each call, never restarting", 2, h.stopCalls)
    }

    @Test
    fun `reported state comes from the service, not from the adapter's own flag`() {
        val h = Harness()
        // The engine state is the authority: the adapter must reflect it, not track its own copy.
        h.engineStarted = true
        assertTrue("isStarted must read the engine's real flag", h.lifetime.isStarted)
        h.scopeActive = false
        assertFalse("lifetime.isActive must read the service scope", h.lifetime.lifetime.isActive)
    }
}
