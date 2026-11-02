package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Intent
import android.os.Looper
import android.provider.Settings
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The RELEASE-COUNT control the reviewer asked for, on the REAL service, through the REAL platform
 * stop path — now reachable because the two EXTERNAL-NATIVE seams are replaced test-only:
 * `WakeWordNative` and `LfmInference`. Everything downstream of those seams is production: the
 * settlement classification, the Settled gate, the per-kind release record, the retry decision.
 *
 * MEASURED claim: the recognizer release is COUNTED at the injected seam — one stop makes exactly
 * one call and later stops make none. The OTHER kinds are asserted as RECORDED (the per-kind
 * durable record), which is a weaker fact than a call count: the release-count control does not
 * have counters on session/feedback/notification/foreground, so "every kind exactly once" is NOT
 * claimed for them — only that each kind is recorded released after stop one and never re-recorded.
 * A repeated media-session or renderer release therefore remains guarded by the pendingReleases
 * filter logic (unit-controlled), not counted here.
 *
 * What set-membership cannot show: a mutant that re-released the SAME kinds a second time would not
 * change `releasedKinds`. That protection is `TeardownRetry.pendingReleases` filtering by the record,
 * controlled at unit level in `TeardownRetryControlTest`; this control proves the REAL service wires
 * that filter into the real stop path (second stop Released, nothing pending), not the filter's logic.
 *
 * Boundary, stated precisely: the ASR backend seam is NOT replaced and this service never started a
 * pipeline, so `wiredBackend` is null and the "backend" release is a null-safe no-op, as it is in
 * production for an idle service. A mid-release throw with the retry that follows is controlled at
 * `AmbiguousReleaseRefusalTest` and `TeardownRetryControlTest`; this control owns the settled count.
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
        RecognizerReleaseProbe.reset()
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
        // Destroy STARTS teardown; the deferred can still be running. Leaving it running is what
        // the reviewer flagged: an asynchronous teardown from this test could land in the next
        // test's assertions. Await it here, pumping the Main looper (the releases run there), with
        // the bound on the COROUTINE, not the test thread.
        // BASELINE BEFORE destroy (reviewer fe405146): an extra release during destroy must be
        // seen, so the counter is captured before teardown starts, not after.
        val callsBefore = RecognizerReleaseProbe.calls.get()
        controller.destroy()
        val service = controller.get()
        val teardown = service.currentTeardown ?: return
        val deadline = System.currentTimeMillis() + 30_000
        while (!teardown.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(20)
        }
        runBlocking { withTimeout(5_000) { teardown.await() } }
        assertEquals(
            "cleanup destroy must not release the recognizer again",
            callsBefore,
            RecognizerReleaseProbe.calls.get(),
        )
    }

    private fun stopViaPlatform(): TeardownResult {
        val service = controller.get()
        controller
            .withIntent(Intent(VoiceControlService.STOP_ACTION))
            .startCommand(0, 1)
        // The in-flight stop is the SAME deferred the platform path receives.
        val teardown = service.currentTeardown
        assertNotNull("a platform stop must start a teardown", teardown)
        // The releases run on Dispatchers.Main; on Robolectric that is the instrumented looper,
        // which nothing pumps while this test blocks. Pump it until the teardown settles.
        val deadline = System.currentTimeMillis() + 30_000
        while (!teardown!!.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(20)
        }
        // Same bounded-await failure behavior as cleanup: a teardown that cannot settle FAILS
        // here instead of hanging on the test thread.
        return runBlocking { withTimeout(5_000) { teardown.await() } }
    }

    /**
     * CLEAN-SETTLEMENT COMPLEMENT. The recognizer release is COUNTED through the injected seam, so
     * "exactly once" is a call count, not set membership: one stop -> exactly one release call, and
     * every later stop (and the @After destroy) leaves the counter unchanged. The second stop
     * returns the already-Released deferred without entering the release path at all -- the counter
     * proves that, which a set could not.
     */
    @Test(timeout = 60_000)
    fun `two platform stops - one recognizer release call and every kind recorded`() = runBlocking {
        val first = stopViaPlatform()
        val service = controller.get()

        assertEquals(
            "the first stop must settle to Released through the real path",
            TeardownResult.Released,
            first,
        )
        assertEquals(
            "an idle service's settled teardown must release every kind in ALL_RELEASES",
            service.allReleaseKinds.toSet(),
            service.releasedKinds,
        )
        assertEquals(
            "exactly ONE recognizer release call for one stop (counted at the native seam)",
            1,
            RecognizerReleaseProbe.calls.get(),
        )

        val second = stopViaPlatform()

        assertEquals(
            "the second stop must also settle to Released, not replay a release",
            TeardownResult.Released,
            second,
        )
        assertEquals(
            "a second stop must not release anything again: the counter is the proof a set cannot give",
            1,
            RecognizerReleaseProbe.calls.get(),
        )
        assertTrue(
            "the retry decision must find nothing pending",
            TeardownRetry.pendingReleases(service.allReleaseKinds, service.releasedKinds).isEmpty(),
        )
    }

    /**
     * THROW-AFTER-EFFECT on the real path. The first stop's recognizer release records its effect
     * (the counter increments, and the router has already cleared its loaded state) and THEN
     * throws, so the teardown reports AmbiguousRelease. Round 40's policy says an ambiguous release
     * is NEVER replayed -- no recheck can prove the throw did not take effect. The counter proves
     * the policy on the real service: every later stop leaves the release count exactly 1.
     */
    @Test(timeout = 60_000)
    fun `a release that records its effect then throws is never re-run by a later stop`() = runBlocking {
        RecognizerReleaseProbe.throwAfterEffect = true

        val first = stopViaPlatform()
        val service = controller.get()

        assertTrue("the ambiguous release must hold the teardown, got $first", first is TeardownResult.Held)
        assertTrue(
            "the hold must be AmbiguousRelease specifically",
            (first as TeardownResult.Held).failure.verdict is EngineQuiescence.Verdict.AmbiguousRelease,
        )
        assertEquals(
            "the effect was recorded exactly once despite the throw",
            1,
            RecognizerReleaseProbe.calls.get(),
        )

        val second = stopViaPlatform()

        assertTrue("the retried stop must still hold, got $second", second is TeardownResult.Held)
        assertEquals(
            "AN AMBIGUOUS RELEASE IS NEVER REPLAYED: later stops must not re-run the release",
            1,
            RecognizerReleaseProbe.calls.get(),
        )
    }
}
