package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.shiftyjelly.pocketcasts.shared.AppLifecycleProviderImpl
import au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device observation of the foreground privacy path: the app losing the foreground must close the
 * grace window, and returning to the foreground must NOT silently reopen it.
 *
 * The lifecycle callbacks are the ones Android invokes on the real provider, so the transition is
 * driven through production objects rather than a stubbed provider. Physical route delivery is a
 * different observation and is not claimed here.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundPrivacyDeviceTest {

    private val tag = "ForegroundPrivacy"

    @Test
    fun losingTheForegroundClosesGraceAndReturningDoesNotRestoreIt() = runBlocking<Unit> {
        val signal = GracePeriodSignal()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val provider = au.com.shiftyjelly.pocketcasts.shared.AppLifecycleProviderImpl()

        // PRODUCTION REGISTRATION (4236563768, mutation-checked): the provider is registered the way
        // AppLifecycleObserver.setup() registers it -- added as a DefaultLifecycleObserver to the
        // PROCESS lifecycle owner the app itself uses. Events are then produced by the REAL dispatch
        // path: moving the actual process to the background (pressHome) and back (relaunch) makes
        // Android's main thread drive ProcessLifecycleOwner, which reaches the registered provider
        // exactly as in production. If AppLifecycleObserver.setup() stopped registering the provider,
        // the flow would never flip and the closure below would never fire -- the control fails.
        // (lifecycle-process is on the app classpath; the app under test has already run setup().)
        val appLifecycleOwner = ProcessLifecycleOwner.get()
        instrumentation.runOnMainSync { appLifecycleOwner.lifecycle.addObserver(provider) }
        delay(300)

        // Bring the app to the foreground first: the edge detector must have SEEN a foreground state,
        // or a later loss is not a transition and closure never fires. The launch intent is the
        // app's own entry activity.
        val launchIntent = ctx.getLaunchIntentForApp()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        ctx.startActivity(launchIntent)
        device.waitForIdle()
        delay(1_500)

        // A wake opens the window, the way a user's spoken wake does.
        signal.onWakeWordDetected()
        assertTrue("the wake must open the window", signal.isActive.value)
        assertFalse("an open window is not a privacy closure", signal.isClosedByPrivacy())
        val closuresBefore = signal.privacyClosureCount()
        Log.i(tag, "window open: active=${'$'}{signal.isActive.value} closures=${'$'}closuresBefore")

        // The REAL foreground loss: pressHome moves the process background, Android dispatches
        // onStop to the registered provider on the main thread.
        device.pressHome()
        delay(1_500)

        val closed = signal.isClosedByPrivacy()
        Log.i(tag, "after foreground loss: closedByPrivacy=${'$'}closed active=${'$'}{signal.isActive.value} closures=${'$'}{signal.privacyClosureCount()}")
        assertTrue("losing the foreground must close the window", closed)
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals("exactly one closure", closuresBefore + 1, signal.privacyClosureCount())

        // Returning to the foreground must not quietly reopen the window the privacy event ended: only
        // a new wake begins another, so grace must stay closed across the return.
        ctx.startActivity(launchIntent)
        device.waitForIdle()
        delay(1_500)
        val reopened = signal.isActive.value
        Log.i(tag, "after foreground return: active=${'$'}reopened closedByPrivacy=${'$'}{signal.isClosedByPrivacy()}")
        assertFalse("returning to the foreground must not restore the closed window", reopened)
        assertTrue("the closure must still stand after the return", signal.isClosedByPrivacy())
        instrumentation.runOnMainSync { appLifecycleOwner.lifecycle.removeObserver(provider) }
    }

    /** The app's own entry activity, launched as a new task. */
    private fun android.content.Context.getLaunchIntentForApp(): android.content.Intent = packageManager.getLaunchIntentForPackage(packageName)!!.apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
