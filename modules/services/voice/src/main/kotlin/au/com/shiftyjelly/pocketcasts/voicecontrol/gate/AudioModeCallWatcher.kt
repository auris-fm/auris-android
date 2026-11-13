package au.com.shiftyjelly.pocketcasts.voicecontrol.gate

import android.media.AudioManager
import android.os.Build
import java.util.concurrent.Executor
import timber.log.Timber

/**
 * Detects phone and VoIP calls from [AudioManager]'s audio mode, feeding the `NotOnCall`
 * gate condition. This replaces the `TelephonyManager` call-state listener, which needed
 * `READ_PHONE_STATE` — a permission the app does not hold — so it failed with
 * "permission not granted" and the gate defaulted to Allowed even during a call.
 * The audio-mode listener needs no permission.
 *
 * Note the interplay with [au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition]:
 * route acquisition never writes the audio mode; that SCO establishment leaves it out of the
 * call modes is verified on device.
 */
class AudioModeCallWatcher(
    private val audioManager: AudioManager,
    private val callbackExecutor: Executor,
    private val onInCall: (Boolean) -> Unit,
) {
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val inCall = isCall(mode)
        Timber.i("Audio mode changed: mode=%d inCall=%s", mode, inCall)
        onInCall(inCall)
    }

    fun start() {
        // Register first, then sample the current mode through the same executor: sampling before
        // registration leaves a window where a call starting is dropped and the gate stays Allowed
        // for the whole call.
        audioManager.addOnModeChangedListener(callbackExecutor, modeListener)
        callbackExecutor.execute {
            // Initial posture, in case a call is already in progress. MODE_IN_CALL covers cellular
            // calls, MODE_IN_COMMUNICATION covers VoIP (Telecom/ConnectionService-routed calls set
            // one of the two while the call is active).
            val initialMode = audioManager.mode
            val inCall = isCall(initialMode)
            Timber.i("Audio mode initial: mode=%d inCall=%s", initialMode, inCall)
            onInCall(inCall)
        }
    }

    companion object {
        /** Every mode in which a call owns the phone's audio. Ringing is not a call. */
        fun isCall(mode: Int): Boolean =
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT))
    }
}
