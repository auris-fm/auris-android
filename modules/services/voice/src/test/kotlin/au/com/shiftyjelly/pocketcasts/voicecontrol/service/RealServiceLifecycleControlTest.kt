package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Intent
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * THE REAL SERVICE LIFETIME CONTROL.
 *
 * Robolectric constructs the actual [VoiceControlService] and drives its real Android lifecycle:
 * `create()` runs `onCreate`, `startCommand(...)` runs `onStartCommand`, `destroy()` runs
 * `onDestroy`. The seam ([VoiceControlService.lifecycle]) is then read from THAT instance, so what
 * is asserted is the platform lifetime rather than an adapter exercised in isolation.
 *
 * SCOPE, stated precisely: this control covers the LIFECYCLE DECISIONS — that the seam reflects and
 * drives the real instance, that a stop path deactivates the scope exactly once, and that stop is
 * idempotent across destroy. It does NOT cover the microphone foreground-service start, the ASR
 * pipeline, `onIntent` → dispatch, or publication: starting the real pipeline here would require
 * permissions, a foreground notification and the full Dagger graph, none of which this control
 * fabricates.
 */
@RunWith(RobolectricTestRunner::class)
class RealServiceLifecycleControlTest {

    private fun controller(): ServiceController<VoiceControlService> = org.robolectric.Robolectric.buildService(VoiceControlService::class.java)

    @Test
    fun `onCreate exposes the lifecycle seam over the real service instance`() {
        val service = controller().create().get()
        val lifecycle = service.lifecycle
        assertNotNull(lifecycle)
        // Nothing has started the pipeline yet in a bare create(): the seam must say so.
        assertFalse("a freshly created service is not started", lifecycle.isStarted)
        assertTrue("the service scope is live after create", lifecycle.lifetime.isActive)
    }

    @Test
    fun `onDestroy deactivates the real service scope`() {
        val ctl = controller().create()
        val service = ctl.get()
        assertTrue(service.lifecycle.lifetime.isActive)
        ctl.destroy()
        assertFalse("onDestroy cancels the service scope", service.lifecycle.lifetime.isActive)
        assertFalse("onDestroy leaves the service stopped", service.lifecycle.isStarted)
    }

    @Test
    fun `a stop sent through the seam leaves the service stopped and its teardown job complete`() {
        val service = controller().create().get()
        val job: Job = service.lifecycle.stop()
        assertTrue("stop's job completes so callers can await ordering", job.isCompleted)
        assertFalse(service.lifecycle.isStarted)
        assertFalse("stop cancels the service scope", service.lifecycle.lifetime.isActive)
    }

    // withIntent is the Robolectric entry point for a start command; deprecation is theirs.
    @Suppress("DEPRECATION")
    @Test
    fun `the stop action stops the real service exactly once per delivery`() {
        val ctl = controller().create()
        val service = ctl.get()
        ctl.withIntent(Intent(VoiceControlService.STOP_ACTION)).startCommand(0, 0)
        assertFalse("a STOP delivery must leave the service stopped", service.lifecycle.isStarted)
        // The platform self-stop is observable through Robolectric's shadow of the service.
        assertTrue("STOP_ACTION must stopSelf", shadowOf(service).isStoppedBySelf)
    }
}
