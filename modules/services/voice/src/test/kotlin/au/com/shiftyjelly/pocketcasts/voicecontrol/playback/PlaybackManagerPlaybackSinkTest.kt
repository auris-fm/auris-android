package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
