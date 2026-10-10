package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recorder's job is to hold what the client submitted for playback so the signal filter can
 * correlate the microphone against it. These drive the real [FingerprintPcmTap] subscription (the
 * episode renderer's production source), not a setter, so a broken tap wiring fails here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackBufferRecorderTest {

    private fun s16le(samples: ShortArray): ByteArray = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
        samples.forEach { putShort(it) }
    }.array()

    private fun chunk(data: ByteArray, encoding: Int = C.ENCODING_PCM_16BIT): FingerprintPcmTap.PcmChunk = FingerprintPcmTap.PcmChunk(
        data = data,
        encoding = encoding,
        sampleRate = 16_000,
        channelCount = 1,
        positionSec = 0.0,
    )

    @Test
    fun `episode PCM submitted to the sink reaches the reference`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        val job = recorder.start(this)
        // The tap drops chunks while nothing is subscribed, so let the collector register first.
        runCurrent()
        // Drive the tap's real production entry point, not a synthetic emission: this is exactly
        // what FingerprintTapAudioProcessor calls as the sink accepts decoded player PCM.
        val samples = shortArrayOf(1000, -1000, 2000, -2000)
        val format = AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT)
        tap.onSinkBuffer(presentationTimeUs = 0)
        tap.onPcm(ByteBuffer.wrap(s16le(samples)), format)

        // The tap emits on the collector's dispatcher, so let the delivery run before asserting.
        runCurrent()
        job.cancel()

        val snapshot = recorder.snapshot()
        assertEquals("the submitted samples are recorded", samples.size, snapshot.size)
        for (i in samples.indices) {
            assertEquals(
                "sample $i follows the s16 -> float mapping",
                samples[i] / 32768f,
                snapshot[i],
                0.0001f,
            )
        }
    }

    @Test
    fun `conversion equals the reference mapping used by the existing tap consumer`() {
        val data = s16le(shortArrayOf(0, 32767, -32768))
        val floats = PlaybackBufferRecorder.chunkToFloats(chunk(data))
        assertArrayEquals(floatArrayOf(0f, 32767 / 32768f, -1f), floats, 0.0001f)
    }

    @Test
    fun `float-encoded chunk is read as floats, not reinterpreted as shorts`() {
        val floats = floatArrayOf(0.25f, -0.5f)
        val data = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder()).apply {
            floats.forEach { putFloat(it) }
        }.array()
        assertArrayEquals(
            floatArrayOf(0.25f, -0.5f),
            PlaybackBufferRecorder.chunkToFloats(chunk(data, C.ENCODING_PCM_FLOAT)),
            0.0001f,
        )
    }

    @Test
    fun `snapshot is empty until something is submitted`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        assertEquals(0, recorder.snapshot().size)
    }
}
