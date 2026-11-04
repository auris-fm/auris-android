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
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because the provider produces `org.json.JSONObject`, which is a stub under plain JUnit
 * (`Method put ... not mocked`). Same runner as the sibling playback tests.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackContextProviderTest {

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

    /**
     * The REAL provider's observation, which is what every app-operation result rests on.
     *
     * The fixture in `CloudRouteSinkTest` models the contract; this asserts that production actually
     * produces it. Without these, `currentObservedState()` could revert to the interface's `null`
     * default and every sink control would still pass against a fake that never consults it.
     */
    private fun providerFor(state: PlaybackState): PlaybackManagerPlaybackContextProvider {
        val playbackManager = mock<PlaybackManager>()
        whenever(playbackManager.playbackStateRelay).thenReturn(BehaviorRelay.createDefault(state).toSerialized())
        return PlaybackManagerPlaybackContextProvider(
            playbackManager,
            mock<FingerprintTimingManager>(),
            CloudPlaybackContextState(),
        )
    }

    @Test
    fun `a playing engine is reported as playing true under the contract's key`() {
        val observed = providerFor(
            PlaybackState(
                state = PlaybackState.State.PLAYING,
                episodeUuid = "episode-id",
                positionMs = 1_500,
            ),
        ).currentObservedState()

        assertEquals(true, observed!!.getBoolean("playing"))
        assertEquals("episode-id", observed.getString("episode_id"))
        assertEquals(1_500, observed.getInt("position_ms"))
        assertEquals(
            "the wire carries `playing`, not the engine's own name",
            false,
            observed.has("is_playing"),
        )
    }

    @Test
    fun `a paused engine is reported as playing false, not as unavailable`() {
        val observed = providerFor(
            PlaybackState(
                state = PlaybackState.State.PAUSED,
                episodeUuid = "episode-id",
                positionMs = 1_500,
            ),
        ).currentObservedState()

        assertEquals("a paused engine is an observation, not a missing one", false, observed!!.getBoolean("playing"))
    }

    @Test
    fun `an empty engine is unavailable rather than reported as paused`() {
        // EMPTY means the engine holds no episode at all. Reporting `playing = false` would claim a
        // measurement about a player that is not there.
        val observed = providerFor(PlaybackState(state = PlaybackState.State.EMPTY)).currentObservedState()

        assertEquals(null, observed)
    }
}
