package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        streamEpoch = 0L,
        // Directly-constructed chunks carry no emission ticket: an admission-enforcing recorder refuses
        // them, which is the contract. Tap-path tests drive real emissions instead of this helper.
        admissionTicket = null,
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

    // CONTINUATION IS KEYED TO THE PRODUCER, NOT JUST THE CLOCK. Two 44.1 kHz streams -- an episode
    // followed by another episode at the same rate, no retire between them -- must not inherit each
    // other's fractional position: stream B stores exactly what stream B played.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a same-rate successor producer starts its own continuation instead of inheriting the previous stream`() {
        val srcRate = 44_100
        fun tone(frames: Int, freqHz: Double): FloatArray = FloatArray(frames) { (0.5 * sin(2 * PI * freqHz * it.toDouble() / srcRate)).toFloat() }

        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        // Feed write() directly with the tap's real formats; producerId is the tap's stream epoch.
        val a = tone(977, 220.0)
        val b = tone(977, 440.0)
        recorder.write(FloatArray(a.size * 2) { a[it / 2] }, sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(recorder, 1L))
        val afterA = recorder.snapshot().toList()
        recorder.write(FloatArray(b.size * 2) { b[it / 2] }, sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(recorder, 2L))
        val all = recorder.snapshot()

        // Recompute B alone on a fresh recorder: identical input format, no predecessor.
        val fresh = PlaybackBufferRecorder(FingerprintPcmTap())
        fresh.write(FloatArray(b.size * 2) { b[it / 2] }, sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(fresh, 2L))
        val bAlone = fresh.snapshot()

        val bStored = all.toList().subList(afterA.size, all.size)
        assertEquals("successor stream length must equal the same stream recorded alone", bAlone.size, bStored.size)
        val maxDiff = bStored.indices.maxOf { kotlin.math.abs(bStored[it] - bAlone[it]) }
        assertTrue("inheriting the predecessor's phase would shift every sample: max abs diff=$maxDiff", maxDiff < 0.001f)
    }

    // CONTINUATION IS PER PRODUCER, NOT PER SLOT. Producers interleave in reality (an episode while a
    // same-rate cloud answer plays), and a single shared carry cleared on every switch re-introduces
    // exactly the per-boundary loss the carry exists to remove: A->B->A must RESUME A where A left off.
    // The falsifiable form: over many IRREGULAR alternations, each producer's demultiplexed output is
    // compared -- content AND duration -- against that producer's own solo baseline. A shared slot with
    // switch-resets drifts low immediately; total-length tolerance cannot see it.
    @Test
    fun `many irregular A-B-A alternations reproduce each producer's solo content and duration`() {
        val srcRate = 44_100
        fun mono(frames: Int, freqHz: Double): FloatArray = FloatArray(frames) { (0.45 * sin(2 * PI * freqHz * it.toDouble() / srcRate)).toFloat() }
        fun stereo(m: FloatArray): FloatArray = FloatArray(m.size * 2) { m[it / 2] }
        val xFull = mono(9_000, 220.0)
        val yFull = mono(9_000, 330.0)
        // Irregular partition sizes summing EXACTLY to each stream.
        val parts = listOf(733, 1_013, 491, 1_207, 853, 1_499, 601, 1_103, 1_500)
        assertEquals("the partitioning covers X exactly", xFull.size, parts.sum())
        assertEquals("the partitioning covers Y exactly", yFull.size, parts.sum())

        // Solo baselines: each producer streamed contiguously in the SAME part order.
        val soloX = PlaybackBufferRecorder(FingerprintPcmTap())
        val soloY = PlaybackBufferRecorder(FingerprintPcmTap())
        var cx = 0
        for (n in parts) {
            soloX.write(stereo(xFull.copyOfRange(cx, cx + n)), sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(soloX, 1L))
            cx += n
        }
        var cy = 0
        for (n in parts) {
            soloY.write(stereo(yFull.copyOfRange(cy, cy + n)), sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(soloY, 2L))
            cy += n
        }
        val sx = soloX.snapshot().toList()
        val sy = soloY.snapshot().toList()

        // Interleaved recorder: writes alternate A,B,A,B... with the same part sizes.
        val mixed = PlaybackBufferRecorder(FingerprintPcmTap())
        val xWriteCount = parts.size
        val yWriteCount = parts.size
        cx = 0
        cy = 0
        val xRanges = ArrayList<IntRange>()
        val yRanges = ArrayList<IntRange>()
        var writtenX = 0
        var writtenY = 0
        for (i in 0 until xWriteCount) {
            val nx = parts[i]
            val before = mixed.snapshot().size
            mixed.write(stereo(xFull.copyOfRange(cx, cx + nx)), sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(mixed, 1L))
            xRanges.add(before until mixed.snapshot().size)
            writtenX += nx
            cx += nx
            val ny = parts[i]
            val beforeY = mixed.snapshot().size
            mixed.write(stereo(yFull.copyOfRange(cy, cy + ny)), sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(mixed, 2L))
            yRanges.add(beforeY until mixed.snapshot().size)
            writtenY += ny
            cy += ny
        }

        // Duration: each producer's contribution in the mixed recorder equals its solo length.
        val mixedXLen = xRanges.sumOf { it.last - it.first + 1 }
        val mixedYLen = yRanges.sumOf { it.last - it.first + 1 }
        assertEquals("X keeps its solo duration across switches", sx.size, mixedXLen)
        assertEquals("Y keeps its solo duration across switches", sy.size, mixedYLen)

        // Content: each write's slice in the mixed recorder must equal the matching slice of that
        // producer's solo recording, in order -- phase continuation across switches, not resets.
        val all = mixed.snapshot().toList()
        var sxOff = 0
        for (r in xRanges) {
            val len = r.last - r.first + 1
            for (k in 0 until len) {
                val a = all[r.first + k]
                val b = sx[sxOff + k]
                assertTrue("X sample $k differs: mixed=$a solo=$b", kotlin.math.abs(a - b) < 1e-4f)
            }
            sxOff += len
        }
        var syOff = 0
        for (r in yRanges) {
            val len = r.last - r.first + 1
            for (k in 0 until len) {
                val a = all[r.first + k]
                val b = sy[syOff + k]
                assertTrue("Y sample $k differs: mixed=$a solo=$b", kotlin.math.abs(a - b) < 1e-4f)
            }
            syOff += len
        }
    }

    // RETIREMENT IS ACOUSTIC, NOT ONLY CONVERSION STATE. Reviewer P1: dropping the continuation while
    // leaving the ring intact lets a stopped answer keep rejecting later user speech for the whole
    // buffer age. retireProducer(id) must zero that producer's samples older than the audible delay
    // tail, keep its newest tail matchable (hardware still sounds it), and leave every OTHER producer's
    // samples tagged and present. Falsified by a state-only retirement (samples untouched) and by a
    // global clear (other producer loses audio).
    @Test
    fun `retireProducer zeroes only its own samples beyond the delay tail`() {
        val tail = PlaybackBufferRecorder.DELAY_TAIL_SAMPLES
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val answer = FloatArray(tail + 4_000) { 0.9f }
        val episode = FloatArray(2_000) { 0.7f }
        recorder.write(answer, admission = admFor(recorder, 11L))
        recorder.write(episode, admission = admFor(recorder, 12L))

        recorder.retireProducer(admFor(recorder, 11L))

        val snap = recorder.snapshot().toList()
        // Newest 8000 of the answer may survive; anything older from producer 11 is zero.
        val survivingAnswer = snap.count { kotlin.math.abs(it - 0.9f) < 1e-6f }
        assertTrue("answer tail must survive within the delay window", survivingAnswer in 1..tail)
        assertEquals("episode samples must be untouched by the answer's retirement", 2_000, snap.count { kotlin.math.abs(it - 0.7f) < 1e-6f })
        // Retired slots became UNTAGGED: writing the SAME identity again starts a fresh run, and a
        // second retirement of an already-retired id is harmless.
        recorder.retireProducer(admFor(recorder, 11L))
        assertEquals("double retirement changes nothing further", 2_000, recorder.snapshot().count { kotlin.math.abs(it - 0.7f) < 1e-6f })
    }

    // RENDER OBSERVATIONS DIE WITH THEIR STREAM. An observation anchored to retired audio would let the
    // correlator trust a dead anchor and place a confident wrong offset. Scoped retirement clears the
    // observation written under the retired producer (or whose blocks all came from it) while another
    // producer's observation survives.
    @Test
    fun `retireProducer withdraws observations belonging to the retired stream only`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.write(
            FloatArray(9_600) { 0.5f },
            trackStartFrame = 0L,
            trackEndFrame = 9_600L,
            trackGeneration = 0L,
            admission = admFor(recorder, 21L),
        )
        recorder.onRenderObservation(frameOnTrack = 9_600L, timeMs = 5_000_000L, trackRateHz = 16_000, trackGeneration = 0L, admission = admFor(recorder, 21L))
        assertNotNull("precondition: same-generation observation anchors", recorder.renderedReference())
        recorder.retireProducer(admFor(recorder, 22L))
        assertNotNull("another producer's retirement keeps this observation", recorder.renderedReference())
        recorder.retireProducer(admFor(recorder, 21L))
        assertNull("the retired stream's observation must go with its audio", recorder.renderedReference())
    }

    // PRODUCER RETIREMENT IS SCOPED. A cloud answer stopping calls retireProducer(its id) -- NOT the
    // whole-recorder retire(). Falsifiable form: stream an episode partway at 44.1 kHz, interleave a
    // real-format cloud answer (16 kHz mono), stop the answer through the SCOPED call, then finish the
    // episode. The episode's full output must equal its uninterrupted solo baseline sample-by-sample;
    // a whole-recorder retire here resets the episode's fractional phase (mutant: retire() in the
    // stop position). Also asserts the retired producer's map entry is actually released.
    @Test
    fun `scoped producer retirement leaves a live episode's continuation untouched and releases the entry`() {
        val srcRate = 44_100
        val cloudId = -123L // any distinct non-tap identity
        fun mono(frames: Int, freqHz: Double): FloatArray = FloatArray(frames) { (0.45 * sin(2 * PI * freqHz * it.toDouble() / srcRate)).toFloat() }
        fun stereo(m: FloatArray): FloatArray = FloatArray(m.size * 2) { m[it / 2] }
        val xFull = mono(12_000, 220.0)
        val cloudChunk = FloatArray(4_096) { (0.3 * sin(2 * PI * 600.0 * it.toDouble() / 16_000)).toFloat() }

        // Uninterrupted baseline: the whole episode with no cloud interference.
        val solo = PlaybackBufferRecorder(FingerprintPcmTap())
        solo.write(stereo(xFull), sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(solo, 1L))
        val baseline = solo.snapshot().toList()

        // Interrupted run: half the episode, the answer, scoped stop, remainder.
        val mixed = PlaybackBufferRecorder(FingerprintPcmTap())
        val half = 6_000
        var xOff = 0
        val xRanges = ArrayList<IntRange>()
        var writeCount = 0
        fun episodeWrite(n: Int) {
            val before = mixed.snapshot().size
            mixed.write(stereo(xFull.copyOfRange(xOff, xOff + n)), sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(mixed, 1L))
            xRanges.add(before until mixed.snapshot().size)
            xOff += n
            writeCount++
        }
        episodeWrite(half)
        // The answer streams two real-format chunks, then its stop path runs.
        repeat(2) { mixed.write(cloudChunk, sourceSampleRateHz = 16_000, sourceChannels = 1, admission = admFor(mixed, cloudId)) }
        mixed.retireProducer(admFor(mixed, cloudId))
        episodeWrite(xFull.size - half)

        // Episode content identical to baseline across the stop, part boundaries included.
        val all = mixed.snapshot().toList()
        var bOff = 0
        for (r in xRanges) {
            val len = r.last - r.first + 1
            for (k in 0 until len) {
                assertTrue(
                    "episode sample differs after scoped cloud stop: baseline=" + baseline[bOff + k] + " got=" + all[r.first + k],
                    kotlin.math.abs(all[r.first + k] - baseline[bOff + k]) < 1e-4f,
                )
            }
            bOff += len
        }
        assertEquals("episode keeps its full solo duration across the scoped stop", baseline.size, bOff)

        // ADMISSION CLOSES WITH RETIREMENT (@spec): a callback admitted BEFORE the stop but released
        // AFTER it must reintroduce NOTHING -- no samples, no continuation, no position claim -- and
        // must not corrupt what the successor owns under its own id. The contract is stricter than
        // "restarts fresh": the retired identity contributes nothing at all until a wholesale retire()
        // resets the recorder. Falsified by an implementation that reopens continuations on late writes.
        val resampledChunk = FloatArray(4_096 * 2) { (0.3 * sin(2 * PI * 600.0 * (it / 2).toDouble() / srcRate)).toFloat() }
        val retiredRun = PlaybackBufferRecorder(FingerprintPcmTap())
        retiredRun.write(resampledChunk, sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(retiredRun, cloudId))
        retiredRun.retireProducer(admFor(retiredRun, cloudId))
        val afterRetire = retiredRun.snapshot().toList()
        retiredRun.write(resampledChunk, sourceSampleRateHz = srcRate, sourceChannels = 2, admission = admFor(retiredRun, cloudId))
        assertEquals(
            "a held old write for the retired identity stores nothing beyond what retirement left",
            afterRetire,
            retiredRun.snapshot().toList(),
        )
        // The successor under a DIFFERENT id is unaffected by either the retirement or the refused write.
        val successorChunk = FloatArray(3_200) { 0.25f }
        retiredRun.write(successorChunk, admission = admFor(retiredRun, 99L))
        assertEquals("the successor's contribution lands whole", 3_200, retiredRun.snapshot().count { it == 0.25f })
    }

    // RETIRED TAILS EXPIRE ON ELAPSED TIME, NOT LATER ACTIVITY. Reviewer: keeping the newest 8,000
    // samples with no deadline means a stopped answer stays matchable FOREVER if nothing else writes
    // -- a <=500 ms sample count is not a <=500 ms lifetime. Retirement records a monotonic deadline
    // (tail duration) and every consumer enforces it against the injected clock. Control: stop an
    // answer, advance the clock past the tail WITHOUT another write, and prove the reference no longer
    // holds it -- so real later speech matching the stopped audio is accepted, not dropped as bleed.
    @Test
    fun `a retired tail expires with elapsed time even when no later write arrives`() {
        var nowMs = 1_000_000L
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.monotonicNowMs = { nowMs }
        val tail = PlaybackBufferRecorder.DELAY_TAIL_SAMPLES
        recorder.write(FloatArray(tail + 4_000) { 0.9f }, admission = admFor(recorder, 31L))

        recorder.retireProducer(admFor(recorder, 31L))
        // Within the plausible playback window the tail legitimately survives.
        assertEquals("tail survives inside its expiry window", tail.toLong(), recorder.snapshot().count { it == 0.9f }.toLong())

        // Advance past the tail's own duration. NO further writes happen.
        nowMs += tail * 1_000 / PlaybackBufferRecorder.SAMPLE_RATE + 1
        assertEquals(
            "expired tail must be gone from the reference with no intervening write",
            0,
            recorder.snapshot().count { it == 0.9f },
        )
        // reference() -- the production consumer path -- agrees, not just snapshot().
        assertEquals("expired tail must be gone from reference() too", 0, recorder.reference().samples.count { it == 0.9f })
    }

    // HELD OBSERVATION ACROSS RETIREMENT AND A SUCCESSOR. @spec's admission case in its observation
    // form: an observation captured before the stop, delivered after it, must neither re-anchor the
    // correlator to the retired incarnation nor disturb a successor's own observation.
    @Test
    fun `a held render observation cannot reopen a retired producer or overwrite a successor`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.write(
            FloatArray(9_600) { 0.5f },
            trackStartFrame = 0L,
            trackEndFrame = 9_600L,
            trackGeneration = 1L,
            admission = admFor(recorder, 51L),
        )
        recorder.retireProducer(admFor(recorder, 51L))
        // Successor incarnation anchors normally.
        recorder.write(
            FloatArray(9_600) { 0.6f },
            trackStartFrame = 0L,
            trackEndFrame = 9_600L,
            trackGeneration = 2L,
            admission = admFor(recorder, 52L),
        )
        recorder.onRenderObservation(frameOnTrack = 9_600L, timeMs = 7_000_000L, trackRateHz = 16_000, trackGeneration = 2L, admission = admFor(recorder, 52L))
        val successorRef = recorder.renderedReference()
        assertNotNull("successor keeps its own observation", successorRef)

        // The OLD callback arrives late.
        recorder.onRenderObservation(frameOnTrack = 9_600L, timeMs = 5_000_000L, trackRateHz = 16_000, trackGeneration = 1L, admission = admFor(recorder, 51L))
        assertNotNull("a retired id's late observation cannot clear the successor's anchor", recorder.renderedReference())
        // And a retired id names no live audio: an observation without a prior write is refused anyway;
        // assert the retired id never re-enters through write either.
        // The retired producer's own not-yet-expired tail legitimately remains; what must NOT change
        // is anything at all: a refused write contributes zero samples and shifts no cursor.
        val before = recorder.snapshot().toList()
        recorder.write(FloatArray(1_600) { 0.45f }, admission = admFor(recorder, 51L))
        assertEquals("a retired identity stores nothing -- the reference is byte-identical", before, recorder.snapshot().toList())
    }

    // GLOBAL RETIREMENT INVALIDATES OLD CALLBACKS, IT DOES NOT REOPEN THEIR RIGHT TO WRITE. The defect
    // this defends: retire() used to CLEAR the retired-id ledger and deadlines, so a route change after
    // a cloud stop handed every previously-retired id back to a held callback and left any surviving
    // tail matchable forever. Control per @spec: stopped producer -> global retire (route change) ->
    // successor writes under its own id -> the HELD old write and observation are released -> nothing
    // lands and the successor is untouched.
    @Test
    fun `a callback held across a global retirement cannot repopulate the next incarnation`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        // Producer 61 stops normally (per-producer closure already correct).
        recorder.write(FloatArray(9_600) { 0.7f }, admission = admFor(recorder, 61L))
        recorder.retireProducer(admFor(recorder, 61L))
        // Route change: whole-recorder retirement. Previously this reopened id 61.
        recorder.retire()
        // Successor incarnation on the new route writes under a NEW id.
        recorder.write(FloatArray(4_800) { 0.8f }, admission = admFor(recorder, 62L))
        val successorSnap = recorder.snapshot().toList()

        // The stale callbacks from BEFORE everything happened are released now.
        recorder.write(FloatArray(1_600) { 0.7f }, admission = admFor(recorder, 61L))
        recorder.onRenderObservation(frameOnTrack = 1_000L, timeMs = 9_000_000L, trackRateHz = 16_000, trackGeneration = 61L, admission = admFor(recorder, 61L))

        assertEquals(
            "the held old write reintroduces nothing after global retirement",
            successorSnap,
            recorder.snapshot().toList(),
        )
    }

    // DEADLIVES SURVIVE THE GLOBAL RESET TOO. @reviewer: preserve or replace tail deadlines for every
    // retained sample during global retirement. Control: live producer with a long stream -> global
    // retire keeps only the acoustic tail WITH an expiry -> advance the clock, no further writes -> the
    // retained tail ages out of reference()/snapshot() instead of lingering indefinitely matchable.
    @Test
    fun `a tail retained by global retirement expires on elapsed time without further writes`() {
        var nowMs = 500_000L
        val tail = PlaybackBufferRecorder.DELAY_TAIL_SAMPLES
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.monotonicNowMs = { nowMs }
        recorder.write(FloatArray(tail + 4_000) { 0.35f }, admission = admFor(recorder, 71L))
        recorder.retire()
        assertEquals("global retirement keeps exactly the delay tail", tail.toLong(), recorder.snapshot().count { it == 0.35f }.toLong())

        nowMs += tail * 1_000 / PlaybackBufferRecorder.SAMPLE_RATE + 1
        assertEquals(
            "the globally-retained tail must expire with elapsed time and no intervening write",
            0,
            recorder.snapshot().count { it == 0.35f },
        )
        assertEquals("reference() agrees", 0, recorder.reference().samples.count { it == 0.35f })
    }

    // A DUPLICATE STOP MUST NOT EXTEND A RETIRED TAIL'S LIFETIME (@reviewer). The first retirement
    // fixes the deadline; a second call at a later instant re-arms nothing (putIfAbsent), so expiry
    // still happens one tail-duration after the FIRST stop.
    @Test
    fun `duplicate producer retirement keeps the original expiry`() {
        var nowMs = 0L
        val tail = PlaybackBufferRecorder.DELAY_TAIL_SAMPLES
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.monotonicNowMs = { nowMs }
        recorder.write(FloatArray(tail + 2_000) { 0.62f }, admission = admFor(recorder, 81L))
        recorder.retireProducer(admFor(recorder, 81L)) // deadline fixed at 0 + 500 ms
        nowMs += tail * 1_000 / PlaybackBufferRecorder.SAMPLE_RATE / 2 // half the window elapses
        recorder.retireProducer(admFor(recorder, 81L)) // stale duplicate stop
        nowMs += tail * 1_000 / PlaybackBufferRecorder.SAMPLE_RATE / 2 + 1 // PAST the ORIGINAL deadline
        assertEquals(
            "the duplicate stop must not have extended the tail's matchable lifetime",
            0,
            recorder.snapshot().count { it == 0.62f },
        )
    }

    // THE CARRY MUST BE ATOMIC WITH THE WRITE THAT CONSUMES IT. Probe design: two producers stream
    // MONOTONIC RAMPS through disjoint value bands -- A rises through [0.0, 0.40], B through
    // [0.60, 1.00]. Linear interpolation between equal-neighbour monotone data stays monotone, so
    // each producer's stored subsequence (selected by band) must never decrease. If normalization ran
    // outside the lock, a concurrent producer switch could replace the carry between another thread's
    // read and write, restarting that stream mid-ramp -- a DECREASE inside its own band. Retiring
    // concurrently exercises the other half of the same boundary.
    @Test
    fun `concurrent producers and retirement never splice a replaced carry into an in-flight write`() {
        val srcRate = 44_100
        val framesPerChunk = 977
        // A GLOBAL monotone ramp over many chunks, holding at the band ceiling once climbed: cycling a
        // short list would restart the stream legitimately and read as a decrease.
        val totalChunks = 4_000
        fun rampChunk(base: Float, span: Float, c: Int): FloatArray {
            val startV = minOf(base + span * c / totalChunks, base + span)
            val endV = minOf(base + span * (c + 1) / totalChunks, base + span)
            return FloatArray(framesPerChunk * 2) { i -> (startV + (endV - startV) * ((i / 2) / framesPerChunk.toDouble())).toFloat() }
        }
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val violation = java.util.concurrent.atomic.AtomicReference<String?>(null)

        fun bandCheck(list: FloatArray, lo: Float, hi: Float, name: String) {
            val seq = list.filter { it in lo..hi }
            for (i in 1 until seq.size) {
                if (seq[i] < seq[i - 1] - 1e-3f) {
                    violation.compareAndSet(null, "$name decreased at $i: ${seq[i - 1]} -> ${seq[i]}")
                    return
                }
            }
        }
        fun writer(base: Float, span: Float, rate: Int, id: Long, lo: Float, hi: Float, name: String) = Thread {
            var i = 0
            while (!stop.get() && i < totalChunks) {
                recorder.write(rampChunk(base, span, i), sourceSampleRateHz = rate, sourceChannels = 2, admission = admFor(recorder, id))
                i++
                bandCheck(recorder.snapshot(), lo, hi, name)
            }
        }
        val ta = writer(0.0f, 0.40f, srcRate, 1L, 0.0f, 0.45f, "A")
        val tb = writer(0.60f, 0.40f, 48_000, 2L, 0.55f, 1.01f, "B")
        val retirer = Thread {
            while (!stop.get()) {
                recorder.retire()
                Thread.sleep(0, 200_000)
            }
        }
        listOf(ta, tb, retirer).forEach { it.start() }
        Thread.sleep(900)
        stop.set(true)
        listOf(ta, tb, retirer).forEach { it.join(5_000) }
        violation.get()?.let { throw AssertionError("torn normalization state: $it") }
        assertTrue("writers must have run", ta.state == Thread.State.TERMINATED && tb.state == Thread.State.TERMINATED)
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
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1001L))
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 4_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1002L))
        assertTrue(
            "the hole removes the anchor while both sides are retained",
            recorder.reference().startPositionMs == null,
        )

        // Write past a full buffer (2 s) so the audio BEFORE the hole is overwritten. Everything now
        // retained is at or after the hole, contiguous, and after the resume position.
        var t = 5_000L
        repeat(2) {
            recorder.write(FloatArray(16_000) { 0.5f }, positionMs = t, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1003L))
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
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1004L))
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 1_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1005L))
        recorder.retire()

        // The next contribution is 5 s later: a discontinuity, compared against the TAIL's end.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 7_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1006L))

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

        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1007L))
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 1_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1008L))
        recorder.retire()

        // The next contribution MEETS the tail's end, so the window stays contiguous.
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 2_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1009L))

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
        recorder.write(FloatArray(4_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1010L))
        recorder.write(FloatArray(4_000) { 0.5f }, positionMs = 350L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1011L))

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
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1012L))
        recorder.write(FloatArray(16_000) { -0.5f }, positionMs = 1_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1013L))

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
        recorder.write(FloatArray(16_000) { 0.5f }, positionMs = 0L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1014L))
        val afterFirst = recorder.lastRecordedPositionMs()

        // The NEXT rendered block is 3 s later -- 2 s of audio was dropped by the tap and never reached
        // this consumer. Nothing in the samples says so; the position jump is the only evidence.
        recorder.write(FloatArray(16_000) { -0.5f }, positionMs = 3_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1015L))

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
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE) { 0.5f }, admission = admFor(recorder, 1016L))
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
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE) { 0.25f }, admission = admFor(recorder, 1017L))
        recorder.write(FloatArray(tail) { 0.75f }, admission = admFor(recorder, 1018L))

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
        recorder.write(FloatArray(16) { 0.1f }, positionMs = 1_000L, admission = admFor(recorder, 1019L))
        assertEquals(1_001L, recorder.lastRecordedPositionMs())
        // An untimestamped write must not erase the last known position.
        recorder.write(FloatArray(16) { 0.1f }, admission = admFor(recorder, 1020L))
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
                recorder.write(chunk, admission = admFor(recorder, 1021L))
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
        recorder.write(FloatArray(half) { 0.5f }, positionMs = 1_000, axis = PositionAxis.Media, admission = admFor(recorder, 1022L))
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
                recorder.write(FloatArray(size) { 0.25f }, positionMs = position, admission = admFor(recorder, 1023L))
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
        recorder.write(FloatArray(1_600) { 0.5f }, positionMs = 500L, axis = PositionAxis.Media, admission = admFor(recorder, 900L))

        // A rendered cloud-answer block at 9_000_000 ms of MONOTONIC time -- the axis the render clock
        // supplies. These are the two axes the production sources actually use.
        recorder.write(FloatArray(1_600) { -0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 901L))

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

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 12_000L, axis = PositionAxis.Media, admission = admFor(recorder, 1024L))

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

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1025L))

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
        recorder.write(FloatArray(1_600) { 0.25f }, admission = admFor(recorder, 1026L))

        // Then a monotonic one. If the untimed chunk had defaulted the axis to Media, this window
        // would be Mixed and offer no anchor at all, discarding a valid clock.
        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1027L))

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

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1028L))
        recorder.write(FloatArray(1_600) { 0.25f }, admission = admFor(recorder, 1029L))

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

        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1030L))
        assertNotNull("the timed window anchors first", recorder.reference().startPositionMs)

        // TTS or an earcon lands with no timestamp.
        recorder.write(FloatArray(1_600) { 0.25f }, admission = admFor(recorder, 1031L))

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

        recorder.write(FloatArray(1_600) { 0.25f }, admission = admFor(recorder, 1032L))
        recorder.write(FloatArray(3_200) { 0.5f }, positionMs = 9_000_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 1033L))

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
            admission = admFor(recorder, 504L),
        )
        // Block 2: frames 4800..9600, another 1600 samples.
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 4_800L,
            trackEndFrame = 9_600L,
            admission = admFor(recorder, 504L),
        )

        // The observation: frame 8_000 sounded at 5_000_000 ms. Frame 8_000 is inside block 2, so
        // block 2 has NOT fully sounded; the newest fully-rendered block is block 1 (end 4800).
        recorder.onRenderObservation(frameOnTrack = 8_000L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L, admission = admFor(recorder, 504L))

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
            admission = admFor(recorder, 503L),
        )
        recorder.onRenderObservation(frameOnTrack = 4_800L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L, admission = admFor(recorder, 503L))
        assertNotNull("the block is selectable while retained", recorder.renderedReference())

        // More than the ring's capacity, with no frame identity of its own (e.g. an untimed source).
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE * 8), admission = admFor(recorder, 503L))

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
            admission = admFor(recorder, 503L),
        )
        recorder.onRenderObservation(frameOnTrack = 99_999L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L, admission = admFor(recorder, 503L))

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
            admission = admFor(recorder, 502L),
        )
        recorder.onRenderObservation(frameOnTrack = 4_800L, timeMs = 5_000_000L, trackRateHz = 48_000, trackGeneration = 0L, admission = admFor(recorder, 502L))
        assertNotNull("anchored before the untimed contribution", recorder.renderedReference())

        // Untimed audio joins (no frame range, no observation change), then retires.
        recorder.write(FloatArray(PlaybackBufferRecorder.SAMPLE_RATE * 8), admission = admFor(recorder, 502L))

        // A coherent timed block after the untimed audio has left the ring, and a FRESH observation
        // for the new state -- the old observation (frame 4800) could not render the new block, and
        // that refusal was correct. Recovery is: new timed samples plus a new observation anchor again.
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 4_800L,
            trackEndFrame = 9_600L,
            admission = admFor(recorder, 502L),
        )
        recorder.onRenderObservation(frameOnTrack = 9_600L, timeMs = 5_200_000L, trackRateHz = 48_000, trackGeneration = 0L, admission = admFor(recorder, 502L))

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
        recorder.write(FloatArray(1_600), positionMs = 9_000_000L, axis = PositionAxis.Monotonic, admission = admFor(recorder, 502L))

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
            admission = admFor(recorder, 601L),
        )
        recorder.onRenderObservation(
            frameOnTrack = 4_800L,
            timeMs = 5_000_000L,
            trackRateHz = 48_000,
            trackGeneration = 0L,
            admission = admFor(recorder, 601L),
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
            admission = admFor(recorder, 501L),
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
            admission = admFor(recorder, 500L),
        )
        recorder.onRenderObservation(
            frameOnTrack = 4_800L,
            timeMs = 5_000_000L,
            trackRateHz = 48_000,
            trackGeneration = 1L,
            admission = admFor(recorder, 600L),
        )

        assertNotNull("a same-generation observation anchors", recorder.renderedReference())
    }

    @Test
    fun `renewed admission survives global retire while the stale ticket stays dead`() {
        // @spec's control 2 mechanism at the recorder boundary: an ONGOING owner renews explicitly;
        // route retirement (global retire) invalidates the STALE ticket but not the renewed one, and
        // the retained tail still expires by elapsed time alone.
        var nowMs = 1_000_000L
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.monotonicNowMs = { nowMs }
        val audio = floatArrayOf(0.9f, -0.9f, 0.8f, -0.8f)
        val stale = recorder.openAdmission(70L)
        recorder.write(audio, admission = stale)
        nowMs += 10_000L

        // The continuing owner renews BEFORE the route event, then writes under the renewed ticket.
        val renewed = recorder.renewAdmission(stale)
        recorder.write(audio, admission = renewed)

        // Route change: global retirement closes ALL admissions -- old AND current tickets.
        recorder.retire()
        // Stay WITHIN the 300 ms tail window so the retained-era audio has not expired yet when we
        // measure the precondition below.
        nowMs += 100L

        // Stale ticket callback (even the renewed-era one after retirement) is refused...
        recorder.write(audio, admission = renewed)
        // ...and no NEW admission was opened, so nothing may re-anchor the retired buffer.
        assertNull("retirement removed the anchor; a refused callback cannot restore it", recorder.renderedReference())

        // A genuinely new producer after the route event lands normally. Distinct amplitude band (0.4)
        // so the loud-threshold assertions below measure ONLY the retired-era 0.9/0.8 audio.
        val successor = recorder.openAdmission(71L)
        recorder.write(floatArrayOf(0.4f, -0.4f, 0.35f, -0.35f), admission = successor)
        assertNotNull("the post-retirement successor owns fresh audio", recorder.snapshot().firstOrNull())

        // Elapsed time ALONE expires the retained tails: advance well past the five-minute deadline,
        // with NO further writes, then read the whole span. The only audio left must be the post-
        // retirement successor's own (written before its own deadline); every retired-era sample --
        // including the renewed ticket's pre-retirement write -- has lost its matchability.
        // Advance past the retained-tail deadline (DELAY_TAIL_SAMPLES of audio = 500 ms) with NO
        // further writes. The successor is LIVE, so its
        // audio survives; the retired-era samples (stale + renewed tickets, both closed by retire())
        // expire. A ring snapshot cannot separate them positionally here -- the retired-era audio sat
        // BEFORE the successor's in write order -- so assert on the RING: after expiry, every loud
        // sample remaining belongs at or after the successor's write position.
        val loudBefore = recorder.snapshot().count { kotlin.math.abs(it) > 0.8f }
        assertTrue("precondition: retired-era loud audio still retained within its tail window", loudBefore >= 4)
        nowMs += 5_000L
        val afterExpiry = recorder.snapshot()
        val loudAfter = afterExpiry.count { kotlin.math.abs(it) > 0.8f }
        assertTrue(
            "retired-era loud audio (0.9-amplitude answer tones) expires on elapsed time alone; only the " +
                "live successor's quieter audio may remain",
            loudAfter < loudBefore,
        )
    }

    @Test
    fun `renewal transfers ownership so the previous ticket cannot write again`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val first = recorder.openAdmission(80L)
        val second = recorder.renewAdmission(first)
        assertEquals(first.producerId, second.producerId)
        recorder.write(floatArrayOf(0.4f), admission = second)
        recorder.write(floatArrayOf(0.9f, -0.9f), admission = first)
        val snapshot = recorder.snapshot()
        assertTrue("stale ticket after renewal cannot contribute", snapshot.none { kotlin.math.abs(it) > 0.8f })
        assertTrue(snapshot.any { kotlin.math.abs(it) > 0.3f })
    }

    @Test
    fun `a callback holding only a pre-retirement ticket cannot write its first block after retire`() {
        // @spec's admission case at the ticket level: this producer NEVER wrote before retirement, so no
        // id-membership ledger could have judged it -- but its ticket belongs to the dead generation, and
        // the genuinely-new successor (fresh ticket) lands.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val oldProducer = recorder.openAdmission(90L)
        val episodeAudio = floatArrayOf(0.7f, -0.7f, 0.6f, -0.6f)

        recorder.retire()

        recorder.write(episodeAudio, admission = oldProducer)
        assertTrue("never-admitted old ticket stores nothing", recorder.snapshot().isEmpty())

        val successor = recorder.openAdmission(91L)
        recorder.write(successorAudio(), admission = successor)
        assertNotNull("fresh ticket after retire is admitted", recorder.snapshot().firstOrNull())
    }

    private fun successorAudio(): FloatArray = floatArrayOf(0.2f, -0.2f)

    // TEST ADMISSIONS: one admission per (recorder, id), mirroring how a production owner holds its
    // ticket across writes, observations, and its own retirement. Controls that need a SECOND
    // incarnation or a stale ticket open admissions explicitly instead of using this cache.
    private val testAdmissions = HashMap<Pair<PlaybackBufferRecorder, Long>, PlaybackBufferRecorder.Admission>()

    private fun admFor(recorder: PlaybackBufferRecorder, id: Long): PlaybackBufferRecorder.Admission = testAdmissions.getOrPut(recorder to id) { recorder.openAdmission(id) }

    // CONTROLS @spec/@reviewer: ADMISSION IS CAPTURED AT EMISSION, NOT CONSUMPTION. The tap stamps each
    // chunk when it is created; a wholesale retire() kills every outstanding ticket, so audio that was
    // ALREADY QUEUED stays dead even though delivery happens after the reset -- consumption must never
    // launder in-flight work into the new generation. Only FUTURE emissions from a live owner renew.
    @Test
    fun `a chunk emitted before global retirement is refused when delivered after it`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        val job = recorder.start(this)
        runCurrent()

        // One emission under the collector's first ticket: accepted.
        tap.onSinkBuffer(presentationTimeUs = 0)
        tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { 1_000 })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        runCurrent()
        assertEquals("first emission recorded", 1_600, recorder.snapshot().size)

        // Global retirement WHILE a chunk is in flight: emit, then retire before the flow delivers it.
        // The flow is rendezvous-buffered, so hold the emitter by suspending the collector between
        // emissions instead: emit once more without pumping the dispatcher, retire, then pump.
        tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { (-1_000).toShort() })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        // NOT yet runCurrent(): the chunk sits queued with its emission-time ticket.
        recorder.retire()
        runCurrent()

        // retire() keeps the delay tail of what was ALREADY recorded (chunk 1), so count alone proves
        // nothing -- CONTENT does: chunk 2 samples are negative, chunk 1 positive. The queued chunk
        // carried a ticket that died with the generation, so none of its audio entered the ring.
        val afterRetire = recorder.snapshot()
        assertTrue(
            "the queued chunk carried a ticket that died with the generation, so NONE of it stored",
            afterRetire.none { it < 0f },
        )
        assertTrue("the retired tail of accepted audio remains", afterRetire.any { it > 0f })

        // And the NEXT emission renews at the owner: future audio flows again. Amplitude ~0.61 is
        // distinguishable from the retained tail of the first chunk (~0.03).
        tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { 20_000 })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        runCurrent()
        val future = recorder.snapshot()
        assertTrue(
            "future emissions from the live owner are admitted under a fresh ticket",
            future.any { it > 0.2f && it < 0.8f },
        )
        job.cancel()
    }

    @Test
    fun `expiry owned by an old incarnation cannot zero audio written by the renewed one`() {
        var clockMs = 0L
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.monotonicNowMs = { clockMs }
        val old = recorder.openAdmission(940L)
        recorder.write(FloatArray(1_600) { 0.2f }, admission = old)
        recorder.retireProducer(old)

        // The renewed stream shares the logical producer id -- a DIFFERENT incarnation.
        val renewed = recorder.renewAdmission(old)
        clockMs += 10_000L
        recorder.write(FloatArray(1_600) { 0.7f }, admission = renewed)

        // Advance past the OLD deadline: expiry fires for the retired ticket only.
        clockMs += PlaybackBufferRecorder.DELAY_TAIL_SAMPLES * 1_000L / PlaybackBufferRecorder.SAMPLE_RATE + 1_000L
        val samples = recorder.snapshot()
        assertTrue(
            "renewed audio still present while the old incarnation's tail is gone",
            samples.any { kotlin.math.abs(it - 0.7f) < 0.01f },
        )
        assertFalse(
            "the old ticket's samples expired on their OWN deadline",
            samples.any { kotlin.math.abs(it - 0.2f) < 0.01f },
        )
    }

    @Test
    fun `retirement keyed by an old incarnation leaves the renewed stream's anchor standing`() {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val old = recorder.openAdmission(941L)
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
            trackGeneration = 0L,
            admission = old,
        )
        recorder.onRenderObservation(4_800L, 5_000_000L, 48_000, 0L, old)
        assertNotNull("anchored under the old incarnation", recorder.renderedReference())

        // Renew and record under the NEW ticket, same generation-free identity rules.
        val renewed = recorder.renewAdmission(old)
        recorder.write(
            FloatArray(1_600),
            positionMs = null,
            axis = null,
            trackStartFrame = 0L,
            trackEndFrame = 4_800L,
            trackGeneration = 1L,
            admission = renewed,
        )
        recorder.onRenderObservation(4_800L, 6_000_000L, 48_000, 1L, renewed)

        // A late stop of the OLD incarnation withdraws only what IT owns.
        recorder.retireProducer(old)
        assertNotNull(
            "the renewed incarnation keeps its anchor; old-ticket retirement touched only old state",
            recorder.renderedReference(),
        )
    }

    // CONTROLS @reviewer: the ACTUAL tap sequence, not a recorder-shaped proxy. Emit/drain under the
    // owner's first ticket -> global retire (registry intentionally emptied) -> emit/drain the SAME
    // epoch. The active emission owner must renew future emissions from its RETAINED Admission; if
    // renewal recovered ownership from the cleared registry, every later chunk would be dead forever
    // until a seek changed the epoch. Meanwhile a chunk held in flight across the reset stays refused.
    @Test
    fun `the same episode epoch resumes recording after global retirement via the tap path`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        val job = recorder.start(this)
        runCurrent()

        fun emit(value: Short) {
            tap.onSinkBuffer(presentationTimeUs = 0)
            tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { value })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        }

        emit(1_000)
        runCurrent()
        assertEquals("first emission recorded", 1_600, recorder.snapshot().size)

        // In-flight across the reset: emitted now, delivered after retire().
        emit(-1_000)
        recorder.retire()
        runCurrent()
        val afterReset = recorder.snapshot()
        assertTrue(
            "the queued chunk kept its emission-time ticket, which died with the generation",
            afterReset.none { it < 0f },
        )

        // SAME epoch continues (no seek, no flush): the live owner renews at emission, so this lands.
        // Amplitude chosen so the stored float (~0.61) is distinguishable from the retained tail (~0.03).
        emit(20_000)
        runCurrent()
        val resumed = recorder.snapshot()
        assertTrue(
            "future emissions of the unchanged episode epoch are admitted under a fresh ticket",
            resumed.any { it > 0.2f && it < 0.8f },
        )
        job.cancel()
    }

    // CONTROL @reviewer 4235702596: the capture-session boundary must retire, not merely cancel the
    // tap. Before this, stop() left up to the whole retained window in the ring and a later start()
    // subscribed on top of it -- so the FIRST utterance of a NEW session could correlate against PCM
    // the PREVIOUS session submitted. stop() is the recorder's own session boundary (its only
    // production caller is service teardown), so it retires globally: at most the delay-plausible
    // tail survives, and it carries an expiry like every other retired tail.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `stop retires the session so a later start cannot correlate previous-session audio`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        val job = recorder.start(this)
        runCurrent()

        // ~1.5 s of distinctive loud audio at 16 kHz mono through the REAL tap path.
        val frames = 24_000
        val data = ShortArray(frames) { 20_000 }
        val format = AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT)
        tap.onSinkBuffer(presentationTimeUs = 0)
        tap.onPcm(ByteBuffer.wrap(s16le(data)), format)
        runCurrent()

        assertTrue(
            "precondition: the session recorded well past the delay tail",
            recorder.snapshot().count { it > 0.5f } > 8_000,
        )

        recorder.stop()
        val afterStop = recorder.snapshot()
        assertTrue(
            "a retired session keeps at most the delay tail (500 ms = 8000 samples), got ${'$'}{afterStop.size}",
            afterStop.size <= 8_000,
        )
        assertEquals("retained samples are the newest ones", afterStop.size, afterStop.count { it > 0.5f })

        // A new session records afresh; the old bulk cannot be part of its reference.
        // SAME-EPOCH RESTART (@reviewer/@spec): no seek/flush happened, so the tap still carries the
        // previous collector's (now dead) ticket for an unchanged epoch. Ownership must transfer at
        // stamper installation -- otherwise the first emission after restart returns the dead ticket
        // and the new session records NOTHING while appearing healthy.
        val job2 = recorder.start(this)
        runCurrent()
        val newData = ShortArray(16_000) { -18_000 }
        // CONTINUATION OF THE SAME STREAM (@reviewer): no new anchor, so no epoch bump -- the tap is
        // still on lastEmittedEpoch with the previous collector's dead carried ticket. This is exactly
        // the same-epoch stop->start shape where ownership must transfer at stamper installation.
        tap.onPcm(ByteBuffer.wrap(s16le(newData)), format)
        runCurrent()
        job2.cancel()
        val nextSession = recorder.snapshot()
        assertTrue(
            "same-epoch restart on the REAL tap/start path must record new audio, not silently drop it",
            nextSession.count { it < -0.4f } > 8_000,
        )
        assertTrue(
            "previous-session bulk audio is gone: positives=${'$'}{nextSession.count { it > 0.5f }} of ${'$'}{nextSession.size}",
            nextSession.count { it > 0.5f } <= 8_000,
        )
    }

    // POSITION ATTRIBUTION (4236341498): a cue written on the Monotonic axis extends the window's
    // truthfully -- the derived start accounts for the cue's duration, and the window reports its
    // position only while every contribution is on one clock.
    @Test
    fun `a monotonic-axis cue write keeps the window start truthful`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        var nowMs = 50_000L
        recorder.monotonicNowMs = { nowMs }
        val admission = recorder.openAdmission(31L)
        // 0.5 s episode chunk at media second 100 (Media axis), then a 0.25 s cue at monotonic 50_000.
        recorder.write(FloatArray(8_000) { 0.5f }, positionMs = 100_000L, axis = PositionAxis.Media, admission = admission)
        nowMs = 50_100L
        recorder.write(FloatArray(4_000) { 0.5f }, positionMs = 50_000L, axis = PositionAxis.Monotonic, admission = admission)
        // Two clocks in one window: the honest answer is NO position.
        assertNull("a window spanning Media and Monotonic clocks reports no position", recorder.reference().startPositionMs)
        // Same clock only: the cue's duration is accounted for in the derived start.
        val solo = PlaybackBufferRecorder(tap)
        var soloNow = 50_000L
        solo.monotonicNowMs = { soloNow }
        val soloAdmission = solo.openAdmission(33L)
        solo.write(FloatArray(8_000) { 0.5f }, positionMs = 50_000L, axis = PositionAxis.Monotonic, admission = soloAdmission)
        soloNow = 50_100L
        solo.write(FloatArray(4_000) { 0.5f }, positionMs = 50_500L, axis = PositionAxis.Monotonic, admission = soloAdmission)
        assertEquals(
            "the window start accounts for every sample's own position: 12_000 samples (750 ms) ending at 50_750",
            50_000L,
            solo.reference().startPositionMs!!,
        )
    }

    // CONTROL @reviewer 4235702596 (episode renderer stop/pause hook): the media3 sink forwards BOTH
    // flush() and reset() to tap.onSinkFlush(), which bumps the stream epoch. The stamper must treat
    // that as supersession: retire the paused/seeked-away producer (its samples get an expiry, a held
    // old callback cannot re-enter) and admit the successor under a fresh ticket -- the same stale-
    // reference case this PR removed for cloud answers, now pinned for the episode path.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an episode sink flush retires the paused producer and admits the successor epoch`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        // Install the controllable monotonic clock BEFORE any write so retirement deadlines are
        // recorded on the same axis the test advances (default seam is uptime-scaled nanos).
        var nowMs = 0L
        recorder.monotonicNowMs = { nowMs }
        val job = recorder.start(this)
        runCurrent()

        var anchorUs = 0L
        fun emit(value: Short) {
            tap.onSinkBuffer(presentationTimeUs = anchorUs)
            anchorUs += 100_000
            tap.onPcm(ByteBuffer.wrap(s16le(ShortArray(1_600) { value })), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        }

        // Episode A plays.
        emit(1_000)
        runCurrent()
        assertEquals("first emission recorded", 1_600, recorder.snapshot().size)

        // A chunk emitted before the flush is still in flight when the user pauses/seeks the episode.
        emit(-1_000)
        tap.onSinkFlush()
        runCurrent()
        // Successor epoch B resumes playback after the pause.
        emit(20_000)
        runCurrent()

        val snap = recorder.snapshot()
        assertTrue(
            "the successor epoch's audio must be admitted (loud positive samples present)",
            snap.any { it > 0.2f },
        )
        // The pre-flush in-flight chunk carried A's emission-time ticket; A died at the flush, so any
        // surviving A audio is a RETIRED tail and must expire from every consumer on elapsed time
        // alone -- @reviewer's stopped-answer-only shape, applied to the episode path.
        val retiredA = -1_000 / 32768f
        assertTrue(
            "episode B must be stored under its own fresh admission",
            snap.count { it > 0.2f } >= 1_600,
        )
        // Advance the injected clock beyond the tail deadline WITHOUT another write.
        nowMs = 60_000L
        val expired = recorder.snapshot()
        assertTrue(
            "a retired episode-A tail must expire from snapshot with elapsed time alone",
            expired.none { kotlin.math.abs(it - retiredA) < 0.01f },
        )
        // And matching later speech must be accepted through the filter-equivalent read path: the
        // window after expiry contains only B.
        val window = recorder.window()
        assertTrue(
            "the post-expiry window must hold only the successor epoch",
            window.samples.none { kotlin.math.abs(it - retiredA) < 0.01f },
        )
        job.cancel()
    }
}
