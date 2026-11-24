package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Intent
import android.os.Looper
import android.provider.Settings
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
import org.junit.Assert.assertNull
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The REAL service consults the ONE process-wide session gate before it touches a shared resource.
 *
 * The engine, the ASR backends and the intent router are singletons, while every guard inside the
 * service belongs to a single instance: its stop-in-flight job, its start state, its shutdown
 * barrier. A second instance therefore passes all of its own guards while the first is still
 * releasing those shared resources, and would start an engine whose previous session has not
 * finished or use a backend whose native recognizer was already freed.
 *
 * HOW THIS IS OBSERVED, and why it is shaped this way. A session held by someone else is exactly
 * what a previous instance's unfinished teardown looks like from here, so the control holds the
 * injected singleton itself and starts the real service through the real platform path. A service
 * that consults the gate cannot proceed: it leaves no pipeline running. A service that skips the
 * acquire proceeds regardless, which is the mutant this must fail against.
 *
 * BOUNDARY, stated precisely: these services never reach a running capture. Model readiness stops a
 * start in this environment (`ensureModelsReady` finds no usable backend and stops the service), so
 * "the second instance's pipeline is refused" is shown by the absence of a started pipeline, not by
 * a live capture being blocked. The gate's own rules — bounded wait, stale-session safety, release
 * ordering — are controlled in `VoiceSessionGateControlTest`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [35])
@Suppress("DEPRECATION")
class RealServiceSessionAdmissionControlTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    /** Arrives through generated injection, so it is the same instance the services receive. */
    @Inject lateinit var sessionGate: VoiceSessionGate

    @Inject lateinit var routeMonitor: au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor

    // The monitor's debounced-read scope, test-lifetime: cancelled in teardown so a pending read
    // cannot fire detached after this class ends.
    @Inject
    @au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteMonitorScope
    lateinit var routeMonitorScope: kotlinx.coroutines.CoroutineScope

    private val controllers = mutableListOf<ServiceController<VoiceControlService>>()

    @Before
    fun setUp() {
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.System.ANDROID_ID,
            "testdev1",
        )
        // Both permissions are checked before the session is taken: the controller requires
        // RECORD_AUDIO, and the service itself also requires FOREGROUND_SERVICE_MICROPHONE. Without
        // them the start is refused long before the gate, and this control would prove nothing.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        )
        hiltRule.inject()
    }

    @After
    fun dispose() {
        routeMonitorScope.cancel()
        controllers.forEach { controller ->
            runCatching { controller.destroy() }
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
        }
        controllers.clear()
    }

    private fun newService(): VoiceControlService {
        val controller = Robolectric.buildService(VoiceControlService::class.java).create()
        controllers += controller
        return controller.get()
    }

    /**
     * The session this instance claimed, read from its own state rather than a test-only accessor:
     * adding product API to make a test possible would test the accessor, not the behaviour.
     *
     * This is the observable that distinguishes a consulting service from one that skips the gate.
     * Engine-started and wired-backend are NOT usable here: model readiness stops the start in this
     * environment either way, so both stay unset even when the gate is bypassed entirely.
     *
     * Put the service in the state a started instance is in: holding a session.
     */
    private fun setSession(service: VoiceControlService, session: VoiceSessionGate.Session) {
        service.javaClass.getDeclaredField("voiceSession")
            .apply { isAccessible = true }.set(service, session)
    }

    /** Whether the start got as far as accepting the engine. */
    private fun engineStarted(service: VoiceControlService): Boolean {
        val state = service.javaClass.getDeclaredField("engineStart")
            .apply { isAccessible = true }.get(service)
        return state.javaClass.getDeclaredField("isStarted")
            .apply { isAccessible = true }.getBoolean(state)
    }

    private fun takenSession(service: VoiceControlService): Any? = service.javaClass.getDeclaredField("voiceSession").apply { isAccessible = true }.get(service)

    /** Await the current teardown, pumping the Main looper the releases run on. */
    private suspend fun awaitTeardown(service: VoiceControlService): TeardownResult {
        val teardown = service.currentTeardown
        assertNotNull("a platform stop must start a teardown", teardown)
        // Above the engine's own worst-case join: it retries up to eight rounds of five seconds.
        val deadline = System.currentTimeMillis() + 60_000
        while (!teardown!!.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(20)
        }
        // The bound is on the COROUTINE, and only after the pump has actually seen completion: a
        // fixed five seconds here would expire while the engine's own join rounds were still running
        // and report a timeout instead of the verdict under test.
        check(teardown.isCompleted) { "teardown did not finish within the pump bound" }
        return withTimeout(5_000) { teardown.await() }
    }

    private fun pump(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test(timeout = 90_000)
    fun `every instance receives the same process-wide gate`() {
        val first = newService()
        val second = newService()

        assertSame(
            "the gate must be process-wide: two instances holding different gates would each " +
                "admit themselves and nothing would serialise them",
            first.voiceSessionGate,
            second.voiceSessionGate,
        )
        assertSame(
            "the services must receive the injected singleton, not a private copy",
            sessionGate,
            first.voiceSessionGate,
        )
    }

    @Test(timeout = 90_000)
    fun `a start does not open a pipeline while another holder owns the session`() = runBlocking {
        // Exactly what a previous instance's unfinished release looks like from a new instance.
        val held = sessionGate.acquire(timeoutMs = 1_000)
        assertTrue("precondition: the session is held", held is VoiceSessionGate.Admission.Acquired)
        val session = (held as VoiceSessionGate.Admission.Acquired).session
        sessionGate.beginRelease(session)

        try {
            val service = newService()
            service.onStartCommand(Intent(), 0, 1)
            // Longer than the service's own acquire bound, so a consulting service has already
            // given up by the time this checks.
            pump(12_000)

            assertNull(
                "a start must not take a session while another holder owns it: the shared engine " +
                    "and backend may still be in release. A service that skips the gate claims one " +
                    "anyway, which is how a second instance starts over half-released resources.",
                takenSession(service),
            )
            assertTrue(
                "the refused start must leave the existing holder untouched",
                sessionGate.acquire(timeoutMs = 100) is VoiceSessionGate.Admission.TimedOut,
            )
        } finally {
            sessionGate.finishRelease(session)
        }
    }

    /**
     * Turning voice off while it is WAITING to start must not strand the session.
     *
     * The teardown reads the session before it cancels the scope, but a start still inside its
     * admission runs on IO, separately. If the previous holder's release finishes in that window the
     * waiting start claims the session after the teardown has already read null -- so neither
     * beginRelease nor finishRelease ever runs for it, and every later start in the process waits the
     * full bound and is refused. Voice would be dead until the process restarts.
     */
    @Test(timeout = 90_000)
    fun `stopping a start that is still waiting leaves the session available`() = runBlocking {
        // Another holder owns the session, so this service's start parks in its admission.
        val held = sessionGate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        sessionGate.beginRelease(held.session)

        val service = newService()
        service.onStartCommand(Intent(), 0, 1)
        pump(500) // let the start reach its wait

        // The user turns voice off while it is waiting.
        service.onStartCommand(Intent(VoiceControlService.STOP_ACTION), 0, 2)
        pump(500)

        // Only now does the previous holder finish. A start still parked could claim the session
        // here, after the teardown has read it.
        sessionGate.finishRelease(held.session)
        pump(2_000)

        val next = sessionGate.acquire(timeoutMs = 1_000)
        assertTrue(
            "a stop during a waiting start must leave the session available; got $next",
            next is VoiceSessionGate.Admission.Acquired,
        )
        (next as? VoiceSessionGate.Admission.Acquired)?.let { sessionGate.release(it.session) }
        Unit
    }

    /**
     * A HELD teardown keeps the session. Held means the shared objects may still be in use, so
     * freeing the gate there would admit a new start over them -- the very overlap the gate exists
     * to prevent. The session is given up only when a retry reaches Released.
     *
     * The hold here is real: the recognizer release records its effect and then throws, which the
     * service classifies as an ambiguous release and never replays.
     */
    @Test(timeout = 90_000)
    fun `a held teardown keeps the session so no new start runs over unreleased resources`() = runBlocking {
        RecognizerReleaseProbe.reset()
        RecognizerReleaseProbe.throwAfterEffect = true
        try {
            val service = newService()
            // Claim the session as a started instance does, so the teardown has one to give up.
            val claimed = sessionGate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
            setSession(service, claimed.session)
            service.onStartCommand(Intent(VoiceControlService.STOP_ACTION), 0, 1)
            val teardown = service.currentTeardown
            assertNotNull("a platform stop must start a teardown", teardown)
            val deadline = System.currentTimeMillis() + 30_000
            while (!teardown!!.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(20)
            }
            val result = withTimeout(5_000) { teardown.await() }
            assertTrue("precondition: the teardown must be Held, got $result", result is TeardownResult.Held)

            val next = sessionGate.acquire(timeoutMs = 300)
            assertTrue(
                "a held teardown must keep the session: the shared objects may still be in use, " +
                    "so admitting a start now would run over them; got $next",
                next is VoiceSessionGate.Admission.TimedOut,
            )
        } finally {
            RecognizerReleaseProbe.throwAfterEffect = false
        }
        Unit
    }

    /**
     * The refused-start pair: a start that cannot be admitted leaves voice off, and a later start is
     * admitted once the holder finishes. Both halves matter -- refusing without ever recovering would
     * be a different bug with the same symptom.
     */
    @Test(timeout = 90_000)
    fun `a refused start leaves voice off and a later start is admitted once the holder finishes`() = runBlocking {
        val held = sessionGate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        sessionGate.beginRelease(held.session)

        val refused = newService()
        refused.onStartCommand(Intent(), 0, 1)
        // Past the service's own admission bound, so the refusal has certainly been reached.
        pump(12_000)
        assertNull(
            "a refused start must not claim a session",
            takenSession(refused),
        )
        assertFalse(
            "a refused start must leave voice off rather than running a pipeline over resources " +
                "whose release never finished",
            engineStarted(refused),
        )

        // The holder finishes; a new start must now be admitted.
        sessionGate.finishRelease(held.session)
        val next = sessionGate.acquire(timeoutMs = 1_000)
        assertTrue(
            "once the holder finishes, a start must be admitted again; got $next",
            next is VoiceSessionGate.Admission.Acquired,
        )
        (next as? VoiceSessionGate.Admission.Acquired)?.let { sessionGate.release(it.session) }
        Unit
    }

    /**
     * The process-wide objects belong to the holder. A second instance is refused while the first
     * holds the session; when that refused instance stops, it must leave the recognizer loaded, or
     * it would pull the shared recognizer out from under the instance still using it.
     */
    @Test(timeout = 60_000)
    fun `a refused instance's teardown leaves the recognizer loaded for the holder`() {
        val holder = newService()
        val refused = newService()
        try {
            runBlocking {
                holder.onStartCommand(Intent(), 0, 1)
                pump(2500)
                val taken = takenSession(holder)
                assertNotNull("the holder must own a session", taken)

                refused.onStartCommand(Intent(), 0, 2)
                pump(1500)
                assertNull("a refused instance must own no session", takenSession(refused))

                val callsBefore = RecognizerReleaseProbe.calls.get()
                // The refused instance stops. It owns nothing shared, so it must release nothing shared.
                refused.onDestroy()
                pump(2000)
                assertEquals(
                    "a refused instance's stop must not release the holder's recognizer",
                    callsBefore,
                    RecognizerReleaseProbe.calls.get(),
                )
                assertTrue(
                    "the holder must still own the session after the refused instance stopped",
                    holder.voiceSessionGate.holds(taken as VoiceSessionGate.Session),
                )
            }
        } finally {
            runCatching { holder.onDestroy() }
            runCatching { refused.onDestroy() }
            pump(1000)
        }
    }
}
