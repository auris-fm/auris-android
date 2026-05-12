package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

class VoicePlaybackIntentExecutor @Inject constructor(
    private val sink: VoicePlaybackSink,
) {
    suspend fun execute(intent: VoicePlaybackIntent) {
        when (intent) {
            VoicePlaybackIntent.Pause -> sink.pause()

            VoicePlaybackIntent.Resume -> sink.resume()

            is VoicePlaybackIntent.SeekRelative -> {
                val seconds = abs(intent.deltaMs / 1000)
                if (seconds == 0) return
                if (intent.deltaMs >= 0) sink.skipForward(seconds) else sink.skipBackward(seconds)
            }

            is VoicePlaybackIntent.SeekAbsolute -> sink.seekTo(intent.positionMs.coerceAtLeast(0))

            VoicePlaybackIntent.NextChapter -> sink.nextChapter()

            VoicePlaybackIntent.PreviousChapter -> sink.previousChapter()

            VoicePlaybackIntent.NextEpisode -> sink.nextEpisode()

            is VoicePlaybackIntent.ChapterByIndex -> sink.chapterByIndex(intent.index)

            is VoicePlaybackIntent.ChapterByTitle -> Unit

            is VoicePlaybackIntent.SetPlaybackSpeed -> {
                if (intent.speed != null) sink.setSpeed(intent.speed)
                else if (intent.delta != null) sink.adjustSpeed(intent.delta)
            }

            is VoicePlaybackIntent.SetVolume -> Unit

            is VoicePlaybackIntent.SleepTimer -> Unit
        }
    }
}

interface VoicePlaybackSink {
    suspend fun pause()
    suspend fun resume()
    suspend fun skipForward(seconds: Int)
    suspend fun skipBackward(seconds: Int)
    suspend fun seekTo(positionMs: Int)
    fun nextChapter()
    fun previousChapter()
    fun chapterByIndex(index: Int)
    fun nextEpisode()
    fun setSpeed(speed: Double)
    fun adjustSpeed(delta: Double)
}

class PlaybackManagerVoicePlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val settings: Settings,
) : VoicePlaybackSink {
    override suspend fun pause() = playbackManager.pauseSuspend(sourceView = SourceView.UNKNOWN)
    override suspend fun resume() = playbackManager.playQueueSuspend(sourceView = SourceView.UNKNOWN)
    override suspend fun skipForward(seconds: Int) = playbackManager.skipForwardSuspend(SourceView.UNKNOWN, seconds)
    override suspend fun skipBackward(seconds: Int) = playbackManager.skipBackwardSuspend(SourceView.UNKNOWN, seconds)
    override suspend fun seekTo(positionMs: Int) = playbackManager.seekToTimeMsSuspend(positionMs)
    override fun nextChapter() = playbackManager.skipToNextSelectedOrLastChapter()
    override fun previousChapter() = playbackManager.skipToPreviousSelectedOrLastChapter()
    override fun chapterByIndex(index: Int) = playbackManager.skipToChapter(index)
    override fun nextEpisode() = playbackManager.playNextInQueue(sourceView = SourceView.UNKNOWN)

    override fun setSpeed(speed: Double) {
        val clamped = speed.coerceIn(0.5, 5.0)
        applySpeed(clamped)
    }

    override fun adjustSpeed(delta: Double) {
        val current = playbackManager.getPlaybackSpeed()
        val clamped = (current + delta).coerceIn(0.5, 5.0)
        // Round to 1 decimal for clean display values
        val rounded = (clamped * 10).roundToInt() / 10.0
        applySpeed(rounded)
    }

    private fun applySpeed(speed: Double) {
        val effects = settings.globalPlaybackEffects.value
        effects.playbackSpeed = speed
        settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
        playbackManager.updatePlayerEffects(effects = effects)
    }
}
