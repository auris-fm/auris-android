package au.com.shiftyjelly.pocketcasts.voice.model

sealed interface VoiceEnrollmentState {
    data object NotEnrolled : VoiceEnrollmentState
    data object Enrolling : VoiceEnrollmentState
    data class Enrolled(val timestampMs: Long) : VoiceEnrollmentState
}
