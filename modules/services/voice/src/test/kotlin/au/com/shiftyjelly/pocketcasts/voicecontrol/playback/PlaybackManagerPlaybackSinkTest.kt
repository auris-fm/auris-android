package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/**
 * The REAL sink's resolution of the schema's signed absolute position. This is the only place the
 * negative-from-the-end rule can be exercised: a fake sink that resolves the offset itself tests its
 * own arithmetic, and cannot fail when production stops resolving.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackManagerPlaybackSinkTest {

    private fun sink(playbackManager: PlaybackManager): PlaybackManagerPlaybackSink = PlaybackManagerPlaybackSink(
        playbackManager,
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `a seek bound to another episode is refused AT THE SINK, not by its caller`() = runTest {
        // THE BOUNDARY IS THE PRODUCTION SINK, so the case must drive IT rather than a fake. This is the
        // same principle as the signed-position case above: a caller-side check can be overtaken by a
        // selection change between the check and the mutation, and the caller's mutex cannot exclude a
        // change made anywhere else in the app. Only the owner of what is playing can decide "which
        // episode is selected" and "seek it" as ONE step.
        //
        // A fake sink that models the refusal cannot fail when the PRODUCTION check is removed -- it
        // would only be testing its own copy -- which is exactly why this case lives here.
        val manager = mock<PlaybackManager>()
        // The OWNER's operation decides, so the mock must answer it: this case is about the adapter
        // consulting the owner, not about the owner's own behaviour.
        org.mockito.kotlin.whenever(
            manager.seekIfPlayingToTimeMs(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull()),
        ).thenReturn(false)
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-B",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        assertEquals(
            "a seek bound to another episode is REFUSED, and the refusal is reported as one",
            EpisodeOperationResult.Refused,
            result,
        )
        verify(manager, org.mockito.kotlin.never()).seekToTimeMsSuspend(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(),
        )
    }

    @Test
    fun `the episode is re-decided AT the mutation, so a change across the suspension is refused`() = runTest {
        // @reviewer's finding: `seekToTimeMsInternal` reads the episode, then SUSPENDS in
        // `withContext(Dispatchers.Main)` before the player mutation -- and a replacement can land in
        // that suspension. A read taken before the suspensions therefore does not exclude a change
        // ACROSS them.
        //
        // The owner's episode-scoped seek re-reads the selection immediately before `player.seekToTimeMs`,
        // where nothing can await in between. This drives that: the manager reports episode A on the
        // FIRST consultation and B on the SECOND, which is exactly a replacement landing during the
        // step, and the seek must then be refused.
        var consultations = 0
        val manager = mock<PlaybackManager>()
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenAnswer {
            consultations++
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = if (consultations == 1) "episode-A" else "episode-B",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            )
        }
        org.mockito.kotlin.whenever(
            manager.seekIfPlayingToTimeMs(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull()),
        ).thenAnswer {
            // Mirrors the owner: it decides from the CURRENT episode at the moment of the mutation.
            manager.getCurrentEpisode()?.uuid == "episode-A"
        }

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        assertTrue(
            "a selection change across the owner's suspension must be REFUSED, not performed " +
                "(result=$result)",
            result is EpisodeOperationResult.Refused,
        )
    }

    @Test
    fun `the owner's answer is AWAITED, not read from a flag written inside a launch`() = runTest {
        // @reviewer's source finding: `seekIfPlayingToTimeMs` used to SET A FLAG INSIDE A LAUNCHED
        // COROUTINE and return it immediately -- so when dispatch queued the coroutine, the caller read
        // the flag's INITIAL value and reported a REFUSAL for a seek that then performed. The outcome is
        // the owner's decision, so the caller must WAIT for it.
        //
        // The owner here answers on a delay, which is exactly the shape that used to be misreported: a
        // launch-and-return would answer false before this completed.
        val manager = mock<PlaybackManager>()
        org.mockito.kotlin.whenever(manager.seekIfPlayingToTimeMs(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull()))
            .thenAnswer {
                // A NON-SUSPENDING delay: the point is that the answer arrives AFTER the call returns,
                // which is what a launch-and-read-a-flag cannot report.
                Thread.sleep(50)
                true
            }
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        assertEquals(
            "an operation the owner ACCEPTED must be reported as accepted, even though the owner " +
                "answered asynchronously",
            true,
            result is EpisodeOperationResult.Accepted && result.episodeId == "episode-A",
        )
    }

    @Test
    fun `a seek bound to the episode now playing still reaches the player`() = runTest {
        // The positive control: the binding must not refuse the ordinary case, or the check above would
        // be satisfied by refusing everything.
        val manager = mock<PlaybackManager>()
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )

        org.mockito.kotlin.whenever(manager.seekIfPlayingToTimeMs(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull()))
            .thenReturn(true)

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        // THE OWNING OPERATION IS WHAT MUST BE CALLED. `seekIfPlayingToTimeMs` re-reads the selection
        // inside the operation, immediately before seeking, so it is the only place the check and the
        // mutation are one step. A position-only `seekToTimeMsSuspend` here would mean the adapter had
        // checked and then seeked separately -- the race this delegation exists to remove.
        val positionMs = argumentCaptor<Int>()
        verify(manager).seekIfPlayingToTimeMs(
            org.mockito.kotlin.eq("episode-A"),
            positionMs.capture(),
            org.mockito.kotlin.anyOrNull(),
        )
        assertEquals(100_000, positionMs.firstValue)
        assertTrue("accepted, reporting the episode it was bound to", result is EpisodeOperationResult.Accepted)
        verify(manager, org.mockito.kotlin.never()).seekToTimeMsSuspend(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(),
        )
    }

    @Test
    fun `a negative position resolves as an offset back from the episode end`() = runTest {
        // voice-intents: "non-negative values are offsets from the beginning, 0 means beginning, and
        // negative values are offsets back from the episode end." -30 s against a 1800 s episode is
        // 1770 s, not 0: clamping would issue a different instruction than the one given.
        val manager = mock<PlaybackManager>()
        sink(manager).seekTo(positionSeconds = -30, episodeDurationSeconds = 1800)

        val positionMs = argumentCaptor<Int>()
        verify(manager).seekToTimeMsSuspend(positionMs.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals(1_770_000, positionMs.firstValue)
    }

    @Test
    fun `a non-negative position is used as given`() = runTest {
        val manager = mock<PlaybackManager>()
        sink(manager).seekTo(positionSeconds = 300, episodeDurationSeconds = 1800)

        val positionMs = argumentCaptor<Int>()
        verify(manager).seekToTimeMsSuspend(positionMs.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals(300_000, positionMs.firstValue)
    }

    @Test
    fun `an offset beyond the episode start clamps to the beginning, not below it`() = runTest {
        // A negative beyond the episode's length has no position before the beginning, so the
        // resolved value is the beginning rather than a negative sink position.
        val manager = mock<PlaybackManager>()
        sink(manager).seekTo(positionSeconds = -5000, episodeDurationSeconds = 1800)

        val positionMs = argumentCaptor<Int>()
        verify(manager).seekToTimeMsSuspend(positionMs.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals(0, positionMs.firstValue)
    }
}
