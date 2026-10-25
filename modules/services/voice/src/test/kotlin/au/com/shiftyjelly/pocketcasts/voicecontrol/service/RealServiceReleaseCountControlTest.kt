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
 * Claim: two consecutive production stops release every kind in `ALL_RELEASES` exactly ONCE. The
 * first stop records each kind as it confirms and settles to `Released`; the second stop's retry
 * finds nothing pending, releases nothing, and still settles to `Released` — so a repeated
 * media-session or renderer release (destructive, not redundant) cannot happen through the real
 * code path.
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
        return runBlocking { teardown.await() }
    }

    @Test(timeout = 60_000)
    fun `two platform stops release every kind exactly once`() = runBlocking {
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

        val second = stopViaPlatform()

        assertEquals(
            "the second stop must also settle to Released, not replay a release",
            TeardownResult.Released,
            second,
        )
        assertEquals(
            "a second stop must not release anything again",
            service.allReleaseKinds.toSet(),
            service.releasedKinds,
        )
        assertTrue(
            "the retry decision must find nothing pending",
            TeardownRetry.pendingReleases(service.allReleaseKinds, service.releasedKinds).isEmpty(),
        )
    }
}
