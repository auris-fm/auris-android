package au.com.shiftyjelly.pocketcasts.voicecontrol.route

sealed interface AudioRoute {
    data class Headset(val hasMicrophone: Boolean) : AudioRoute
    data object Speaker : AudioRoute
    data object BluetoothA2dpOnly : AudioRoute
    data object Unknown : AudioRoute
}

/**
 * Whether a route can still be an acquisition target.
 *
 * A pending acquisition stays valid while its candidate is present **or has become the selected
 * external microphone**. The enumerated device list does not change when a microphone is selected,
 * and the selection callbacks publish [AudioRoute.Headset] while acquisition is still confirming, so
 * treating every non-candidate route as candidate loss aborts the acquisition that just succeeded
 * and releases the routing it acquired. Only actual device loss — speaker, unknown, or a headset
 * without a microphone — ends the attempt.
 */
internal fun AudioRoute.keepsPendingAcquisition(): Boolean = when (this) {
    is AudioRoute.BluetoothA2dpOnly -> true
    is AudioRoute.Headset -> hasMicrophone
    AudioRoute.Speaker, AudioRoute.Unknown -> false
}
