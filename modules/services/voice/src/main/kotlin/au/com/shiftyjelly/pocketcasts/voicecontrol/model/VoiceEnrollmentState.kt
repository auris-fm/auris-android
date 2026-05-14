package au.com.shiftyjelly.pocketcasts.voicecontrol.model

sealed interface VoiceEnrollmentState {
    data object NotEnrolled : VoiceEnrollmentState
    data object Enrolling : VoiceEnrollmentState
    data class Enrolled(val timestampMs: Long) : VoiceEnrollmentState
}
