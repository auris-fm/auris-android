package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages audio focus with local media player.
 */
abstract class LocalPlayer(override val onPlayerEvent: (Player, PlayerEvent) -> Unit) : Player {

    companion object {
        // The volume we set the media player to seekToTimeMswhen we lose audio focus, but are allowed to reduce the volume instead of stopping playback.
        const val VOLUME_DUCK = 0.5f

        // The volume we set the media player when we have audio focus.
        const val VOLUME_NORMAL = 1.0f
    }

    // playback position for starting or resuming from focus lost
    @Volatile private var positionMs: Int = 0

    private var seekingToPositionMs: Int = 0
    private var seekRetryAllowed: Boolean = false

    protected var episodeLocation: EpisodeLocation? = null

    private val episode get() = episodeLocation?.episode

    override val episodeUuid: String? get() = episode?.uuid

    override val isDownloading: Boolean get() = episode?.isDownloaded == true

    override val url: String?
        get() = (episodeLocation as? EpisodeLocation.Stream)?.uri

    override val filePath: String?
        get() = (episodeLocation as? EpisodeLocation.Downloaded)?.uri

    override val isRemote: Boolean
        get() = false

    override val isStreaming: Boolean
        get() = episodeLocation is EpisodeLocation.Stream

    override val name: String
        get() = "System"

    abstract fun handlePrepare()
    abstract fun handleStop()
    abstract fun handlePause()
    abstract fun handlePlay()

    /** [completionToken] is the invocation identity of THIS request; the completion it produces carries it back. */
    abstract fun handleSeekToTimeMs(positionMs: Int, completionToken: String?)
    abstract fun handleIsBuffering(): Boolean
    abstract fun handleIsPrepared(): Boolean
    abstract fun handleCurrentPositionMs(): Int

    override suspend fun load(currentPositionMs: Int) {
        withContext(Dispatchers.Main) {
            this@LocalPlayer.positionMs = currentPositionMs
            handlePrepare()
            seekToTimeMs(currentPositionMs)
        }
    }

    // downloaded episodes don't buffer
    override suspend fun isBuffering(): Boolean {
        return withContext(Dispatchers.Main) {
            if (filePath != null) false else handleIsBuffering()
        }
    }

    override suspend fun play(currentPositionMs: Int) {
        withContext(Dispatchers.Main) {
            this@LocalPlayer.positionMs = currentPositionMs

            handlePrepare()
            playIfAllowed()
        }
    }

    override suspend fun pause() {
        withContext(Dispatchers.Main) {
            if (isPlaying()) {
                handlePause()
                positionMs = handleCurrentPositionMs()
            }
            onPlayerEvent(this@LocalPlayer, PlayerEvent.PlayerPaused)
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            positionMs = handleCurrentPositionMs()
            handleStop()
        }
    }

    override suspend fun getCurrentPositionMs(): Int {
        return withContext(Dispatchers.Main) {
            handleCurrentPositionMs()
        }
    }

    protected fun onError(event: PlayerEvent.PlayerError) {
        onPlayerEvent(this, event)
    }

    private suspend fun playIfAllowed() {
        setVolume(VOLUME_NORMAL)

        // already playing?
        if (isPlaying()) {
            onPlayerEvent(this, PlayerEvent.PlayerPlaying)
        } else {
            // check the player is seeked to the correct position
            val playerPositionMs = getCurrentPositionMs()
            // check if the player is where it's meant to be, allow for a 2 second variance
            if (Math.abs(positionMs - playerPositionMs) > 2000) {
                onSeekStart(positionMs)
                // AN UNTRACKED UI PLAY-CARRYING SEEK CARRIES NO TOKEN.
                handleSeekToTimeMs(positionMs, null)
            }
            handlePlay()
            onPlayerEvent(this, PlayerEvent.PlayerPlaying)
        }
    }

    private fun onSeekStart(positionMs: Int) {
        this.seekingToPositionMs = positionMs
        this.seekRetryAllowed = true
    }

    protected fun onSeekComplete(positionMs: Int, completionToken: String?) {
        // Fix for the BLU phone. With a new media player (also after a hibernate) the MediaTek player call switches to an invalid time.
        if (positionMs < seekingToPositionMs - 5000 && seekRetryAllowed) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Player issue was meant to be %.3f but was %.3f", seekingToPositionMs / 1000f, positionMs / 1000f)
            SystemClock.sleep(100)
            seekRetryAllowed = false
            // THE RETRY IS THE SAME INVOCATION: it re-issues under the SAME request token, so the
            // eventual completion is still addressable to the operation that asked for it.
            handleSeekToTimeMs(seekingToPositionMs, completionToken)
            return
        }
        this.positionMs = positionMs
        // THE COMPLETION CARRIES ITS OWN REQUEST'S TOKEN. [completionToken] was supplied BY the seek
        // request this callback belongs to -- not read from any shared state -- so even overlapping
        // native requests cannot misattribute a completion: each callback is born with its own
        // request's identity.
        onPlayerEvent(this, PlayerEvent.SeekComplete(positionMs, completionToken))
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "LocalPlayer onSeekComplete %.3f", positionMs / 1000f)
    }

    protected fun onDurationAvailable() {
        onPlayerEvent(this, PlayerEvent.DurationAvailable)
    }

    protected fun onCompletion() {
        onPlayerEvent(this, PlayerEvent.Completion(episodeUuid))
    }

    protected fun onBufferingStateChanged() {
        if (isStreaming) {
            onPlayerEvent(this, PlayerEvent.BufferingStateChanged)
        }
    }

    protected fun onMetadataAvailable(episodeMetadata: EpisodeFileMetadata) {
        onPlayerEvent(this, PlayerEvent.MetadataAvailable(episodeMetadata))
    }

    protected fun onEpisodeChanged(episodeUuid: String) {
        onPlayerEvent(this, PlayerEvent.EpisodeChanged(episodeUuid))
    }

    protected fun onVideoTrackChanged(hasVideo: Boolean) {
        onPlayerEvent(this, PlayerEvent.VideoTrackChanged(hasVideo))
    }

    override suspend fun seekToTimeMs(positionMs: Int) {
        withContext(Dispatchers.Main) {
            if (positionMs < 0) {
                return@withContext
            }

            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "LocalPlayer seekToToTimeMs %.3f", positionMs / 1000f)

            this@LocalPlayer.positionMs = positionMs
            // AN UNTRACKED UI SEEK CARRIES NO TOKEN: its completion carries null, never another
            // request's identity.
            if (handleIsPrepared()) {
                onSeekStart(positionMs)
                handleSeekToTimeMs(positionMs, null)
            }
        }
    }

    /**
     * Guards the episode against the bound seek's read.
     *
     * BEING "ADJACENT ON MAIN" IS NOT EXCLUSION. The bound seek decides on `Dispatchers.Main`, but
     * `setEpisode` is a SYNCHRONOUS SETTER that any thread can call, so it can still land between the
     * check and the mutation -- the read and the write are ordered only if they share something. A plain
     * monitor is that something: a lock cannot span a suspension, so the seek reads and decides while
     * holding it, and the setter waits rather than interleaving.
     */
    private val episodeLock = Any()

    override val enforcesEpisodeBoundSeek: Boolean get() = true

    /** Milliseconds on the SAME monotonic epoch the admitted deadline was minted on; seam for deterministic controls. */
    open fun admittedClockMs(): Long = System.nanoTime() / 1_000_000L

    override suspend fun seekToTimeMsIfEpisode(positionMs: Int, expectedEpisodeUuid: String, completionToken: String?, admittedDeadlineMs: Long?): Player.EpisodeSeekOutcome {
        return withContext(Dispatchers.Main) {
            if (positionMs < 0) return@withContext Player.EpisodeSeekOutcome.Refused
            // THE CHECK, THE PRE-ACTION SNAPSHOT AND THE MUTATION ARE ONE STEP, under the lock
            // `setEpisode` writes under. The position is read HERE, so it describes what the player was
            // at the moment it decided -- not what a caller read around the call.
            synchronized(episodeLock) {
                if (episodeLocation?.episode?.uuid != expectedEpisodeUuid) {
                    LogBuffer.i(LogBuffer.TAG_PLAYBACK, "LocalPlayer refused a seek: episode changed")
                    return@synchronized Player.EpisodeSeekOutcome.Refused
                }
                // The NON-SUSPENDING inner form: the public one wraps in withContext, which a monitor cannot hold.
                val before = handleCurrentPositionMs().toLong()
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "LocalPlayer seekToToTimeMs %.3f", positionMs / 1000f)
                // THE DEADLINE IS ENFORCED AT THE MUTATION, under the same lock and BEFORE the
                // seek is issued: past-deadline here means no effect, so this is a refusal and no
                // completion can ever arrive to be misattributed.
                if (admittedDeadlineMs != null && admittedClockMs() >= admittedDeadlineMs) {
                    Timber.w("[Playback] episode-bound seek refused: admitted deadline expired at the mutation")
                    return@withContext Player.EpisodeSeekOutcome.Refused
                }
                this@LocalPlayer.positionMs = positionMs
                // THE TOKEN TRAVELS WITH THE REQUEST ITSELF: the completion this seek produces is
                // invoked with THIS token by this request site, never read back from shared state.
                if (handleIsPrepared()) {
                    onSeekStart(positionMs)
                    handleSeekToTimeMs(positionMs, completionToken)
                }
                // ACCEPTED WITH ITS POSITION: the seek happened and the pre-action value is known.
                Player.EpisodeSeekOutcome.accepted(before)
            }
        }
    }

    override fun setEpisode(episode: BaseEpisode, preferStream: Boolean) {
        // WRITTEN UNDER THE SAME LOCK THE BOUND SEEK READS AND DECIDES UNDER, and SYNCHRONOUSLY so a
        // caller that relies on the new episode sees it when this returns.
        val prepared = EpisodeLocation.create(episode, preferStream)
        synchronized(episodeLock) {
            episodeLocation = prepared
        }
    }

    override suspend fun setPlaybackEffects(playbackEffects: PlaybackEffects) {}
}
