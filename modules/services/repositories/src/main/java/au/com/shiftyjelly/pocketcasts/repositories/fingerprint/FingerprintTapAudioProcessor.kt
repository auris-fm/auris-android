package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Passthrough processor that copies the player's decoded PCM into [FingerprintPcmTap]. */
@OptIn(UnstableApi::class)
class FingerprintTapAudioProcessor(
    private val tap: FingerprintPcmTap,
    /** STATIC eligibility (feature/platform/decode policy); cannot change mid-stream. */
    private val isEnabled: () -> Boolean,
    /** DYNAMIC eligibility (active capture session); checked per buffer and at configure time. */
    private val episodeReferenceEligibility: EpisodeReferenceEligibility? = null,
) : BaseAudioProcessor() {

    // LIFECYCLE TRANSITION IS THE CONTRACT (4235652501, @reviewer 7fc5a3b8): media3 configures the
    // processor chain only when the renderer (re)enables, and a NOT_SET configuration BYPASSES this
    // processor entirely -- a capture session starting during existing playback could then never
    // activate it. The processor therefore stays CONFIGURED whenever it is constructed (the player
    // builds it only when the tap exists) and eligibility is evaluated PER BUFFER in [queueInput]:
    // capture start begins forwarding on the next buffer; teardown stops it -- no reconfiguration,
    // no player restart. The bypass cost of an ineligible-but-configured passthrough is one copy.
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    private fun isEligible(): Boolean = isEnabled() || episodeReferenceEligibility?.isCaptureActive() == true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (isEligible()) tap.onPcm(inputBuffer.asReadOnlyBuffer(), inputAudioFormat)
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }
}
