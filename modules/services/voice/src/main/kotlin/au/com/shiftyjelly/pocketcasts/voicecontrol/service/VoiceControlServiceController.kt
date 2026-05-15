package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Context
import android.content.Intent
import au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentState
import au.com.shiftyjelly.pocketcasts.voicecontrol.ui.EnrollmentActivity
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
    private val enrollmentManager: VoiceEnrollmentManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isMonitoring = false
    private var hasPromptedEnrollment = false

    fun start() {
        // Prevent loop: if not enrolled, go directly to enrollment instead of
        // starting the service (which would stop itself and re-trigger us).
        if (enrollmentManager.state.value !is VoiceEnrollmentState.Enrolled) {
            Timber.i("VoiceControlServiceController: not enrolled, launching enrollment activity")
            val intent = Intent(context, EnrollmentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            return
        }
        Timber.i("VoiceControlServiceController: starting service")
        context.startForegroundService(Intent(context, VoiceControlService::class.java))
    }

    /**
     * Launches the enrollment activity if the user is not yet enrolled.
     * Call this independently of the gate so users can enroll without
     * needing active playback or a headset.
     */
    fun promptEnrollmentIfNeeded() {
        if (enrollmentManager.state.value !is VoiceEnrollmentState.Enrolled) {
            Timber.i("VoiceControlServiceController: not enrolled, prompting enrollment")
            val intent = Intent(context, EnrollmentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    fun stop() {
        Timber.i("VoiceControlServiceController: stopping service")
        context.stopService(Intent(context, VoiceControlService::class.java))
    }

    fun startMonitoring(gate: VoiceControlGate) {
        if (isMonitoring) return
        isMonitoring = true
        Timber.i("VoiceControlServiceController: starting gate monitoring")

        combine(gate.state, appLifecycleProvider.isInForeground, enrollmentManager.state) { gateState, foreground, _ ->
            gateState to foreground
        }.onEach { (gateState, foreground) ->
            // Prompt enrollment once when the app first becomes visible.
            // This runs independently of the gate so the user can enroll
            // without needing active playback or a headset.
            if (foreground && !hasPromptedEnrollment) {
                hasPromptedEnrollment = true
                if (enrollmentManager.state.value !is VoiceEnrollmentState.Enrolled) {
                    promptEnrollmentIfNeeded()
                    return@onEach
                }
            }

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
