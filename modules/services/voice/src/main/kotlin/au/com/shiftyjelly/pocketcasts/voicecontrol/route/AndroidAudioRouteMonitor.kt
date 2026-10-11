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
    /**
     * Where an owned stream reports its output actually goes, or null when that is not knowable.
     *
     * Enumeration says what COULD be an output; this says what one IS routed to, so it is the stronger
     * input when present. It is a provider rather than a value because the answer belongs to whichever
     * stream is live, and it is nullable because an unknown route must stay unknown: treating a missing
     * answer as the speaker would be a claim the platform has not made.
     */
    private val routedOutputObserver: RoutedOutputObserver? = null,
    /**
     * The device types the platform offers, injectable so a test can present an available device that
     * no stream is routed to — the case that distinguishes the two surfaces.
     */
    private val enumeratedDeviceTypes: (() -> EnumeratedDevices)? = null,
) : AudioRouteMonitor {

    /** Output and input types the platform enumerates, as one value so they cannot be read apart. */
    data class EnumeratedDevices(val outputs: List<Int>, val inputs: List<Int>)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableRoute = MutableStateFlow(readRoute())
    private val mutableAvailability = MutableStateFlow(readAvailability())
    private val scope = CoroutineScope(Dispatchers.Default)
    private var debounceJob: Job? = null

    // Bluetooth devices are enumerated incrementally — output first, then input.
    // A 500ms debounce prevents transient Headset(hasMicrophone=false) → NoMic
    // from killing the engine during the connection gap.
    override val route: StateFlow<AudioRoute> = mutableRoute.asStateFlow()
    override val availability: StateFlow<AudioRoute> = mutableAvailability.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onDevicesChanged()
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

    /**
     * The device-event entry point, as the system invokes it.
     *
     * Kept internal so a test can deliver the event Android would deliver and observe the production
     * debounce, route read and privacy closure that follow — the connection cannot be exercised from
     * outside the process otherwise, and a test that assigns its own callback would only prove that a
     * callback does what it says.
     */
    internal fun onDevicesChanged() = scheduleRouteUpdate()

    private fun scheduleRouteUpdate() {
        // The privacy closure is the owning transition and must not wait on the debounce: grace ends
        // when the device event arrives, not 500ms later when the route has stabilised. Taking it here
        // also means a burst of events cannot defer it, and it is taken against the window that was
        // current when the event arrived.
        // The event is the fact; the route read is the derived value. Bluetooth devices enumerate
        // incrementally, so reading immediately can catch a transient Headset(noMic) — that is what the
        // debounce is for. Ending grace is not derived from the read, so it is taken here, once, on
        // arrival: waiting would defer the owning transition, and a burst of events would defer it
        // further, since each new event cancels the pending job.
        gracePeriodSignal.onAudioRouteChanged()

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500L)
            mutableRoute.value = readRoute()
            mutableAvailability.value = readAvailability()
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
        val devices = enumerated()
        val outputDevices = devices.outputs
        val inputDevices = devices.inputs

        // Observed routing only. With no live stream reporting an output there is no routed answer, and
        // Unknown is the honest one: falling back to enumeration here would substitute availability for
        // the observation and impose headset setup on a session that may use the speaker.
        val routed = routedOutputObserver?.current() ?: return AudioRoute.Unknown
        return classifyRoute(
            outputDeviceTypes = listOf(routed),
            inputDeviceTypes = inputDevices,
            bluetoothScoActive = audioManager.isBluetoothScoOn,
        )
    }

    /** The injected enumeration when present, otherwise the platform's. */
    private fun enumerated(): EnumeratedDevices = enumeratedDeviceTypes?.invoke() ?: EnumeratedDevices(
        outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type },
        inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type },
    )

    /** What the system offers, for setup and discovery. Never used to derive [route]. */
    @Suppress("DEPRECATION") // isBluetoothScoOn deprecated in API 34; used to distinguish enumerated-but-inactive SCO
    private fun readAvailability(): AudioRoute {
        val devices = enumerated()

        return classifyRoute(
            outputDeviceTypes = devices.outputs,
            inputDeviceTypes = devices.inputs,
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
            val hasWiredInput = inputDeviceTypes.any { it.isAutoSelectedExternalMicrophone() }
            val hasBleInput = inputDeviceTypes.any { it.isBleHeadset() }
            val hasWiredOutput = outputDeviceTypes.any { it.isWiredHeadsetOutput() }
            val hasBleOutput = outputDeviceTypes.any { it.isBleHeadset() }
            val hasBleDevice = hasBleInput || hasBleOutput
            val hasA2dp = outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val hasScoDevices = outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ||
                inputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

            return when {
                // The OUTPUT names the route. An enumerated headset INPUT beside a speaker output is
                // availability, not the route: arch/android.md gives `hasMicrophone` as "a headset
                // OUTPUT with a matching input device", so a route with no headset output is not a
                // headset route however many headset inputs the system offers.
                hasWiredOrBleOutput ->
                    AudioRoute.Headset(hasMicrophone = hasWiredOrBleInput)

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
