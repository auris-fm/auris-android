package au.com.shiftyjelly.pocketcasts.voice.route

sealed interface AudioRoute {
    data class Headset(val hasMicrophone: Boolean) : AudioRoute
    data object Speaker : AudioRoute
    data object BluetoothA2dpOnly : AudioRoute
    data object Unknown : AudioRoute
}
