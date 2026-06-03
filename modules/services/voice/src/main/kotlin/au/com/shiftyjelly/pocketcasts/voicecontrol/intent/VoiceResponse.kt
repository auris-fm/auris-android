package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

sealed interface VoiceResponse {
    data object Silent : VoiceResponse
    data class Earcon(val id: String) : VoiceResponse
    data class Spoken(val text: String) : VoiceResponse
}
