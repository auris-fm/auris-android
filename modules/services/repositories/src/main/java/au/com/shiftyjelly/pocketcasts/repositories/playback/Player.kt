package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects

sealed interface EpisodeLocation {
    val episode: BaseEpisode
    val uri: String?

    data class Stream(
        override val episode: BaseEpisode,
        override val uri: String?,
        val isHls: Boolean,
    ) : EpisodeLocation

    data class Downloaded(
        override val episode: BaseEpisode,
        override val uri: String?,
    ) : EpisodeLocation

    companion object {
        fun create(episode: BaseEpisode, preferStream: Boolean = false) = if (episode.isDownloaded && !(preferStream && episode.isStreamUrlHls)) {
            EpisodeLocation.Downloaded(episode, episode.downloadedFilePath)
        } else {
            EpisodeLocation.Stream(episode, episode.streamUrl, episode.isStreamUrlHls)
        }
    }
}

val EpisodeLocation?.isHlsStream: Boolean
    get() = (this as? EpisodeLocation.Stream)?.isHls == true

/**
 * Whether the stream the player prepared carries video. HLS starts [Unknown] until the player's tracks
 * resolve it to [HasVideo] or [AudioOnly]; the video surface is shown only once it reaches [HasVideo].
 */
enum class StreamVideoState {
    NotVideo,
    Unknown,
    HasVideo,
    AudioOnly,
    ;

    companion object {
        fun initialFor(episode: BaseEpisode, audioOnly: Boolean, playingHlsStream: Boolean, isRemote: Boolean) = when {
            audioOnly && (episode.isVideo || playingHlsStream) -> AudioOnly
            playingHlsStream && !isRemote -> Unknown
            else -> NotVideo
        }
    }
}

interface Player {
    var isPip: Boolean
    val isRemote: Boolean
    val isStreaming: Boolean
    val filePath: String?
    val url: String?
    val episodeUuid: String?
    val name: String
    val isDownloading: Boolean
    val onPlayerEvent: (Player, PlayerEvent) -> Unit

    /**
     * Smoothed RMS level of the playing audio in the range 0f..1f, updated from the audio
     * pipeline so it is safe to poll every frame. Players that don't measure audio return 0f,
     * which the UI treats as "no level available" rather than silence.
     */
    val currentAudioLevel: Float get() = 0f

    suspend fun load(currentPositionMs: Int)
    suspend fun getCurrentPositionMs(): Int
    suspend fun play(currentPositionMs: Int)
    suspend fun pause()
    suspend fun stop()
    suspend fun setPlaybackEffects(playbackEffects: PlaybackEffects)
    fun updateAudioOnly() {}
    suspend fun seekToTimeMs(positionMs: Int)

    /**
     * Seeks [positionMs] ONLY while this player still addresses [expectedEpisodeUuid], reporting whether
     * it did.
     *
     * THE EPISODE IS CHECKED HERE, IN THE PLAYER'S OWN MUTATING OPERATION, because nowhere else can be
     * early enough. A player is REUSED across episodes, and `seekToTimeMs` SUSPENDS -- so a caller-side
     * check before or after it can only OBSERVE that the player changed, by which point the seek it
     * issued has already run on the wrong audio. A returned false cannot undo a mutation.
     *
     * RETURNS THE PRE-ACTION POSITION, so the caller attributes history to the value the PLAYER saw at
     * its own mutation rather than to a read taken around the call. A null result is a refusal.
     *
     * THE DEFAULT REFUSES. It cannot enforce the binding, so a player that wants this operation must
     * override it -- `LocalPlayer` and `CastPlayer` both do, each deciding at its own mutation point and
     * returning the position it saw there. A player that does not override makes the bound call
     * UNAVAILABLE, which is what stops a direct caller reaching an unrestricted seek through the
     * default.
     */
    suspend fun seekToTimeMsIfEpisode(positionMs: Int, expectedEpisodeUuid: String): Long? {
        // THE DEFAULT REFUSES RATHER THAN PRETENDING. It cannot enforce the binding -- it checks and then
        // delegates, leaving the window this contract exists to close -- so returning a position here
        // would report an enforcement that did not happen. An implementation that CAN enforce it
        // overrides this; one that cannot makes the BOUND call unavailable.
        //
        // Refusing at the DEFAULT is what stops a direct caller from reaching an unrestricted seek
        // through it, which the manager's guard alone cannot prevent: a caller that invokes the player
        // operation without going through the manager is exactly the bypass @reviewer isolated.
        error("seekToTimeMsIfEpisode requires an implementation that enforces the episode binding")
    }

    /**
     * Whether this player can enforce [seekToTimeMsIfEpisode]'s binding itself.
     *
     * THE DEFAULT ABOVE IS A FALLBACK, NOT AN ENFORCEMENT. It checks and then delegates, so it carries the
     * same check-then-act gap the contract exists to close -- it is correct only for an implementation
     * whose `episodeUuid` cannot change while a seek is in flight. A player that can be RE-POINTED must
     * report true here and override the operation, which is why [LocalPlayer] and [CastPlayer] both do.
     *
     * A BOUND CALLER CAN REFUSE AN UNSUPPORTED PLAYER rather than fall back to an unrestricted seek:
     * `false` here means "do not rely on the default for a binding".
     */
    val enforcesEpisodeBoundSeek: Boolean get() = false
    suspend fun isPlaying(): Boolean
    suspend fun isBuffering(): Boolean
    suspend fun durationMs(): Int?
    suspend fun bufferedUpToMs(): Int
    suspend fun bufferedPercentage(): Int
    fun supportsTrimSilence(): Boolean
    fun supportsVolumeBoost(): Boolean
    fun supportsVideo(): Boolean
    fun setVolume(volume: Float)
    fun setPodcast(podcast: Podcast?)
    fun setEpisode(episode: BaseEpisode, preferStream: Boolean = false)
}
