package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val provider = AppLifecycleProviderImpl()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = ForegroundStateMonitor(provider, scope, signal)
        // The owner is not used by the provider's callbacks; it only satisfies the observer signature.
        val owner = monitorLifecycleOwner()

        // The app is in the foreground before anything else: the monitor's edge detector needs to have
        // SEEN a foreground state, or a later loss is not a transition and closure never fires.
        provider.onResume(owner = owner)
        delay(300)

        // A wake opens the window, the way a user's spoken wake does.
        signal.onWakeWordDetected()
        assertTrue("the wake must open the window", signal.isActive.value)
        assertFalse("an open window is not a privacy closure", signal.isClosedByPrivacy())
        val closuresBefore = signal.privacyClosureCount()
        Log.i(tag, "window open: active=${signal.isActive.value} closures=$closuresBefore")

        // The real lifecycle callback Android delivers when the app leaves the foreground.
        provider.onPause(owner = owner)
        delay(500)

        val closed = signal.isClosedByPrivacy()
        Log.i(tag, "after foreground loss: closedByPrivacy=$closed active=${signal.isActive.value} closures=${signal.privacyClosureCount()}")
        assertTrue("losing the foreground must close the window", closed)
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals("exactly one closure", closuresBefore + 1, signal.privacyClosureCount())

        // Returning to the foreground must not quietly reopen the window the privacy event ended: only
        // a new wake begins another, so grace must stay closed across the return.
        provider.onResume(owner = owner)
        delay(500)
        val reopened = signal.isActive.value
        Log.i(tag, "after foreground return: active=$reopened closedByPrivacy=${signal.isClosedByPrivacy()}")
        assertFalse("returning to the foreground must not restore the closed window", reopened)
        assertTrue("the closure must still stand after the return", signal.isClosedByPrivacy())
    }

    private fun monitorLifecycleOwner(): androidx.lifecycle.LifecycleOwner = object : androidx.lifecycle.LifecycleOwner {
        override val lifecycle: androidx.lifecycle.Lifecycle
            get() = androidx.lifecycle.LifecycleRegistry(this)
    }
}
