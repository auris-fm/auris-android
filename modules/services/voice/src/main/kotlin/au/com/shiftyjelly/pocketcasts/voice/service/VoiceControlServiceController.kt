package au.com.shiftyjelly.pocketcasts.voice.service

import android.content.Context
import android.content.Intent
import au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGateState
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

    fun start() {
        Timber.i("VoiceControlServiceController: starting service")
        context.startForegroundService(Intent(context, VoiceControlService::class.java))
    }

    fun stop() {
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
            when (gateState) {
                is VoiceControlGateState.Allowed -> {
                    if (foreground) {
                        start()
                    } else {
                        Timber.i("VoiceControlServiceController: gate allowed but app backgrounded, deferring")
                    }
                }
                is VoiceControlGateState.Blocked -> stop()
            }
        }.launchIn(scope)
    }

    fun stopMonitoring() {
        isMonitoring = false
        scope.cancel()
    }
}
