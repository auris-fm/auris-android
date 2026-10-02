package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.math.max

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
) {
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var audioTrack: AudioTrack? = null
    private val frameBuffer = mutableListOf<ByteArray>()
    private var playing = false
    private var paused = false
    private var released = false
    private var decodeCodec: MediaCodec? = null
    private var decodeBuffering = false

    /** Negotiated codec name; empty means PCM. */
    var codec: String = ""
        internal set

    /** True while audio is actively being played (not paused, not idle). */
    val isPlaying: Boolean get() = playing && !paused

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
            } else if (!playing) {
                audioTrack?.play()
                playing = true
                drainFrames()
                Timber.i("[CloudAudio] started")
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
            }
        }
    }

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
            sampleRateHz,
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
                    .setSampleRate(sampleRateHz)
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
            }
        }
    }

    private fun decodeOpus(input: ByteArray): ByteArray {
        if (decodeCodec == null) {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                sampleRateHz,
                if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1,
            )
            format.setString(MediaFormat.KEY_CHANNEL_COUNT,
                if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) "2" else "1")
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRateHz)

            try {
                decodeCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                decodeCodec?.configure(format, null, null, 0)
                decodeCodec?.start()
                decodeBuffering = true
            } catch (e: Exception) {
                Timber.w(e, "[CloudAudio] failed to create Opus decoder")
                decodeCodec = null
                return input // fall back to raw
            }
        }

        val codec = decodeCodec ?: return input

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
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
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
    }
}
