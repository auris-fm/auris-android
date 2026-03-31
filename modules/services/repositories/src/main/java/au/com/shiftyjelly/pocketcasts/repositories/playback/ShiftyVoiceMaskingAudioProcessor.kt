package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos

@OptIn(UnstableApi::class)
class ShiftyVoiceMaskingAudioProcessor : BaseAudioProcessor() {
    var enabled: Boolean = false

    private var randomState: Int = 0x1EAF83D
    private var sampleRate: Int = 0
    private var channelCount: Int = 1
    private var framesIntoSegment: Long = 0
    private var segmentFrames: Long = 1
    private var pulseActive: Boolean = true
    private var pulseFrames: Long = 1
    private var maskDepth: Float = 0.72f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        resetPulseState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val size = inputBuffer.remaining()
        val usePassthrough = !enabled || sampleRate <= 0
        val passthroughInput = if (usePassthrough) inputBuffer.duplicate() else null
        val output = replaceOutputBuffer(size)

        if (usePassthrough) {
            val isAliasedBuffer = output === inputBuffer
            output.put(passthroughInput!!).flip()
            if (!isAliasedBuffer) {
                inputBuffer.position(inputBuffer.limit())
            }
            return
        }

        val inBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val outBuffer = output.order(ByteOrder.LITTLE_ENDIAN)

        var channelIndex = 0
        while (inBuffer.remaining() >= Short.SIZE_BYTES) {
            val sample = inBuffer.short.toInt()
            val envelope = if (pulseActive) pulseEnvelope(framesIntoSegment, pulseFrames) else 0f
            val attenuation = 1f - (maskDepth * envelope)
            val maskedSample = (sample * attenuation).toInt()
            outBuffer.putShort(maskedSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())

            channelIndex++
            if (channelIndex == channelCount) {
                channelIndex = 0
                advanceSegment()
            }
        }

        outBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        randomState = 0x1EAF83D
        resetPulseState()
    }

    override fun onReset() {
        enabled = false
        randomState = 0x1EAF83D
        sampleRate = 0
        channelCount = 1
        framesIntoSegment = 0
        segmentFrames = 1
        pulseActive = true
        pulseFrames = 1
        maskDepth = 0.72f
    }

    private fun advanceSegment() {
        framesIntoSegment++
        if (framesIntoSegment < segmentFrames) {
            return
        }

        framesIntoSegment = 0
        if (pulseActive) {
            pulseActive = false
            segmentFrames = nextSegmentFrames(minMs = 130f, maxMs = 420f)
        } else {
            pulseActive = true
            pulseFrames = nextSegmentFrames(minMs = 220f, maxMs = 720f)
            segmentFrames = pulseFrames
            maskDepth = nextRandomFloat(min = 0.58f, max = 0.86f)
        }
    }

    private fun resetPulseState() {
        framesIntoSegment = 0
        pulseActive = true
        pulseFrames = nextSegmentFrames(minMs = 220f, maxMs = 720f)
        segmentFrames = pulseFrames
        maskDepth = nextRandomFloat(min = 0.58f, max = 0.86f)
    }

    private fun pulseEnvelope(frameIndex: Long, totalFrames: Long): Float {
        val halfFrames = (totalFrames / 2).coerceAtLeast(1L)
        val edgeFrames = (totalFrames * 0.22f).toLong().coerceAtLeast(1L).coerceAtMost(halfFrames)
        if (edgeFrames <= 1L || totalFrames <= 2L * edgeFrames) {
            return 1f
        }

        return when {
            frameIndex < edgeFrames -> easedRamp(frameIndex, edgeFrames)
            frameIndex >= totalFrames - edgeFrames -> {
                val framesFromEnd = totalFrames - frameIndex - 1
                easedRamp(framesFromEnd, edgeFrames)
            }
            else -> 1f
        }
    }

    private fun easedRamp(frameIndex: Long, edgeFrames: Long): Float {
        val progress = ((frameIndex.toDouble() + 1.0) / edgeFrames.toDouble()).coerceIn(0.0, 1.0)
        return (0.5 - 0.5 * cos(PI * progress)).toFloat()
    }

    private fun nextSegmentFrames(minMs: Float, maxMs: Float): Long {
        if (sampleRate <= 0) {
            return 1L
        }
        val durationMs = nextRandomFloat(min = minMs, max = maxMs)
        return ((durationMs / 1000f) * sampleRate).toLong().coerceAtLeast(1L)
    }

    private fun nextRandomFloat(min: Float, max: Float): Float {
        randomState = randomState * 1664525 + 1013904223
        val normalized = ((randomState ushr 1).toLong() and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
        return min + ((max - min) * normalized)
    }
}
