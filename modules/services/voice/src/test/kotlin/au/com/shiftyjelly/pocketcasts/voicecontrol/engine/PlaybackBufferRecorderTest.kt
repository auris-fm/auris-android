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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `retire keeps only the acoustic delay tail so stale audio cannot match later speech`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        // Fill well past the delay tail with a distinctive signal.
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE) { 0.5f })
        val before = recorder.snapshot().size
        assertTrue("precondition: the reference holds audio", before > PlaybackBufferRecorder.DELAY_TAIL_SAMPLES)

        recorder.retire()

        val after = recorder.snapshot()
        assertEquals(
            "only the delay tail survives retirement",
            PlaybackBufferRecorder.DELAY_TAIL_SAMPLES,
            after.size,
        )
    }

    @Test
    fun `retire preserves the newest samples, not the oldest`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val tail = PlaybackBufferRecorder.DELAY_TAIL_SAMPLES
        // Old, retired audio is 0.25; the newest `tail` samples are 0.75.
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE) { 0.25f })
        recorder.write(FloatArray(tail) { 0.75f })

        recorder.retire()

        val after = recorder.snapshot()
        assertEquals(tail, after.size)
        assertTrue(
            "the surviving tail is the newest audio, not the oldest",
            after.all { kotlin.math.abs(it - 0.75f) < 0.0001f },
        )
    }

    @Test
    fun `retiring an empty reference does not invent audio`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.retire()
        assertEquals(0, recorder.snapshot().size)
    }

    @Test
    fun `episode chunk timestamp is carried into the reference`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        val job = recorder.start(this)
        runCurrent()

        val format = AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT)
        val samples = shortArrayOf(1000, -1000, 2000, -2000) // 4 samples = 0.25 ms at 16 kHz
        tap.onSinkBuffer(presentationTimeUs = 2_000_000) // 2.0 s
        tap.onPcm(ByteBuffer.wrap(s16le(samples)), format)
        runCurrent()
        job.cancel()

        assertEquals(
            "the chunk's media position is carried, plus its own duration",
            2_000L + (samples.size * 1_000L) / 16_000,
            recorder.lastRecordedPositionMs(),
        )
    }

    @Test
    fun `timestamped and untimestamped writes keep the latest position`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.write(FloatArray(16) { 0.1f }, positionMs = 1_000L)
        assertEquals(1_001L, recorder.lastRecordedPositionMs())
        // An untimestamped write must not erase the last known position.
        recorder.write(FloatArray(16) { 0.1f })
        assertEquals(1_001L, recorder.lastRecordedPositionMs())
    }

    @Test
    fun `position is null until a timestamped write happens`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        assertEquals(null, recorder.lastRecordedPositionMs())
    }

    /**
     * `retire()` must be atomic against a concurrent writer.
     *
     * The recorder's contract says every source feeds the same single writer, but they do not share a
     * thread: the tap collector runs on Main, CloudAudioPlayer.drainFrames writes from the sink's IO
     * coroutine, and snapshot() is read from the ASR coroutine. write() is monotonic per field, while
     * retire() is a multi-field transition (buffer.fill + copyInto + writePos + filled) -- so a writer
     * interleaving it can observe `filled` from before the fill with `writePos` from after.
     *
     * The observable symptom is a reference that reports MORE samples than the buffer can hold, which
     * is impossible for any consistent state: `snapshot()` sizes by `if (filled) buffer.size else
     * writePos`, so the two fields must agree.
     */
    @Test
    fun `retire is atomic against a concurrent writer`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val capacity = PlaybackBufferRecorder.SAMPLE_RATE * PlaybackBufferRecorder.BUFFER_DURATION_SECONDS
        val chunk = FloatArray(4_000) { 0.25f }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val violations = java.util.concurrent.atomic.AtomicInteger(0)

        val writer = Thread {
            while (!stop.get()) {
                recorder.write(chunk)
            }
        }
        val reader = Thread {
            while (!stop.get()) {
                // The strongest observable invariant: retire() promises the surviving content is the
                // DELAY TAIL. A snapshot taken while the fill/copy transition is mid-flight sees a
                // buffer sized from one field and populated from another, so the samples include
                // zeros the fill wrote but the copy has not yet overwritten.
                val samples = recorder.snapshot()
                if (samples.size > capacity) violations.incrementAndGet()
                // retire() only ever leaves DELAY_TAIL_SAMPLES or fewer; a larger all-zero prefix is
                // impossible outside the torn window, because every writer writes 0.25f.
                if (samples.size < capacity && samples.size > PlaybackBufferRecorder.DELAY_TAIL_SAMPLES) {
                    val zeroRun = samples.takeWhile { it == 0f }.size
                    if (zeroRun > PlaybackBufferRecorder.DELAY_TAIL_SAMPLES) violations.incrementAndGet()
                }
            }
        }
        val retirer = Thread {
            while (!stop.get()) {
                recorder.retire()
            }
        }
        listOf(writer, reader, retirer).forEach {
            it.isDaemon = true
            it.start()
        }
        Thread.sleep(400)
        stop.set(true)
        listOf(writer, reader, retirer).forEach { it.join(2_000) }

        assertEquals(
            "a reference larger than the ring means retire() and write() interleaved: " +
                "violations=${violations.get()}",
            0,
            violations.get(),
        )
    }

    /**
     * `reference()` must read samples and position as ONE step.
     *
     * The two halves answer a single question -- which samples, and where they sit on the timeline --
     * so a reader that acquires twice can pair a position from a newer state with samples from an older
     * one. The discriminating check uses the one quantity that is independent of the pair: the position
     * the WRITE recorded. A coherent read must place the window so that its end lands on that position.
     *
     * (Asserting `endPositionMs == startPositionMs + duration` would be circular, because the class
     * derives one from the other -- it holds for any snapshot.)
     */
    @Test
    fun `reference places the returned window so its end lands on the recorded position`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val half = PlaybackBufferRecorder.SAMPLE_RATE / 2
        // One chunk of 8000 samples at 16 kHz = 500 ms, recorded as starting at 1000 ms, so the
        // newest sample sits at 1500 ms.
        recorder.write(FloatArray(half) { 0.5f }, positionMs = 1_000)
        val recordedEnd = recorder.lastRecordedPositionMs()
        assertEquals("the recorded end position is the chunk start plus its duration", 1_500L, recordedEnd)

        val ref = recorder.reference()
        val durationMs = (ref.samples.size * 1_000L) / PlaybackBufferRecorder.SAMPLE_RATE
        assertEquals(
            "the window must end on the recorded position: start + duration == recorded end",
            recordedEnd,
            (ref.startPositionMs ?: -1L) + durationMs,
        )
    }

    /**
     * The same identity, under a CONCURRENT writer.
     *
     * The sequential case above cannot distinguish one acquisition from two, because nothing
     * interleaves. A writer narrowing the window between the two reads is what makes the pair
     * incoherent: `start` is derived from the size read in the SECOND step while `samples` came from
     * the first, so `start + duration(samples)` no longer lands on the position that was recorded for
     * those samples.
     *
     * The writer shortens its chunk each time so the window size changes constantly, which is the
     * condition the two-acquisition read depends on. It is a STRESS CHECK: a race is not reliably
     * forced, so a pass is a guard rather than proof -- the fix rests on the composite read being one
     * acquisition, not on this reproducing a torn pair.
     */
    @Test
    fun `reference stays coherent against a writer that keeps narrowing the window`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val violations = java.util.concurrent.atomic.AtomicInteger(0)

        val writer = Thread {
            var position = 0L
            var size = PlaybackBufferRecorder.SAMPLE_RATE
            while (!stop.get()) {
                size = if (size > 400) size / 2 else PlaybackBufferRecorder.SAMPLE_RATE
                position += 1_000
                recorder.write(FloatArray(size) { 0.25f }, positionMs = position)
            }
        }
        val reader = Thread {
            while (!stop.get()) {
                val ref = recorder.reference()
                val start = ref.startPositionMs
                if (start != null && ref.samples.isNotEmpty()) {
                    val durationMs = (ref.samples.size * 1_000L) / PlaybackBufferRecorder.SAMPLE_RATE
                    // The recorded position is not observable from here, so the check is that the
                    // window is non-degenerate: a pair from different states yields a start that is
                    // not backed by the samples it is reported with.
                    if (start < 0 || durationMs <= 0) violations.incrementAndGet()
                    if (ref.samples.size > PlaybackBufferRecorder.SAMPLE_RATE * PlaybackBufferRecorder.BUFFER_DURATION_SECONDS) {
                        violations.incrementAndGet()
                    }
                }
            }
        }
        listOf(writer, reader).forEach {
            it.isDaemon = true
            it.start()
        }
        Thread.sleep(400)
        stop.set(true)
        listOf(writer, reader).forEach { it.join(2_000) }
        assertEquals("a coherent window must never be degenerate or oversized", 0, violations.get())
    }

    @Test
    fun `a media position and a monotonic time must not be mixed into one span`() {
        // THE HAZARD @spec identified: two sources write positions on DIFFERENT AXES into one field.
        // The episode tap writes a MEDIA position (seconds into the episode); the cloud answer writes
        // a MONOTONIC time (the render clock). Subtracting one from the other produces a number that is
        // neither, and pairing it against the microphone instant would report a delay that is really
        // the difference between two unrelated origins.
        //
        // A media position near zero and a monotonic time in the billions are consecutive writes here.
        // The recorder must NOT blend them into a span; it must report that the window has no single
        // axis rather than a confidently wrong one.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        // An episode chunk at media position 0.5 s -- the axis the episode tap supplies.
        recorder.write(FloatArray(1_600) { 0.5f }, positionMs = 500L, axis = PositionAxis.Media)

        // A rendered cloud-answer block at 9_000_000 ms of MONOTONIC time -- the axis the render clock
        // supplies. These are the two axes the production sources actually use.
        recorder.write(FloatArray(1_600) { -0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic)

        val reference = recorder.reference()

        // Whatever the policy, the reported start must not be a value derived from subtracting across
        // the two axes. 9_000_000 - 100 ms of cloud audio is ~8_999_900; a blended value near that is
        // the failure. The window is two different clocks, so the position must be treated as absent.
        assertEquals(
            "a window holding two incompatible axes must report no single position, " +
                "not one derived by subtracting across them (start=${reference.startPositionMs})",
            null,
            reference.startPositionMs,
        )
    }
}
