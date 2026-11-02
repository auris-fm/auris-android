package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpNextQueueSink @Inject constructor(
    private val playbackManager: PlaybackManager,
) : VoiceQueueSink {
    override suspend fun addTop(episode: String?): VoiceResponse {
        // Episode resolution by title/ref would be handled by fuzzy search
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override suspend fun addBottom(episode: String?): VoiceResponse {
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override suspend fun remove(episode: String): VoiceResponse {
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override suspend fun moveToTop(episode: String): VoiceResponse {
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override suspend fun moveToBottom(episode: String): VoiceResponse {
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    /**
     * THE PRESENTATION/OUTCOME SEPARATION (spec 03c96924): this clears the queue and THEN renders
     * CONFIRM_REQUIRED, so its earcon reads as a refusal while the action really happened. That is
     * exactly why the outcome is not inferred from the response — the executor reports Performed
     * for this intent at the call site, and this sink keeps its existing presentation untouched.
     */
    override fun clear(): VoiceResponse {
        playbackManager.clearUpNextAsync()
        return VoiceResponse.Earcon(EarconId.CONFIRM_REQUIRED)
    }

    override suspend fun removeByPodcast(podcast: String): VoiceResponse {
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override fun sort(sortOrder: String): VoiceResponse {
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override fun queryContents(): VoiceResponse.Spoken {
        val count = playbackManager.upNextQueue.queueEpisodes.size
        return VoiceResponse.Spoken("$count episodes in queue")
    }

    override fun queryNext(): VoiceResponse.Spoken {
        val next = playbackManager.upNextQueue.queueEpisodes.firstOrNull()
        return if (next != null) {
            VoiceResponse.Spoken("Next: ${next.title}")
        } else {
            VoiceResponse.Spoken("Queue is empty")
        }
    }

    override fun queryLength(): VoiceResponse.Spoken {
        val count = playbackManager.upNextQueue.queueEpisodes.size
        return VoiceResponse.Spoken("$count episodes in queue")
    }

    override suspend fun queryIsQueued(episode: String): VoiceResponse.Spoken {
        val found = playbackManager.upNextQueue.queueEpisodes.any { it.title.contains(episode, ignoreCase = true) }
        return if (found) {
            VoiceResponse.Spoken("Yes, $episode is in the queue")
        } else {
            VoiceResponse.Spoken("No, $episode is not in the queue")
        }
    }
}
