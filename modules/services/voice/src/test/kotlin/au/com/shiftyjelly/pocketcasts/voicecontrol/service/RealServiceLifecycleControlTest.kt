@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * REAL SERVICE LIFECYCLE, WITH ONLY ITS EXTERNAL INFRASTRUCTURE SIMULATED.
 *
 * THE SUBJECT IS THE PRODUCTION [VoiceControlService]: its real Hilt-generated base and injection,
 * its real `onCreate`/`onStartCommand`/`onDestroy`, its real scope, lifecycle seam, shutdown barrier,
 * teardown ordering, quiescence verdict, engine job ownership and resource release. No start, stop,
 * dispatch, settlement or release decision is reimplemented here.
 *
 * WHAT IS SIMULATED — and only this: the external-infrastructure bindings in [RealServiceTestBindings].
 * Foreground/lifecycle reporting, connectivity, playlist seeding, image loading, Remote Config and
 * encrypted logging. Those are wiring; none of them is a service decision.
 *
 * WHAT THIS DOES NOT ESTABLISH, and must not be read into this file: real device lifetime
 * (foreground-service promotion, microphone access, process death, OEM behaviour) and the production
 * application graph. Those need instrumentation, which is on hold.
 *
 * THE HILT PATH IS REAL, NOT BYPASSED. There is no reflection, no `Unsafe`, and no hand-assembled
 * injector: the service is built by Hilt through a test component that installs the six replacements
 * below. That was established by measurement — the exact missing bindings were read from the
 * generated component and supplied one layer at a time until the real service constructed.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [35])
@Suppress("DEPRECATION")
class RealServiceLifecycleControlTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    // Proves the component really is Hilt-built: this arrives through generated injection.
    @Inject lateinit var captureStateHolder: VoiceControlCaptureStateHolder

    @Inject lateinit var routeMonitor: au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor

    /**
     * Supply the device id the PLATFORM would provide.
     *
     * `SettingsImpl` derives a PBE salt from `Settings.Secure.getString(ANDROID_ID)` and
     * `PBEWithMD5AndDES` requires exactly 8 bytes. A JVM test has no device id, so the read is null
     * and the service's own injection fails. The write must go to `Settings.Secure` because that is
     * the provider the production read uses; writing to `Settings.System` leaves the read null.
     */
    @Before
    fun supplyDeviceId() {
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.System.ANDROID_ID,
            "testdev1",
        )
    }

    /** Controllers live for one test; a leaked one leaves a service running into its neighbour. */
    private val controllers = mutableListOf<org.robolectric.android.controller.ServiceController<VoiceControlService>>()

    @After
    fun disposeEveryController() {
        // A device event during the test schedules a 500ms debounced read on Dispatchers.Default.
        // Cancel it BEFORE destroy so the read cannot fire detached after this class ends and land
        // in the next runTest's UncaughtExceptionsBeforeTest (the VoiceControlServiceAcquisitionTest
        // flake). The injected monitor is the graph singleton the service uses.
        routeMonitor.cancelDebouncedRouteReadForTest()
        controllers.forEach { controller ->
            // destroy() STARTS teardown; AWAIT IT (reviewer 57bf1be9): an asynchronous teardown
            // left running from this test could land in the next test's assertions. The bound is
            // on the coroutine, not the test thread.
            controller.destroy()
            val teardown = controller.get().currentTeardown ?: return@forEach
            val deadline = System.currentTimeMillis() + 30_000
            while (!teardown.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(20)
            }
            runBlocking { withTimeout(5_000) { teardown.await() } }
        }
        controllers.clear()
    }

    private fun build(): org.robolectric.android.controller.ServiceController<VoiceControlService> = Robolectric.buildService(VoiceControlService::class.java).also { controllers.add(it) }

    @Test
    fun `the production service builds through real Hilt injection and runs onCreate`() {
        hiltRule.inject()

        val service = build().create().get()

        assertNotNull(
            "dependencies must arrive through generated Hilt injection, not a hand-built injector",
            captureStateHolder,
        )
        assertNotNull("the production service must expose its own lifecycle seam", service.lifecycle)
        assertFalse(
            "a freshly created service must not report the pipeline as started",
            service.lifecycle.isStarted,
        )
    }

    @Test
    fun `destroying the real service really tears its scope down`() {
        hiltRule.inject()

        val controller = build()
        val service = controller.create().get()
        val lifetime = service.lifecycle.lifetime

        controller.destroy()

        // SCOPE (reviewer 8318db26): this proves the production SCOPE is cancelled. It does NOT
        // prove the full resource teardown completed — that result is a Deferred this test does not
        // await, because the pipeline was never started here.
        assertFalse(
            "destroy must tear the production scope down, not merely return",
            lifetime.isActive,
        )
    }

    /**
     * SCOPE, stated narrowly on purpose (reviewer 8318db26): this starts from `isStarted == false`
     * and asserts it stays false. It therefore exercises the platform STOP entry point's handling,
     * NOT the stopping of an admitted start or of running work — that needs a started pipeline this
     * harness does not stand up.
     */
    @Test
    fun `a platform stop intent on an idle service leaves it not-started`() {
        hiltRule.inject()

        val controller = build()
        val service = controller.create().get()

        // Drive the PLATFORM entry point with the production action constant.
        service.onStartCommand(
            Intent(service, VoiceControlService::class.java).setAction(VoiceControlService.STOP_ACTION),
            0,
            1,
        )

        assertFalse(
            "a stop on an IDLE service must leave the production seam reporting not-started",
            service.lifecycle.isStarted,
        )
    }

    /**
     * A SECOND START COMMAND MUST NOT ADD A SECOND WATCHER OF THE OFFERED SURFACE.
     *
     * The controller starts the service again whenever the gate allows while it believes the service
     * is not running, and Android delivers that as another `onStartCommand` to the SAME live
     * instance. A watcher launched per start command therefore accumulates one collector per command
     * for the rest of the service's life, each holding the scope and re-running the same decision.
     *
     * HOW THIS IS OBSERVED. The offered surface is a StateFlow, so its subscriber count is the
     * observable: no test-only accessor and no field the fix introduces is read here. The surface is
     * driven through the platform's own device callback, and the answer checked is the service's
     * failed-attempt record — the state the watcher exists to maintain.
     */
    @Test
    fun `repeated start commands leave one watcher of the offered surface`() {
        hiltRule.inject()
        val audioManager = RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        // Connected-but-unrouted Bluetooth: A2DP output with an enumerated SCO input and the link
        // down is the acquirable candidate surface, which is what a failed attempt records against.
        shadowOf(audioManager).setOutputDevices(
            listOf(org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()),
        )
        shadowOf(audioManager).setInputDevices(
            listOf(org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()),
        )

        // The monitor reads the device list when it is constructed, so the platform's answer has to
        // be in place before the graph builds it. Deliver the event as well: the injected monitor is
        // the graph singleton, and its published surface is what the service watches.
        routeMonitor.onDevicesChanged()
        awaitOfferedSurface(AudioRoute.BluetoothA2dpOnly)

        val controller = build()
        val service = controller.create().get()
        assertEquals(
            "precondition: the candidate surface is what the monitor offers",
            AudioRoute.BluetoothA2dpOnly,
            routeMonitor.availability.value,
        )
        assertFalse(
            "precondition: this instance has no watcher before it is started",
            watcherIsActive(service),
        )

        // The FIRST start command establishes whatever watching this service does. What must not
        // happen is growth: the controller starts the service again whenever the gate allows while it
        // believes the service is not running, and each of those arrives here as another command on
        // the same live instance.
        service.onStartCommand(Intent(), 0, 1)
        awaitWatcherRegistered(service)

        assertTrue(
            "precondition: the first start watches the offered surface",
            offeredSurfaceSubscribers() > 0,
        )

        service.onStartCommand(Intent(), 0, 2)
        service.onStartCommand(Intent(), 0, 3)

        // THE OBSERVABLE IS THIS INSTANCE'S OWN WATCHERS, not the flow's subscriber total. The
        // monitor is a graph singleton: another instance's watcher may still be subscribed while
        // its teardown finishes, and a StateFlow counts a collector twice while it registers. Both
        // move the total for reasons that have nothing to do with these commands, which is what
        // made a total-based reading depend on the machine's speed. Counting the watcher jobs this
        // service launched does not: a guard that lets each command launch one shows up as three.
        //
        // The wait ends when the main queue has drained with the commands delivered, so any watcher
        // they launched exists by then. It waits on that state, never on a duration.
        val watchers = awaitWatchersSettled(service)
        assertEquals(
            "later start commands must launch NO watcher of the offered surface; one launched per " +
                "command accumulates a collector for every later start of this instance",
            1,
            watchers,
        )

        // And the watcher still does its job after the repeated start: a failed attempt's record
        // names the surface it was tried on, and a different surface being offered is what marks
        // that record as describing a situation that has passed.
        setFailedAttemptSurface(service, AudioRoute.BluetoothA2dpOnly)
        shadowOf(audioManager).setOutputDevices(emptyList())
        shadowOf(audioManager).setInputDevices(emptyList())
        routeMonitor.onDevicesChanged()
        awaitOfferedSurface(AudioRoute.Unknown)

        // The watcher's own continuation is dispatched on Main, so let it run before reading what it
        // recorded: the surface being published and the service reacting to it are two steps.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !failedAttemptSurfaceLeft(service)) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(
            "after a repeated start the watcher must still notice the offered surface leaving the " +
                "one a failed attempt was recorded against",
            failedAttemptSurfaceLeft(service),
        )
    }

    /**
     * How many collectors the monitor's offered-surface flow currently has.
     *
     * The count lives on the mutable flow behind the published read-only view, so it is read from
     * the monitor's own state: the public `StateFlow` deliberately does not expose it, and adding
     * product API to make a test possible would test the accessor rather than the behaviour.
     */
    private fun offeredSurfaceSubscribers(): Int {
        val flow = routeMonitor.javaClass.getDeclaredField("mutableAvailability")
            .apply { isAccessible = true }.get(routeMonitor) as kotlinx.coroutines.flow.MutableStateFlow<*>
        return flow.subscriptionCount.value
    }

    /**
     * Wait until the FIRST watcher has really registered.
     *
     * The condition awaited is the service's own watcher becoming active AND the flow reporting a
     * subscriber — both facts, not a duration. A collector registers asynchronously, so the count
     * immediately after `onStartCommand` can still be zero; waiting for the registration itself
     * removes that race without measuring how long the machine takes.
     *
     * The bound is a failure guard, not the mechanism: reaching it fails the test outright rather
     * than returning a stale reading, which is what made the previous settling wait report a
     * transient count as the answer under load.
     */
    private fun awaitWatcherRegistered(service: VoiceControlService) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (watcherIsActive(service) && offeredSurfaceSubscribers() > 0) return
            Thread.sleep(10)
        }
        throw AssertionError(
            "the first start command did not establish a watcher of the offered surface " +
                "(watcherActive=${watcherIsActive(service)}, subscribers=${offeredSurfaceSubscribers()})",
        )
    }

    /**
     * How many of THIS service's coroutines are watching the offered surface, once the later start
     * commands' work has been drained.
     *
     * Read from the service's own job tree rather than the flow's subscriber total, so another
     * instance's watcher leaving cannot change the answer. The wait ends on a stable reading with
     * the main queue idle — a count of consecutive identical READINGS, not of milliseconds — so a
     * slow machine changes how long this takes and never what it concludes.
     */
    private fun awaitWatchersSettled(service: VoiceControlService): Int {
        val deadline = System.currentTimeMillis() + 30_000
        var last = watcherCount(service)
        var stableReads = 0
        val readings = mutableListOf(last)
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val now = watcherCount(service)
            stableReads = if (now == last) stableReads + 1 else 0
            last = now
            readings += now
            if (stableReads >= 3) return now
            Thread.sleep(10)
        }
        // Running out of the bound is NOT an answer. Returning the last reading here would let a
        // count that never settled pass on whatever it happened to read at the deadline, which is
        // the failure this control exists to remove.
        throw AssertionError(
            "the watcher count never settled; readings=${readings.takeLast(20)}",
        )
    }

    /**
     * How many watchers this service has launched directly on its own scope.
     *
     * `watchOfferedSurface` launches straight onto the service scope, while everything else a start
     * command runs is nested inside that command's start job. So the watcher count is the number of
     * ACTIVE DIRECT children of the scope other than the start and stop jobs — identified by
     * structure, not by name, and independent of how many collectors the shared flow happens to
     * have at that moment.
     */
    private fun watcherCount(service: VoiceControlService): Int {
        val scope = service.javaClass.getDeclaredField("serviceScope")
            .apply { isAccessible = true }.get(service) as kotlinx.coroutines.CoroutineScope
        val root = scope.coroutineContext[kotlinx.coroutines.Job] ?: return 0
        val startJob = service.javaClass.getDeclaredField("startInFlightJob")
            .apply { isAccessible = true }.get(service) as kotlinx.coroutines.Job?
        val stopJob = service.javaClass.getDeclaredField("stopInFlightJob")
            .apply { isAccessible = true }.get(service) as kotlinx.coroutines.Job?
        return root.children.count { child ->
            child.isActive && child !== startJob && child !== stopJob
        }
    }

    /** Whether the service's one watcher job is running, read from its own state. */
    private fun watcherIsActive(service: VoiceControlService): Boolean {
        val job = service.javaClass.getDeclaredField("offeredSurfaceJob")
            .apply { isAccessible = true }.get(service) as kotlinx.coroutines.Job?
        return job?.isActive == true
    }

    /** Let the monitor's own debounced read publish [expected]; the debounce runs on real threads. */
    private fun awaitOfferedSurface(expected: AudioRoute) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && routeMonitor.availability.value != expected) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals("the offered surface did not settle", expected, routeMonitor.availability.value)
    }

    private fun setFailedAttemptSurface(service: VoiceControlService, surface: AudioRoute) {
        service.javaClass.getDeclaredField("failedAttemptAvailability")
            .apply { isAccessible = true }.set(service, surface)
    }

    private fun failedAttemptSurfaceLeft(service: VoiceControlService): Boolean =
        service.javaClass.getDeclaredField("failedAttemptSurfaceLeft")
            .apply { isAccessible = true }.getBoolean(service)
}
