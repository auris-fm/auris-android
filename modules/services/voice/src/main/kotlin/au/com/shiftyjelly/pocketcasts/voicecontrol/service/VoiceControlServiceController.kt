package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

@Singleton
class VoiceControlServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLifecycleProvider: AppLifecycleProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isMonitoring = false
    private var serviceStarted = false
    private var startInFlight = false

    /**
     * Set when the user stops listening from the notification. Their action stands until the gate
     * is re-armed (the feature switched off and back on); otherwise the next foreground would
     * quietly undo it and bring the microphone back.
     */
    private var stoppedByUser = false

    /**
     * True only while the service itself has confirmed it is running. A start can be refused by
     * Android (a microphone foreground service started from an ineligible app state throws from
     * `startForeground`), so the request is not treated as success.
     */
    val isServiceRunning: Boolean get() = serviceStarted

    fun start() {
        if (serviceStarted || startInFlight) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("VoiceControlServiceController: RECORD_AUDIO not granted, requesting")
            context.startActivity(
                Intent(context, au.com.shiftyjelly.pocketcasts.voicecontrol.ui.PermissionRequestActivity::class.java)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
            return
        }
        Timber.i("VoiceControlServiceController: starting service")
        // Deliberately not marking it started: the service confirms below once it is genuinely
        // running. Believing a refused start had succeeded is what left voice recognition dead
        // for the rest of the process, because the only retry condition is !serviceStarted.
        // In flight, though, so that a second gate or foreground emission before the service
        // reports in cannot start it twice.
        startInFlight = true
        try {
            context.startForegroundService(Intent(context, VoiceControlService::class.java))
        } catch (e: Exception) {
            // Including the system refusing a foreground start outright; not in flight, so the
            // next foreground can try again.
            startInFlight = false
            Timber.w(e, "VoiceControlServiceController: could not start the service")
        }
    }

    /** Called by the service once it is really running and holding the microphone. */
    fun onServiceStarted() {
        startInFlight = false
        if (serviceStarted) return
        serviceStarted = true
        Timber.i("VoiceControlServiceController: service confirmed running")
    }

    /**
     * Called whenever the service stops, for any reason — its own decision, a refusal, or the
     * system killing it. Clearing the flag here is what lets the next foreground start it again.
     */
    fun onServiceStopped() {
        startInFlight = false
        if (!serviceStarted) return
        serviceStarted = false
        Timber.i("VoiceControlServiceController: service stopped")
    }

    /** The user stopped listening from the notification: do not restart until the gate re-arms. */
    fun onServiceStoppedByUser() {
        startInFlight = false
        serviceStarted = false
        stoppedByUser = true
        Timber.i("VoiceControlServiceController: stopped by the user; will not restart until re-armed")
    }

    fun stop() {
        serviceStarted = false
        Timber.i("VoiceControlServiceController: stopping service")
        context.stopService(Intent(context, VoiceControlService::class.java))
    }

    fun startMonitoring(gate: VoiceControlGate) {
        if (isMonitoring) return
        isMonitoring = true
        Timber.i("VoiceControlServiceController: starting gate monitoring")

        combine(gate.state, appLifecycleProvider.isInForeground) { gateState, foreground ->
            gateState to foreground
        }.onEach { (gateState, foreground) ->
            // Re-arm only on the user's own switch. The gate also goes disallowed for transient
            // reasons — the app backgrounded, an incoming call, another app playing — and keying
            // the re-arm to `allowed` meant an ordinary background trip cleared the suppression
            // and brought the microphone back after the user had turned listening off.
            if (gateState.rules[EnabledByUserCondition.ID] is VoiceControlRuleState.Blocked) {
                stoppedByUser = false
            }
            // Foreground is not decoration here. A microphone foreground service started while the
            // app is not in an eligible foreground state is refused by Android: startForeground
            // throws, the service stops itself, and nothing asks again while it believes the
            // service is running. Starting only when the app is genuinely foreground is what
            // keeps this out of that state.
            if (gateState.allowed && foreground && !serviceStarted && !startInFlight && !stoppedByUser) {
                Timber.i("VoiceControlServiceController: gate allowed, starting service")
                start()
            }
        }.launchIn(scope)
    }

    fun stopMonitoring() {
        isMonitoring = false
        scope.cancel()
    }
}
