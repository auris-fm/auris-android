package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filters utterances before they reach the intent parser.
 *
 * Two checks:
 * 1. Speaker consistency — Moonshine diarization via [identify_speakers].
 *    First accepted command in a listening session establishes the target speaker;
 *    subsequent utterances from other speaker indices are dropped.
 * 2. Playback bleed rejection — cross-correlation of mic audio against the
 *    playback buffer. Disabled when using a headset (no acoustic path from
 *    speaker to mic).
 */
@Singleton
class UtteranceFilter @Inject constructor(
    private val playbackCorrelator: PlaybackCrossCorrelator,
    private val audioRouteMonitor: AudioRouteMonitor,
) {
    private var sessionTargetSpeaker: Int? = null

    fun reset() {
        sessionTargetSpeaker = null
    }

    fun shouldProcess(
        audio: FloatArray,
        hasSpeakerId: Boolean,
        speakerIndex: Int,
        playbackBuffer: FloatArray,
    ): Boolean = shouldProcessReference(
        audio = audio,
        hasSpeakerId = hasSpeakerId,
        speakerIndex = speakerIndex,
        reference = PlaybackReference(playbackBuffer, 16_000, startPositionMs = null),
    )

    /**
     * As above, but with the playback reference's player-timeline position. Prefer this overload
     * whenever the recorder could stamp the window: the position is what lets the correlator align
     * to a moment rather than to a buffer offset.
     */
    fun shouldProcessReference(
        audio: FloatArray,
        hasSpeakerId: Boolean,
        speakerIndex: Int,
        reference: PlaybackReference,
        // The instant this audio was CAPTURED, in milliseconds on the same monotonic clock the
        // reference's positions use, or null when the capture stream reported no timestamp. This is
        // the other half of the alignment: the reference says WHERE in the player timeline a window
        // sits and this says WHEN it was recorded, so correlation is restricted to physically
        // plausible delays rather than to buffer offsets that only look like delays. Null keeps the
        // previous offset-based behaviour rather than assuming a position, because an assumed
        // instant aligns confidently against the wrong moment.
        micPositionMs: Long? = null,
    ): Boolean {
        if (hasSpeakerId) {
            if (sessionTargetSpeaker == null) {
                sessionTargetSpeaker = speakerIndex
            } else if (speakerIndex != sessionTargetSpeaker) {
                return false
            }
        }

        if (audioRouteMonitor.route.value !is AudioRoute.Headset) {
            if (playbackCorrelator.isPlaybackBleed(audio, reference, micPositionMs)) {
                return false
            }
        }

        return true
    }
}
