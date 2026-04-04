package au.com.shiftyjelly.pocketcasts.player.viewmodel

import android.content.SharedPreferences
import android.content.Context
import android.content.res.Resources
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import au.com.shiftyjelly.pocketcasts.analytics.EpisodeAnalytics
import au.com.shiftyjelly.pocketcasts.models.converter.SafeDate
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.NoiseEnvironmentMode
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.models.to.PracticeFilters
import au.com.shiftyjelly.pocketcasts.player.viewmodel.PlayerViewModel.PlaybackEffectsSettingsTab
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.UserSetting
import au.com.shiftyjelly.pocketcasts.preferences.model.ArtworkConfiguration
import au.com.shiftyjelly.pocketcasts.repositories.ads.BlazeAdsManager
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadQueue
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimer
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimerState
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import com.automattic.eventhorizon.EventHorizon
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.Schedulers
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelPracticeFiltersTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    @Before
    fun setUp() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxAndroidPlugins.reset()
    }

    @Test
    fun `setPracticeFilters writes global settings when podcast uses all podcasts tab`() = runTest {
        val globalPracticeFiltersSetting = FakeUserSetting(PracticeFilters())
        val playbackManager = mockPlaybackManager()
        val podcastManager = mock<PodcastManager>()
        val viewModel = initViewModel(
            playbackManager = playbackManager,
            podcastManager = podcastManager,
            globalPracticeFiltersSetting = globalPracticeFiltersSetting,
        )
        val filters = PracticeFilters(
            isBackgroundNoiseEnabled = true,
            noiseMode = NoiseEnvironmentMode.BUSY_STREET,
            noiseIntensity = 0.8f,
        )
        val podcast = Podcast(uuid = "podcast-1").apply { overrideGlobalEffects = false }

        viewModel.setPracticeFilters(filters, podcast)
        advanceUntilIdle()

        assertTrue(globalPracticeFiltersSetting.value == filters)
        verify(playbackManager).updatePracticeFilters(filters)
        verifyNoInteractions(podcastManager)
    }

    @Test
    fun `setPracticeFilters writes podcast settings when podcast uses this podcast tab`() = runTest {
        val globalPracticeFiltersSetting = FakeUserSetting(PracticeFilters())
        val playbackManager = mockPlaybackManager()
        val podcastManager = mock<PodcastManager>()
        val viewModel = initViewModel(
            playbackManager = playbackManager,
            podcastManager = podcastManager,
            globalPracticeFiltersSetting = globalPracticeFiltersSetting,
        )
        val filters = PracticeFilters(
            isBackgroundNoiseEnabled = true,
            noiseMode = NoiseEnvironmentMode.MEETING_ROOM,
            noiseIntensity = 0.63f,
            isVoiceMaskingEnabled = true,
        )
        val podcast = Podcast(uuid = "podcast-1").apply { overrideGlobalEffects = true }

        viewModel.setPracticeFilters(filters, podcast)
        advanceUntilIdle()

        verify(podcastManager).updatePracticeFiltersBlocking(podcast, filters)
        verify(playbackManager).updatePracticeFilters(filters)
        assertTrue(globalPracticeFiltersSetting.value != filters)
    }

    @Test
    fun `switching to this podcast tab applies podcast scoped practice filters`() = runTest {
        val globalPracticeFilters = PracticeFilters(
            isBackgroundNoiseEnabled = true,
            noiseMode = NoiseEnvironmentMode.COFFEE_SHOP,
            noiseIntensity = 0.2f,
        )
        val podcastPracticeFilters = PracticeFilters(
            isBackgroundNoiseEnabled = true,
            noiseMode = NoiseEnvironmentMode.MEETING_ROOM,
            noiseIntensity = 0.9f,
            isLowPassEnabled = true,
        )
        val globalPracticeFiltersSetting = FakeUserSetting(globalPracticeFilters)
        val playbackManager = mockPlaybackManager(
            currentEpisode = PodcastEpisode(
                uuid = "episode-1",
                podcastUuid = "podcast-1",
                publishedDate = SafeDate(),
            ),
        )
        val podcastManager = mock<PodcastManager>()
        val viewModel = initViewModel(
            playbackManager = playbackManager,
            podcastManager = podcastManager,
            globalPracticeFiltersSetting = globalPracticeFiltersSetting,
        )
        val podcast = Podcast(uuid = "podcast-1").apply {
            overrideGlobalEffects = false
            playbackSpeed = 1.4
            isPracticeBackgroundNoiseEnabled = true
            practiceNoiseMode = NoiseEnvironmentMode.MEETING_ROOM
            practiceNoiseIntensity = 0.9f
            isPracticeLowPassEnabled = true
        }

        viewModel.onEffectsSettingsSegmentedTabSelected(podcast, PlaybackEffectsSettingsTab.ThisPodcast)
        advanceUntilIdle()

        verify(playbackManager).updatePracticeFilters(podcastPracticeFilters)
        assertTrue(podcast.overrideGlobalEffects)
    }

    private fun initViewModel(
        playbackManager: PlaybackManager = mockPlaybackManager(),
        podcastManager: PodcastManager = mock(),
        globalPracticeFiltersSetting: UserSetting<PracticeFilters> = FakeUserSetting(PracticeFilters()),
    ): PlayerViewModel {
        val settings = mockSettings(globalPracticeFiltersSetting)
        val sleepTimer = mock<SleepTimer> {
            on { state } doReturn SleepTimerState()
            on { stateFlow } doReturn MutableStateFlow(SleepTimerState())
        }
        val mockResources = mock<Resources> {
            on { getString(any()) } doReturn ""
            on { getString(any(), any()) } doReturn ""
            on { getString(any(), any(), any()) } doReturn ""
        }
        val context = mock<Context> {
            on { resources } doReturn mockResources
        }
        return PlayerViewModel(
            playbackManager = playbackManager,
            episodeManager = mock {
                on { findEpisodeByUuidRxFlowable(any()) } doReturn io.reactivex.Flowable.never()
            },
            podcastManager = podcastManager,
            bookmarkManager = mock<BookmarkManager>(),
            downloadQueue = mock<DownloadQueue>(),
            sleepTimer = sleepTimer,
            settings = settings,
            theme = mock<Theme>(),
            analyticsTracker = AnalyticsTracker.test(),
            eventHorizon = mock<EventHorizon>(),
            episodeAnalytics = mock<EpisodeAnalytics>(),
            blazeAdsManager = mock<BlazeAdsManager> {
                on { findPlayerAd() } doReturn flowOf(null)
            },
            context = context,
            ioDispatcher = coroutineRule.testDispatcher,
        )
    }

    private fun mockPlaybackManager(currentEpisode: PodcastEpisode? = null): PlaybackManager {
        val playbackStateRelay = BehaviorRelay.createDefault(PlaybackState())
        val upNextQueue = mock<UpNextQueue> {
            on { getChangesObservableWithLiveCurrentEpisode(any(), any()) } doReturn Observable.just(UpNextQueue.State.Empty)
            on { this.currentEpisode } doReturn currentEpisode
            on { queueEpisodes } doReturn emptyList()
        }
        return mock {
            on { this.playbackStateRelay } doReturn playbackStateRelay
            on { this.upNextQueue } doReturn upNextQueue
            on { playerFlow } doReturn MutableStateFlow(null)
            on { isSleepAfterEpisodeEnabled() } doReturn false
            on { isSleepAfterChapterEnabled() } doReturn false
            on { getCurrentEpisode() } doReturn currentEpisode
            on { isPlaybackRemote() } doReturn false
            on { isPlaying() } doReturn false
            on { shouldWarnAboutPlayback(anyOrNull()) } doReturn false
        }
    }

    private fun mockSettings(globalPracticeFiltersSetting: UserSetting<PracticeFilters>): Settings {
        val globalPlaybackEffectsSetting = FakeUserSetting(PlaybackEffects())
        val skipBackInSecsSetting = FakeUserSetting(30)
        val skipForwardInSecsSetting = FakeUserSetting(45)
        val useRealTimeRemainingSetting = FakeUserSetting(false)
        val artworkConfigurationSetting = FakeUserSetting(ArtworkConfiguration(useEpisodeArtwork = false))
        return mock {
            on { getBooleanForKey(any(), any()) } doAnswer { it.arguments[1] as Boolean }
            on { globalPlaybackEffects } doReturn globalPlaybackEffectsSetting
            on { globalPracticeFilters } doReturn globalPracticeFiltersSetting
            on { skipBackInSecs } doReturn skipBackInSecsSetting
            on { skipForwardInSecs } doReturn skipForwardInSecsSetting
            on { useRealTimeForPlaybackRemaingTime } doReturn useRealTimeRemainingSetting
            on { artworkConfiguration } doReturn artworkConfigurationSetting
            on { getSleepTimerCustomMins() } doReturn 5
            on { getSleepEndOfEpisodes() } doReturn 1
            on { getSleepEndOfChapters() } doReturn 1
        }
    }

    private class FakeUserSetting<T>(
        initialValue: T,
    ) : UserSetting<T>(
        sharedPrefKey = "test",
        sharedPrefs = mockSharedPreferences(),
    ) {
        private var storedValue = initialValue

        override fun get(): T = storedValue

        override fun persist(value: T, commit: Boolean) {
            storedValue = value
        }

        companion object {
            private fun mockSharedPreferences(): SharedPreferences {
                val editor = mock<SharedPreferences.Editor>()
                org.mockito.kotlin.whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
                return mock {
                    on { edit() } doReturn editor
                }
            }
        }
    }
}
