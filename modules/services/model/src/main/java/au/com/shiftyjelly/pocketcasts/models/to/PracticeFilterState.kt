package au.com.shiftyjelly.pocketcasts.models.to

enum class PracticeFilterApplyStatus {
    APPLIED,
    PENDING,
    FAILED_UNSUPPORTED,
    FAILED_PROCESSING,
}

enum class NoiseEnvironmentMode {
    COFFEE_SHOP,
    BUSY_STREET,
    MEETING_ROOM,
}

data class PracticeFilters(
    val isBackgroundNoiseEnabled: Boolean = false,
    val noiseMode: NoiseEnvironmentMode = NoiseEnvironmentMode.COFFEE_SHOP,
    val noiseIntensity: Float = 0.55f,
    val noiseEventfulness: Float = 0.5f,
    val noiseSpatialMotion: Float = 0.5f,
    val isVoiceMaskingEnabled: Boolean = false,
    val isLowPassEnabled: Boolean = false,
) {
    val isAnyEnabled: Boolean
        get() = isBackgroundNoiseEnabled || isVoiceMaskingEnabled || isLowPassEnabled
}

data class PracticeFilterState(
    val filters: PracticeFilters = PracticeFilters(),
    val status: PracticeFilterApplyStatus = PracticeFilterApplyStatus.APPLIED,
    val message: String? = null,
)
