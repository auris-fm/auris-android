package au.com.shiftyjelly.pocketcasts.voice.audio

data class PcmAudioFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
)
