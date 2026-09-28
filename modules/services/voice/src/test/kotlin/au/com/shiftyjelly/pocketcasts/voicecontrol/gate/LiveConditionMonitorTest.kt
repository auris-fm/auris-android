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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The service can stop and start more than once in a process lifetime now that it retries after a
 * refusal or a kill. If a later start is ignored, the call and power-save conditions stay frozen at
 * whatever they were when the earlier lifetime ended — so a lifetime that ended during a call or in
 * power save leaves voice control blocked for the rest of the process.
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

    private fun powerSaveReceiverRegistered(): Boolean = shadowOf(context).registeredReceivers.any { holder ->
        holder.intentFilter.hasAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
    }

    @Test
    fun `a stopped monitor no longer follows the cast state`() {
        monitor.start()
        castFlow.value = true
        verify(notCastingCondition).updateCasting(true)

        monitor.stop()
        clearInvocations(notCastingCondition)
        castFlow.value = false

        verify(
            notCastingCondition,
            never(),
        ).updateCasting(false)
    }

    @Test
    fun `a later lifetime registers the conditions again`() {
        monitor.start()
        assertTrue(powerSaveReceiverRegistered())

        monitor.stop()
        assertFalse(powerSaveReceiverRegistered())

        // A second lifetime: this is the start that used to return early.
        monitor.start()

        assertTrue(
            "a start after a stop must re-register, or the conditions stay frozen at their last value",
            powerSaveReceiverRegistered(),
        )
    }
}
