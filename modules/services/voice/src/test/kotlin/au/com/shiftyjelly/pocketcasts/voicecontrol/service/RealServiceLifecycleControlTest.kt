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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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

    // The monitor's debounced-read scope, test-lifetime: cancelled in teardown so a pending read
    // cannot fire detached after this class ends.
    @Inject
    @au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteMonitorScope
    lateinit var routeMonitorScope: kotlinx.coroutines.CoroutineScope

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
        routeMonitorScope.cancel()
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
     * HOW THIS IS OBSERVED. The watcher job the service records (`offeredSurfaceJob`) is the
     * observable: the SAME job must still be recorded after the later commands, because a guard
     * that lets a command launch a watcher REPLACES the record. Identity is exactly the defect;
     * nothing else the service runs can move it. The surface is driven through the platform's own
     * device callback, and the answer checked is the service's failed-attempt record — the state
     * the watcher exists to maintain.
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
        val firstWatcher = watcherJob(service)
        assertNotNull("precondition: the first start recorded its watcher job", firstWatcher)

        service.onStartCommand(Intent(), 0, 2)
        service.onStartCommand(Intent(), 0, 3)
        // Let the later commands' work run: a watcher they launched would exist by then. The wait
        // ends when the main queue has drained with the commands delivered, never on a duration.
        drainMainQueue()

        // IDENTITY, NOT A COUNT. A guard that lets a later command launch a watcher REPLACES the
        // recorded job, so the same instance still being recorded is exactly the one-watcher fact.
        // Counting active scope children inferred that from position in the job tree and broke for
        // reasons unrelated to the guard (any other direct-scope launch moves the count); the
        // record cannot be moved by anything else the service runs.
        assertSame(
            "later start commands must leave the FIRST watcher recorded; a replacement means a " +
                "command launched another watcher of the offered surface",
            firstWatcher,
            watcherJob(service),
        )

        // And the watcher still does its job after the repeated start: a failed attempt's record
        // names the surface it was tried on, and a different surface being offered is what marks
        // that record as describing a situation that has passed.
        setFailedAttemptSurface(service, AudioRoute.BluetoothA2dpOnly)
        shadowOf(audioManager).setOutputDevices(emptyList())
        shadowOf(audioManager).setInputDevices(emptyList())
        routeMonitor.onDevicesChanged()
        awaitOfferedSurface(AudioRoute.Unknown)

        // The surface change above was published only AFTER awaitWatcherRegistered confirmed the
        // subscription, so the watcher receives it as a transition, not as its initial value. Its
        // continuation is dispatched on Main: idle the queue while waiting on the RECORD, with a
        // bound that THROWS rather than reporting a stale reading as the answer.
        awaitFailedAttemptSurfaceLeft(service)
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

    /** The watcher job this service recorded, read from its own state. */
    private fun watcherJob(service: VoiceControlService): kotlinx.coroutines.Job? =
        service.javaClass.getDeclaredField("offeredSurfaceJob")
            .apply { isAccessible = true }.get(service) as kotlinx.coroutines.Job?

    /**
     * Drain the main queue until it stays empty across consecutive idles, so work the later start
     * commands posted has run. Ends on the queue's state, never on a duration; the bound throws.
     */
    private fun drainMainQueue() {
        val deadline = System.currentTimeMillis() + 30_000
        var emptyReads = 0
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            emptyReads = if (shadowOf(Looper.getMainLooper()).isIdle) emptyReads + 1 else 0
            if (emptyReads >= 3) return
            Thread.sleep(10)
        }
        throw AssertionError("the main queue never drained after the later start commands")
    }

    /**
     * Wait for the watcher's record that the offered surface left the failed attempt's, idling the
     * main queue so the watcher's Main-dispatched continuation runs. The bound THROWS: reaching it
     * is a failure, not a stale reading reported as the answer.
     */
    private fun awaitFailedAttemptSurfaceLeft(service: VoiceControlService) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (failedAttemptSurfaceLeft(service)) return
            Thread.sleep(10)
        }
        throw AssertionError(
            "after a repeated start the watcher never noticed the offered surface leaving the " +
                "one a failed attempt was recorded against",
        )
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

    private fun failedAttemptSurfaceLeft(service: VoiceControlService): Boolean = service.javaClass.getDeclaredField("failedAttemptSurfaceLeft")
        .apply { isAccessible = true }.getBoolean(service)
}
