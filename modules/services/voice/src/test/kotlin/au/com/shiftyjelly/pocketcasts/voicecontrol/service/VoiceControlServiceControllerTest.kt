@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.Manifest
import android.app.Application
import au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The controller must only believe the service is running when the service has said so.
 *
 * Android refuses a microphone foreground service started from an ineligible app state: the
 * `startForeground` call throws and the service stops itself. If the controller had already
 * marked itself started, voice recognition stayed dead for the rest of the process, because the
 * only condition that retries it is "not started" — which is what a device showed after the
 * system killed the service while the app was backgrounded.
 *
 * Pinned to a Robolectric SDK that has `Context.startForegroundService`; an older one throws
 * NoSuchMethodError and the start can never be observed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceControlServiceControllerTest {
    private lateinit var context: Application
    private lateinit var lifecycle: FakeAppLifecycleProvider
    private lateinit var controller: VoiceControlServiceController

    // An empty rule list allows, so these tests exercise the lifecycle, not the gate.
    private val allowedGate = VoiceControlGate(rules = emptyList())

    private class FakeAppLifecycleProvider : AppLifecycleProvider {
        private val state = MutableStateFlow(false)
        override val isInForeground: StateFlow<Boolean> = state

        fun set(value: Boolean) {
            state.value = value
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        lifecycle = FakeAppLifecycleProvider()
        controller = VoiceControlServiceController(context, lifecycle)
        controller.startMonitoring(allowedGate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A start request is pending for the service (read without consuming it). */
    private fun startRequested(): Boolean = shadowOf(context).peekNextStartedService() != null

    private fun clearStarts() = shadowOf(context).clearStartedServices()

    @Test
    fun `a start request is not treated as a running service until the service confirms`() {
        lifecycle.set(true)

        assertTrue("the app should ask the service to start", startRequested())
        assertFalse(
            "asking is not evidence: the service must confirm before the app believes it is running",
            controller.isServiceRunning,
        )

        controller.onServiceStarted()

        assertTrue(controller.isServiceRunning)
    }

    @Test
    fun `a service that refuses to start is retried when the app next comes to the foreground`() {
        lifecycle.set(true)
        clearStarts()

        // Android refused the start (the app was not eligible for microphone access) and the
        // service stopped itself without ever confirming.
        controller.onServiceStopped()

        assertFalse(controller.isServiceRunning)

        lifecycle.set(false)
        lifecycle.set(true)

        assertTrue("the refusal must not be sticky — the next foreground must try again", startRequested())
    }

    @Test
    fun `a service that dies in the background is restarted on the next foreground`() {
        lifecycle.set(true)
        controller.onServiceStarted()
        clearStarts()

        // The system killed the service while the app was backgrounded, so nothing reported in.
        lifecycle.set(false)
        controller.onServiceStopped()

        lifecycle.set(true)

        assertTrue("a dead service must come back when the app is foregrounded", startRequested())
    }

    @Test
    fun `foreground transitions do not start a service that is already running`() {
        lifecycle.set(true)
        controller.onServiceStarted()
        clearStarts()

        lifecycle.set(false)
        lifecycle.set(true)
        lifecycle.set(false)
        lifecycle.set(true)

        assertFalse("a running service must not be started again", startRequested())
    }

    @Test
    fun `an explicit stop clears the running state`() {
        lifecycle.set(true)
        controller.onServiceStarted()

        controller.stop()

        assertFalse(controller.isServiceRunning)
    }

    @Test
    fun `a start is not requested while the app is in the background`() {
        assertFalse("a microphone service cannot start from the background", startRequested())
    }
}
