package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext

data class VoiceRecognitionContext(
    val playbackContext: PlaybackContext,
    val audioRoute: au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute,
    val timestampMs: Long = System.currentTimeMillis(),
)
