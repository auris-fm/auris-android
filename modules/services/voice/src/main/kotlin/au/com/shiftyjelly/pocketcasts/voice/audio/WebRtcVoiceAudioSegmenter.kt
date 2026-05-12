package au.com.shiftyjelly.pocketcasts.voice.audio

import android.content.Context
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcVoiceAudioSegmenter @Inject constructor(
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
    private val accumulatedFrames = mutableListOf<PcmAudioFrame>()
    private val maxSpeechDurationMs = 5_000L
    private var cooldownUntilMs: Long = 0L

    /** Rolling buffer of recent frames to include as context before VAD triggers. */
    private val contextFrames = ArrayDeque<PcmAudioFrame>()
    private val maxContextFrames = 5 // ~600ms of context at 120ms/frame

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
            if (vad.isSpeech(chunk)) {
                currentSpeech = true
            }
        }

        if (currentSpeech) {
            if (!speechActive) {
                contextFrames.forEach { accumulatedFrames.add(it) }
            }
            accumulatedFrames.add(frame)
            speechFrames++
            if (!speechActive) {
                speechActive = true
                speechStartTimeMs = now
                contextFrames.addLast(frame)
                if (contextFrames.size > maxContextFrames) contextFrames.removeFirst()
                return VoiceSegmenterResult.SpeechStarted
            }
            contextFrames.addLast(frame)
            if (contextFrames.size > maxContextFrames) contextFrames.removeFirst()
            return VoiceSegmenterResult.SpeechContinuing
        }

        // Keep rolling context, don't add if it would push out speech frames
        if (!speechActive) {
            contextFrames.addLast(frame)
            if (contextFrames.size > maxContextFrames) contextFrames.removeFirst()
        }

        if (speechActive) {
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
        accumulatedFrames.clear()
        contextFrames.clear()
        cooldownUntilMs = System.currentTimeMillis() + 1500L
    }
}
