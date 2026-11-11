package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.EnergyVoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.RejectionReason
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection must open when trailing silence BEGINS, not when the endpoint is decided.
 *
 * The window the optimisation exists to save is the end-of-speech wait plus ASR (~0.45s plus
 * recognition). Opening at the endpoint decision spends all of it; opening on the first silent
 * frame spends none of it. These controls drive a real [EnergyVoiceAudioSegmenter] so the point at
 * which the result is emitted is the point the production segmenter picks.
 */
class TrailingSilenceOpenControlTest {

    private val speechFrame = PcmAudioFrame(
        samples = ShortArray(320) { 16000 },
        sampleRateHz = 16000,
    )

    private val silentFrame = PcmAudioFrame(
        samples = ShortArray(320) { 0 },
        sampleRateHz = 16000,
    )

    @Test
    fun `trailing silence is reported on the first silent frame, before the segment closes`() {
        val segmenter = EnergyVoiceAudioSegmenter()

        assertEquals(VoiceSegmenterResult.SpeechStarted, segmenter.process(speechFrame))
        assertEquals(VoiceSegmenterResult.SpeechContinuing, segmenter.process(speechFrame))

        // The FIRST silent frame is where an early connection can be opened; every later frame is
        // still inside the endpoint wait. Reporting only at SpeechEnded would give back the wait.
        assertEquals(
            VoiceSegmenterResult.TrailingSilenceBegan,
            segmenter.process(silentFrame),
        )
    }

    @Test
    fun `trailing silence fires once per pause, and the segment still closes`() {
        val segmenter = EnergyVoiceAudioSegmenter()
        segmenter.process(speechFrame)
        segmenter.process(speechFrame)

        assertEquals(VoiceSegmenterResult.TrailingSilenceBegan, segmenter.process(silentFrame))
        // Successive silent frames inside the same pause are not new onsets: a second report would
        // look like a second utterance and open a second connection.
        assertEquals(VoiceSegmenterResult.Silence, segmenter.process(silentFrame))
        assertEquals(VoiceSegmenterResult.Silence, segmenter.process(silentFrame))
        // The fourth closes the segment, as before.
        assertTrue(segmenter.process(silentFrame) is VoiceSegmenterResult.SpeechEnded)
    }

    @Test
    fun `silence with no speech before it is not trailing silence`() {
        val segmenter = EnergyVoiceAudioSegmenter()

        // Nothing has been said, so there is no utterance that could be over. Reporting here would
        // let the engine open a connection for speech it never heard.
        assertEquals(VoiceSegmenterResult.Silence, segmenter.process(silentFrame))
        assertEquals(VoiceSegmenterResult.Silence, segmenter.process(silentFrame))
    }

    @Test
    fun `a pause that resumes reports a fresh trailing-silence onset`() {
        val segmenter = EnergyVoiceAudioSegmenter()
        segmenter.process(speechFrame)
        segmenter.process(speechFrame)
        assertEquals(VoiceSegmenterResult.TrailingSilenceBegan, segmenter.process(silentFrame))

        // The user resumes: speech continues the same utterance, and the next pause is a new
        // onset. The engine keys the connection on the utterance tag, so this must not dial again.
        assertEquals(VoiceSegmenterResult.SpeechContinuing, segmenter.process(speechFrame))
        assertEquals(VoiceSegmenterResult.TrailingSilenceBegan, segmenter.process(silentFrame))
    }
}
