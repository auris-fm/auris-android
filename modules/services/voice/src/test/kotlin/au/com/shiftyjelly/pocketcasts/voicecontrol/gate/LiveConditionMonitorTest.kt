@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.gate

import android.app.Application
import android.os.PowerManager
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The monitor bridges Android system callbacks into the gate's transient conditions. It is started
 * once for the process (see PocketCastsApplication), because these conditions decide whether the
 * service may run at all — registering them per service lifetime meant a condition blocked when a
 * lifetime ended could never clear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveConditionMonitorTest {
    private lateinit var context: Application
    private val castManager = mock<CastManager>()
    private val notCastingCondition = mock<NotCastingCondition>()
    private val castFlow = MutableStateFlow(false)
    private lateinit var monitor: LiveConditionMonitor

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        whenever(castManager.isConnectedFlow).thenReturn(castFlow)
        monitor = LiveConditionMonitor(
            context = context,
            castManager = castManager,
            notOnCallCondition = mock<NotOnCallCondition>(),
            batteryOkCondition = mock<BatteryOkCondition>(),
            notCastingCondition = notCastingCondition,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start registers for power save changes`() {
        monitor.start()

        assertTrue(
            shadowOf(context).registeredReceivers.any { holder ->
                holder.intentFilter.hasAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
        )
    }

    @Test
    fun `start bridges the cast state into its condition`() {
        monitor.start()

        // Subscribing to the StateFlow delivers its current value, so clear between phases to
        // assert that changes are delivered rather than counting the initial one.
        castFlow.value = true
        verify(notCastingCondition).updateCasting(true)
        clearInvocations(notCastingCondition)

        castFlow.value = false
        verify(notCastingCondition).updateCasting(false)
    }
}
