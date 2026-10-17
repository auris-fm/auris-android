package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PlaybackContext {
    data class Active(val currentEpisodeUuid: String, val isPlaying: Boolean) : PlaybackContext
    data object Inactive : PlaybackContext
}

@Singleton
class PlaybackContextMonitor @javax.inject.Inject constructor(
    private val playbackManager: PlaybackManager,
    private val gracePeriodSignal: au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal,
    @ApplicationScope
    private val scope: CoroutineScope,
    /**
     * Whether the stop being classified was a user's SPOKEN pause.
     *
     * `VOICE_COMMANDS` is the interface the command arrived through, not who asked for it: the voice
     * path's hold pause and a user's spoken pause carry the same tag. The executor records the
     * spoken one through [PlaybackManager.noteUserPlaybackCommand], and this reports whether that
     * happened for the current stop, so a user pause is never exempted by its interface.
     */
    private val isSpokenUserPause: Boolean = false,
) {
    val context: StateFlow<PlaybackContext> = playbackManager.playbackStateFlow
        .map(::toPlaybackContext)
        .stateIn(scope, SharingStarted.Eagerly, PlaybackContext.Inactive)

    /**
     * How many play/pause/stop commands the playback layer has seen, including
     * this app's own. Read synchronously rather than through a flow: a caller
     * compares it before and after its own command, and a `stateIn` value can
     * lag the command it is meant to describe.
     */
    fun playbackCommandRevision(): Long = playbackManager.playbackCommandRevision()

    /** The current episode's duration in seconds, for resolving a negative absolute position. */
    fun episodeDurationSeconds(): Int = playbackManager.getCurrentEpisode()?.duration?.toInt() ?: 0

    /**
     * True when a privacy event closed the window. Read by the cloud turn so it does not restore a
     * pause over playback the user's own privacy action ended; a privacy closure does not reach the
     * playback layer, so it cannot be seen through the command revision.
     */
    fun isPrivacyClosed(): Boolean = gracePeriodSignal.isClosedByPrivacy()

    /**
     * Monotonic count of privacy closures. A turn samples it when it takes its pause obligation and
     * again at cleanup: a difference means a privacy event ended this turn, which a live boolean
     * cannot show once a later wake has cleared it.
     */
    fun privacyClosureCount(): Long = gracePeriodSignal.privacyClosureCount()

    /**
     * Monotonic count of closures that coincided with the host's audio actually stopping.
     *
     * The playback-restoration guard reads this rather than [privacyClosureCount]: a visibility
     * change closes the window without touching playback, so counting it here would let a turn-owned
     * pause be stranded by an event that was never a fact about playback.
     */
    fun playbackStopClosureCount(): Long = gracePeriodSignal.playbackStopClosureCount()

    init {
        // Separate the closure kinds at the owning event boundary. A closure that coincides with the
        // host's audio actually stopping is a fact about playback; a visibility change alone is not.
        // The sink's restoration guard reads the playback-stop count, so backgrounding without a
        // playback stop does not strand a turn-owned pause.
        scope.launch {
            var wasPlaying = isHostAudioActive.value
            playbackManager.playbackStateFlow.collect { state ->
                // The cause is read from THIS emission, so a newer command replacing a shared
                // "last command" field cannot re-attribute a stop the observer processes late.
                if (hostStoppedWhileClosed(wasPlaying, state.isPlaying, state.changedByVoiceHold)) {
                    gracePeriodSignal.noteHostPlaybackStopped()
                }
                wasPlaying = state.isPlaying
            }
        }
    }

    /**
     * Whether a playing-to-stopped transition during a closed window counts as a playback-stop
     * closure. Extracted so the counting rule is testable through the same decision the watcher
     * applies, rather than through a counter a test pokes directly.
     *
     * [cause] is the source of the command that produced this state, carried on the emission itself.
     */
    internal fun hostStoppedWhileClosed(
        wasPlaying: Boolean,
        isPlaying: Boolean,
        causedByVoiceHold: Boolean = false,
    ): Boolean = wasPlaying &&
        !isPlaying &&
        gracePeriodSignal.isClosedByPrivacy() &&
        // Ownership, not interface: only a stop the turn caused while HOLDING is exempt. The user's
        // own pause reaches the player through the same VOICE_COMMANDS tag, and a voice-path skip or
        // next-episode carries it too, so the tag says nothing about whose stop this was.
        !causedByVoiceHold

    /**
     * True when the host app (Pocket Casts) is actively playing audio. Used by
     * [OtherAppPlayingCondition], together with a short time-bounded recency window,
     * to distinguish the host's own audio from another app's during a play/pause/route
     * transition (see voice-control-core).
     */
    val isHostAudioActive: StateFlow<Boolean> = context.map {
        it is PlaybackContext.Active && it.isPlaying
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private fun toPlaybackContext(playbackState: PlaybackState): PlaybackContext {
        val currentEpisodeUuid = playbackState.episodeUuid
        return if (currentEpisodeUuid.isNotBlank() && !playbackState.isStopped && !playbackState.isEmpty) {
            PlaybackContext.Active(currentEpisodeUuid = currentEpisodeUuid, isPlaying = playbackState.isPlaying)
        } else {
            PlaybackContext.Inactive
        }
    }
}
