package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Intent
import android.os.Looper
import android.provider.Settings
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
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
        routeMonitor.cancelDebouncedRouteReadForTest()
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
     */
    private fun takenSession(service: VoiceControlService): Any? =
        service.javaClass.getDeclaredField("voiceSession").apply { isAccessible = true }.get(service)

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
}