package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT CONTROL for the lifecycle seam.
 *
 * This is the SHAPE contract, not the production wiring: it fixes the decisions the service lifetime
 * must make — a second start never yields a second pipeline, and a stop is idempotent. It is written
 * against [VoiceServiceLifecycle] so the production implementation and the later dispatch/cancel
 * control are held to the same rules. It does NOT drive the real Service yet; that is the next step
 * and is stated as unfinished rather than implied.
 */
class VoiceServiceLifecycleContractTest {

    /** A source-level lifetime with the required decisions, used to pin the contract itself. */
    private class Lifetime : VoiceServiceLifecycle {
        private var started = false
        private var stopCount = 0
        private val scope = object : VoiceServiceLifecycle.LifetimeScope {
            override var isActive = true
        }

        override val isStarted get() = started
        override val lifetime: VoiceServiceLifecycle.LifetimeScope get() = scope

        override fun start(): Boolean {
            if (started) return false
            started = true
            return true
        }

        override fun stop(): Job {
            stopCount++
            started = false
            scope.isActive = false
            return Job().apply { complete() }
        }

        val stops get() = stopCount
    }

    @Test
    fun `a second start does not start a second pipeline`() {
        val lifetime = Lifetime()
        assertTrue(lifetime.start())
        assertFalse("a second start must report already-started", lifetime.start())
        assertTrue(lifetime.isStarted)
    }

    @Test
    fun `stop is idempotent and deactivates the lifetime scope`() {
        val lifetime = Lifetime()
        lifetime.start()
        val first = lifetime.stop()
        assertTrue("stop's job completes so a control can await real ordering", first.isCompleted)
        assertFalse(lifetime.isStarted)
        assertFalse("the service scope is cancelled on stop", lifetime.lifetime.isActive)
        lifetime.stop()
        assertFalse("stopping twice must not restart anything", lifetime.isStarted)
    }
}
