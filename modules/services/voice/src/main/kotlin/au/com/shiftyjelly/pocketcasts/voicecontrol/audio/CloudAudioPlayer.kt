package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import java.nio.ByteBuffer
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Plays binary audio frames from the cloud route.
 *
 * The player buffers frames as they arrive, decodes them if the codec requires it (Opus), and
 * writes decoded PCM to an [AudioTrack] for playback. It supports pause/resume so the host
 * player can be paused while cloud audio plays and restored after.
 *
 * **Supported codecs:**
 * - `pcm_s16le` — 16-bit signed little-endian PCM, played directly.
 * - `opus` — decoded via [MediaCodec] to PCM, then played.
 *
 * Unknown or empty codecs default to PCM with the provided sample rate and channel config.
 */
class CloudAudioPlayer(
    private val context: Context,
    private val sampleRateHz: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    // Fed with the PCM this player actually sends to the output, so the shared echo reference holds
    // the cloud answer as it is played. Null in contexts that do not run the echo filter.
    private val playbackBufferRecorder: PlaybackBufferRecorder? = null,
    // Publishes this player's observed output routing, so route-dependent decisions read where the audio
    // actually goes rather than what is merely available. Null in contexts with no route consumer.
    private val routedOutputObserver: au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver? = null,
) {
    /** This stream's claim on the routed observation; cleared when the player retires. */
    private val observedRoute = routedOutputObserver?.observe()

    /**
     * Converts decoded PCM to the recorder's timeline, holding phase across chunks.
     *
     * Per-player state on purpose: the reference is written chunk by chunk, so continuity belongs to
     * this stream's frame sequence rather than to a single conversion call.
     */
    private val resampler = ChunkResampler()

    /**
     * Audio attributes that route through the shared STREAM_MUSIC output path.
     *
     * USAGE_MEDIA + CONTENT_TYPE_SPEECH maps to AudioManager.STREAM_MUSIC, the same
     * stream the host player uses. This means cloud audio is mixed into the shared
     * output path by the Android audio system — not isolated on a separate path.
     *
     * Ducking is handled by AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, which causes the
     * host player's FocusManager to lower its volume while cloud audio plays.
     */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var audioTrack: AudioTrack? = null

    /**
     * Reports whether this player is currently making sound, for the voice gate.
     *
     * The gate treats `AudioManager.isMusicActive` as "another app is playing" unless we are the
     * ones emitting. Its self-attribution timestamp is written by the earcon/TTS renderer, which
     * never runs for a cloud answer — so a multi-second answer was heard as a stranger and the
     * microphone was cut while we were still speaking. This is the cloud path joining that same
     * signal rather than the gate special-casing a cloud turn.
     */
    var onPlaybackAudibleChanged: ((Boolean) -> Unit)? = null

    /** Ticks the audible stamp while we are playing, so it cannot go stale mid-answer. */
    private var audibleHeartbeat: Job? = null
    private val frameBuffer = mutableListOf<ByteArray>()
    private var playing = false
    private var paused = false

    /** Cumulative samples this player has submitted, used as the answer's playback position. */
    private var submittedSamples = 0L
    private var released = false
    private var decodeCodec: MediaCodec? = null
    private var decodeBuffering = false

    /** Negotiated codec name; empty means PCM. */
    var codec: String = ""
        internal set

    /** Current sample rate derived from the negotiated codec. */
    var currentSampleRate: Int = sampleRateHz
        private set

    /**
     * Update the codec after the server's auth response, before any audio frames arrive.
     * Re-creates the [AudioTrack] and decoder so the sample rate and channel config
     * match the server's negotiated settings.
     */
    fun setCodec(name: String) {
        if (name != codec) {
            codec = name
            currentSampleRate = parseSampleRate(name)
            Timber.i("[CloudAudio] negotiated codec: %s (sample rate: %d Hz)", name, currentSampleRate)
            rebuildTrackAndDecoder()
        }
    }

    /** Parse the sample rate from a codec string like `opus@48k` or `pcm@24k`. */
    private fun parseSampleRate(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("48k") -> 48000
            lower.contains("24k") -> 24000
            lower.contains("16k") -> 16000
            else -> sampleRateHz // default
        }
    }

    /** Re-create the [AudioTrack] and [MediaCodec] decoder to match the negotiated codec. */
    private fun rebuildTrackAndDecoder() {
        decodeCodec?.stop()
        decodeCodec?.release()
        decodeCodec = null
        audioTrack?.release()
        audioTrack = null
        ensureAudioTrack()
    }

    /** True while audio is actively being played (not paused, not idle). */
    val isPlaying: Boolean get() = playing && !paused

    /**
     * Where this player's output is ACTUALLY routed, or null when that cannot be known.
     *
     * `AudioRouting.getRoutedDevice()` reports the device a live stream is routed to, which is a
     * different question from the enumerated availability the route monitor reads: a paired headset
     * that media is not using is enumerated and not routed. Null is returned rather than a guess when
     * the track is absent, inactive, or the platform has no routing yet — an unknown route must stay
     * unknown, because reporting it as the speaker would be a claim the API has not made.
     */
    fun routedOutputDevice(): AudioDeviceInfo? {
        val track = audioTrack ?: return null
        if (!isPlaying) return null
        return runCatching {
            // Valid only while the stream is active; a released or stopped track reports null.
            track.routedDevice
        }.getOrNull()
    }

    /** True when at least one frame was successfully written to the AudioTrack. */
    var audioWritten = false

    /** Set when the sink refuses audio mid-answer, so the turn is stopped rather than silently gapped. */
    @Volatile
    private var audioWriteFailed = false
        private set

    /**
     * Submits one frame to the output and returns how many bytes the sink accepted (<= 0 for a
     * refusal). Defaults to the real AudioTrack; a test can substitute a refusing writer so the
     * refusal path is exercised deterministically rather than inferred from a code reading.
     */
    var writeToSink: ((track: AudioTrack, bytes: ByteArray, offset: Int, count: Int) -> Int)? = null

    /**
     * Called when the sink refuses answer audio mid-turn.
     *
     * A refusal stops the answer, and without a path to the turn's result the caller could not tell
     * it from a clean end — the same gap the TTS path had. This reports the failure so the turn is
     * not recorded as completed; it is deliberately separate from any server terminal outcome, which
     * is a different fact and is not overwritten by a local playback failure.
     */
    var onWriteFailure: ((framesSubmitted: Long) -> Unit)? = null

    init {
        ensureAudioTrack()
    }

    /**
     * Submit a binary audio frame for buffering and eventual playback.
     *
     * Frames are queued until [play] is called; they are then consumed in submission order.
     */
    fun submitFrame(bytes: ByteArray) {
        if (released) return
        synchronized(frameBuffer) {
            frameBuffer.add(bytes)
        }
        // If we're already playing, try to drain immediately.
        if (playing && !paused) {
            drainFrames()
        }
    }

    /**
     * Start (or resume) playback.
     *
     * If the track is not yet playing, this transitions it to playing.
     * If it was paused, this resumes from where it left off.
     */
    fun play() {
        if (released) return
        ensureAudioTrack()
        synchronized(frameBuffer) {
            if (paused) {
                audioTrack?.play()
                paused = false
                Timber.i("[CloudAudio] resumed")
                startAudibleHeartbeat()
            } else if (!playing) {
                audioTrack?.play()
                playing = true
                drainFrames()
                Timber.i("[CloudAudio] started")
                startAudibleHeartbeat()
            }
        }
    }

    /** Pause playback without discarding the buffer. */
    fun pause() {
        if (released) return
        synchronized(frameBuffer) {
            if (playing && !paused) {
                audioTrack?.pause()
                paused = true
                Timber.i("[CloudAudio] paused")
            }
        }
    }

    /**
     * Drain remaining buffered frames to the audio track, then stop and discard.
     *
     * Use this on turn completion so the last audio frames land before the player
     * resets. Call [stop] when a turn is superseded and stale frames must not play.
     */
    fun drainAndStop() {
        if (released) return
        synchronized(frameBuffer) {
            drainFrames()
        }
        stop()
    }

    /**
     * Stop playback and discard the frame buffer.
     *
     * Call this when a turn is finished or cancelled so stale frames from a superseded turn
     * do not bleed into the next one.
     */
    fun stop() {
        if (released) return
        synchronized(frameBuffer) {
            frameBuffer.clear()
            if (playing) {
                audioTrack?.stop()
                playing = false
                paused = false
                Timber.i("[CloudAudio] stopped")
                stopAudibleHeartbeat()
            }
        }
        // The answer is no longer playing, so retire its reference contribution. Only the acoustic
        // delay tail survives, so speech after the stop cannot be matched against audio that is
        // already gone and dropped as bleed.
        playbackBufferRecorder?.retire()
        // And withdraw the routed observation. The contract is that the published route describes a
        // live reporting stream: this stream has stopped, so leaving the route published would keep
        // answering for a stream that is gone. Safe to call alongside release()'s own clear: the
        // observer releases a claim at most once per stream, so the second clear withdraws nothing
        // and the claim accounting stays correct.
        observedRoute?.clear()
    }

    /**
     * Report "we are making sound" every [AUDIBLE_HEARTBEAT_MS] while playing.
     *
     * A single stamp at play start goes stale in an answer longer than the gate's attribution
     * window (5 s), and the microphone is then cut while we are still speaking — the reported bug,
     * just past the window. The refresh cannot ride the write path, because that path returns when
     * the buffer empties and the client is designed to ride out an underrun; a stamp tied to it
     * would look like coverage and be a no-op in exactly the case the property exists for.
     *
     * The tick samples the same predicate the write path and [submitFrame] already sample
     * (`playing && !paused`) — it is that predicate sampled by time rather than by frame arrival,
     * not a second way of answering the question.
     *
     * Coupled to [pause] deliberately: pausing stops the tick, so the gate's copy keeps the last
     * stamp and the 5 s window expires on its own. That expiry is what carries the paused case,
     * which is why [pause] does not signal `false` itself — if this predicate ever stops matching
     * "consuming audio", the paused case breaks with it.
     */
    private fun startAudibleHeartbeat() {
        // Sample the routed output alongside the audible stamp: the heartbeat already runs while this
        // player is producing sound, which is exactly when its routing is the answer for route decisions.
        observedRoute?.publish(audioTrack?.routedDevice)
        audibleHeartbeat?.cancel()
        onPlaybackAudibleChanged?.invoke(true)
        audibleHeartbeat = heartbeatScope.launch {
            while (isActive) {
                delay(AUDIBLE_HEARTBEAT_MS)
                if (!isPlaying) break
                onPlaybackAudibleChanged?.invoke(true)
            }
        }
    }

    private fun stopAudibleHeartbeat() {
        audibleHeartbeat?.cancel()
        audibleHeartbeat = null
        onPlaybackAudibleChanged?.invoke(false)
    }

    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Release all resources. Must be called before the player is discarded. */
    fun release() {
        observedRoute?.clear()
        if (released) return
        released = true
        stop()
        decodeCodec?.stop()
        decodeCodec?.release()
        decodeCodec = null
        audioTrack?.release()
        audioTrack = null
        Timber.i("[CloudAudio] released")
    }

    // -- internal --

    private fun ensureAudioTrack() {
        if (audioTrack != null) return

        val minBufSize = AudioTrack.getMinBufferSize(
            currentSampleRate,
            channelConfig,
            audioFormat,
        )
        if (minBufSize <= 0) {
            Timber.w("[CloudAudio] could not determine min buffer size")
            return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(currentSampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBufSize * 4, 32 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
    }

    private fun drainFrames() {
        val track = audioTrack ?: return
        if (!playing || paused) return

        while (true) {
            val frame = synchronized(frameBuffer) {
                if (frameBuffer.isEmpty()) return
                frameBuffer.removeAt(0)
            }

            // Decode if needed.
            val pcmBytes = if (codec.equals("opus", ignoreCase = true)) {
                decodeOpus(frame)
            } else {
                frame
            }

            if (pcmBytes.isNotEmpty()) {
                // Write the whole frame, tracking the accepted prefix: a short write must not drop
                // the remainder, or answer audio is silently lost while the turn is still active.
                var offset = 0
                while (offset < pcmBytes.size) {
                    val writer = writeToSink
                    val written = if (writer != null) {
                        writer(track, pcmBytes, offset, pcmBytes.size - offset)
                    } else {
                        track.write(
                            pcmBytes,
                            offset,
                            pcmBytes.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                    }
                    if (written <= 0) {
                        // An explicit failure rather than a silent drop: stop this answer and report
                        // it, so the turn is not treated as a clean end.
                        Timber.w("[CloudAudio] AudioTrack.write returned $written; stopping answer")
                        audioWriteFailed = true
                        onWriteFailure?.invoke(submittedSamples)
                        break
                    }
                    audioWritten = true
                    // Record only what the sink accepted, so the reference never holds audio the
                    // output rejected, and position it on the submitted-sample timeline.
                    val floats = s16leBytesToFloats(pcmBytes, written, offset)
                    // The decoded PCM is at the codec's rate; the recorder's timeline is 16 kHz.
                    // Recording it unchanged would store rate/16000 times too many samples for the
                    // span of audio they represent, so the reference would claim a duration that
                    // never played and correlation could never align against it. Resample through the
                    // stateful resampler so successive chunks stay phase-continuous: a frame whose
                    // length does not divide into a whole output count would otherwise drop its
                    // remainder every time and drift off the producer clock.
                    resampler.beginChunk(currentSampleRate)
                    val referenceFloats = resampler.resample(floats)
                    // The producer position of this chunk's FIRST sample, from the CUMULATIVE submitted
                    // count of the chunks before it. write() adds this chunk's own duration, so passing
                    // the end here would count the chunk twice; and deriving it per chunk would floor
                    // away each chunk's sub-millisecond remainder, so the clock falls behind over a
                    // long answer.
                    val chunkStartMs = (submittedSamples * 1_000L) / currentSampleRate
                    playbackBufferRecorder?.write(referenceFloats, chunkStartMs)
                    submittedSamples += floats.size
                    offset += written
                }
                if (audioWriteFailed) break
            }
        }
    }

    private fun decodeOpus(input: ByteArray): ByteArray {
        if (decodeCodec == null) {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                currentSampleRate,
                if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1,
            )
            format.setString(
                MediaFormat.KEY_CHANNEL_COUNT,
                if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) "2" else "1",
            )
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, currentSampleRate)

            try {
                decodeCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                decodeCodec?.configure(format, null, null, 0)
                decodeCodec?.start()
                decodeBuffering = true
            } catch (e: Exception) {
                Timber.w(e, "[CloudAudio] failed to create Opus decoder")
                decodeCodec = null
                return ByteArray(0) // reject: raw Opus bytes are not valid audio
            }
        }

        val codec = decodeCodec ?: return ByteArray(0)

        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return input
            inputBuffer.clear()
            inputBuffer.put(input)
            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                input.size,
                0,
                0,
            )
        }

        val info = MediaCodec.BufferInfo()
        val outputBufferIndex = codec.dequeueOutputBuffer(info, 10_000)

        return if (outputBufferIndex >= 0) {
            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
            var out = ByteArray(info.size)
            outputBuffer?.get(out)
            codec.releaseOutputBuffer(outputBufferIndex, false)
            // Drain any remaining output.
            while (true) {
                val next = codec.dequeueOutputBuffer(info, 0)
                if (next < 0) break
                if (next >= 0) {
                    val buf = codec.getOutputBuffer(next)
                    if (buf != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        buf.get(chunk)
                        out += chunk
                    }
                    codec.releaseOutputBuffer(next, false)
                }
            }
            out
        } else {
            ByteArray(0)
        }
    }

    companion object {
        private const val TAG = "CloudAudio"

        /**
         * Resamples [samples] from [sourceRateHz] to the recorder's 16 kHz timeline.
         *
         * Stateless single-shot conversion, kept for callers that need one buffer. Prefer
         * [ChunkResampler] for a stream: with a fractional ratio this discards each chunk's
         * remainder independently, so successive chunks drift off the producer clock.
         */
        internal fun resampleToRecorderRate(
            samples: FloatArray,
            sourceRateHz: Int,
            targetRateHz: Int = PlaybackBufferRecorder.SAMPLE_RATE,
        ): FloatArray {
            val resampler = ChunkResampler(targetRateHz)
            resampler.beginChunk(sourceRateHz)
            return resampler.resample(samples)
        }

        /**
         * Resamples a stream of chunks while preserving phase and audio continuity across them.
         *
         * A per-chunk conversion cannot be correct in general: a chunk whose length does not divide
         * into a whole output count drops its remainder, so a long answer loses samples at every
         * boundary and the reference drifts behind the audio that actually played. This holds back
         * the tail it cannot yet interpolate against and prepends it to the next chunk, so the
         * emitted count over N chunks equals the emitted count for their concatenation.
         */
        internal class ChunkResampler(
            private val targetRateHz: Int = PlaybackBufferRecorder.SAMPLE_RATE,
        ) {
            private var sourceRateHz = 0
            private var step = 1.0

            /** Position in the current virtual chunk at which the next output sample is due. */
            private var cursor = 0.0

            /** The unconsumed tail of the previous chunk, carried into the next one. */
            private var carry = FloatArray(0)

            /** Starts a chunk at [rateHz], carrying position and tail from the previous one. */
            fun beginChunk(rateHz: Int) {
                if (rateHz != sourceRateHz) {
                    sourceRateHz = rateHz
                    step = rateHz.toDouble() / targetRateHz
                    cursor = 0.0
                    carry = FloatArray(0)
                }
            }

            /**
             * Converts one chunk, continuing the previous chunk's phase. The unconsumed tail is kept
             * for the next call, so no audio is dropped at a boundary and none is repeated.
             */
            fun resample(samples: FloatArray): FloatArray {
                if (sourceRateHz == targetRateHz || samples.isEmpty()) {
                    // At the target rate the samples pass through, but a carried tail from a previous
                    // rate must not be stranded.
                    if (carry.isEmpty()) return samples
                    val joined = carry + samples
                    carry = FloatArray(0)
                    return joined
                }
                // Work on the carried tail plus this chunk, so the interpolation that could not be
                // completed last time is completed now rather than dropped.
                val source = if (carry.isEmpty()) samples else carry + samples
                val out = ArrayList<Float>((source.size / step).toInt() + 2)
                var idx = cursor
                while (true) {
                    val lo = idx.toInt()
                    // Need lo and lo+1 present to interpolate between.
                    if (lo + 1 >= source.size) break
                    val frac = (idx - lo).toFloat()
                    out.add(source[lo] + (source[lo + 1] - source[lo]) * frac)
                    idx += step
                }
                // Hold back everything from the last emitted sample's floor onward: the next chunk
                // continues from there, so the boundary sample is interpolated, not lost.
                val consumed = idx.toInt()
                if (consumed >= source.size) {
                    carry = FloatArray(0)
                    cursor = idx - source.size
                } else {
                    carry = source.copyOfRange(consumed, source.size)
                    cursor = idx - consumed
                }
                val result = FloatArray(out.size)
                for (i in out.indices) result[i] = out[i]
                return result
            }
        }

        /**
         * Converts 16-bit little-endian PCM bytes to the float samples the echo reference stores.
         * [lengthBytes] bounds what is read, so a partial AudioTrack write records only what the
         * sink actually accepted rather than the whole decoded buffer.
         */
        internal fun s16leBytesToFloats(bytes: ByteArray, lengthBytes: Int, offset: Int = 0): FloatArray {
            val samples = lengthBytes / 2
            val out = FloatArray(samples)
            for (i in 0 until samples) {
                // Read from the chunk's own offset. Converting the remainder of a partially accepted
                // frame from index 0 re-reads the frame's opening bytes for every chunk, so the
                // reference holds duplicated, misaligned audio of a length that never matches what
                // the sink took.
                val lo = bytes[offset + i * 2].toInt() and 0xFF
                val hi = bytes[offset + i * 2 + 1].toInt()
                val v = (hi shl 8) or lo
                out[i] = v.toShort() / 32768f
            }
            return out
        }

        /**
         * How often to refresh the audible stamp while playing.
         *
         * The gate attributes sound by asking whether this app emitted within the last 5 s
         * (`OtherAppPlayingCondition.transitionWindowMs`), so the refresh has to be comfortably
         * inside that: an answer of 5 s or more would otherwise go stale mid-playback.
         */
        private const val AUDIBLE_HEARTBEAT_MS = 1_000L
    }
}
