package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SileroVadSegmenter @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceAudioSegmenter {

    private val vad = Vad.builder()
        .setContext(context)
        .setSampleRate(SampleRate.SAMPLE_RATE_16K)
        .setFrameSize(FrameSize.FRAME_SIZE_1024)
        .setMode(Mode.NORMAL)
        .setSpeechDurationMs(64)
        .setSilenceDurationMs(400)
        .build()

    private val vadFrameSize = 1024 // 64ms at 16kHz

    private val pending = mutableListOf<Short>()

    private var speechActive = false
    private var speechFrames = 0
    private var speechStartTimeMs: Long = 0L
    private var consecutiveSilentFrames = 0
    private val accumulatedFrames = mutableListOf<PcmAudioFrame>()
    private val maxSpeechDurationMs = 5_000L
    private var cooldownUntilMs: Long = 0L

    // 400ms of silence at ~64ms/frame = ~7 frames
    private val silenceTimeoutFrames = 7

    /** Rolling buffer of recent frames to include as context before VAD triggers. */
    private val contextFrames = ArrayDeque<PcmAudioFrame>()
    private val maxContextFrames = 20 // ~1.28s of pre-speech context (1024 samples/frame @16kHz = 64ms)

    /** Post-speech drain: keep accumulating frames after silence so whisper sees trailing audio. */
    private val postSpeechDrainFrames = 10 // ~640ms
    private var drainRemaining = 0

    override fun process(frame: PcmAudioFrame): VoiceSegmenterResult {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) {
            contextFrames.clear()
            pending.clear()
            return VoiceSegmenterResult.Silence
        }

        if (speechActive && now - speechStartTimeMs > maxSpeechDurationMs) {
            val segment = if (speechFrames >= 1) accumulatedFrames.toList() else null
            reset()
            return if (segment != null) {
                VoiceSegmenterResult.SpeechEnded(segment)
            } else {
                VoiceSegmenterResult.Rejected(RejectionReason.Timeout)
            }
        }

        pending.addAll(frame.samples.toList())

        var currentSpeech = false
        while (pending.size >= vadFrameSize) {
            val chunk = ShortArray(vadFrameSize)
            for (i in 0 until vadFrameSize) {
                chunk[i] = pending.removeFirst()
            }
            val rms = kotlin.math.sqrt(chunk.map { (it * it).toDouble() }.average())
            if (rms >= 500.0 && vad.isSpeech(chunk)) {
                currentSpeech = true
            }
        }

        if (currentSpeech) {
            consecutiveSilentFrames = 0
            drainRemaining = 0 // Cancel any drain if speech resumes
            if (!speechActive) {
                contextFrames.forEach { accumulatedFrames.add(it) }
            }
            accumulatedFrames.add(frame)
            speechFrames++
            if (!speechActive) {
                speechActive = true
                speechStartTimeMs = now
                return VoiceSegmenterResult.SpeechStarted
            }
            return VoiceSegmenterResult.SpeechContinuing
        }

        // Keep rolling context when not in speech or drain
        if (!speechActive && drainRemaining <= 0) {
            contextFrames.addLast(frame)
            if (contextFrames.size > maxContextFrames) contextFrames.removeFirst()
        }

        if (speechActive) {
            consecutiveSilentFrames++
            if (consecutiveSilentFrames >= silenceTimeoutFrames) {
                // Enter or continue post-speech drain
                if (drainRemaining <= 0) {
                    drainRemaining = postSpeechDrainFrames
                }
                drainRemaining--
                accumulatedFrames.add(frame)
                if (drainRemaining > 0) {
                    return VoiceSegmenterResult.SpeechContinuing
                } else {
                    val segment = accumulatedFrames.toList()
                    reset()
                    return VoiceSegmenterResult.SpeechEnded(segment)
                }
            }
            accumulatedFrames.add(frame)
            return VoiceSegmenterResult.SpeechContinuing
        }

        return VoiceSegmenterResult.Silence
    }

    private fun reset() {
        pending.clear()
        speechActive = false
        speechFrames = 0
        speechStartTimeMs = 0L
        consecutiveSilentFrames = 0
        accumulatedFrames.clear()
        contextFrames.clear()
        drainRemaining = 0
        cooldownUntilMs = System.currentTimeMillis() + 1500L
    }
}
