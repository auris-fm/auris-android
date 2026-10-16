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
        // 4236563768: the transition is driven through the REGISTERED observer, exactly as Android
        // dispatches it. The provider is added to the process lifecycle owner's registry, and the
        // lifecycle events are delivered through that registry -- the same path ProcessLifecycleOwner's
        // main-thread bookkeeping uses -- not by calling the provider's methods directly. The app's
        // own production registration uses this same owner, so the observer here sits where the
        // shipped one sits.
        // (ProcessLifecycleOwner itself is this pattern: a LifecycleOwner whose registry dispatches
        // events to its registered DefaultLifecycleObservers; lifecycle-process is not on the
        // androidTest classpath, so the same registry mechanism is exercised here on a local owner.)
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle: androidx.lifecycle.Lifecycle =
                androidx.lifecycle.LifecycleRegistry(this)
        }
        val registry = androidx.lifecycle.LifecycleRegistry(owner)
        registry.addObserver(provider)

        // The app is in the foreground before anything else: the monitor's edge detector needs to have
        // SEEN a foreground state, or a later loss is not a transition and closure never fires.
        registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        delay(300)

        // A wake opens the window, the way a user's spoken wake does.
        signal.onWakeWordDetected()
        assertTrue("the wake must open the window", signal.isActive.value)
        assertFalse("an open window is not a privacy closure", signal.isClosedByPrivacy())
        val closuresBefore = signal.privacyClosureCount()
        Log.i(tag, "window open: active=${signal.isActive.value} closures=$closuresBefore")

        // The real lifecycle dispatch: ON_PAUSE through the registry reaches the registered observer.
        registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        delay(500)

        val closed = signal.isClosedByPrivacy()
        Log.i(tag, "after foreground loss: closedByPrivacy=$closed active=${signal.isActive.value} closures=${signal.privacyClosureCount()}")
        assertTrue("losing the foreground must close the window", closed)
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals("exactly one closure", closuresBefore + 1, signal.privacyClosureCount())

        // Returning to the foreground must not quietly reopen the window the privacy event ended: only
        // a new wake begins another, so grace must stay closed across the return.
        registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        delay(500)
        val reopened = signal.isActive.value
        Log.i(tag, "after foreground return: active=$reopened closedByPrivacy=${signal.isClosedByPrivacy()}")
        assertFalse("returning to the foreground must not restore the closed window", reopened)
        assertTrue("the closure must still stand after the return", signal.isClosedByPrivacy())
    }
}
