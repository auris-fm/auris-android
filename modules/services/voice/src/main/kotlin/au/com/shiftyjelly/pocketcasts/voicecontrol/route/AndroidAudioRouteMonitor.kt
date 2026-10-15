package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class AndroidAudioRouteMonitor @Inject constructor(
    @ApplicationContext context: Context,
    private val gracePeriodSignal: GracePeriodSignal,
) : AudioRouteMonitor {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableRoute = MutableStateFlow(readRoute())
    private val scope = CoroutineScope(Dispatchers.Default)
    private var debounceJob: Job? = null

    // Bluetooth devices are enumerated incrementally — output first, then input.
    // A 500ms debounce prevents transient Headset(hasMicrophone=false) → NoMic
    // from killing the engine during the connection gap.
    override val route: StateFlow<AudioRoute> = mutableRoute.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            scheduleRouteUpdate()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            scheduleRouteUpdate()
        }
    }

    // The enumerated device set is unchanged when the session *selects* a microphone, so the
    // device callback above never fires for these. Without them the route stays on the candidate
    // (`BluetoothA2dpOnly` → `Exposed`) even after SCO/BLE selection, and the gate has no new
    // route to act on.
    private val communicationDeviceListener = AudioManager.OnCommunicationDeviceChangedListener {
        refreshRouteNow()
    }

    @Suppress("DEPRECATION") // SCO state has no replacement broadcast; the actions are deprecated, not unused
    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED ||
                intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED
            ) {
                refreshRouteNow()
            }
        }
    }

    private fun scheduleRouteUpdate() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500L)
            mutableRoute.value = readRoute()
            gracePeriodSignal.onAudioRouteChanged()
        }
    }

    // Selection changes are discrete and leave the device list untouched, so they need no
    // debounce: the route is re-read from the selected state immediately.
    private fun refreshRouteNow() {
        debounceJob?.cancel()
        mutableRoute.value = readRoute()
        gracePeriodSignal.onAudioRouteChanged()
    }

    /**
     * Publishes the external microphone acquisition has just confirmed selected. The device list
     * does not change when the session selects a microphone, so the callbacks may not fire before
     * the gate must know; acquisition already verified the selected device, so this is the
     * authority. Emitting here lets the mode leave `Off` (capture is governed) and makes a later
     * blocker produce a new mode emission that stops capture.
     */
    fun onExternalMicrophoneConfirmed() {
        debounceJob?.cancel()
        mutableRoute.value = AudioRoute.Headset(hasMicrophone = true)
        gracePeriodSignal.onAudioRouteChanged()
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        audioManager.addOnCommunicationDeviceChangedListener(
            ContextCompat.getMainExecutor(context),
            communicationDeviceListener,
        )
        ContextCompat.registerReceiver(
            context,
            scoStateReceiver,
            scoStateFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Suppress("DEPRECATION") // SCO state has no replacement broadcast; the actions are deprecated, not unused
    private fun scoStateFilter(): IntentFilter = IntentFilter().apply {
        addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        addAction(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED)
    }

    @Suppress("DEPRECATION") // isBluetoothScoOn deprecated in API 34; used to distinguish enumerated-but-inactive SCO
    private fun readRoute(): AudioRoute {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

        return classifyRoute(
            outputDeviceTypes = outputDevices.map { it.type },
            inputDeviceTypes = inputDevices.map { it.type },
            // SCO I/O devices are enumerated for AirPods even when the SCO link is off
            // (A2DP music path). Only treat BT as a live headset mic when SCO is actually on.
            bluetoothScoActive = audioManager.isBluetoothScoOn,
            // For BLE, an enumerated input is not a selected one. getCommunicationDevice() is
            // the selection; presence in getDevices(INPUTS) never counts on its own.
            bleCommunicationDeviceSelected = audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }

    internal companion object {
        fun classifyRoute(
            outputDeviceTypes: List<Int>,
            inputDeviceTypes: List<Int>,
            bluetoothScoActive: Boolean = false,
            bleCommunicationDeviceSelected: Boolean = false,
        ): AudioRoute {
            val hasWiredOrUsbInput = inputDeviceTypes.any { it.isAutoSelectedExternalMicrophone() }
            val hasBleInput = inputDeviceTypes.any { it.isBleHeadset() }
            val hasWiredOutput = outputDeviceTypes.any { it.isWiredHeadsetOutput() }
            val hasBleOutput = outputDeviceTypes.any { it.isBleHeadset() }
            val hasBleDevice = hasBleInput || hasBleOutput
            val hasA2dp = outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val hasScoDevices = outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ||
                inputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

            return when {
                // A wired or USB input is auto-selected by the platform.
                hasWiredOrUsbInput -> AudioRoute.Headset(hasMicrophone = true)

                // For BLE only getCommunicationDevice() proves selection; an enumerated input
                // is not a selected one.
                hasBleDevice && bleCommunicationDeviceSelected ->
                    AudioRoute.Headset(hasMicrophone = true)

                // A wired output with no matching microphone (headphones) exposes no mic and
                // cannot be acquired; it stays NoMic. A USB output is not treated here: a USB DAC
                // carries no microphone, and treating its presence as NoMic would preempt a
                // connected Bluetooth headset's candidate.
                hasWiredOutput -> AudioRoute.Headset(hasMicrophone = false)

                // A BLE device whose microphone the session has not selected yet is an
                // acquirable candidate: LE Audio selection (setCommunicationDevice) turns
                // it into an Isolated headset. It must not collapse to NoMic.
                hasBleDevice -> AudioRoute.BluetoothA2dpOnly

                // Classic BT with a live SCO link — mic is on the headset.
                (hasScoDevices || hasA2dp) && bluetoothScoActive ->
                    AudioRoute.Headset(hasMicrophone = true)

                // A2DP (and/or enumerated-but-inactive SCO) — acquisition must open SCO
                // before capture, otherwise Oboe binds the phone bottom mic.
                hasA2dp || hasScoDevices -> AudioRoute.BluetoothA2dpOnly

                outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioRoute.Speaker

                else -> AudioRoute.Unknown
            }
        }

        private fun Int.isWiredHeadsetOutput(): Boolean {
            return this == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                this == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }

        private fun Int.isBleHeadset(): Boolean {
            return this == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }
}
