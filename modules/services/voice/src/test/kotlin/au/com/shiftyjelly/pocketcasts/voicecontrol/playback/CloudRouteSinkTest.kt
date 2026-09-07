package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CloudRouteSinkTest {

    private val playbackContext = PlaybackContext(
        episodeId = "episode-id",
        podcastId = "podcast-id",
        referencePositionMs = 1_230_000L,
        clientPositionMs = 50_000L,
    )

    @Test
    fun `empty base url returns coming soon without calling route`() = runTest {
        val deps = TestDeps(baseUrl = "")
        val sink = deps.sink()

        val response = sink.routeToCloud("summarize", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("Cloud processing is coming soon"), response)
        assertTrue(deps.routeCalls.isEmpty())
        assertTrue(deps.playback.calls.isEmpty())
    }

    @Test
    fun `buffers token texts and speaks once on done`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Token("She "),
                CloudRouteEvent.Token("is arguing."),
                CloudRouteEvent.Done(inputTokens = 10, outputTokens = 5),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("She is arguing."), response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
        assertEquals(
            listOf(CloudRouteAnalyticsCall("done", 10, 5)),
            deps.analytics.calls,
        )
    }

    @Test
    fun `action-only stream returns silent on done`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action("playback", "pause", emptyMap()),
                CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("pause", VoiceIntent.CloudTier.Free, playbackContext)

        assertEquals(VoiceResponse.Silent, response)
        assertTrue(deps.playback.calls.contains("pause"))
    }

    @Test
    fun `seek_to uses fingerprint mapping when available`() = runTest {
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(45_000)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(deps.playback.calls.contains("seekTo:45000"))
    }

    @Test
    fun `seek_to falls back to reference ms as playback when unmapped`() = runTest {
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(deps.playback.calls.contains("seekTo:1130000"))
    }

    @Test
    fun `play_quote captures position seeks and resumes`() = runTest {
        val deps = TestDeps(
            clientPositionMs = 60_000L,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 900_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play quote", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            listOf("pause", "seekTo:900000", "resume"),
            deps.playback.calls,
        )
    }

    @Test
    fun `stop_quote restores pre-quote playback position`() = runTest {
        val deps = TestDeps(
            clientPositionMs = 60_000L,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 900_000L),
                ),
                CloudRouteEvent.Action("playback", "stop_quote", emptyMap()),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("quote", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(deps.playback.calls.contains("seekTo:60000"))
    }

    @Test
    fun `unknown tool and action are ignored`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action("effects", "set_speed", mapOf("speed" to 2.0)),
                CloudRouteEvent.Action("playback", "next_episode", emptyMap()),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `error clears token buffer restores auto pause and speaks message`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Token("partial"),
                CloudRouteEvent.Error(code = "connection_lost", message = "Connection lost"),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("Connection lost"), response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
        assertEquals(listOf(CloudRouteAnalyticsCall("error", null, null)), deps.analytics.calls)
    }

    @Test
    fun `error with empty message returns earcon`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Error(code = "invalid_request", message = ""),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), response)
    }

    @Test
    fun `error does not roll back seeks`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 500_000L),
                ),
                CloudRouteEvent.Error(code = "connection_lost", message = "lost"),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(deps.playback.calls.contains("seekTo:500000"))
        assertTrue(!deps.playback.calls.contains("seekTo:50000"))
    }

    @Test
    fun `builds route context enriched from sink state`() = runTest {
        val state = CloudPlaybackContextState(
            recentReferencePositions = listOf(100L, 200L),
            previousReferencePositionMs = 300L,
        )
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            events = flowOf(CloudRouteEvent.Done(1, 0)),
        )
        val sink = deps.sink()

        sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            CloudRouteContext(
                episodeId = "episode-id",
                podcastId = "podcast-id",
                referencePositionMs = 1_230_000L,
                clientPositionMs = 50_000L,
                recentReferencePositions = listOf(100L, 200L),
                previousReferencePositionMs = 300L,
            ),
            deps.routeContexts.single(),
        )
    }

    private data class CloudRouteAnalyticsCall(
        val outcome: String,
        val inputTokens: Int?,
        val outputTokens: Int?,
    )

    private class TestDeps(
        private val baseUrl: String = "https://cloud.example.com",
        private val userId: String = "user_test",
        clientPositionMs: Long = 50_000L,
        // Mockito defaults Int? to 0; unmapped seeks must see null so we fall back to referenceMs.
        private val fingerprintTimingManager: FingerprintTimingManager = mock<FingerprintTimingManager>().also {
            whenever(it.playbackTimeMs(any())).thenReturn(null)
        },
        private val cloudPlaybackContextState: CloudPlaybackContextState = CloudPlaybackContextState(),
        private val events: kotlinx.coroutines.flow.Flow<CloudRouteEvent> = flowOf(CloudRouteEvent.Done(0, 0)),
    ) {
        val playback = FakePlaybackSink()
        val analytics = FakeCloudRouteAnalytics()
        val routeCalls = mutableListOf<String>()
        val routeContexts = mutableListOf<CloudRouteContext>()

        private val playbackContextProvider = object : PlaybackContextProvider {
            override fun current(): PlaybackContext = PlaybackContext(clientPositionMs = clientPositionMs)
        }

        fun sink(): CloudRouteSink = CloudRouteSink(
            resolveBaseUrl = { baseUrl },
            resolveUserId = { userId },
            playbackSink = playback,
            fingerprintTimingManager = fingerprintTimingManager,
            playbackContextProvider = playbackContextProvider,
            cloudPlaybackContextState = cloudPlaybackContextState,
            analytics = analytics,
            routeInvoker = { request: String, context: CloudRouteContext ->
                routeCalls += request
                routeContexts += context
                events
            },
        )
    }

    private class FakePlaybackSink : VoicePlaybackSink {
        val calls = mutableListOf<String>()

        override suspend fun pause(): VoiceResponse {
            calls += "pause"
            return VoiceResponse.Silent
        }

        override suspend fun resume(): VoiceResponse {
            calls += "resume"
            return VoiceResponse.Silent
        }

        override suspend fun skipForward(seconds: Int): VoiceResponse = VoiceResponse.Silent
        override suspend fun skipBackward(seconds: Int): VoiceResponse = VoiceResponse.Silent

        override suspend fun seekTo(positionMs: Int): VoiceResponse {
            calls += "seekTo:$positionMs"
            return VoiceResponse.Silent
        }

        override fun nextEpisode(): VoiceResponse = VoiceResponse.Silent
    }

    private class FakeCloudRouteAnalytics : CloudRouteAnalytics {
        val calls = mutableListOf<CloudRouteAnalyticsCall>()

        override fun recordTurn(outcome: String, inputTokens: Int?, outputTokens: Int?) {
            calls += CloudRouteAnalyticsCall(outcome, inputTokens, outputTokens)
        }
    }
}
