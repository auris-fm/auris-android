package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import au.com.shiftyjelly.pocketcasts.models.to.NoiseEnvironmentMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ShiftyNoiseAudioProcessorTest {

    @Test
    fun `pass through does not crash when input reuses previous output buffer`() {
        val processor = ShiftyNoiseAudioProcessor().apply { enabled = false }
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val firstInput = pcm16Buffer(1_234, -2_048)
        processor.queueInput(firstInput)
        val recycledBuffer = processor.output
        val expected = readPcm16Samples(recycledBuffer)

        processor.queueInput(recycledBuffer)
        val secondOutput = processor.output

        assertEquals(expected, readPcm16Samples(secondOutput))
    }

    @Test
    fun `voice masking pass through does not crash when input reuses previous output buffer`() {
        val processor = ShiftyVoiceMaskingAudioProcessor().apply { enabled = false }
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val firstInput = pcm16Buffer(100, -200)
        processor.queueInput(firstInput)
        val recycledBuffer = processor.output
        val expected = readPcm16Samples(recycledBuffer)

        processor.queueInput(recycledBuffer)
        val secondOutput = processor.output

        assertEquals(expected, readPcm16Samples(secondOutput))
    }

    @Test
    fun `low pass pass through does not crash when input reuses previous output buffer`() {
        val processor = ShiftyLowPassAudioProcessor().apply { enabled = false }
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val firstInput = pcm16Buffer(321, -654)
        processor.queueInput(firstInput)
        val recycledBuffer = processor.output
        val expected = readPcm16Samples(recycledBuffer)

        processor.queueInput(recycledBuffer)
        val secondOutput = processor.output

        assertEquals(expected, readPcm16Samples(secondOutput))
    }

    @Test
    fun `noise filter remains continuous without long silent gaps`() {
        val processor = ShiftyNoiseAudioProcessor().apply { enabled = true }
        processor.configure(AudioProcessor.AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        processor.queueInput(pcm16ConstantBuffer(sample = 0, sampleCount = 4_000))
        val output = readPcm16Samples(processor.output)
        val longestNearSilentRun = longestRun(output) { abs(it.toInt()) <= 2 }

        assertTrue(output.any { it.toInt() != 0 })
        assertTrue(longestNearSilentRun < 80)
    }

    @Test
    fun `voice masking filter fades in before full attenuation`() {
        val processor = ShiftyVoiceMaskingAudioProcessor().apply { enabled = true }
        processor.configure(AudioProcessor.AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        processor.queueInput(pcm16ConstantBuffer(sample = 12_000, sampleCount = 2_000))
        val output = readPcm16Samples(processor.output).map { it.toInt() }

        assertTrue(output.first() > 11_000)
        assertTrue(output.minOrNull()!! < 7_000)
        assertTrue(output.any { it in 8_000..11_000 })
    }

    @Test
    fun `noise filter profiles generate distinct environment signatures`() {
        val coffee = renderNoiseForMode(NoiseEnvironmentMode.COFFEE_SHOP)
        val street = renderNoiseForMode(NoiseEnvironmentMode.BUSY_STREET)
        val meeting = renderNoiseForMode(NoiseEnvironmentMode.MEETING_ROOM)

        assertTrue(coffee.any { it != 0 })
        assertTrue(street.any { it != 0 })
        assertTrue(meeting.any { it != 0 })
        assertNotEquals(coffee, street)
        assertNotEquals(coffee, meeting)
        assertNotEquals(street, meeting)
    }

    @Test
    fun `noise intensity scales output energy`() {
        val low = renderNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 0.25f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )
        val high = renderNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 0.9f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )

        val lowRms = rms(low)
        val highRms = rms(high)

        assertTrue(highRms > (lowRms * 1.6f))
    }

    @Test
    fun `noise volume spans a wide audible range`() {
        val low = renderNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 0.1f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )
        val high = renderNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 0.9f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )

        val lowRms = rms(low)
        val highRms = rms(high)

        assertTrue(highRms > (lowRms * 5f))
    }

    @Test
    fun `sample backed noise stays clearly audible at high volume`() {
        val output = renderSampleBackedNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 0.9f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )

        val highRms = rms(output)

        assertTrue("highRms=$highRms", highRms > 300f)
    }

    @Test
    fun `sample backed noise maintains a steady audible floor across time`() {
        val output = renderSampleBackedNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 1f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )

        val windowedRms = windowedRms(output, windowSize = 4_410)
        val quietestWindow = windowedRms.minOrNull() ?: 0f
        val loudestWindow = windowedRms.maxOrNull() ?: 0f

        assertTrue(
            "quietestWindow=$quietestWindow loudestWindow=$loudestWindow",
            quietestWindow > (loudestWindow * 0.45f),
        )
    }

    @Test
    fun `sample backed noise at max intensity reaches strong output energy`() {
        val output = renderSampleBackedNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 1f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )

        val highRms = rms(output)

        assertTrue("highRms=$highRms", highRms > 450f)
    }

    @Test
    fun `sample backed noise keeps lower volume settings useful and max volume strong`() {
        val quarterOutput = renderSampleBackedNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 0.25f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )
        val maxOutput = renderSampleBackedNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 1f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )

        val quarterRms = rms(quarterOutput)
        val maxRms = rms(maxOutput)

        assertTrue("quarterRms=$quarterRms", quarterRms > 180f)
        assertTrue("maxRms=$maxRms", maxRms > 650f)
    }

    @Test
    fun `sample backed bed loop avoids seam sized discontinuities`() {
        val clip = PracticeNoiseSamples.Clip(
            sampleRate = 1_000,
            samples = FloatArray(64) { index ->
                if (index == 63) {
                    -0.9f
                } else {
                    -0.2f + (0.006f * index)
                }
            },
        )
        val processor = ShiftyNoiseAudioProcessor(
            sampleSource = {
                PracticeNoiseSamples.ModeSamples(
                    beds = listOf(clip),
                    speech = emptyList(),
                )
            },
        ).apply {
            enabled = true
            environmentMode = NoiseEnvironmentMode.COFFEE_SHOP
            intensity = 1f
            eventfulness = 0f
            spatialMotion = 0f
        }
        processor.configure(AudioProcessor.AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        processor.queueInput(pcm16ConstantBuffer(sample = 0, sampleCount = 512))
        val output = readPcm16Samples(processor.output).map { it.toInt() }
        val deltas = adjacentDeltas(output)
        val baselineDelta = percentile(deltas, 0.95f)
        val maxDelta = deltas.maxOrNull() ?: 0

        assertTrue(
            "maxDelta=$maxDelta baselineDelta=$baselineDelta",
            maxDelta < (baselineDelta * 6f),
        )
    }

    @Test
    fun `zero noise intensity produces silence for silent input`() {
        val output = renderNoiseWithTuning(
            mode = NoiseEnvironmentMode.COFFEE_SHOP,
            intensity = 0f,
            eventfulness = 0.5f,
            spatialMotion = 0.5f,
        )

        assertTrue(output.all { it == 0 })
    }

    @Test
    fun `noise eventfulness increases sample-to-sample activity`() {
        val low = renderNoiseWithTuning(
            mode = NoiseEnvironmentMode.MEETING_ROOM,
            intensity = 0.7f,
            eventfulness = 0.15f,
            spatialMotion = 0.5f,
        )
        val high = renderNoiseWithTuning(
            mode = NoiseEnvironmentMode.MEETING_ROOM,
            intensity = 0.7f,
            eventfulness = 0.95f,
            spatialMotion = 0.5f,
        )

        val lowActivity = averageDelta(low)
        val highActivity = averageDelta(high)

        assertTrue(highActivity > (lowActivity * 1.2f))
    }

    private fun pcm16Buffer(vararg samples: Short): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        return buffer.flip() as ByteBuffer
    }

    private fun pcm16ConstantBuffer(sample: Short, sampleCount: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(sampleCount * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) {
            buffer.putShort(sample)
        }
        return buffer.flip() as ByteBuffer
    }

    private fun readPcm16Samples(buffer: ByteBuffer): List<Short> {
        val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return buildList {
            while (copy.remaining() >= Short.SIZE_BYTES) {
                add(copy.short)
            }
        }
    }

    private fun renderNoiseForMode(mode: NoiseEnvironmentMode): List<Int> {
        val processor = ShiftyNoiseAudioProcessor().apply {
            enabled = true
            environmentMode = mode
        }
        processor.configure(AudioProcessor.AudioFormat(2_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm16ConstantBuffer(sample = 0, sampleCount = 4_000))
        return readPcm16Samples(processor.output).map { it.toInt() }
    }

    private fun renderNoiseWithTuning(
        mode: NoiseEnvironmentMode,
        intensity: Float,
        eventfulness: Float,
        spatialMotion: Float,
    ): List<Int> {
        val processor = ShiftyNoiseAudioProcessor().apply {
            enabled = true
            environmentMode = mode
            this.intensity = intensity
            this.eventfulness = eventfulness
            this.spatialMotion = spatialMotion
        }
        processor.configure(AudioProcessor.AudioFormat(2_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm16ConstantBuffer(sample = 0, sampleCount = 4_000))
        return readPcm16Samples(processor.output).map { it.toInt() }
    }

    private fun renderSampleBackedNoiseWithTuning(
        mode: NoiseEnvironmentMode,
        intensity: Float,
        eventfulness: Float,
        spatialMotion: Float,
    ): List<Int> {
        val processor = ShiftyNoiseAudioProcessor(sampleSource = ::sampleMode).apply {
            enabled = true
            environmentMode = mode
            this.intensity = intensity
            this.eventfulness = eventfulness
            this.spatialMotion = spatialMotion
        }
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm16ConstantBuffer(sample = 0, sampleCount = 44_100))
        return readPcm16Samples(processor.output).map { it.toInt() }
    }

    private fun sampleMode(mode: NoiseEnvironmentMode): PracticeNoiseSamples.ModeSamples {
        return when (mode) {
            NoiseEnvironmentMode.COFFEE_SHOP -> PracticeNoiseSamples.ModeSamples(
                beds = listOf(loadSampleClip("practice_noise_coffee_bed.wav")),
                speech = listOf(loadSampleClip("practice_noise_speech_pub_murmur.wav")),
            )

            NoiseEnvironmentMode.BUSY_STREET -> PracticeNoiseSamples.ModeSamples(
                beds = listOf(loadSampleClip("practice_noise_street_bed.wav")),
                speech = listOf(loadSampleClip("practice_noise_speech_crowd_male.wav")),
            )

            NoiseEnvironmentMode.MEETING_ROOM -> PracticeNoiseSamples.ModeSamples(
                beds = listOf(loadSampleClip("practice_noise_meeting_bed.wav")),
                speech = listOf(loadSampleClip("practice_noise_speech_crowd_male.wav")),
            )
        }
    }

    private fun loadSampleClip(fileName: String): PracticeNoiseSamples.Clip {
        val bytes = Files.readAllBytes(File("src/main/res/raw/$fileName").toPath())
        require(bytes.size > 44) { "WAV too small: $fileName" }

        var offset = 12
        var sampleRate = 44_100
        var channels = 1
        var pcmDataOffset = -1
        var pcmDataSize = -1

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4)
            val chunkSize = littleEndianInt(bytes, offset + 4)
            val chunkDataStart = offset + 8
            if (chunkDataStart + chunkSize > bytes.size) break

            when (chunkId) {
                "fmt " -> {
                    channels = littleEndianShort(bytes, chunkDataStart + 2).toInt() and 0xFFFF
                    sampleRate = littleEndianInt(bytes, chunkDataStart + 4)
                }

                "data" -> {
                    pcmDataOffset = chunkDataStart
                    pcmDataSize = chunkSize
                }
            }

            offset = chunkDataStart + chunkSize + (chunkSize and 1)
        }

        require(pcmDataOffset >= 0 && pcmDataSize > 0) { "Missing PCM data: $fileName" }
        val frameSizeBytes = channels * 2
        val frameCount = pcmDataSize / frameSizeBytes
        val sampleBuffer = ByteBuffer.wrap(bytes, pcmDataOffset, frameCount * frameSizeBytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        val mono = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            repeat(channels) {
                sum += sampleBuffer.short.toInt()
            }
            mono[frame] = (sum / channels.toFloat()) / Short.MAX_VALUE.toFloat()
        }

        return PracticeNoiseSamples.Clip(sampleRate = sampleRate, samples = mono)
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Short {
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return value.toShort()
    }

    private fun rms(samples: List<Int>): Float {
        if (samples.isEmpty()) return 0f
        val meanSquare = samples.sumOf { sample ->
            val value = sample.toDouble()
            value * value
        } / samples.size.toDouble()
        return sqrt(meanSquare).toFloat()
    }

    private fun windowedRms(samples: List<Int>, windowSize: Int): List<Float> {
        return samples
            .chunked(windowSize)
            .filter { it.isNotEmpty() }
            .map(::rms)
    }

    private fun averageDelta(samples: List<Int>): Float {
        if (samples.size < 2) return 0f
        var total = 0f
        for (index in 1 until samples.size) {
            total += abs((samples[index] - samples[index - 1]).toFloat())
        }
        return total / (samples.size - 1)
    }

    private fun adjacentDeltas(samples: List<Int>): List<Int> {
        if (samples.size < 2) return emptyList()
        return buildList(samples.size - 1) {
            for (index in 1 until samples.size) {
                add(abs(samples[index] - samples[index - 1]))
            }
        }
    }

    private fun percentile(values: List<Int>, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val index = ((sorted.lastIndex) * fraction.coerceIn(0f, 1f)).toInt()
        return sorted[index].toFloat()
    }

    private fun <T> longestRun(values: List<T>, predicate: (T) -> Boolean): Int {
        var best = 0
        var current = 0
        values.forEach { value ->
            if (predicate(value)) {
                current++
                if (current > best) best = current
            } else {
                current = 0
            }
        }
        return best
    }
}
