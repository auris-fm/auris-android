package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.models.converter.SafeDate
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.NoiseEnvironmentMode
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.models.to.PracticeFilters
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.UserSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class PlaybackStateTest {

    @Test
    fun `buildState restores persisted practice filters when switching episodes`() {
        val persistedPracticeFilters = PracticeFilters(
            isBackgroundNoiseEnabled = true,
            noiseMode = NoiseEnvironmentMode.BUSY_STREET,
            noiseIntensity = 0.82f,
            isVoiceMaskingEnabled = true,
        )
        val globalPlaybackEffectsSetting = mock<UserSetting<PlaybackEffects>> {
            on { value } doReturn PlaybackEffects()
        }
        val globalPracticeFiltersSetting = mock<UserSetting<PracticeFilters>> {
            on { value } doReturn persistedPracticeFilters
        }
        val settings = mock<Settings> {
            on { globalPlaybackEffects } doReturn globalPlaybackEffectsSetting
            on { globalPracticeFilters } doReturn globalPracticeFiltersSetting
        }

        val previousState = PlaybackState(
            episodeUuid = "previous-episode",
            practiceFilters = PracticeFilters(),
            practiceFilterMessage = "old error",
        )

        val state = PlaybackState.buildState(
            state = PlaybackState.State.PAUSED,
            episode = PodcastEpisode(uuid = "new-episode", publishedDate = SafeDate()),
            podcast = null,
            isPrepared = true,
            previousPlaybackState = previousState,
            lastChangeFrom = PlaybackManager.LastChangeFrom.OnInit,
            settings = settings,
        )

        assertEquals(persistedPracticeFilters, state.practiceFilters)
        assertEquals(
            au.com.shiftyjelly.pocketcasts.models.to.PracticeFilterApplyStatus.APPLIED,
            state.practiceFilterApplyStatus,
        )
        assertNull(state.practiceFilterMessage)
    }

    @Test
    fun `buildState uses podcast practice filters when podcast overrides global effects`() {
        val globalPracticeFilters = PracticeFilters(
            isBackgroundNoiseEnabled = true,
            noiseMode = NoiseEnvironmentMode.COFFEE_SHOP,
            noiseIntensity = 0.25f,
        )
        val podcast = Podcast(uuid = "podcast-1").apply {
            overrideGlobalEffects = true
            isPracticeBackgroundNoiseEnabled = true
            practiceNoiseMode = NoiseEnvironmentMode.MEETING_ROOM
            practiceNoiseIntensity = 0.91f
            isPracticeVoiceMaskingEnabled = true
            isPracticeLowPassEnabled = true
        }
        val settings = mockSettings(persistedPracticeFilters = globalPracticeFilters)

        val state = PlaybackState.buildState(
            state = PlaybackState.State.PAUSED,
            episode = PodcastEpisode(uuid = "episode-1", publishedDate = SafeDate()),
            podcast = podcast,
            isPrepared = true,
            previousPlaybackState = PlaybackState(episodeUuid = "different-episode"),
            lastChangeFrom = PlaybackManager.LastChangeFrom.OnInit,
            settings = settings,
        )

        assertEquals(podcast.practiceFilters, state.practiceFilters)
    }

    @Test
    fun `buildState uses global practice filters when podcast does not override global effects`() {
        val globalPracticeFilters = PracticeFilters(
            isBackgroundNoiseEnabled = true,
            noiseMode = NoiseEnvironmentMode.BUSY_STREET,
            noiseIntensity = 0.74f,
            isLowPassEnabled = true,
        )
        val podcast = Podcast(uuid = "podcast-1").apply {
            overrideGlobalEffects = false
            isPracticeBackgroundNoiseEnabled = true
            practiceNoiseMode = NoiseEnvironmentMode.MEETING_ROOM
            practiceNoiseIntensity = 0.12f
            isPracticeVoiceMaskingEnabled = true
        }
        val settings = mockSettings(persistedPracticeFilters = globalPracticeFilters)

        val state = PlaybackState.buildState(
            state = PlaybackState.State.PAUSED,
            episode = PodcastEpisode(uuid = "episode-1", publishedDate = SafeDate()),
            podcast = podcast,
            isPrepared = true,
            previousPlaybackState = PlaybackState(episodeUuid = "different-episode"),
            lastChangeFrom = PlaybackManager.LastChangeFrom.OnInit,
            settings = settings,
        )

        assertEquals(globalPracticeFilters, state.practiceFilters)
    }

    private fun mockSettings(persistedPracticeFilters: PracticeFilters): Settings {
        val globalPlaybackEffectsSetting = mock<UserSetting<PlaybackEffects>> {
            on { value } doReturn PlaybackEffects()
        }
        val globalPracticeFiltersSetting = mock<UserSetting<PracticeFilters>> {
            on { value } doReturn persistedPracticeFilters
        }
        return mock {
            on { globalPlaybackEffects } doReturn globalPlaybackEffectsSetting
            on { globalPracticeFilters } doReturn globalPracticeFiltersSetting
        }
    }
}
