package au.com.shiftyjelly.pocketcasts.voicecontrol.model

/**
 * Manifest `router_input_format`. Missing field → [EnglishV1].
 * Only [EnglishV1] is ready for inference until byte-matched fixtures land for others.
 */
sealed class RouterInputFormat {
    abstract val wireName: String

    data object EnglishV1 : RouterInputFormat() {
        override val wireName: String = "english_v1"
    }

    data object SourceV1 : RouterInputFormat() {
        override val wireName: String = "source_v1"
    }

    data object DualV1 : RouterInputFormat() {
        override val wireName: String = "dual_v1"
    }

    data class Unknown(val raw: String) : RouterInputFormat() {
        override val wireName: String = raw
    }

    val isReadyForInference: Boolean
        get() = this is EnglishV1

    companion object {
        fun parse(raw: String?): RouterInputFormat = when (raw) {
            null, EnglishV1.wireName -> EnglishV1
            SourceV1.wireName -> SourceV1
            DualV1.wireName -> DualV1
            else -> Unknown(raw)
        }
    }
}
