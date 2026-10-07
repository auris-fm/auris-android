package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class PlaybackManagerPlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val context: Context,
) : VoicePlaybackSink {

    private val audioManager: AudioManager = context.getSystemService<AudioManager>()
        ?: throw IllegalStateException("AudioManager not available")

    /**
     * Duck the host player by requesting duckable transient focus.
     *
     * The [PlaybackManager] already responds to AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
     * by lowering its volume (VOLUME_DUCK) when configured for duck-over-notification.
     */
    // The listener-based overload is deprecated in favour of AudioFocusRequestCompat, which lives in
    // androidx.media — a dependency this module does not carry (FocusManager in :repositories uses it).
    // Migrating means adding that dependency; suppressing is the smaller change until then.
    @Suppress("DEPRECATION")
    override suspend fun duck(): VoiceResponse {
        val result = audioManager.requestAudioFocus(
            { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Timber.i("[VoicePipeline] audio focus restored after cloud duck")
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Timber.i("[VoicePipeline] host player ducked via focus")
                    }

                    else -> {}
                }
            },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.i("[VoicePipeline] cloud audio ducked host player")
        } else {
            Timber.w("[VoicePipeline] cloud audio duck request rejected")
        }
        return VoiceResponse.Silent
    }

    /** Restore by abandoning the duckable focus so the host player regains full volume. */
    @Suppress("DEPRECATION") // Same reason as duck(): see the note there.
    override suspend fun restore(): VoiceResponse {
        audioManager.abandonAudioFocus {}
        Timber.i("[VoicePipeline] abandoned cloud duck focus, host player restored")
        return VoiceResponse.Silent
    }

    override suspend fun pause(): VoiceResponse {
        playbackManager.pauseSuspend(sourceView = SourceView.VOICE_COMMANDS)
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override suspend fun resume(): VoiceResponse {
        playbackManager.playQueueSuspend(sourceView = SourceView.VOICE_COMMANDS)
        return VoiceResponse.Silent
    }

    override suspend fun skipForward(seconds: Int?): VoiceResponse {
        playbackManager.skipForwardSuspend(SourceView.VOICE_COMMANDS, seconds)
        return VoiceResponse.Silent
    }

    override suspend fun skipBackward(seconds: Int?): VoiceResponse {
        playbackManager.skipBackwardSuspend(SourceView.VOICE_COMMANDS, seconds)
        return VoiceResponse.Silent
    }

    override suspend fun seekTo(positionSeconds: Int): VoiceResponse {
        playbackManager.seekToTimeMsSuspend(positionSeconds * 1000)
        return VoiceResponse.Silent
    }

    override fun nextEpisode(): VoiceResponse {
        playbackManager.playNextInQueue(sourceView = SourceView.VOICE_COMMANDS)
        return VoiceResponse.Earcon(EarconId.NEXT_EPISODE)
    }
}
