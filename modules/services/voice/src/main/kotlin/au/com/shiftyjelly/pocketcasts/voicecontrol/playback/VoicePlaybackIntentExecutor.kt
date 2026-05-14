package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.type.TrimMode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import com.automattic.eventhorizon.BookmarkSourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

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
            is VoicePlaybackIntent.SetSpeed -> sink.setSpeed(intent.speed)
            is VoicePlaybackIntent.AdjustSpeed -> sink.adjustSpeed(intent.delta)
            is VoicePlaybackIntent.SetVolume -> sink.setVolume(intent.volume)
            is VoicePlaybackIntent.AdjustVolume -> sink.adjustVolume(intent.delta)
            is VoicePlaybackIntent.SleepTimer -> sink.sleepAfter(intent.minutes)
            is VoicePlaybackIntent.SetTrimMode -> sink.setTrimMode(intent.mode)
            is VoicePlaybackIntent.SetVolumeBoost -> sink.setVolumeBoost(intent.enabled)
            is VoicePlaybackIntent.AddBookmark -> sink.addBookmark(intent.title)
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
    fun setVolume(volume: Int)
    fun adjustVolume(delta: Int)
    fun sleepAfter(minutes: Int)
    fun setTrimMode(mode: String)
    fun setVolumeBoost(enabled: Boolean)
    fun addBookmark(title: String)
}

class PlaybackManagerVoicePlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val settings: Settings,
    private val sleepTimer: SleepTimer,
    private val bookmarkManager: BookmarkManager,
    @ApplicationContext private val context: Context,
) : VoicePlaybackSink {

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override suspend fun pause() = playbackManager.pauseSuspend(sourceView = SourceView.VOICE_COMMANDS)
    override suspend fun resume() = playbackManager.playQueueSuspend(sourceView = SourceView.VOICE_COMMANDS)
    override suspend fun skipForward(seconds: Int) = playbackManager.skipForwardSuspend(SourceView.VOICE_COMMANDS, seconds)
    override suspend fun skipBackward(seconds: Int) = playbackManager.skipBackwardSuspend(SourceView.VOICE_COMMANDS, seconds)
    override suspend fun seekTo(positionMs: Int) = playbackManager.seekToTimeMsSuspend(positionMs)
    override fun nextChapter() = playbackManager.skipToNextSelectedOrLastChapter()
    override fun previousChapter() = playbackManager.skipToPreviousSelectedOrLastChapter()
    override fun chapterByIndex(index: Int) = playbackManager.skipToChapter(index)
    override fun nextEpisode() = playbackManager.playNextInQueue(sourceView = SourceView.VOICE_COMMANDS)

    override fun setSpeed(speed: Double) {
        val clamped = speed.coerceIn(0.5, 5.0)
        applySpeed(clamped)
    }

    override fun adjustSpeed(delta: Double) {
        val current = playbackManager.getPlaybackSpeed()
        val clamped = (current + delta).coerceIn(0.5, 5.0)
        val rounded = (clamped * 10).roundToInt() / 10.0
        applySpeed(rounded)
    }

    override fun setVolume(volume: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val scaled = (volume * max / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaled, 0)
    }

    override fun adjustVolume(delta: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + delta * max / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    override fun sleepAfter(minutes: Int) {
        if (minutes > 0) {
            sleepTimer.sleepAfter(minutes.minutes)
        } else {
            sleepTimer.cancelTimer()
        }
    }

    override fun setTrimMode(mode: String) {
        val trimMode = when (mode.lowercase()) {
            "low" -> TrimMode.LOW
            "medium" -> TrimMode.MEDIUM
            "high" -> TrimMode.HIGH
            else -> TrimMode.OFF
        }
        val effects = settings.globalPlaybackEffects.value
        effects.trimMode = trimMode
        settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
        playbackManager.updatePlayerEffects(effects = effects)
    }

    override fun setVolumeBoost(enabled: Boolean) {
        val effects = settings.globalPlaybackEffects.value
        effects.isVolumeBoosted = enabled
        settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
        playbackManager.updatePlayerEffects(effects = effects)
    }

    override fun addBookmark(title: String) {
        val episode = playbackManager.getCurrentEpisode() ?: return
        kotlinx.coroutines.runBlocking {
            val positionMs = playbackManager.getCurrentTimeMs(episode)
            val timeSecs = positionMs / 1000
            bookmarkManager.sourceView = SourceView.VOICE_COMMANDS
            bookmarkManager.add(
                episode = episode,
                timeSecs = timeSecs,
                title = title,
                creationSource = BookmarkSourceType.Headphones,
            )
        }
    }

    private fun applySpeed(speed: Double) {
        val effects = settings.globalPlaybackEffects.value
        effects.playbackSpeed = speed
        settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
        playbackManager.updatePlayerEffects(effects = effects)
    }
}
