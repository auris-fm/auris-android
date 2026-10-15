package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo

/**
 * Audio input types that count as an external microphone.
 *
 * [isAutoSelectedExternalMicrophone] types are routed to by the platform as soon as they are
 * connected, so their presence is selection. Bluetooth is not: a classic headset needs SCO and LE
 * Audio needs `setCommunicationDevice()`, so those are never auto-selected.
 *
 * One place defines the set so the route classifier and the capture-start confirmation cannot
 * disagree about the same device — a device classified as an external microphone but refused at
 * confirmation would leave capture permanently off.
 *
 * USB audio is deliberately not included: a standalone USB microphone presents no external output,
 * so it could not be discovered as a candidate on either platform and would only widen the value
 * without being reachable. It stays `Exposed` and blocked, like any other route the rule does not
 * cover.
 */
internal fun Int.isAutoSelectedExternalMicrophone(): Boolean = this == AudioDeviceInfo.TYPE_WIRED_HEADSET

/** Types a selected input may have for capture: the auto-selected ones plus Bluetooth. */
internal fun Int.isExternalMicrophone(): Boolean = isAutoSelectedExternalMicrophone() ||
    this == AudioDeviceInfo.TYPE_BLE_HEADSET ||
    this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
