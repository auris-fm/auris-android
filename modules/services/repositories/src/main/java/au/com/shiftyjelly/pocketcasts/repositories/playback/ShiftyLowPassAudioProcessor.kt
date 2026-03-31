package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class ShiftyLowPassAudioProcessor : BaseAudioProcessor() {
    var enabled: Boolean = false

    private var channelCount: Int = 1
    private var previousSamples: FloatArray = FloatArray(1)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        if (previousSamples.size != channelCount) {
            previousSamples = FloatArray(channelCount)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val size = inputBuffer.remaining()
        val passthroughInput = if (!enabled) inputBuffer.duplicate() else null
        val output = replaceOutputBuffer(size)

        if (!enabled) {
            val isAliasedBuffer = output === inputBuffer
            output.put(passthroughInput!!).flip()
            if (!isAliasedBuffer) {
                inputBuffer.position(inputBuffer.limit())
            }
            return
        }

        val inBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val outBuffer = output.order(ByteOrder.LITTLE_ENDIAN)

        val alpha = 0.15f
        var channelIndex = 0

        while (inBuffer.remaining() >= Short.SIZE_BYTES) {
            val raw = inBuffer.short.toFloat()
            val filtered = previousSamples[channelIndex] + alpha * (raw - previousSamples[channelIndex])
            previousSamples[channelIndex] = filtered
            outBuffer.putShort(filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())

            channelIndex = (channelIndex + 1) % channelCount
        }

        outBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        previousSamples.fill(0f)
    }

    override fun onReset() {
        enabled = false
        channelCount = 1
        previousSamples = FloatArray(1)
    }
}
