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
    fun `the reference version is carried only for the episode whose mapping is active`() {
        // core 351dd308 follow-up: the snapshot retains the SHA-256 of the active map's reference
        // data. Provenance is EPISODE-BOUND like the mapped position: for another episode (or no
        // episode) it is null, and null stays null -- it is never fetched or manufactured.
        val playbackManager = mock<PlaybackManager>()
        val fingerprintTimingManager = mock<FingerprintTimingManager>()
        val provider = PlaybackManagerPlaybackContextProvider(
            playbackManager,
            fingerprintTimingManager,
            CloudPlaybackContextState(),
        )
        val episode = PodcastEpisode(
            uuid = "episode-id",
            publishedDate = Date(),
            podcastUuid = "podcast-id",
        )
        val playbackState = PlaybackState(positionMs = 10_000, episodeUuid = "episode-id")
        whenever(playbackManager.getCurrentEpisode()).thenReturn(episode)
        whenever(playbackManager.playbackStateRelay).thenReturn(BehaviorRelay.createDefault(playbackState).toSerialized())
        val version = "a".repeat(64)
        whenever(
            fingerprintTimingManager.activeReference(10_000),
        ).thenReturn(FingerprintTimingManager.ActiveReference("episode-id", version, 9_000L))
        assertEquals(version, provider.current().referenceAssetVersion)

        // The mapping moved to ANOTHER episode: no provenance for this one.
        whenever(
            fingerprintTimingManager.activeReference(10_000),
        ).thenReturn(FingerprintTimingManager.ActiveReference("episode-other", version, 9_000L))
        assertEquals(null, provider.current().referenceAssetVersion)
    }

    @Test
    fun `a turn with unknown provenance still carries everything else`() {
        // Missing provenance must not degrade the snapshot: null version, full context.
        val playbackManager = mock<PlaybackManager>()
        val fingerprintTimingManager = mock<FingerprintTimingManager>()
        val provider = PlaybackManagerPlaybackContextProvider(
            playbackManager,
            fingerprintTimingManager,
            CloudPlaybackContextState(),
        )
        val episode = PodcastEpisode(
            uuid = "episode-id",
            publishedDate = Date(),
            podcastUuid = "podcast-id",
        )
        val playbackState = PlaybackState(positionMs = 42_500, episodeUuid = "episode-id")
        whenever(playbackManager.getCurrentEpisode()).thenReturn(episode)
        whenever(playbackManager.playbackStateRelay).thenReturn(BehaviorRelay.createDefault(playbackState).toSerialized())
        whenever(
            fingerprintTimingManager.activeReference(42_500),
        ).thenReturn(FingerprintTimingManager.ActiveReference("episode-id", null, 41_000L))

        val context = provider.current()

        assertEquals(null, context.referenceAssetVersion)
        assertEquals(41_000L, context.referencePositionMs)
        assertEquals(42_500L, context.clientPositionMs)
    }

    @Test
    fun `unmatched playback window keeps client position but clears reference position`() {
        val playbackManager = mock<PlaybackManager>()
        val fingerprintTimingManager = mock<FingerprintTimingManager>()
        val provider = PlaybackManagerPlaybackContextProvider(
            playbackManager,
            fingerprintTimingManager,
            CloudPlaybackContextState(),
        )
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
        // The ad-region contrast (reviewer finding 2): a KNOWN digest for an instant the map did
        // not measure is NOT sent -- no mapped position means no provenance.
        whenever(
            fingerprintTimingManager.activeReference(42_500),
        ).thenReturn(FingerprintTimingManager.ActiveReference("episode-id", "f".repeat(64), null))

        val context = provider.current()

        assertEquals(
            PlaybackContext(
                episodeId = "episode-id",
                podcastId = "podcast-id",
                referencePositionMs = null,
                clientPositionMs = 42_500L,
                referenceAssetVersion = null,
            ),
            context,
        )
        verify(fingerprintTimingManager).activeReference(42_500)
    }
}
