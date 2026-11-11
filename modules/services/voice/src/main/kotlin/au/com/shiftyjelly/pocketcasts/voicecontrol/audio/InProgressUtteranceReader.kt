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
    /** The capture sample rate, at which [availableSamples] and [speechOnsetSample] are expressed. */
    fun sampleRateHz(): Int

    /** Samples accumulated so far for the utterance in progress, or 0 between utterances. */
    fun availableSamples(): Int

    /** The speech onset inside the utterance in progress, in samples from its start. */
    fun speechOnsetSample(): Int

    /** Reads the utterance in progress into [buffer]; returns how many samples were read. */
    fun readInto(buffer: ShortArray): Int
}

/** The production reader, over the native capture bridge. */
class NativeInProgressUtteranceReader(
    private val hardware: CaptureHardware,
    private val sampleRateHz: Int = OboeConfig.SAMPLE_RATE_HZ,
) : InProgressUtteranceReader {
    override fun sampleRateHz(): Int = sampleRateHz
    override fun availableSamples(): Int = hardware.getInProgressPcmSize()
    override fun speechOnsetSample(): Int = hardware.getInProgressSpeechOnsetSample()
    override fun readInto(buffer: ShortArray): Int = hardware.getInProgressPcm(buffer)
}
