package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Streaming windowed spectral-peak fingerprinter — the mobile port of the
 * server's `SpectralGenerator` (Go, `internal/fingerprint/spectral.go`).
 *
 * It computes per-window (8 s windows, 1 s stride) sorted uint32 hash sets
 * that are bit-identical to the server's reference fingerprints for the same
 * audio, so the client can match against `fingerprint-compact-v2` data served
 * by `/api/v1/episodes/{id}/fingerprints`.
 *
 * Pipeline: PCM → downmix → windowed-sinc resample to 16 kHz → Hann STFT
 * (4096-pt FFT, 1024 hop) → spectral peak picking (adaptive floor) →
 * anchor/target pairing into 32-bit hashes → per-window sorted hash sets.
 *
 * IMPORTANT: every constant and float operation mirrors the Go implementation
 * exactly. The trig comes from fdlibm-derived implementations on both sides
 * (Go `math` and JVM `StrictMath`), so hash parity holds. Do not "improve"
 * the math here without changing the server in lockstep and regenerating the
 * parity fixtures (`CloudFingerprinterTest`).
 */
class CloudFingerprinter {
    // Window geometry mirrors the server constants (also the mobile
    // FingerprintConstants where the same concept exists).
    private val windowDurationMs = 8000
    private val windowIntervalMs = 1000
    private val targetSampleRate = 16000
    private val fftSize = 4096
    private val hopSize = 1024
    private val maxPeaksPerFrame = 8
    private val peakMinSeparation = 3
    private val peakFloorRatio = 0.05
    private val targetZoneFrames = 24
    private val maxTargetsPerAnchor = 5
    private val resampleTaps = 16
    private val frameDurationS = hopSize.toDouble() / targetSampleRate

    /** A completed window: start time (s, relative to this stream's start) and its sorted hash set. */
    data class Window(val timestampSec: Int, val hashes: LongArray) {
        override fun equals(other: Any?): Boolean = other is Window && timestampSec == other.timestampSec && hashes.contentEquals(other.hashes)
        override fun hashCode(): Int = 31 * timestampSec + hashes.contentHashCode()
    }

    private data class Peak(val bin: Int, val frame: Int)

    /**
     * Growable float buffer with absolute indexing: [get]/[trimTo] take
     * absolute positions, so consumers can trim consumed prefixes without
     * re-basing their arithmetic. Not thread-safe (single streaming thread).
     */
    private class FloatSpan(initialCapacity: Int = 1 shl 16) {
        private var array = FloatArray(initialCapacity)
        private var base = 0L // absolute index of array[0]
        private var count = 0 // valid elements in array

        /** Absolute index of the first retained element. */
        val start: Long get() = base

        /** Absolute index one past the last appended element. */
        val end: Long get() = base + count

        operator fun get(index: Long): Float = array[(index - base).toInt()]

        fun append(value: Float) {
            ensureCapacity(count + 1)
            array[count++] = value
        }

        fun trimTo(newBase: Long) {
            val drop = (newBase - base).toInt()
            if (drop <= 0) return
            array.copyInto(array, 0, drop, count)
            base += drop
            count -= drop
        }

        private fun ensureCapacity(required: Int) {
            if (required <= array.size) return
            var newSize = array.size
            while (newSize < required) newSize = newSize shl 1
            array = array.copyOf(newSize)
        }
    }

    // Input (downmixed, source rate) and resampled (16 kHz mono) buffers.
    // Both are absolute-indexed with consumed prefixes trimmed after use, so
    // peak memory is O(window), not O(episode).
    private val input = FloatSpan()
    private val resampled = FloatSpan()
    private var sourceRate = targetSampleRate

    // STFT frames and their peaks, computed incrementally. framePeaks holds
    // frames [frameBase, frameBase + framePeaks.size); Peak.frame stores the
    // absolute frame index, so dt math is trim-invariant.
    private val framePeaks = mutableListOf<List<Peak>>()
    private var frameBase = 0L
    private var frameStart = 0L

    private val hannWindow = DoubleArray(fftSize) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))
    }

    private val windows = mutableListOf<Window>()
    private var consumedWindows = 0
    private var nextWindowStartSec = 0
    private var finished = false

    /** Feeds a chunk of decoded PCM (normalized float, [-1, 1]) at the source sample rate. */
    fun pushSamples(samples: FloatArray, channels: Int, sampleRate: Int) {
        if (finished) return
        if (sampleRate > 0) sourceRate = sampleRate
        appendDownmixed(samples, channels)
        drainResampled()
        computeFrames()
        emitWindows()
    }

    /**
     * Signals the end of the stream: flushes the remaining resampled samples,
     * computes the final frames, and emits every remaining window with the
     * target-zone clamped to the available frames (mirrors the server's
     * whole-file processing).
     */
    fun finish(): List<Window> {
        if (!finished) {
            finished = true
            drainResampled()
            computeFrames()
            while (nextWindowStartSec <= lastFullWindowStartSec()) {
                emitWindow(nextWindowStartSec)
                nextWindowStartSec += windowIntervalMs / 1000
            }
        }
        // Only the not-yet-drained tail: callers that already drainWindows()
        // during the stream must not re-match the whole history.
        return drainWindows()
    }

    /** Windows emitted so far (a window is only emitted once its tail lookahead is available). */
    fun windowsSoFar(): List<Window> = windows

    /**
     * Returns the windows emitted since the last call to this method. The
     * caller feeds PCM in chunks and drains windows after each push; finished
     * windows are tracked so the same window is not returned twice.
     */
    fun drainWindows(): List<Window> {
        val emitted = windows.drop(consumedWindows)
        consumedWindows = windows.size
        return emitted
    }

    private fun appendDownmixed(samples: FloatArray, channels: Int) {
        if (channels <= 1) {
            for (sample in samples) input.append(sample)
        } else {
            val n = samples.size / channels
            for (i in 0 until n) {
                var sum = 0.0f
                for (c in 0 until channels) sum += samples[i * channels + c]
                input.append(sum / channels)
            }
        }
    }

    private fun drainResampled() {
        if (input.end == 0L) return
        // Mirror the server's output length: floor(len × toRate / fromRate).
        val targetOutLen = (input.end.toDouble() * targetSampleRate / sourceRate).toLong()
        val ratio = sourceRate.toDouble() / targetSampleRate
        val cutoff = if (targetSampleRate < sourceRate) {
            0.9 * targetSampleRate.toDouble() / sourceRate
        } else {
            0.9
        }
        while (resampled.end < targetOutLen) {
            val pos = resampled.end * ratio
            val i0 = pos.toInt()
            val frac = pos - i0
            var sum = 0.0
            for (k in -resampleTaps..resampleTaps) {
                val idx = i0.toLong() + k
                if (idx < 0 || idx >= input.end) continue
                sum += input[idx].toDouble() * sincKernel(k - frac, cutoff)
            }
            resampled.append(sum.toFloat())
        }
        // Input samples older than the next resample position minus the kernel
        // radius can never be read again.
        val stillNeeded = ((resampled.end * ratio).toInt() - resampleTaps).coerceAtLeast(0).toLong()
        input.trimTo(stillNeeded)
    }

    /** Blackman-windowed sinc kernel — mirrors the server's `sincKernel`. */
    private fun sincKernel(t: Double, cutoff: Double): Double {
        val value = if (t == 0.0) cutoff else sin(PI * t * cutoff) / (PI * t)
        val n = 2.0 * resampleTaps
        val win = 0.42 -
            0.5 * cos(2.0 * PI * (t + resampleTaps) / n) +
            0.08 * cos(4.0 * PI * (t + resampleTaps) / n)
        return value * win
    }

    private fun computeFrames() {
        while (frameStart + fftSize <= resampled.end) {
            val re = DoubleArray(fftSize)
            val im = DoubleArray(fftSize)
            for (i in 0 until fftSize) {
                re[i] = resampled[frameStart + i].toDouble() * hannWindow[i]
            }
            fft(re, im)
            val mag = DoubleArray(fftSize / 2 + 1) { i -> hypot(re[i], im[i]) }
            framePeaks.add(pickPeaks(mag))
            frameStart += hopSize
        }
        // Frames before the oldest window still to be emitted are never read
        // again (windowHashes starts at firstFrame(nextWindowStartSec)).
        val firstNeededFrame = (nextWindowStartSec.toDouble() / frameDurationS).toLong()
        trimFramesBefore(firstNeededFrame)
        // Resampled samples before the current frame frontier are consumed.
        resampled.trimTo(frameStart)
    }

    private fun trimFramesBefore(absoluteFrame: Long) {
        var drop = (absoluteFrame - frameBase).toInt()
        if (drop <= 0) return
        if (drop > framePeaks.size) drop = framePeaks.size
        if (drop <= 0) return
        framePeaks.subList(0, drop).clear()
        frameBase += drop
    }

    private fun pickPeaks(mag: DoubleArray): List<Peak> {
        val floor = mag.max() * peakFloorRatio
        val local = mutableListOf<Int>()
        for (b in 2 until mag.size - 2) {
            if (mag[b] > floor &&
                mag[b] >= mag[b - 1] && mag[b] >= mag[b - 2] &&
                mag[b] >= mag[b + 1] && mag[b] >= mag[b + 2]
            ) {
                local.add(b)
            }
        }
        // Descending magnitude, deterministic tie-break by bin (matches Go).
        local.sortWith(compareByDescending<Int> { mag[it] }.thenBy { it })
        val selected = mutableListOf<Int>()
        for (b in local) {
            if (selected.size >= maxPeaksPerFrame) break
            if (selected.none { abs(it - b) < peakMinSeparation }) selected.add(b)
        }
        return selected.map { Peak(it, (frameBase + framePeaks.size).toInt()) }
    }

    private fun emitWindows() {
        if (finished) return
        while (nextWindowStartSec <= lastFullWindowStartSec() && canEmit(nextWindowStartSec)) {
            emitWindow(nextWindowStartSec)
            nextWindowStartSec += windowIntervalMs / 1000
        }
    }

    private fun lastFullWindowStartSec(): Int {
        // lastFullStart = int(totalDuration) - windowDuration; we don't know the
        // final duration mid-stream, so upper-bound with the available frames.
        val duration = windowDurationMs / 1000
        val totalDuration = (resampled.end.toDouble() / targetSampleRate).toInt()
        return (totalDuration - duration).coerceAtLeast(0)
    }

    // A window can be emitted once its last anchor frame's target zone is
    // within the computed frames: lastFrame(start) + targetZoneFrames < size.
    private fun canEmit(startSec: Int): Boolean {
        val lastFrame = ((startSec + windowDurationMs / 1000).toDouble() / frameDurationS).toInt()
        return lastFrame + targetZoneFrames < frameBase + framePeaks.size
    }

    private fun emitWindow(startSec: Int) {
        windows.add(Window(startSec, windowHashes(startSec)))
    }

    private fun windowHashes(startSec: Int): LongArray {
        val firstFrame = (startSec.toDouble() / frameDurationS).toInt()
        val lastFrame = ((startSec + windowDurationMs / 1000).toDouble() / frameDurationS).toInt()

        val seen = HashSet<Long>()
        val hashes = mutableListOf<Long>()
        for (fi in firstFrame..lastFrame) {
            if (fi >= frameBase + framePeaks.size) break
            val anchorFrameList = framePeaks.getOrNull((fi - frameBase).toInt()) ?: break
            for (anchor in anchorFrameList) {
                var targets = 0
                var tf = fi + 1
                while (tf < frameBase + framePeaks.size && tf <= fi + targetZoneFrames && targets < maxTargetsPerAnchor) {
                    for (target in framePeaks[(tf - frameBase).toInt()]) {
                        if (targets >= maxTargetsPerAnchor) break
                        val h = pairHash(anchor.bin, target.bin, tf - fi)
                        if (seen.add(h)) hashes.add(h)
                        targets++
                    }
                    tf++
                }
            }
        }
        return hashes.sorted().toLongArray()
    }

    /** Mirrors the server's `pairHash` and `freqCode`. */
    private fun pairHash(bin1: Int, bin2: Int, dt: Int): Long = ((freqCode(bin1).toLong() shl 22) or (freqCode(bin2).toLong() shl 12) or (dt.toLong() and 0xFFF))

    private fun freqCode(bin: Int): Int {
        var hz = bin * targetSampleRate.toDouble() / fftSize
        if (hz < 20.0) hz = 20.0
        val lo = log2(20.0)
        val hi = log2(targetSampleRate / 2.0)
        var v = (log2(hz) - lo) / (hi - lo)
        if (v < 0) v = 0.0
        if (v > 1) v = 1.0
        return (v * 1023).toInt()
    }

    // Mirrors Go math.Log2: Log(x) * (1 / Ln2).
    private fun log2(x: Double): Double = ln(x) * (1.0 / 0.6931471805599453)

    private fun abs(v: Int): Int = if (v < 0) -v else v

    /** In-place radix-2 Cooley-Tukey FFT — verbatim port of the server's `fft`. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val ang = -2.0 * PI / length
            val wRe = cos(ang)
            val wIm = sin(ang)
            val half = length / 2
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += length
            }
            length = length shl 1
        }
    }
}
