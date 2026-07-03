package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import com.jakewharton.rxrelay2.BehaviorRelay
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlaybackContextProviderTest {

    @Test
    fun `unmatched playback window keeps client position but clears reference position`() {
        val playbackManager = mock<PlaybackManager>()
        val fingerprintTimingManager = mock<FingerprintTimingManager>()
        val provider = PlaybackManagerPlaybackContextProvider(playbackManager, fingerprintTimingManager)
        val episode = PodcastEpisode(
            uuid = "episode-id",
            publishedDate = Date(),
            podcastUuid = "podcast-id",
        )
        val playbackState = PlaybackState(
            positionMs = 42_500,
            episodeUuid = "episode-id",
        )
        whenever(playbackManager.getCurrentEpisode()).thenReturn(episode)
        whenever(playbackManager.playbackStateRelay).thenReturn(BehaviorRelay.createDefault(playbackState).toSerialized())
        whenever(fingerprintTimingManager.activeEpisodeUuid).thenReturn("episode-id")
        whenever(fingerprintTimingManager.referenceTime(42_500)).thenReturn(41.0)
        whenever(fingerprintTimingManager.matchedReferenceTime(42_500)).thenReturn(null)

        val context = provider.current()

        assertEquals(
            PlaybackContext(
                episodeId = "episode-id",
                podcastId = "podcast-id",
                referencePositionMs = null,
                clientPositionMs = 42_500L,
            ),
            context,
        )
        verify(fingerprintTimingManager).matchedReferenceTime(42_500)
    }
}
