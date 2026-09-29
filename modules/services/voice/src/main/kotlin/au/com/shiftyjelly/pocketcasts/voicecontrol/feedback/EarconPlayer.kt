package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class EarconPlayer(context: Context) {
    private val soundPool: SoundPool
    private val idToSoundId: Map<EarconId, Int>
    private var released = false

    init {
        val attrs = earconAudioAttributes()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()

        idToSoundId = EarconId.entries.associateWith { id ->
            val resId = context.resources.getIdentifier(
                "earcon_${id.name.lowercase()}",
                "raw",
                context.packageName,
            )
            if (resId != 0) soundPool.load(context, resId, 1) else 0
        }
    }

    fun play(id: EarconId): Boolean {
        if (released) return false
        val soundId = idToSoundId[id] ?: return false
        if (soundId == 0) return false
        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
        return true
    }

    fun release() {
        released = true
        soundPool.release()
    }
    internal companion object {
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
