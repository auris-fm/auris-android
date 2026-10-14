package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Which Bluetooth transport a candidate microphone is reached over. */
enum class BluetoothTransport {
    /** Classic `HFP`/`SCO`, opened with `AudioManager.startBluetoothSco()`. */
    ClassicSco,

    /** `LE Audio`, selected with `AudioManager.setCommunicationDevice()`. */
    LeAudio,
}

/**
 * A Bluetooth device that is currently output-only and whose microphone must be
 * selected by acquisition before capture is permitted.
 */
data class BluetoothCandidate(
    val deviceId: Int,
    val transport: BluetoothTransport,
)

/** The outcome of an acquisition attempt. */
sealed interface AcquisitionResult {
    /** An external headset microphone is confirmed selected; capture may start. */
    data class Acquired(val deviceId: Int) : AcquisitionResult

    /** Acquisition was abandoned because a gate question blocked or the caller cancelled. */
    data object Cancelled : AcquisitionResult

    /** Acquisition failed, timed out, or could not confirm an external microphone. Routing was released. */
    data class Failed(val reason: String) : AcquisitionResult

    /** The route already exposes an external microphone (or no microphone); nothing to acquire. */
    data object NotNeeded : AcquisitionResult
}

/**
 * The platform operations acquisition performs. Abstracted so the ordering and the
 * "availability is not selection" rules can be unit tested without Android or Oboe.
 *
 * See core `voice-control-core.md` > "Route acquisition" and `arch/android.md` >
 * "Audio Route Detection".
 */
interface RouteAcquisitionPlatform {
    /** Bluetooth devices that are connected but whose microphone is not selected. */
    fun bluetoothCandidates(): List<BluetoothCandidate>

    fun startBluetoothSco()

    fun stopBluetoothSco()

    fun isBluetoothScoOn(): Boolean

    fun setCommunicationDevice(deviceId: Int): Boolean

    fun communicationDeviceId(): Int?

    fun clearCommunicationDevice()

    /** The current [AudioManager] mode, saved so acquisition can restore it. */
    fun audioMode(): Int

    /** Sets the [AudioManager] mode for the duration of the acquisition. */
    fun setAudioMode(mode: Int)

    /** The Android device type for [deviceId], or null when the id does not resolve. */
    fun deviceType(deviceId: Int): Int?

    /** Opens the input stream without starting it; returns the selected device id. */
    fun openInputStream(): Int

    /** Starts the previously opened stream. */
    fun startInputStream(): Boolean

    /** Closes any open stream. Idempotent. */
    fun closeInputStream()
}

/**
 * Acquires an external Bluetooth microphone without capturing.
 *
 * A Bluetooth device is output-only until the session selects its microphone, so the
 * route is `Exposed` and capture is blocked. This opens the route, opens the Oboe stream
 * without starting it, and starts capture only when the stream reports a wired, BLE, or
 * `SCO` headset microphone. `TYPE_BUILTIN_MIC`, `kUnspecified`, and unresolvable ids are
 * unconfirmed, so the stream is closed and the route stays blocked.
 *
 * Acquisition is bounded and cancellable: a blocking gate question aborts it, the opened
 * stream is closed, and the acquired routing (`startBluetoothSco()` / communication
 * device) is released on cancellation, failure, and gate loss.
 */
class RouteAcquisition(
    private val platform: RouteAcquisitionPlatform,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
) {

    /** True once this attempt selected routing that [release] must undo. */
    private var routingAcquired = false

    /** True when this attempt opened an input stream that it has not handed off yet. */
    private var openedStream = false

    /** Device id reported by the stream this attempt opened, reused when capture already started it. */
    private var openedDeviceId: Int = -1

    /** True once the opened stream was started and so belongs to the capture engine. */
    private var streamHandedOff = false

    /**
     * True when an acquisition has opened and confirmed a stream that has not been released.
     * The route monitor is debounced, so immediately after acquisition the reported route may
     * still be `BluetoothA2dpOnly`; the service uses this to recognise that the route is
     * already eligible on the strength of the confirmed stream.
     */
    val hasConfirmedStream: Boolean
        get() = openedStream

    /** The [AudioManager] mode that was active before classic acquisition changed it. */
    private var savedAudioMode: Int? = null

    suspend fun acquire(route: AudioRoute, gateAllows: () -> Boolean): AcquisitionResult {
        if (route !is AudioRoute.BluetoothA2dpOnly) return AcquisitionResult.NotNeeded
        val candidate = platform.bluetoothCandidates().firstOrNull()
            ?: return AcquisitionResult.Failed(REASON_FAILED)
        return acquire(candidate, gateAllows)
    }

    suspend fun acquire(candidate: BluetoothCandidate, gateAllows: () -> Boolean): AcquisitionResult {
        return try {
            performAcquisition(candidate, gateAllows)
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            Timber.w(e, "[RouteAcquisition] route_acquisition_failed: %s", e.message)
            release()
            AcquisitionResult.Failed(REASON_FAILED)
        }
    }

    private suspend fun performAcquisition(
        candidate: BluetoothCandidate,
        gateAllows: () -> Boolean,
    ): AcquisitionResult {
        if (candidate.transport == BluetoothTransport.LeAudio) {
            if (!platform.setCommunicationDevice(candidate.deviceId)) {
                release()
                return AcquisitionResult.Failed(REASON_FAILED)
            }
            routingAcquired = true
        }
        if (candidate.transport == BluetoothTransport.ClassicSco) {
            // HFP establishment needs the normal audio mode on some devices. This is the mode
            // handling that used to live in VoiceAsrEngine.awaitBluetoothSco(), scoped here to
            // the acquisition path; release() restores the previous mode.
            savedAudioMode = platform.audioMode()
            platform.setAudioMode(AudioManager.MODE_NORMAL)
            platform.startBluetoothSco()
            routingAcquired = true
        }

        // Wait, bounded, for the platform to actually select the candidate. Presence in
        // getDevices is never selection: only isBluetoothScoOn() / getCommunicationDevice()
        // reporting the candidate counts.
        val selection = withTimeoutOrNull(timeoutMs) {
            var outcome: Selection? = null
            while (outcome == null) {
                outcome = when {
                    !gateAllows() -> Selection.GATE_LOST

                    isSelected(candidate) -> Selection.SELECTED

                    else -> {
                        delay(pollIntervalMs)
                        null
                    }
                }
            }
            outcome
        } ?: Selection.TIMEOUT

        when (selection) {
            Selection.GATE_LOST -> {
                release()
                return AcquisitionResult.Cancelled
            }

            Selection.TIMEOUT -> {
                release()
                return AcquisitionResult.Failed(REASON_TIMEOUT)
            }

            Selection.SELECTED -> Unit
        }

        // The mode observer can confirm, start and hand off a stream while this attempt was
        // waiting for selection: the callbacks publish the confirmed route, and the engine starts
        // from that. Opening a second stream here would delete the one the engine is using, and
        // the coordinator would then report "already started" with VAD attached to nothing.
        // Reuse the stream this attempt already owns.
        if (openedStream) return AcquisitionResult.Acquired(openedDeviceId)

        if (openConfirmedStream() < 0) return AcquisitionResult.Failed(REASON_FAILED)

        // A gate question may have blocked while the stream was opening; do not start capture then.
        if (!gateAllows()) {
            release()
            return AcquisitionResult.Cancelled
        }

        if (!platform.startInputStream()) {
            release()
            return AcquisitionResult.Failed(REASON_FAILED)
        }

        return AcquisitionResult.Acquired(openedDeviceId)
    }

    private fun isSelected(candidate: BluetoothCandidate): Boolean = when (candidate.transport) {
        BluetoothTransport.ClassicSco -> platform.isBluetoothScoOn()
        BluetoothTransport.LeAudio -> platform.communicationDeviceId() == candidate.deviceId
    }

    /**
     * Marks the stream opened by [acquire] as owned by the capture engine. After this,
     * [release] must not close the stream — the engine tears it down itself, and closing it
     * from here would delete its live capture. Releasing the acquired routing still happens.
     */
    fun handOffStream() {
        if (openedStream) streamHandedOff = true
    }

    /**
     * Opens the input stream and starts it only when the device it selected is a wired, BLE,
     * or `SCO` headset microphone. This is the confirmation every capture start goes through,
     * not only acquisition: a wired, BLE, or already-active `SCO` route is classified
     * `Isolated` directly and would otherwise open-and-start whatever Oboe picks — including
     * the built-in microphone. `TYPE_BUILTIN_MIC`, `kUnspecified`, and unresolvable ids fail
     * closed: the stream is closed and no capture starts.
     *
     * Reuses the stream an [Acquired] acquisition already opened and started.
     */
    fun confirmCaptureStream(): Boolean {
        if (openedStream) return true
        if (openConfirmedStream() < 0) return false
        if (!platform.startInputStream()) {
            release()
            return false
        }
        return true
    }

    /**
     * Opens the input stream and confirms the device it selected is a wired, BLE, or `SCO` headset
     * microphone, recording the ownership fields. Returns the device id, or -1 with the stream
     * released when the selection cannot be confirmed. Acquisition and capture start both go
     * through this one primitive; acquisition additionally rechecks the gate before starting.
     */
    private fun openConfirmedStream(): Int {
        if (openedStream) return openedDeviceId
        val deviceId = platform.openInputStream()
        openedStream = true
        openedDeviceId = deviceId
        streamHandedOff = false
        if (deviceId < 0 || !isConfirmedExternalMicrophone(platform.deviceType(deviceId))) {
            Timber.w(
                "[RouteAcquisition] capture_start_refused: selected device=%d type=%s",
                deviceId,
                platform.deviceType(deviceId),
            )
            release()
            return -1
        }
        return deviceId
    }

    /**
     * Releases routing this acquisition actually acquired and closes the input stream only
     * while this component still owns it. Once the stream has been handed off (see
     * [handOffStream]) the capture engine owns it and tears it down itself; touching it here
     * would delete the engine's live capture. Idempotent.
     */
    fun release() {
        if (openedStream && !streamHandedOff) {
            runCatching { platform.closeInputStream() }
        }
        openedStream = false
        streamHandedOff = false
        openedDeviceId = -1
        if (routingAcquired) {
            routingAcquired = false
            runCatching { platform.stopBluetoothSco() }
            runCatching { platform.clearCommunicationDevice() }
        }
        savedAudioMode?.let { mode -> runCatching { platform.setAudioMode(mode) } }
        savedAudioMode = null
    }

    private enum class Selection { SELECTED, GATE_LOST, TIMEOUT }

    companion object {
        internal const val REASON_TIMEOUT = "route_acquisition_timeout"
        internal const val REASON_FAILED = "route_acquisition_failed"

        private const val DEFAULT_TIMEOUT_MS = 3_000L
        private const val DEFAULT_POLL_MS = 50L

        /**
         * Only a wired, BLE, or classic `SCO` headset microphone confirms acquisition.
         * `TYPE_BUILTIN_MIC`, a null (unresolvable) id, and every other type are unconfirmed.
         */
        internal fun isConfirmedExternalMicrophone(deviceType: Int?): Boolean = deviceType == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
}
