package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import android.media.AudioAttributes
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
) {
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

    /** True when at least one frame was successfully written to the AudioTrack. */
    var audioWritten = false
        private set

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
                val written = track.write(
                    pcmBytes,
                    0,
                    pcmBytes.size,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written < 0) {
                    Timber.w("[CloudAudio] AudioTrack.write returned $written")
                    break
                }
                audioWritten = true
                // Record what was just sent to the output, so the shared echo reference holds this
                // renderer's contribution. Only the accepted prefix is recorded, matching the sink.
                if (written > 0) {
                    val floats = s16leBytesToFloats(pcmBytes, written)
                    // Position the answer on its own submitted-sample timeline so the reference
                    // carries a timestamp rather than relying on arrival order.
                    val positionMs = (submittedSamples * 1_000L) / sampleRateHz
                    playbackBufferRecorder?.write(floats, positionMs)
                    submittedSamples += floats.size
                }
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
         * Converts 16-bit little-endian PCM bytes to the float samples the echo reference stores.
         * [lengthBytes] bounds what is read, so a partial AudioTrack write records only what the
         * sink actually accepted rather than the whole decoded buffer.
         */
        internal fun s16leBytesToFloats(bytes: ByteArray, lengthBytes: Int): FloatArray {
            val samples = lengthBytes / 2
            val out = FloatArray(samples)
            for (i in 0 until samples) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
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
