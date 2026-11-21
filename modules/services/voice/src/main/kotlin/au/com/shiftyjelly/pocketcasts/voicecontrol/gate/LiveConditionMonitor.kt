package au.com.shiftyjelly.pocketcasts.voicecontrol.gate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Bridges Android system callbacks (phone state, power save mode, cast state)
 * into the mutable transient conflict conditions so they stay current.
 */
@Singleton
class LiveConditionMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val castManager: CastManager,
    private val notOnCallCondition: NotOnCallCondition,
    private val batteryOkCondition: BatteryOkCondition,
    private val notCastingCondition: NotCastingCondition,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private var started = false

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isPowerSave = pm?.isPowerSaveMode == true
            Timber.i("Power save mode changed: %s", isPowerSave)
            batteryOkCondition.updatePowerSaveMode(isPowerSave)
        }
    }

    // Call detection reads AudioManager's audio mode (no permission needed). The old
    // TelephonyManager listener needed READ_PHONE_STATE, which the app does not hold, so it
    // failed with "permission not granted" and the gate defaulted to Allowed during a call.
    private val callModeWatcher = AudioModeCallWatcher(
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        callbackExecutor = callbackExecutor,
        onInCall = { inCall -> notOnCallCondition.updateInCall(inCall) },
    )

    fun start() {
        if (started) return
        started = true

        // Phone/VoIP call state, via AudioManager's audio mode
        callModeWatcher.start()

        // Power save mode
        context.registerReceiver(
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )

        // Cast state. Started once for the process (see PocketCastsApplication), so this collector
        // has the process's lifetime and nothing accumulates.
        castManager.isConnectedFlow
            .onEach { isCasting -> notCastingCondition.updateCasting(isCasting) }
            .launchIn(scope)
    }
}
