package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Release-count control on the REAL service, through the REAL platform stop path.
 *
 * BOUNDARY, stated precisely. On the JVM the engine's ASR backend cannot stop cleanly: its native
 * runtime (onnxruntime) is absent from `java.library.path`, so the production `EngineQuiescence.settle`
 * returns `StopFailed`. This control therefore pins the two things that are genuinely reachable and
 * true here — and they are load-bearing:
 *
 * 1. FAIL-CLOSED THROUGH THE REAL PATH. A failed engine stop holds EVERY release kind: not one
 *    entry of `ALL_RELEASES` is released when the verdict is not `Settled`. This is the policy that
 *    prevents use-after-release, exercised end to end rather than through a stubbed collaborator.
 * 2. THE HOLD IS STABLE ACROSS A SECOND STOP. A retry of a held teardown re-runs the decision,
 *    releases nothing, and does not turn the hold into a release.
 *
 * What is NOT claimed: the every-kind-released-once happy path. Its counting logic is controlled at
 * `TeardownRetryControlTest` and `AmbiguousReleaseRefusalTest`; standing it up on the real service
 * requires the engine's native runtime and is instrumented-device territory, not JVM.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [35])
@Suppress("DEPRECATION")
class RealServiceReleaseCountControlTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    // Proves the component really is Hilt-built: this arrives through generated injection.
    @Inject lateinit var captureStateHolder: VoiceControlCaptureStateHolder

    private lateinit var controller: ServiceController<VoiceControlService>

    @Before
    fun setUp() {
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.System.ANDROID_ID,
            "testdev1",
        )
        hiltRule.inject()
        controller = Robolectric.buildService(VoiceControlService::class.java).create()
    }

    @After
    fun dispose() {
        controller.destroy()
    }

    private fun stopViaPlatform(): TeardownResult {
        val service = controller.get()
        controller
            .withIntent(Intent(VoiceControlService.STOP_ACTION))
            // startCommand(flags, startId) -- Robolectric's two-arg form.
            .startCommand(0, 1)
        // The in-flight stop is the SAME deferred the platform path receives.
        val teardown = service.currentTeardown
        assertNotNull("a platform stop must start a teardown", teardown)
        return runBlocking { teardown!!.await() }
    }

    // A timeout, not patience: if a mutant releases while the engine is unsettled, this test must
    // FAIL, not hang the build (M27 verification).
    @Test(timeout = 60_000)
    fun `a failed engine stop holds every release kind on the real path`() = runBlocking {
        val first = stopViaPlatform()
        val service = controller.get()

        assertTrue(
            "the JVM cannot settle the engine, so the stop must be Held, got $first",
            first is TeardownResult.Held,
        )
        assertTrue(
            "the hold must come from the stop itself, not a later ambiguity",
            (first as TeardownResult.Held).failure.verdict is EngineQuiescence.Verdict.StopFailed,
        )
        assertTrue(
            "FAIL-CLOSED: with a non-Settled verdict not one release kind may run",
            service.releasedKinds.isEmpty(),
        )

        val second = stopViaPlatform()

        assertTrue("the retried stop must still be Held, got $second", second is TeardownResult.Held)
        assertTrue(
            "a retry of a held teardown must not release anything either",
            service.releasedKinds.isEmpty(),
        )
        assertTrue(
            "the retry decision must find nothing released, so nothing can be double-released",
            TeardownRetry.pendingReleases(service.allReleaseKinds, service.releasedKinds)
                .size == service.allReleaseKinds.size,
        )
    }
}
