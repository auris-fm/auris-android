package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exposes the PRODUCTION privacy-chain singletons from the real app graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ForegroundPrivacyEntryPoint {
    fun foregroundStateMonitor(): ForegroundStateMonitor
    fun gracePeriodSignal(): GracePeriodSignal
}

/**
 * Device observation of the PRODUCTION foreground privacy chain: ProcessLifecycleOwner -> the
 * provider AppLifecycleObserver.setup() registered -> ForegroundStateMonitor -> GracePeriodSignal.
 *
 * Every object here comes from the real application graph (EntryPointAccessors), so NO observer is
 * test-installed: if setup() stopped registering the provider on ProcessLifecycleOwner, the
 * production flow never flips and the closure assertion below fails. The transition itself is
 * produced by the real dispatch path -- UiDevice.pressHome() moves the process background and a
 * relaunch brings it back, so Android's main thread drives ProcessLifecycleOwner exactly as in
 * production. Not executed on a device yet (device locked); the mutation claim is by construction,
 * not by execution.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundPrivacyDeviceTest {

    private val tag = "ForegroundPrivacy"

    @Test
    fun losingTheForegroundClosesGraceAndReturningDoesNotRestoreIt() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, ForegroundPrivacyEntryPoint::class.java)
        val signal = entry.gracePeriodSignal()
        // Touching the monitor binds the production chain; its init collects the provider flow.
        val monitor = entry.foregroundStateMonitor()

        val device = UiDevice.getInstance(instrumentation)
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Foreground first: the edge detector must have SEEN a foreground state, or a later loss is
        // not a transition and closure never fires.
        ctx.startActivity(launchIntent)
        device.waitForIdle()
        delay(1_500)
        assertTrue("the app must be foreground before the wake (production flow did not report it)", monitor.isInForeground.value)

        // A wake opens the window, the way a user's spoken wake does.
        signal.onWakeWordDetected()
        assertTrue("the wake must open the window", signal.isActive.value)
        assertFalse("an open window is not a privacy closure", signal.isClosedByPrivacy())
        val closuresBefore = signal.privacyClosureCount()
        Log.i(tag, "window open: active=${signal.isActive.value} closures=$closuresBefore")

        // The REAL foreground loss: pressHome, dispatched by Android to the registered provider.
        device.pressHome()
        delay(1_500)

        val closed = signal.isClosedByPrivacy()
        Log.i(tag, "after foreground loss: closedByPrivacy=$closed active=${signal.isActive.value} closures=${signal.privacyClosureCount()}")
        assertTrue("losing the foreground must close the window", closed)
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals("exactly one closure", closuresBefore + 1, signal.privacyClosureCount())

        // Returning to the foreground must not quietly reopen the window the privacy event ended.
        ctx.startActivity(launchIntent)
        device.waitForIdle()
        delay(1_500)
        assertFalse("returning to the foreground must not restore the closed window", signal.isActive.value)
        assertTrue("the closure must still stand after the return", signal.isClosedByPrivacy())
    }
}
