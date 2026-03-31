package au.com.shiftyjelly.pocketcasts.player.view

import au.com.shiftyjelly.pocketcasts.models.to.NoiseEnvironmentMode
import au.com.shiftyjelly.pocketcasts.models.to.PracticeFilters
import kotlin.math.roundToInt

internal object PracticeNoiseUiMapper {
    private val defaultNoiseSettings = PracticeFilters()

    fun filtersFromUi(
        noiseMode: NoiseEnvironmentMode,
        noiseVolumeProgress: Int,
        isVoiceMaskingEnabled: Boolean,
        isLowPassEnabled: Boolean,
    ): PracticeFilters {
        val noiseIntensity = progressToUnit(noiseVolumeProgress)
        return PracticeFilters(
            isBackgroundNoiseEnabled = noiseIntensity > 0f,
            noiseMode = noiseMode,
            noiseIntensity = noiseIntensity,
            noiseEventfulness = defaultNoiseSettings.noiseEventfulness,
            noiseSpatialMotion = defaultNoiseSettings.noiseSpatialMotion,
            isVoiceMaskingEnabled = isVoiceMaskingEnabled,
            isLowPassEnabled = isLowPassEnabled,
        )
    }

    fun noiseVolumeProgress(filters: PracticeFilters): Int {
        return if (filters.isBackgroundNoiseEnabled) {
            noiseVolumeProgress(filters.noiseIntensity)
        } else {
            0
        }
    }

    fun noiseVolumeProgress(noiseIntensity: Float): Int = unitToProgress(noiseIntensity)

    private fun unitToProgress(value: Float): Int = (value.coerceIn(0f, 1f) * 100f).roundToInt()

    private fun progressToUnit(value: Int): Float = value.coerceIn(0, 100) / 100f
}
