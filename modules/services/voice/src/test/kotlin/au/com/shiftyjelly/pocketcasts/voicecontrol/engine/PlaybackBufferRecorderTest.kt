package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // THE DECODER FORMAT IS NOT THE RECORDER FORMAT. The tap delivers chunks at the decoder's own
    // rate and channel count (44.1 kHz stereo for ordinary episodes), while the ring, the window
    // duration and every position computation assume 16 kHz mono. The recorder is the ONE boundary
    // where sources meet the reference format, so normalization lives there -- not in the tap (which
    // must stay a faithful pass-through of the player's PCM) and not in the correlator (which reads
    // what the recorder stored).
    @Test
    fun `a 44100 Hz stereo episode chunk is normalized to 16 kHz mono with its true duration`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        val job = recorder.start(this)
        runCurrent()

        // One second of 44.1 kHz stereo: L counts up, R counts down, so any surviving L/R
        // interleaving is visible as an alternating sawtooth in the stored samples.
        val oneSecond = 44_100
        val data = ShortArray(oneSecond * 2) { i ->
            if (i % 2 == 0) ((i / 2) % 3276 * 10).toShort() else ((i / 2) % 3276 * -10).toShort()
        }
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        tap.onSinkBuffer(presentationTimeUs = 0)
        tap.onPcm(ByteBuffer.wrap(s16le(data)), format)
        runCurrent()
        job.cancel()

        val snapshot = recorder.snapshot()
        assertEquals(
            "one second of 44.1 kHz source must store one second of 16 kHz samples, not raw frames",
            16_000,
            snapshot.size,
        )
        // Downmixed content: with L = -R the mono average is silence for EVERY pair, so the stored
        // signal must be near-zero throughout -- interleaved storage would alternate +/- loudly.
        val peak = snapshot.maxOf { kotlin.math.abs(it) }
        assertTrue("L and R are exact opposites, so the mono downmix is near-silence: peak=$peak", peak < 0.01f)
    }

    // STREAMING, NOT SINGLE-BUFFER: decoder chunks arrive as ordinary-sized pieces. A conversion that
    // floors each chunk's output length and restarts interpolation at source zero makes the STORED
    // stream depend on how the producer happened to split it -- duration wobbles per chunk and drift
    // accumulates. Same source bytes, two partitionings, one result: that is the property.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `the stored stream is invariant to how the same 44100 Hz stereo source is partitioned into chunks`() = runTest {
        val srcRate = 44_100
        val frames = 6_777 // deliberately not a multiple of any convenient divisor
        val monoSource = FloatArray(frames) { i -> (0.5 * kotlin.math.sin(2 * PI * 220.0 * i / srcRate)).toFloat() }
        val interleaved = FloatArray(frames * 2) { i -> monoSource[i / 2] } // centered image: L == R

        suspend fun recordInChunks(sizes: List<Int>): FloatArray {
            val tap = FingerprintPcmTap()
            val recorder = PlaybackBufferRecorder(tap)
            val job = recorder.start(this)
            runCurrent()
            val format = AudioProcessor.AudioFormat(srcRate, 2, C.ENCODING_PCM_16BIT)
            var cursor = 0
            for (size in sizes) {
                val part = ShortArray(size * 2) { i -> (interleaved[cursor + i] * 32767f).toInt().toShort() }
                cursor += size * 2
                tap.onSinkBuffer(presentationTimeUs = 0)
                tap.onPcm(ByteBuffer.wrap(s16le(part)), format)
                runCurrent()
            }
            job.cancel()
            return recorder.snapshot()
        }

        val whole = recordInChunks(listOf(frames))
        // Sizes must EXACTLY cover `frames`: a short list would silently record a shorter stream.
        val parts = listOf(977, 311, 1_500, 1_013, 800, 999, 1_177)
        assertEquals("the partitioning covers the whole source", frames, parts.sum())
        val irregular = recordInChunks(parts)
        // At most ONE output sample (~62 microseconds) may differ between partitionings: the shared
        // truncation of the final partial position, not per-boundary drift. A stateless converter
        // loses up to one sample at EVERY boundary (seven chunks here), which this tolerance rejects.
        assertTrue(
            "chunk boundaries must not change the stored duration: whole=" + whole.size + " irregular=" + irregular.size,
            kotlin.math.abs(whole.size - irregular.size) <= 1,
        )
        val common = minOf(whole.size, irregular.size)
        val maxDiff = (0 until common).maxOf { kotlin.math.abs(whole[it] - irregular[it]) }
        assertTrue("and not the stored content: max abs diff=$maxDiff", maxDiff < 0.001f)
        // The total is the stream's true duration, not a sum of floored chunks.
        val expected = (frames.toLong() * 16_000 / srcRate).toInt()
        assertTrue("stored samples must cover the source duration: ${whole.size} vs $expected", kotlin.math.abs(whole.size - expected) <= 1)
    }

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
    fun `the anchor RETURNS once the hole has been overwritten, so the guard is not a permanent latch`() {
        // @spec's requirement: a hole is a property of the RETAINED RANGE, not a latch on the recorder.
        // After one dropped chunk, an anchor suppressed forever would discard a good alignment for the
        // rest of the session. Once the ring has overwritten the audio before the hole, every retained
        // sample lies on one side of it and the window is contiguous again.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        // A hole: 0..1000 then a jump to 4000..5000.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic)
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 4_000L, axis = PositionAxis.Monotonic)
        assertTrue(
            "the hole removes the anchor while both sides are retained",
            recorder.reference().startPositionMs == null,
        )

        // Write past a full buffer (2 s) so the audio BEFORE the hole is overwritten. Everything now
        // retained is at or after the hole, contiguous, and after the resume position.
        var t = 5_000L
        repeat(2) {
            recorder.write(FloatArray(16_000) { 0.5f }, positionMs = t, axis = PositionAxis.Monotonic)
            t += 1_000L
        }

        val reference = recorder.reference()
        assertTrue(
            "once the hole is overwritten the window is contiguous again and must anchor " +
                "(start=${reference.startPositionMs} end=${reference.endPositionMs})",
            reference.startPositionMs != null,
        )
        assertEquals(
            "and the recovered anchor is end minus duration",
            reference.endPositionMs!! - (reference.samples.size * 1_000L) / reference.sampleRateHz,
            reference.startPositionMs,
        )
    }

    @Test
    fun `a contribution that does not meet the surviving tail is not accepted as adjacent`() {
        // @spec's retirement-seam defect: `retire()` cleared `expectedNextPositionMs` while samples
        // survived, so the FIRST post-retire contribution could not be compared against the tail. A
        // contribution that does not meet the tail was therefore accepted as adjacent, hiding a
        // discontinuity the retained window cannot cover.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        // Contiguous audio ending at 2000 ms.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic)
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 1_000L, axis = PositionAxis.Monotonic)
        recorder.retire()

        // The next contribution is 5 s later: a discontinuity, compared against the TAIL's end.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 7_000L, axis = PositionAxis.Monotonic)

        val reference = recorder.reference()
        assertTrue(
            "a contribution that does not meet the surviving tail must not be treated as adjacent " +
                "(start=${reference.startPositionMs} end=${reference.endPositionMs})",
            reference.startPositionMs == null,
        )
    }

    @Test
    fun `a CONTIGUOUS contribution after retire still anchors`() {
        // The positive control: the metadata must be kept, not made permanently distrustful.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic)
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 1_000L, axis = PositionAxis.Monotonic)
        recorder.retire()

        // The next contribution MEETS the tail's end, so the window stays contiguous.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 2_000L, axis = PositionAxis.Monotonic)

        val reference = recorder.reference()
        assertTrue(
            "a contribution meeting the tail's end must still anchor " +
                "(start=${reference.startPositionMs} end=${reference.endPositionMs})",
            reference.startPositionMs != null,
        )
    }

    @Test
    fun `a hole INSIDE the retained tail still suppresses the anchor after retire`() {
        // retire() replaces the retained span, so a hole outside the surviving tail is gone with it --
        // but the tail keeps the NEWEST samples, so a hole inside the tail still separates them.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        // The hole must sit INSIDE the surviving 500 ms tail -- that is what "inside the tail" means.
        // Two 250 ms blocks with a 100 ms gap: the tail keeps the newest 500 ms, which spans BOTH sides
        // of the hole, so the retained samples are still separated by it.
        recorder.write(FloatArray(4_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic)
        recorder.write(FloatArray(4_000) { 0.5f }, positionMs = 350L, axis = PositionAxis.Monotonic)

        recorder.retire()
        assertTrue(
            "a hole inside the retained tail must still suppress the anchor",
            recorder.reference().startPositionMs == null,
        )
    }

    @Test
    fun `contiguous rendered audio still anchors, so the gap guard cannot silently disable the anchor`() {
        // THE GUARD MUST NOT BE ALWAYS-OFF. Removing a fabricated anchor is only correct if a real one
        // survives: a window of genuinely adjacent rendered audio is exactly the case the anchor exists
        // for, and a guard that disabled it always would pass the case above for the wrong reason.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        // Two ADJACENT blocks: 0..1000 ms then 1000..2000 ms, no hole between them.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic)
        recorder.write(FloatArray(16_000) { -0.5f }, positionMs = 1_000L, axis = PositionAxis.Monotonic)

        val reference = recorder.reference()
        // The window is contiguous, so its start is exactly end minus its duration -- and NOT null,
        // which is what the gap guard would produce if it were always-on.
        assertEquals(
            "adjacent rendered audio must still offer its position: start = end - duration " +
                "(start=${reference.startPositionMs} end=${reference.endPositionMs})",
            reference.endPositionMs!! - (reference.samples.size * 1_000L) / reference.sampleRateHz,
            reference.startPositionMs,
        )
        assertTrue(
            "and an anchored window must actually report a start, not null",
            reference.startPositionMs != null,
        )
    }

    @Test
    fun `a DROPPED chunk does not leave a window that claims a span it does not cover`() {
        // @reviewer's finding, taken to the path where it actually bites. The tap's flow is DROP_OLDEST
        // with 64 slots and this change made PlaybackBufferRecorder a second consumer, so `tryEmit` can
        // silently discard the OLDEST chunk -- the collector's stream then has a HOLE nothing announces.
        //
        // The EPISODE path is already protected by a different guard: its axis is Media, which never
        // anchors. The exposure is a MONOTONIC window -- rendered cloud audio -- because that axis DOES
        // anchor, and `reference()` derives its start by subtracting the window's duration from the
        // newest position. That arithmetic assumes the samples are CONTIGUOUS. Across a hole they are
        // not, so the reported span covers a range whose middle is audio from a different moment: the
        // correlator matches the microphone against samples that were never played together.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        // One second of rendered audio at t=0.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic)
        val afterFirst = recorder.lastRecordedPositionMs()

        // The NEXT rendered block is 3 s later -- 2 s of audio was dropped by the tap and never reached
        // this consumer. Nothing in the samples says so; the position jump is the only evidence.
        recorder.write(FloatArray(16_000) { -0.5f }, positionMs = 3_000L, axis = PositionAxis.Monotonic)

        val reference = recorder.reference()
        assertTrue(
            "a window that spans a DROPPED chunk must not offer a position: its samples were never " +
                "adjacent, so the derived span describes audio that was never played together " +
                "(start=${reference.startPositionMs} end=${reference.endPositionMs} " +
                "afterFirst=$afterFirst)",
            reference.startPositionMs == null,
        )
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
        // Declared Media explicitly: this case is about the end-position relation, which holds on
        // either axis, and Media is the axis an episode tap supplies. It used the default before the
        // axis existed, and the default is the only reason it was passing.
        recorder.write(FloatArray(half) { 0.5f }, positionMs = 1_000, axis = PositionAxis.Media)
        val recordedEnd = recorder.lastRecordedPositionMs()
        assertEquals("the recorded end position is the chunk start plus its duration", 1_500L, recordedEnd)

        // The END position is recorded regardless of axis -- it is what the newest contribution
        // reported, and the axis is what decides whether the START may be offered as an anchor. So
        // this asserts the end relation through the recorder's own accessor, and asserts separately
        // that a media window offers no anchor.
        assertEquals("the recorded end position is the chunk start plus its duration", 1_500L, recordedEnd)
        assertEquals(
            "a media-positioned window offers no anchor to the microphone's clock",
            null,
            recorder.reference().startPositionMs,
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

    @Test
    fun `an episode-only window offers no anchor because its axis is not the microphone's`() {
        // @spec's single-axis case, and the one a mixed-axis guard alone does not cover. This window
        // is internally CONSISTENT -- every contribution is a media position -- but the microphone
        // instant is a monotonic time, so offering this start would pair seconds-into-an-episode with
        // a wall-clock instant and report the gap between two unrelated origins as an echo delay.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 12_000L, axis = PositionAxis.Media)

        assertNull(
            "a media-positioned window must not offer an anchor to a monotonic microphone instant " +
                "(start=${recorder.reference().startPositionMs})",
            recorder.reference().startPositionMs,
        )
    }

    @Test
    fun `a monotonic window does offer an anchor`() {
        // The control for the case above: without this, the exclusion could be passing because the
        // recorder never reports a position at all rather than because the axis was wrong.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic)

        val reference = recorder.reference()
        // The write anchors the chunk's START at 9_000_000 and records the END as start + duration,
        // so the window's start comes back as the input: end (9_000_200) minus the window (200 ms).
        // Stated as the relation rather than as a constant, because the constant is what I got wrong
        // first -- I expected 8_999_800 by subtracting the duration from the INPUT and had to read
        // the reported values to see the off-by-a-duration error was mine, not the code's.
        val durationMs = (reference.samples.size * 1_000L) / PlaybackBufferRecorder.SAMPLE_RATE
        assertEquals("3200 samples at 16 kHz is 200 ms", 200L, durationMs)
        assertEquals("the chunk's own start is where it was anchored", 9_000_000L, reference.startPositionMs)
        assertEquals(
            "and the window ends a duration later, which is the relation the anchor must satisfy",
            9_000_000L + durationMs,
            reference.endPositionMs,
        )
    }

    @Test
    fun `an untimed contribution does not set the window's axis`() {
        // @spec's case: earcons and TTS supply no timestamp. They contribute samples and must not
        // inherit an axis, because an inherited axis would make the window look placeable on a clock
        // nothing measured for this audio -- and the recorder's answer would then anchor the whole
        // window to the other producer's origin.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        // An untimed chunk FIRST: it must leave the axis unset rather than defaulting it.
        recorder.write(FloatArray(1_600) { 0.25f })

        // Then a monotonic one. If the untimed chunk had defaulted the axis to Media, this window
        // would be Mixed and offer no anchor at all, discarding a valid clock.
        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic)

        // REVERSED, and the reversal is the point: this case originally asserted that the window
        // STILL anchors, which encoded the behaviour @spec corrected. An untimed contribution is not
        // merely neutral -- it invalidates any anchor, because the untimed samples are now part of the
        // span whose start would be reported. An untimed chunk still must not SET an axis (it is not a
        // media position), but the consequence of its presence is no anchor at all, not a retained one.
        val reference = recorder.reference()
        val durationMs = (reference.samples.size * 1_000L) / PlaybackBufferRecorder.SAMPLE_RATE
        assertEquals("the window holds both chunks", 300L, durationMs)
        assertNull(
            "an untimed contribution joins the retained window, so the window has no start it can place",
            reference.startPositionMs,
        )
    }

    @Test
    fun `an untimed contribution after a timed one removes the anchor`() {
        // REVERSED like the case above, and for the same reason: the earlier version asserted the
        // anchor survived, which is the reading @spec corrected. The samples are still retained -- the
        // reference must remain complete for WHAT it holds -- but the window can no longer be placed.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic)
        recorder.write(FloatArray(1_600) { 0.25f })

        assertEquals(
            "the samples are still retained: an absent position changes WHEN, never WHAT",
            4_800,
            recorder.reference().samples.size,
        )
        assertNull(
            "a trailing untimed chunk removes the anchor",
            recorder.reference().startPositionMs,
        )
    }

    @Test
    fun `an unanchored contribution invalidates an anchor the window already had`() {
        // @spec's requirement: an untimed contribution must not simply be ignored once an anchor
        // exists. The anchor describes the START of the retained window, and untimed audio joining
        // that window moves the start to a sample whose sounding time nothing measured -- so keeping
        // the old anchor would place those samples at a moment derived from another producer's clock.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic)
        assertNotNull("the timed window anchors first", recorder.reference().startPositionMs)

        // TTS or an earcon lands with no timestamp.
        recorder.write(FloatArray(1_600) { 0.25f })

        assertNull(
            "untimed audio joining an anchored window must invalidate the anchor " +
                "(start=${recorder.reference().startPositionMs})",
            recorder.reference().startPositionMs,
        )
    }

    @Test
    fun `a later timed contribution does not restore an anchor the window lost`() {
        // The window cannot become anchored again once untimed audio is retained in it: the samples
        // that have no known time are still part of the span whose start would be reported.
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)

        recorder.write(FloatArray(1_600) { 0.25f })
        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic)

        assertNull(
            "a window containing untimed samples has no trustworthy start " +
                "(start=${recorder.reference().startPositionMs})",
            recorder.reference().startPositionMs,
        )
    }

    @Test
    fun `the observation's frame selects the retained block and the window ends at what has sounded`() {
        // @spec's assignment: the actual rendered-block mapping. Two chunks on the track's index;
        // the observation names a frame inside the SECOND, so only the FIRST has fully sounded and the
        // window must end there -- later queued PCM is not rendered progress and must not stand for it.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())

        // Block 1: frames 0..4800, 1600 recorder samples (48k -> 16k).
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
        )
        // Block 2: frames 4800..9600, another 1600 samples.
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 4_800L,
            trackEndFrame = 9_600L,
        )

        // The observation: frame 8_000 sounded at 5_000_000 ms. Frame 8_000 is inside block 2, so
        // block 2 has NOT fully sounded; the newest fully-rendered block is block 1 (end 4800).
        recorder.onRenderObservation(frameOnTrack = 8_000L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L)

        val rendered = recorder.renderedReference()
        assertNotNull("a fully rendered block exists", rendered)
        // The window holds ONLY block 1's samples: block 2 is still queued.
        assertEquals("queued PCM is excluded from rendered progress", 1_600, rendered!!.samples.size)
        // Block 1's END sounded 3200 frames before the observed frame: 32000/48000 = 66.67 -> 66 ms.
        val endMs = 5_000_000L - (8_000L - 4_800L) * 1_000L / 48_000L
        val durationMs = rendered.samples.size * 1_000L / PlaybackBufferRecorder.SAMPLE_RATE
        assertEquals(
            "the block's end instant is the observation translated back across the frame difference",
            endMs,
            rendered.startPositionMs!! + durationMs,
        )
    }

    @Test
    fun `timing metadata retires with its samples`() {
        // The per-window recovery condition: when the ring drops samples, the records naming them go
        // too, so an old observation can no longer select a block whose audio is gone. Without this,
        // a stale selection would anchor the remaining window at a start derived from samples that no
        // longer exist.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())

        // A small block, then enough audio to push it out of the retention window.
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
        )
        recorder.onRenderObservation(frameOnTrack = 4_800L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L)
        assertNotNull("the block is selectable while retained", recorder.renderedReference())

        // More than the ring's capacity, with no frame identity of its own (e.g. an untimed source).
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE * 8))

        assertNull(
            "a retired block cannot anchor: its samples are gone and the observation names nothing " +
                "retained",
            recorder.renderedReference(),
        )
    }

    @Test
    fun `an observation naming a frame ahead of the write head is refused`() {
        // A track reset or stale read produces an observation for audio that was never written here.
        // Refusing it keeps the mapping unavailable rather than anchoring retained samples to a
        // reading that does not describe them.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())

        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
        )
        recorder.onRenderObservation(frameOnTrack = 99_999L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L)

        assertNull(
            "an observation ahead of the write head names nothing written here",
            recorder.renderedReference(),
        )
    }

    @Test
    fun `the mapping recovers once untimed audio has fully retired and timed samples follow`() {
        // @spec's recovery condition: retiring one timed block and clearing its observation does not
        // alone prove recovery. Here an UNTIMED contribution joins the window, fully retires, and a
        // later coherent timed block is then selectable -- the mapping must anchor again, because the
        // untimed audio no longer contaminates the retained span.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())

        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
        )
        recorder.onRenderObservation(frameOnTrack = 4_800L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L)
        assertNotNull("anchored before the untimed contribution", recorder.renderedReference())

        // Untimed audio joins (no frame range, no observation change), then retires.
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE * 8))

        // A coherent timed block after the untimed audio has left the ring, and a FRESH observation
        // for the new state -- the old observation (frame 4800) could not render the new block, and
        // that refusal was correct. Recovery is: new timed samples plus a new observation anchor again.
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 4_800L,
            trackEndFrame = 9_600L,
        )
        recorder.onRenderObservation(frameOnTrack = 9_600L, timeMs = 5_200_000L, trackRateHz = 48_000, trackGeneration = 0L)

        val rendered = recorder.renderedReference()
        assertNotNull(
            "the mapping anchors again once the untimed audio has retired " +
                "(selected=${rendered != null})",
            rendered,
        )
        assertEquals(
            "and the window holds the newest coherent timed block's samples",
            1_600,
            rendered!!.samples.size,
        )
        assertEquals(
            "anchored at the fresh observation's translation, not a stale one",
            5_200_000L - rendered.samples.size * 1_000L / PlaybackBufferRecorder.SAMPLE_RATE,
            rendered.startPositionMs,
        )
    }

    @Test
    fun `the fallback window never offers an anchor`() {
        // @spec's check on the fallback path: when renderedReference() cannot anchor, the fallback
        // must not restore an estimated or stale anchor through reference(). window() is what the
        // production supplier falls back to, and its position is null by construction.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.write(FloatArray(1_600), positionMs = 9_000_000L, axis = PositionAxis.Monotonic)

        val fallback = recorder.window()
        assertEquals("the samples are the retained window", 1_600, fallback.samples.size)
        assertNull(
            "the fallback window carries samples, never a timing claim",
            fallback.startPositionMs,
        )
    }

    @Test
    fun `an observation from a previous track incarnation never matches a new track's blocks`() {
        // Finding B: after a rebuild both frame indexes restart at zero, so an old observation's
        // frame number can agree with a new write head while describing different audio. Generation
        // stamping is what keeps them apart -- the numbers agreeing is exactly the trap.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())

        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
            trackGeneration = 0L,
        )
        recorder.onRenderObservation(
            frameOnTrack = 4_800L,
            timeMs = 5_000_000L,
            trackRateHz = 48_000,
            trackGeneration = 0L,
        )
        assertNotNull("anchored within the incarnation", recorder.renderedReference())

        // Rebuild: a NEW track's block with the SAME frame range, under generation 1.
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
            trackGeneration = 1L,
        )

        assertNull(
            "the old observation must not select the new track's block, even at the same frame " +
                "numbers",
            recorder.renderedReference(),
        )
    }

    @Test
    fun `a fresh observation on the new incarnation anchors the new track's blocks`() {
        // The control for the case above: generation checking must refuse stale-track observations
        // without refusing observation entirely.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())

        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
            trackGeneration = 1L,
        )
        recorder.onRenderObservation(
            frameOnTrack = 4_800L,
            timeMs = 5_000_000L,
            trackRateHz = 48_000,
            trackGeneration = 1L,
        )

        assertNotNull("a same-generation observation anchors", recorder.renderedReference())
    }
}
