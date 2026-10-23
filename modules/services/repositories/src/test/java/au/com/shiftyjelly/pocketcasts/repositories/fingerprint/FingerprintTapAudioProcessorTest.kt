package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class FingerprintTapAudioProcessorTest {

    private val tap = mock<FingerprintPcmTap>()
    private val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun processor(isEnabled: Boolean) = FingerprintTapAudioProcessor(tap, isEnabled = { isEnabled }).apply {
        configure(format)
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    // LIFECYCLE CONTRACT (4235652501, @reviewer 7fc5a3b8): the processor is ALWAYS configured -- a
    // NOT_SET configuration bypasses it entirely and would make a capture session that starts during
    // playback unable to activate it. Forwarding is gated per buffer instead.
    @Test
    fun `always configured, whatever the static flag`() {
        val processor = FingerprintTapAudioProcessor(tap, isEnabled = { false })
        assertSame(format, processor.configure(format))
        assertTrue(processor.isActive)
        val processorEnabled = FingerprintTapAudioProcessor(tap, isEnabled = { true })
        assertSame(format, processorEnabled.configure(format))
    }

    @Test
    fun `capture starting during playback begins forwarding on the next buffer`() {
        val gate = EpisodeReferenceEligibility()
        val processor = FingerprintTapAudioProcessor(tap, isEnabled = { false }, episodeReferenceEligibility = gate).apply {
            configure(format)
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        queueThrough(processor)
        verify(tap, never()).onPcm(any(), any())
        // Capture attaches MID-PLAYBACK: the very next buffer is forwarded.
        gate.setCaptureActive(true)
        queueThrough(processor)
        verify(tap, atLeastOnce()).onPcm(any(), any())
    }

    @Test
    fun `capture teardown stops forwarding without reconfiguration`() {
        val gate = EpisodeReferenceEligibility()
        gate.setCaptureActive(true)
        val processor = FingerprintTapAudioProcessor(tap, isEnabled = { false }, episodeReferenceEligibility = gate).apply {
            configure(format)
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        queueThrough(processor)
        verify(tap, atLeastOnce()).onPcm(any(), any())
        gate.setCaptureActive(false)
        clearInvocations(tap)
        queueThrough(processor)
        verify(tap, never()).onPcm(any(), any())
    }

    private fun queueThrough(processor: FingerprintTapAudioProcessor) {
        val buffer = ByteBuffer.allocate(16)
        processor.queueInput(buffer)
        processor.getOutput().position(0)
    }

    @Test
    fun `passes input through unchanged`() {
        val processor = processor(isEnabled = true)
        val bytes = ByteArray(16) { it.toByte() }
        val input = ByteBuffer.wrap(bytes.copyOf())
        processor.queueInput(input)
        assertEquals(0, input.remaining())
        val output = processor.output
        val copied = ByteArray(output.remaining())
        output.get(copied)
        assertArrayEquals(bytes, copied)
    }

    @Test
    fun `copies the pcm into the tap as a read-only view`() {
        val processor = processor(isEnabled = true)
        val bytes = ByteArray(16) { it.toByte() }
        processor.queueInput(ByteBuffer.wrap(bytes.copyOf()))
        val captor = argumentCaptor<ByteBuffer>()
        verify(tap).onPcm(captor.capture(), eq(format))
        val seen = captor.firstValue
        assertTrue(seen.isReadOnly)
        val copied = ByteArray(seen.remaining())
        seen.get(copied)
        assertArrayEquals(bytes, copied)
    }

    @Test
    fun `ignores empty input`() {
        val processor = processor(isEnabled = true)
        processor.queueInput(ByteBuffer.allocate(0))
        verifyNoInteractions(tap)
        assertEquals(0, processor.output.remaining())
    }
}
