package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContext

data class VoiceRecognitionContext(
    val playbackContext: PlaybackContext,
    val audioRoute: au.com.shiftyjelly.pocketcasts.voice.route.AudioRoute,
    val timestampMs: Long = System.currentTimeMillis(),
)
