package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where voicing actually stopped — the number the wake-only rule measures. */
class EnergyVoiceAudioSegmenterTest {

    private fun voiced(samples: Int = 160) = PcmAudioFrame(ShortArray(samples) { 5_000 }, 16_000)
    private fun silent(samples: Int = 160) = PcmAudioFrame(ShortArray(samples), 16_000)

    @Test
    fun `voiced end is the last voiced sample, not a prefix count`() {
        val segmenter = EnergyVoiceAudioSegmenter()

        // Speech, a short pause, then speech again: frames alternate, so counting
        // the first N frames would land on the pause and miss the second
        // utterance — which is exactly the measurement the rule depends on.
        segmenter.process(voiced())
        segmenter.process(silent())
        segmenter.process(voiced())

        var ended: VoiceSegmenterResult.SpeechEnded? = null
        repeat(4) {
            if (ended == null) {
                val result = segmenter.process(silent())
                if (result is VoiceSegmenterResult.SpeechEnded) ended = result
            }
        }

        assertEquals("voicing stopped at the end of the second utterance", 480, ended?.speechEndSample)
    }
}
