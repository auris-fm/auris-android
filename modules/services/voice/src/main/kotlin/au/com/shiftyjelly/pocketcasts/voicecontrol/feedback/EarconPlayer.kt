package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Plays the bundled earcons through an [AudioTrack] this app owns, so the samples sent to the output
 * are known and can be recorded into the shared echo reference.
 *
 * Audible fidelity is preserved: the clip is played at its own rate. Only the *copy* that enters the
 * reference is normalized to the reference's 16 kHz, so the output sink and the reference are
 * allowed different rates and the cue is not band-limited just to simplify the correlator.
 */
class EarconPlayer(
    context: Context,
    private val playbackBufferRecorder: PlaybackBufferRecorder? = null,
) {
    private val appContext = context.applicationContext
    private val clips: Map<EarconId, EarconClip>
    private var track: AudioTrack? = null
    private var released = false

    init {
        clips = EarconId.entries.associateWithNotNull { id ->
            val resId = appContext.resources.getIdentifier(
                "earcon_${id.name.lowercase()}",
                "raw",
                appContext.packageName,
            )
            if (resId == 0) {
                null
            } else {
                runCatching {
                    appContext.resources.openRawResource(resId).use { decodeWavToMono(it) }
                }.onFailure { Timber.w(it, "[Earcon] failed to decode %s", id) }.getOrNull()
            }
        }
    }

    /** Plays [id] and reports whether it actually started. */
    fun play(id: EarconId): Boolean {
        if (released) return false
        val clip = clips[id] ?: return false
        if (clip.samples.isEmpty()) return false
        return try {
            val audioTrack = ensureTrack(clip.sampleRateHz)
            // The output plays the clip at its own rate; the reference gets a normalized copy, so
            // audible bandwidth is not traded away for the correlation's convenience.
            playbackBufferRecorder?.write(
                clip.referenceSamples16kFloats(),
            )
            var offset = 0
            while (offset < clip.samples.size) {
                val written = audioTrack.write(clip.samples, offset, clip.samples.size - offset)
                if (written <= 0) {
                    Timber.w("[Earcon] AudioTrack.write returned $written")
                    return false
                }
                offset += written
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "[Earcon] play failed")
            false
        }
    }

    fun release() {
        released = true
        track?.runCatching {
            stop()
            release()
        }
        track = null
    }

    private fun ensureTrack(sampleRateHz: Int): AudioTrack {
        val current = track
        if (current != null) {
            if (current.sampleRate == sampleRateHz) return current
            // The bundled clips share a rate; if one ever differs, recreate rather than play it at
            // the wrong rate, which would change its pitch and duration.
            current.runCatching {
                stop()
                release()
            }
            track = null
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val created = AudioTrack.Builder()
            .setAudioAttributes(earconAudioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()
        created.play()
        track = created
        return created
    }

    /**
     * Decodes a PCM WAV resource to mono 16 kHz 16-bit samples.
     *
     * Handles the format the bundles actually use (16-bit PCM, possibly stereo); anything else is
     * rejected rather than silently mis-read, since a wrong interpretation would put wrong audio in
     * the reference.
     */
    private fun decodeWavToMono(input: InputStream): EarconClip {
        val clip = EarconAudio.decodeWavToMono(input)
        return EarconClip(clip.samples, clip.sampleRateHz)
    }

    /**
     * A decoded earcon: the samples to PLAY at their own [sampleRateHz], plus the normalized copy
     * that goes into the reference. The two are deliberately separate — playback keeps its full
     * bandwidth, and only the correlation works at the reference's rate.
     */
    private inner class EarconClip(
        val samples: ShortArray,
        val sampleRateHz: Int,
    ) {
        private val reference16k: ShortArray by lazy {
            if (sampleRateHz == REFERENCE_RATE_HZ) {
                samples
            } else {
                EarconAudio.resampleMono(samples, sampleRateHz, REFERENCE_RATE_HZ)
            }
        }

        /** The clip as float samples on the reference's timeline, for the echo reference. */
        fun referenceSamples16kFloats(): FloatArray = FloatArray(reference16k.size) { reference16k[it] / 32768f }
    }

    private inline fun <K, V : Any> Iterable<K>.associateWithNotNull(valueSelector: (K) -> V?): Map<K, V> {
        val result = LinkedHashMap<K, V>()
        for (key in this) {
            valueSelector(key)?.let { result[key] = it }
        }
        return result
    }

    internal companion object {
        /** The rate the shared echo reference and the correlator work at. */
        const val REFERENCE_RATE_HZ = 16_000

        /**
         * The stream earcons ride.
         *
         * Deliberately **media** rather than sonification. Sonification maps to the system stream, which
         * this family of devices aliases to the ring stream, so Do Not Disturb silenced every earcon
         * while ordinary playback was perfectly audible — a user speaking to an app they deliberately
         * opened heard no confirmation. Media follows the audio the user is already listening to, which
         * is also where the spoken responses go through platform TTS, so the two feedback channels agree.
         *
         * Reported from a device where `STREAM_SYSTEM` was `Muted: true / streamVolume: 0` under
         * `zen_mode: 1` while every earcon play attempt was logged `usage:13 muted`. If this is ever
         * changed back, the reason it must not be is that sentence.
         */
        internal fun earconAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
