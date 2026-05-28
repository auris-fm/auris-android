package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

object NativeVad {
    init {
        System.loadLibrary("pocketcasts_voice_capture")
    }

    external fun nativeInit(assetManager: android.content.res.AssetManager): Boolean
    external fun nativeIsSpeech(samples: ShortArray): Float
    external fun nativeClose()
}

@Singleton
class NativeVadSegmenter @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceAudioSegmenter {

    private var initialized = false

    private val vadFrameSize = 1024 // 64ms at 16kHz

    private val pending = ShortArray(vadFrameSize * 4)
    private var pendingPos = 0

    private var speechActive = false
    private var speechFrames = 0
    private var speechStartTimeMs: Long = 0L
    private var consecutiveSilentFrames = 0
    private val accumulatedFrames = mutableListOf<PcmAudioFrame>()
    private val maxSpeechDurationMs = 5_000L
    private var cooldownUntilMs: Long = 0L

    private val silenceTimeoutFrames = 7 // ~448ms of silence

    private val contextFrames = ArrayDeque<PcmAudioFrame>()
    private val maxContextFrames = 20 // ~1.28s pre-speech context

    private val postSpeechDrainFrames = 10 // ~640ms
    private var drainRemaining = 0

    private val speechThreshold = 0.1f

    private var debugSampleCount = 0
    private var isFirstSilent = true

    private fun ensureInit() {
        if (initialized) return
        // Init on first call — defers ONNX loading until engine is running.
        initialized = NativeVad.nativeInit(context.assets)
        if (!initialized) {
            Timber.e("NativeVAD init failed")
        }
    }

    override fun process(frame: PcmAudioFrame): VoiceSegmenterResult {
        ensureInit()
        if (!initialized) {
            return fallbackEnergyVad(frame)
        }

        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) {
            pendingPos = 0
            contextFrames.clear()
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

        // Diagnostic: log state before buffer loop to catch the -514 bug
        if (pendingPos < 0 || pendingPos > pending.size) {
            Timber.e(
                "VAD DIAG: bad pendingPos=%d pending.size=%d frame.samples.size=%d",
                pendingPos,
                pending.size,
                frame.samples.size,
            )
            pendingPos = 0
        }

        // Buffer incoming samples into the fixed-size pending array.
        try {
            for (s in frame.samples) {
                if (pendingPos < pending.size) {
                    pending[pendingPos++] = s
                }
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            Timber.e(
                e,
                "VAD DIAG: crash in buffer loop pendingPos=%d pending.size=%d frame.samples.size=%d thread=%s",
                pendingPos,
                pending.size,
                frame.samples.size,
                Thread.currentThread().name,
            )
            throw e
        }

        var currentSpeech = false
        var maxProb = 0f
        while (pendingPos >= vadFrameSize) {
            val chunk = pending.copyOfRange(0, vadFrameSize)
            val remaining = pendingPos - vadFrameSize
            if (remaining > 0) System.arraycopy(pending, vadFrameSize, pending, 0, remaining)
            pendingPos = remaining
            val prob = NativeVad.nativeIsSpeech(chunk)
            if (prob > maxProb) maxProb = prob
            if (prob >= speechThreshold) {
                currentSpeech = true
            }
        }

        // Log RMS and VAD probability for first 200 frames to debug audio levels
        if (debugSampleCount < 200) {
            val rms = kotlin.math.sqrt(frame.samples.map { (it * it).toDouble() }.average())
            if (maxProb > 0f || debugSampleCount < 40) {
                Timber.i("VAD: rms=%.0f prob=%.3f samples=%d", rms, maxProb, frame.samples.size)
            }
            debugSampleCount++
        }

        if (currentSpeech && !speechActive) {
            Timber.i("VAD: speech started")
        }
        if (!currentSpeech && speechActive && !isFirstSilent) {
            Timber.i("VAD: speech ended")
            isFirstSilent = true
        }
        if (currentSpeech) isFirstSilent = false

        if (currentSpeech) {
            consecutiveSilentFrames = 0
            drainRemaining = 0
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

        if (!speechActive && drainRemaining <= 0) {
            contextFrames.addLast(frame)
            if (contextFrames.size > maxContextFrames) contextFrames.removeFirst()
        }

        if (speechActive) {
            consecutiveSilentFrames++
            if (consecutiveSilentFrames >= silenceTimeoutFrames) {
                if (drainRemaining <= 0) drainRemaining = postSpeechDrainFrames
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

    private fun fallbackEnergyVad(frame: PcmAudioFrame): VoiceSegmenterResult {
        val rms = kotlin.math.sqrt(frame.samples.map { (it * it).toDouble() }.average())
        val isSpeech = rms >= 1500.0

        if (isSpeech && !speechActive) {
            speechActive = true
            speechFrames = 1
            speechStartTimeMs = System.currentTimeMillis()
            accumulatedFrames.add(frame)
            return VoiceSegmenterResult.SpeechStarted
        }
        if (isSpeech && speechActive) {
            accumulatedFrames.add(frame)
            speechFrames++
            return VoiceSegmenterResult.SpeechContinuing
        }
        if (!isSpeech && speechActive) {
            accumulatedFrames.add(frame)
            consecutiveSilentFrames++
            if (consecutiveSilentFrames >= silenceTimeoutFrames) {
                val segment = accumulatedFrames.toList()
                reset()
                return VoiceSegmenterResult.SpeechEnded(segment)
            }
            return VoiceSegmenterResult.SpeechContinuing
        }
        return VoiceSegmenterResult.Silence
    }

    private fun reset() {
        pendingPos = 0
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
