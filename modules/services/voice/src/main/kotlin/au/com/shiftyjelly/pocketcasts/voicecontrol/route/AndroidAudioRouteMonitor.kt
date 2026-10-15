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
    /**
     * The device id the live capture stream is routed to, or null when no capture route is observed.
     * A provider, because the answer belongs to whichever stream is live.
     */
    private val captureRoutedDeviceId: (() -> Int?)? = null,
) : AudioRouteMonitor {

    /**
     * Output and input types the platform enumerates, as one value so they cannot be read apart,
     * plus the input devices' IDs so a device observed on the live capture stream can be resolved to
     * its type. The pair is needed because the capture observation is an ID while routing wants a
     * type; keeping them together is what lets the observation be used without a second enumeration.
     */
    data class EnumeratedDevices(
        val outputs: List<Int>,
        val inputs: List<Int>,
        val inputIds: List<Int> = emptyList(),
    ) {
        /** The type of the observed capture device, or null when it is not among the enumerated ones. */
        fun typeOfInput(deviceId: Int): Int? {
            val index = inputIds.indexOf(deviceId)
            return if (index >= 0 && index < inputs.size) inputs[index] else null
        }
    }
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
        // SELECTION AND SCO STATE CHANGE AVAILABILITY TOO: readAvailability() reads the selected
        // communication device and the live SCO link, and neither touches the enumerated device list
        // -- so without this refresh the eligibility surface keeps the pre-selection answer forever
        // (connected AirPods stay BluetoothA2dpOnly -> Exposed after acquisition selects them, and the
        // listening mode can never leave Off).
        mutableAvailability.value = readAvailability()
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
        // The confirmed selection is also availability's newest answer -- see refreshRouteNow().
        mutableAvailability.value = readAvailability()
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
        // THE OBSERVATION IS THE STRONGER INPUT, AND ITS ABSENCE IS THE MISSING ANSWER. When a live
        // stream reports what it is routed to, that overrides enumeration: enumeration says what COULD
        // be, the observation says what IS. When no stream reports -- the paired-but-inactive case --
        // the route is Unknown, because substituting availability for an observation would impose
        // headset setup on a session that may be using the speaker. @spec's rule, and it is what makes
        // "paired" different from "routed".
        routedOutputObserver?.current()?.let { routed ->
            val devices = enumerated()
            // The observed CAPTURE input, resolved through the same enumeration: classifyRoute needs a
            // TYPE, and the observation carries an ID.
            val captureType = captureRoutedDeviceId?.invoke()?.let { devices.typeOfInput(it) }
            return classifyRoute(
                outputDeviceTypes = listOf(routed),
                inputDeviceTypes = devices.inputs,
                bluetoothScoActive = audioManager.isBluetoothScoOn,
                bleCommunicationDeviceSelected = audioManager.communicationDevice?.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                captureInputType = captureType,
                outputIsObserved = true,
            )
        }
        // A SELECTED COMMUNICATION DEVICE IS AN OBSERVATION TOO. The platform established that routing
        // when the session selected the device; it does not need a live stream to be true, and treating
        // its absence as "no answer" would drop the route to Unknown during the BLE-selection moment the
        // acquisition flow depends on.
        val selected = audioManager.communicationDevice
        if (selected != null) {
            val devices = enumerated()
            return classifyRoute(
                outputDeviceTypes = listOf(selected.type),
                inputDeviceTypes = devices.inputs,
                bluetoothScoActive = audioManager.isBluetoothScoOn,
                bleCommunicationDeviceSelected = selected.type == AudioDeviceInfo.TYPE_BLE_HEADSET,
                captureInputType = captureRoutedDeviceId?.invoke()?.let { devices.typeOfInput(it) },
                outputIsObserved = true,
            )
        }
        // AN OPEN SCO LINK IS AN OBSERVED ACQUISITION, NOT AN ENUMERATION. BluetoothScoOn is the
        // platform reporting that the session HAS the headset link up -- that is the acquired input, so
        // it answers the route question the same way a live stream does. It is deliberately NOT reached
        // from the enumerated device list: an SCO device merely PRESENT in the list is availability.
        if (audioManager.isBluetoothScoOn) {
            val devices = enumerated()
            // Classified through the same function so the observed capture input and the enumerated
            // input set are both accounted for rather than hard-coded here.
            return classifyRoute(
                // The OBSERVED fact is the SCO link itself. Passing the ENUMERATED outputs here would
                // let an available wired/BLE output out-rank the live link, which is enumeration posing
                // as routing evidence -- the substitution this surface exists to prevent.
                outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                inputDeviceTypes = devices.inputs,
                bluetoothScoActive = true,
                captureInputType = captureRoutedDeviceId?.invoke()?.let { devices.typeOfInput(it) },
                outputIsObserved = true,
            )
        }
        // NO OBSERVATION, NO ANSWER. With no live routed stream, no selected communication device and no
        // open SCO link there is no routing the platform has established, and substituting ENUMERATION
        // for that missing answer is exactly the availability-as-acquisition inference PR #56 removed.
        // The route stays Unknown; `availability` is the separate enumeration-based surface the setup
        // decision reads, so an acquisition that still needs to proceed reads THAT.
        return AudioRoute.Unknown
    }

    /** The injected enumeration when present, otherwise the platform's. */
    private fun enumerated(): EnumeratedDevices = enumeratedDeviceTypes?.invoke() ?: run {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        EnumeratedDevices(
            outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type },
            inputs = inputs.map { it.type },
            inputIds = inputs.map { it.id },
        )
    }

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
        /**
         * The OBSERVED-ROUTE classifier: the observation of what is routed decides, and enumeration is
         * not consulted as routing evidence. The output types here are the observed ones (a live routed
         * stream, a selected communication device, or an open SCO link), so the first matching observed
         * output names the route; whether its microphone is usable is answered by the OBSERVED capture
         * input alone -- an enumerated wired input must not promote an observed speaker or A2DP route to
         * a headset, which is the availability-as-acquisition inference PR #56 removed.
         */
        private fun classifyObservedRoute(
            outputDeviceTypes: List<Int>,
            captureInputType: Int?,
        ): AudioRoute {
            val hasWiredOutput = outputDeviceTypes.any { it.isWiredHeadsetOutput() }
            val hasBleOutput = outputDeviceTypes.any { it.isBleHeadset() }
            // The OBSERVED capture input is external when it is wired, BLE or SCO: an observed
            // TYPE_BLUETOOTH_SCO capture IS the headset microphone, not a contradiction of it.
            val observedCaptureIsExternal = captureInputType?.isScoOrHeadsetInput() == true
            return when {
                // The observed output names the route; the observed capture input alone decides whether
                // its microphone is usable.
                hasWiredOutput || hasBleOutput ->
                    AudioRoute.Headset(hasMicrophone = observedCaptureIsExternal)

                // LIVE-OUTPUT PRECEDENCE: an explicitly observed speaker or A2DP output keeps its own
                // route even while the SCO link is open -- the SCO fact arrives as an observed OUTPUT
                // type, never as a global override, so it cannot out-rank what a stream reports.
                outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioRoute.Speaker

                // An observed A2DP output keeps its own output even when a headset capture input is
                // observed: the route is what is routed, not what the input implies.
                outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ->
                    AudioRoute.BluetoothA2dpOnly

                // An observed SCO output (the open-SCO fallback passes exactly this) IS the observed
                // acquisition: the headset microphone is in use unless a capture observation contradicts
                // it.
                outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ->
                    AudioRoute.Headset(
                        // An observed SCO capture (wired, BLE or SCO) decides; with NO capture
                        // observation the open link itself is the acquisition evidence, as main
                        // treated live SCO.
                        hasMicrophone = captureInputType?.let { it.isScoOrHeadsetInput() } ?: true,
                    )

                else -> AudioRoute.Unknown
            }
        }

        fun classifyRoute(
            outputDeviceTypes: List<Int>,
            inputDeviceTypes: List<Int>,
            bluetoothScoActive: Boolean = false,
            bleCommunicationDeviceSelected: Boolean = false,
            /**
             * The TYPE of the device the live capture stream is OBSERVED on, or null when no capture
             * route is observed. My branch's addition: an observation is stronger than enumeration, so
             * when it is present it decides whether a headset route has a USABLE microphone.
             */
            captureInputType: Int? = null,
            /**
             * Whether the OUTPUT types were OBSERVED (a live routed stream, a selected communication
             * device, or an open SCO link) rather than enumerated. The two classifications are
             * SEPARATE: with an observation the output names the route and enumeration is never
             * routing evidence; without one this function is the availability classifier and main's
             * enumeration rules stand.
             */
            outputIsObserved: Boolean = false,
        ): AudioRoute {
            if (outputIsObserved) {
                return classifyObservedRoute(
                    outputDeviceTypes = outputDeviceTypes,
                    captureInputType = captureInputType,
                )
            }
            val hasWiredInput = inputDeviceTypes.any { it.isAutoSelectedExternalMicrophone() }
            val hasBleInput = inputDeviceTypes.any { it.isBleHeadset() }
            val hasWiredOutput = outputDeviceTypes.any { it.isWiredHeadsetOutput() }
            val hasBleOutput = outputDeviceTypes.any { it.isBleHeadset() }
            val hasBleDevice = hasBleInput || hasBleOutput
            val hasA2dp = outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val hasScoDevices = outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ||
                inputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

            return when {
                // A wired input is auto-selected by the platform.
                hasWiredInput -> AudioRoute.Headset(hasMicrophone = true)

                // For BLE only getCommunicationDevice() proves selection; an enumerated input
                // is not a selected one.
                hasBleDevice && bleCommunicationDeviceSelected ->
                    AudioRoute.Headset(hasMicrophone = true)

                // A wired output with no wired microphone (headphones) exposes no mic and
                // cannot be acquired; it stays NoMic.
                hasWiredOutput -> AudioRoute.Headset(
                    // The observed capture input decides when it is present; main's enumeration rule
                    // stands when no observation exists.
                    hasMicrophone = captureInputType?.isWiredOrBleHeadsetInput() == true,
                )

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

        private fun Int.isWiredOrBleHeadsetOutput(): Boolean {
            return this == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                this == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                isBleHeadset()
        }

        private fun Int.isWiredOrBleHeadsetInput(): Boolean {
            return this == AudioDeviceInfo.TYPE_WIRED_HEADSET || isBleHeadset()
        }

        private fun Int.isWiredHeadsetOutput(): Boolean {
            return this == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                this == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }

        private fun Int.isScoOrHeadsetInput(): Boolean {
            return this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isWiredOrBleHeadsetInput()
        }

        private fun Int.isBleHeadset(): Boolean {
            return this == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }
}
