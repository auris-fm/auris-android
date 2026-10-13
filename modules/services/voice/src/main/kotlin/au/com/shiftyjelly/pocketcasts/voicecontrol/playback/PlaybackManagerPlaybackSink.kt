package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class PlaybackManagerPlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
    // Qualified: Hilt binds Context only as @ApplicationContext (or @ActivityContext), so a bare
    // Context is a missing binding. Every other injection in this module qualifies it.
    @ApplicationContext private val context: Context,
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

    /**
     * The turn's own hold pause. Identical to [pause] through the playback interface, so it declares
     * its ownership before the call: `VOICE_COMMANDS` is the interface, not the owner, and the user's
     * own pause arrives through the same one.
     */
    override suspend fun pauseAsHold(): VoiceResponse {
        playbackManager.pauseSuspend(sourceView = SourceView.VOICE_COMMANDS, asVoiceHold = true)
        return VoiceResponse.Earcon(EarconId.SUCCESS)
    }

    override suspend fun resume(expectedEpisodeId: String?): EpisodeOperationResult {
        // THROUGH THE OWNER when the caller has an episode-specific subject. A quote's resume is about
        // the recording the quote came from, so it must not start a replacement.
        if (expectedEpisodeId != null) {
            // THE REFUSAL PROPAGATES. Discarding this Boolean made a REFUSED resume indistinguishable
            // from an accepted one, so the caller reported the action as performed. A resume that never
            // happened must not be reported as one.
            val before = playbackManager.getCurrentEpisode()
                ?.takeIf { it.uuid == expectedEpisodeId }
                ?.playedUpToMs
                ?.toLong()
            val accepted = playbackManager.resumeIfPlaying(expectedEpisodeId, sourceView = SourceView.VOICE_COMMANDS)
            return if (accepted) {
                EpisodeOperationResult.Accepted(expectedEpisodeId, before)
            } else {
                EpisodeOperationResult.Refused
            }
        }
        playbackManager.playQueueSuspend(sourceView = SourceView.VOICE_COMMANDS)
        // Episode-independent: no episode-specific subject, so no provenance to report.
        return EpisodeOperationResult.Accepted(episodeId = null, preActionPositionMs = null)
    }

    override suspend fun skipForward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult {
        // SAME RULE AS seekTo: an episode-bound skip is refused when the player has moved to another
        // recording, because the skip's magnitude was chosen for the one it was planned against.
        if (expectedEpisodeId != null) {
            // THROUGH THE OWNER, which re-reads the selection and performs the skip as one step.
            val before = playbackManager.getCurrentEpisode()
                ?.takeIf { it.uuid == expectedEpisodeId }
                ?.playedUpToMs
                ?.toLong()
            // THE OWNER'S TYPED OUTCOME IS HONOURED. A Boolean has no room for "issued without a
            // returned outcome", so collapsing it here would report an unknowable skip as a REFUSAL --
            // a retryable failure for an operation the receiver may already have performed.
            return when (
                playbackManager.skipIfPlaying(
                    episodeUuid = expectedEpisodeId,
                    forward = true,
                    sourceView = SourceView.VOICE_COMMANDS,
                    jumpAmountSeconds = seconds,
                )
            ) {
                is PlaybackManager.SkipOutcome.Accepted ->
                    EpisodeOperationResult.Accepted(expectedEpisodeId, before)

                is PlaybackManager.SkipOutcome.Unknown ->
                    EpisodeOperationResult.Unknown(expectedEpisodeId, before)

                is PlaybackManager.SkipOutcome.Refused -> EpisodeOperationResult.Refused
            }
        }
        playbackManager.skipForwardSuspend(SourceView.VOICE_COMMANDS, seconds)
        return EpisodeOperationResult.Accepted(episodeId = null, preActionPositionMs = null)
    }

    override suspend fun skipBackward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult {
        // SAME RULE AS seekTo: an episode-bound skip is refused when the player has moved to another
        // recording, because the skip's magnitude was chosen for the one it was planned against.
        if (expectedEpisodeId != null) {
            // THROUGH THE OWNER, which re-reads the selection and performs the skip as one step.
            val before = playbackManager.getCurrentEpisode()
                ?.takeIf { it.uuid == expectedEpisodeId }
                ?.playedUpToMs
                ?.toLong()
            // THE OWNER'S TYPED OUTCOME IS HONOURED. A Boolean has no room for "issued without a
            // returned outcome", so collapsing it here would report an unknowable skip as a REFUSAL --
            // a retryable failure for an operation the receiver may already have performed.
            return when (
                playbackManager.skipIfPlaying(
                    episodeUuid = expectedEpisodeId,
                    forward = false,
                    sourceView = SourceView.VOICE_COMMANDS,
                    jumpAmountSeconds = seconds,
                )
            ) {
                is PlaybackManager.SkipOutcome.Accepted ->
                    EpisodeOperationResult.Accepted(expectedEpisodeId, before)

                is PlaybackManager.SkipOutcome.Unknown ->
                    EpisodeOperationResult.Unknown(expectedEpisodeId, before)

                is PlaybackManager.SkipOutcome.Refused -> EpisodeOperationResult.Refused
            }
        }
        playbackManager.skipBackwardSuspend(SourceView.VOICE_COMMANDS, seconds)
        return EpisodeOperationResult.Accepted(episodeId = null, preActionPositionMs = null)
    }

    override suspend fun seekTo(
        positionSeconds: Int,
        episodeDurationSeconds: Int,
        expectedEpisodeId: String?,
    ): EpisodeOperationResult {
        // The schema's signed semantics, resolved here rather than by each caller: a negative value is
        // an offset back from the episode end. Clamping it would silently convert "seek back 30 s
        // from the end" into "seek to the beginning", which is a different instruction.
        val resolvedSeconds = if (positionSeconds < 0) {
            (episodeDurationSeconds + positionSeconds).coerceIn(0, episodeDurationSeconds)
        } else {
            positionSeconds
        }
        // THE OWNING OPERATION CARRIES THE EXPECTATION. A check here followed by a position-only seek
        // is still a RACE: the selection can change between them, and no lock this adapter holds can
        // exclude a change made elsewhere in the app. `seekIfPlayingToTimeMs` is the manager's own
        // episode-scoped seek -- it re-reads the selection INSIDE the operation, immediately before
        // seeking -- so validation and mutation happen AT THE OWNER, not merely consecutively here.
        if (expectedEpisodeId != null) {
            // THE OWNER BOTH DECIDES AND CAPTURES. `seekIfPlayingCapturing` validates the selection and
            // the player's own episode under its boundary AND reports the pre-action position it saw
            // there, so this adapter cannot report a position read from a state that changed between its
            // own observation and the owner's decision.
            val scoped = playbackManager.seekIfPlayingCapturing(
                expectedEpisodeId,
                resolvedSeconds * 1000,
            )
            return when {
                // UNKNOWN SURVIVES TO THE CALLER. The owner issued the request and could not learn its
                // outcome, so reporting Refused here would make an unknowable execution look retryable.
                scoped != null && scoped.unknown ->
                    EpisodeOperationResult.Unknown(scoped.episodeUuid, scoped.preActionPositionMs?.toLong())

                scoped != null ->
                    EpisodeOperationResult.Accepted(scoped.episodeUuid, scoped.preActionPositionMs?.toLong())

                else -> EpisodeOperationResult.Refused
            }
        }
        // NO EXPECTATION MEANS THE CALLER HAS NO EPISODE-SPECIFIC SUBJECT. Legitimate only for an
        // episode-INDEPENDENT operation: an episode-specific one must never reach here with a null,
        // because the null would turn it into an unrestricted seek. Callers refuse those instead.
        playbackManager.seekToTimeMsSuspend(resolvedSeconds * 1000)
        // UNBOUND: no episode-specific subject, so there is no episode or bound position to report.
        return EpisodeOperationResult.Accepted(episodeId = null, preActionPositionMs = null)
    }

    override fun nextEpisode(): VoiceResponse {
        playbackManager.playNextInQueue(sourceView = SourceView.VOICE_COMMANDS)
        return VoiceResponse.Earcon(EarconId.NEXT_EPISODE)
    }
}
