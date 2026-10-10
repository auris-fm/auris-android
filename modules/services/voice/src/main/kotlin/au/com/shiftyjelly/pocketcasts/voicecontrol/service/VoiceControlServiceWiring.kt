package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import timber.log.Timber

/**
 * The production connection between an undelivered spoken reply and user-visible feedback.
 *
 * Extracted so the connection itself is testable: a test that installs its own callback verifies
 * that callback, not that the service installs one. This function is the thing the service calls,
 * so a test can assert its effect and **fail if this wiring is removed**, which is the property the
 * inline assignment could not have.
 */
object VoiceControlServiceWiring {

    /**
     * Attaches the delivery-failure handler to [renderer]. The handler reports the local failure and
     * raises the same user-visible feedback the cloud path uses; it does not replay the reply and
     * does not emit a successful-drain signal.
     */
    fun attachPlaybackFailureHandling(renderer: AudioFeedbackRenderer) {
        renderer.onPlaybackFailure = { error ->
            Timber.e(error, "voice_response_not_delivered: the spoken reply did not finish playing")
            // The user hears that the reply was cut short, through the existing feedback path.
            renderer.playEarcon(EarconId.ERROR)
        }
    }
}
