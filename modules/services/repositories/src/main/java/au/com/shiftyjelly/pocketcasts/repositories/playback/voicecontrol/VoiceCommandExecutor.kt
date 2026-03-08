package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.utils.extensions.roundedSpeed

interface VoicePlaybackController {
    fun skipForward()
    fun rewind()
    fun speedUp()
    fun speedDown()
    fun nextEpisode()
}

class PlaybackManagerVoicePlaybackController(
    private val playbackManager: PlaybackManager,
    private val settings: Settings,
) : VoicePlaybackController {
    override fun skipForward() {
        playbackManager.skipForward(sourceView = SourceView.VOICE_COMMAND)
    }

    override fun rewind() {
        playbackManager.skipBackward(sourceView = SourceView.VOICE_COMMAND)
    }

    override fun speedUp() {
        updateSpeed(delta = 0.1)
    }

    override fun speedDown() {
        updateSpeed(delta = -0.1)
    }

    override fun nextEpisode() {
        playbackManager.playNextInQueue(sourceView = SourceView.VOICE_COMMAND)
    }

    private fun updateSpeed(delta: Double) {
        val currentEffects = settings.globalPlaybackEffects.value
        val updatedEffects = PlaybackEffects().apply {
            playbackSpeed = (currentEffects.playbackSpeed + delta).roundedSpeed()
            trimMode = currentEffects.trimMode
            isVolumeBoosted = currentEffects.isVolumeBoosted
        }
        settings.globalPlaybackEffects.set(updatedEffects, updateModifiedAt = true)
        playbackManager.updatePlayerEffects(updatedEffects)
    }
}

class VoiceCommandExecutor(
    private val playbackController: VoicePlaybackController,
) {
    constructor(
        playbackManager: PlaybackManager,
        settings: Settings,
    ) : this(PlaybackManagerVoicePlaybackController(playbackManager, settings))

    fun execute(intentType: IntentType): Boolean {
        return when (intentType) {
            IntentType.SKIP_FORWARD -> {
                playbackController.skipForward()
                true
            }

            IntentType.REWIND -> {
                playbackController.rewind()
                true
            }

            IntentType.SPEED_UP -> {
                playbackController.speedUp()
                true
            }

            IntentType.SPEED_DOWN -> {
                playbackController.speedDown()
                true
            }

            IntentType.NEXT_EPISODE -> {
                playbackController.nextEpisode()
                true
            }

            IntentType.UNSUPPORTED_ADVANCED,
            IntentType.UNKNOWN,
            -> false
        }
    }
}
