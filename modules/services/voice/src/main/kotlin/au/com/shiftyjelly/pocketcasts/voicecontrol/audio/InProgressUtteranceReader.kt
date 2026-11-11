package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.EarlyWakeScore

/**
 * The utterance the native VAD is still accumulating, before the segment has closed.
 *
 * Wake detection scores the first [EarlyWakeScore.CUTOFF_SAMPLES] after onset regardless of how
 * long the utterance runs, so for any utterance longer than that the answer is already determined
 * while the user is still speaking. This reader is what makes an early score possible: without it
 * the engine cannot see audio the segmenter has not yet reported, and the verdict can only exist
 * after the endpoint wait it exists to overlap.
 */
interface InProgressUtteranceReader {
    /** The capture sample rate, at which the snapshot's onset and size are expressed. */
    fun sampleRateHz(): Int

    /**
     * ONE coherent snapshot of the utterance in progress: PCM copied into [buffer], and
     * [header] filled with `[generation, onsetSample, sizeSamples]`. All four values come from a
     * single locked native call, so they can never describe different utterances. Returns the
     * number of samples copied, or 0 when no utterance is in progress.
     *
     * The [generation] changes at every utterance boundary (finalization or fault reset). A caller
     * that recorded the generation of its utterance drops a snapshot carrying any other: that
     * snapshot describes audio the caller's utterance does not contain.
     */
    fun readSnapshot(buffer: ShortArray, header: LongArray): Int
}

/** The production reader, over the native capture bridge. */
class NativeInProgressUtteranceReader(
    private val hardware: CaptureHardware,
    private val sampleRateHz: Int = OboeConfig.SAMPLE_RATE_HZ,
) : InProgressUtteranceReader {
    override fun sampleRateHz(): Int = sampleRateHz
    override fun readSnapshot(buffer: ShortArray, header: LongArray): Int = hardware.getInProgressSnapshot(buffer, header)
}
