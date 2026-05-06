package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent
import javax.inject.Inject
import kotlin.math.abs

class VoicePlaybackIntentExecutor @Inject constructor(
    private val sink: VoicePlaybackSink,
) {
    suspend fun execute(intent: VoicePlaybackIntent) {
        when (intent) {
            VoicePlaybackIntent.Pause -> sink.pause()
            VoicePlaybackIntent.Resume -> sink.resume()
            is VoicePlaybackIntent.SeekRelative -> {
                val seconds = abs(intent.deltaMs / 1000)
                if (intent.deltaMs >= 0) sink.skipForward(seconds) else sink.skipBackward(seconds)
            }
            is VoicePlaybackIntent.SeekAbsolute -> sink.seekTo(intent.positionMs.coerceAtLeast(0))
            VoicePlaybackIntent.NextChapter -> sink.nextChapter()
            VoicePlaybackIntent.PreviousChapter -> sink.previousChapter()
            is VoicePlaybackIntent.ChapterByIndex -> sink.chapterByIndex(intent.index)
            is VoicePlaybackIntent.ChapterByTitle -> Unit
            is VoicePlaybackIntent.SetPlaybackSpeed -> Unit
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
}

class PlaybackManagerVoicePlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
) : VoicePlaybackSink {
    override suspend fun pause() = playbackManager.pauseSuspend(sourceView = SourceView.UNKNOWN)
    override suspend fun resume() = playbackManager.playQueueSuspend(sourceView = SourceView.UNKNOWN)
    override suspend fun skipForward(seconds: Int) = playbackManager.skipForwardSuspend(SourceView.UNKNOWN, seconds)
    override suspend fun skipBackward(seconds: Int) = playbackManager.skipBackwardSuspend(SourceView.UNKNOWN, seconds)
    override suspend fun seekTo(positionMs: Int) = playbackManager.seekToTimeMsSuspend(positionMs)
    override fun nextChapter() = playbackManager.skipToNextSelectedOrLastChapter()
    override fun previousChapter() = playbackManager.skipToPreviousSelectedOrLastChapter()
    override fun chapterByIndex(index: Int) = playbackManager.skipToChapter(index)
}
