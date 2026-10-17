package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the episode reference (the [FingerprintPcmTap]) must be live INDEPENDENTLY of the
 * transcripts feature: while a voice-capture session is active, the echo reference is required
 * for bleed rejection whatever the transcript toggle says (4235652501). The voice layer sets
 * [setCaptureActive] at the capture lifecycle boundaries; playback reads [isCaptureActive] when
 * the audio processor (re)configures.
 */
@Singleton
class EpisodeReferenceEligibility @Inject constructor() {
    private val captureActive = AtomicBoolean(false)

    fun setCaptureActive(active: Boolean) {
        captureActive.set(active)
    }

    fun isCaptureActive(): Boolean = captureActive.get()

    companion object {
        /**
         * The full tap-eligibility decision: the transcript-feature path keeps its existing
         * constraints (flag, platform, decode policy); an ACTIVE CAPTURE SESSION qualifies on its
         * own. Exposed as a pure function so the flag-off/capture-active boundary is testable
         * without standing up a player.
         */
        fun tapEnabled(captureActive: Boolean, transcriptsEligible: Boolean): Boolean = captureActive || transcriptsEligible
    }
}
