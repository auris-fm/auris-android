package au.com.shiftyjelly.pocketcasts.player.view

import au.com.shiftyjelly.pocketcasts.models.to.NoiseEnvironmentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeNoiseUiMapperTest {

    @Test
    fun `zero noise volume disables background noise`() {
        val filters = PracticeNoiseUiMapper.filtersFromUi(
            noiseMode = NoiseEnvironmentMode.MEETING_ROOM,
            noiseVolumeProgress = 0,
            isVoiceMaskingEnabled = true,
            isLowPassEnabled = false,
        )

        assertFalse(filters.isBackgroundNoiseEnabled)
        assertEquals(0f, filters.noiseIntensity)
        assertEquals(NoiseEnvironmentMode.MEETING_ROOM, filters.noiseMode)
        assertTrue(filters.isVoiceMaskingEnabled)
        assertFalse(filters.isLowPassEnabled)
    }

    @Test
    fun `non zero noise volume enables background noise`() {
        val filters = PracticeNoiseUiMapper.filtersFromUi(
            noiseMode = NoiseEnvironmentMode.BUSY_STREET,
            noiseVolumeProgress = 37,
            isVoiceMaskingEnabled = false,
            isLowPassEnabled = true,
        )

        assertTrue(filters.isBackgroundNoiseEnabled)
        assertEquals(0.37f, filters.noiseIntensity)
        assertEquals(NoiseEnvironmentMode.BUSY_STREET, filters.noiseMode)
        assertFalse(filters.isVoiceMaskingEnabled)
        assertTrue(filters.isLowPassEnabled)
    }

    @Test
    fun `noise volume progress reflects intensity`() {
        assertEquals(0, PracticeNoiseUiMapper.noiseVolumeProgress(0f))
        assertEquals(55, PracticeNoiseUiMapper.noiseVolumeProgress(0.55f))
        assertEquals(100, PracticeNoiseUiMapper.noiseVolumeProgress(1.5f))
    }
}
