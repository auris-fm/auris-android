package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTurnRouteTest {

    private class FakeTransport(private val events: List<CloudRouteEvent>) : CloudTurnTransport {
        val frames = mutableListOf<CloudTurnFrame>()

        override fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent> = flow {
            frames += frame
            events.forEach { emit(it) }
        }
    }

    private class FixedToken(private val token: String?) : CloudTokenProviding {
        override suspend fun currentToken(): String? = token
        override suspend fun refreshToken() = Unit
    }

    private val turn = CloudRouteTurn(
        request = "play the next episode",
        context = CloudRouteContext(episodeId = "e-1", clientPositionMs = 1000L),
        requestId = "req-1",
        capabilities = listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1),
    )

    private fun route(transport: CloudTurnTransport, token: String? = "tok") = CloudTurnRoute(
        baseUrl = "https://cloud.example.com",
        tokenProvider = FixedToken(token),
        transport = transport,
    )

    @Test
    fun `the socket carries the auth frame with the advertised codecs`() = runTest {
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))

        val events = route(transport).route(turn).toList()

        val frame = transport.frames.single() as CloudTurnFrame.Authenticate
        assertEquals("tok", frame.accessToken)
        assertEquals("req-1", frame.requestId)
        assertEquals("play the next episode", frame.request)
        assertEquals(listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1), frame.capabilities)
        assertEquals(CloudRouteCodecs.advertised, frame.codecs)
        assertEquals(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")), events)
    }

    @Test
    fun `discovery with no current episode omits the episode and position fields entirely`() = runTest {
        // @spec's contract check: a turn with nothing playing must still dispatch, carrying an ABSENT
        // playback context -- and the absence must survive to the wire. An empty episode id or a zero
        // position would both be real values: the first claims a subject the turn does not have, the
        // second places it at the very start of a recording nobody is playing.
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))
        val discovery = CloudRouteTurn(
            request = "what podcasts are trending",
            context = CloudRouteContext(), // no episode, no position
            requestId = "req-discovery",
            capabilities = listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1),
        )

        route(transport).route(discovery).toList()

        val frame = transport.frames.single() as CloudTurnFrame.Authenticate
        // The TYPED fields are absent...
        assertNull("no episode means no episode_id in the context", frame.context.episodeId)
        assertNull("and no client_position_ms", frame.context.clientPositionMs)

        // ...and the WIRE carries no such keys at all. Asserting only the typed context would pass
        // even if the encoder substituted an empty id or a zero position, because the substitution
        // happens at serialization -- so the serialized frame is the thing under test.
        val json = CloudRouteJson.authenticateAdapter.toJson(frame)
        assertFalse(
            "the wire must not name an episode for a turn that has none (json=$json)",
            json.contains("episode_id"),
        )
        assertFalse(
            "and must not report a position that does not exist (json=$json)",
            json.contains("client_position_ms"),
        )
        assertFalse("nor a reference position", json.contains("reference_position_ms"))
        assertFalse("nor a podcast id", json.contains("podcast_id"))
    }

    @Test
    fun `a turn with a current episode carries its local position`() = runTest {
        // The control for the case above: with an episode the fields ARE present, so the omission
        // above is about absence rather than about the encoder dropping them.
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))
        val withEpisode = CloudRouteTurn(
            request = "what am I listening to",
            context = CloudRouteContext(
                episodeId = "e-42",
                podcastId = "p-9",
                clientPositionMs = 91_000L,
            ),
            requestId = "req-with-episode",
        )

        route(transport).route(withEpisode).toList()

        val frame = transport.frames.single() as CloudTurnFrame.Authenticate
        assertEquals("e-42", frame.context.episodeId)
        assertEquals("p-9", frame.context.podcastId)
        assertEquals(91_000L, frame.context.clientPositionMs)
        // And the control reaches the wire too: with an episode the fields ARE serialized, so the
        // omission above is about absence rather than about the encoder dropping them.
        val json = CloudRouteJson.authenticateAdapter.toJson(frame)
        assertTrue("the episode reaches the wire (json=$json)", json.contains("\"episode_id\":\"e-42\""))
        assertTrue("with its position", json.contains("\"client_position_ms\":91000"))
    }

    @Test
    fun `the admission pair is encoded together and omitted when either half is absent`() = runTest {
        // @spec's admission contract: a turn carries the conversation it creates or resumes. The pair
        // is written TOGETHER -- a mode without a handle, or a handle without a mode, is not a state
        // the contract admits, so it must be omitted rather than half-sent.
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))
        val firstTurn = CloudRouteTurn(
            request = "start the conversation",
            context = CloudRouteContext(),
            requestId = "req-create",
            conversationId = "conv-1",
            conversationMode = CloudConversationMode.Create,
        )

        route(transport).route(firstTurn).toList()

        val json = CloudRouteJson.authenticateAdapter
            .toJson(transport.frames.single() as CloudTurnFrame.Authenticate)
        assertTrue("the handle is on the wire (json=$json)", json.contains("\"conversation_id\":\"conv-1\""))
        assertTrue("with its mode", json.contains("\"conversation_mode\":\"create\""))
    }

    @Test
    fun `a resume turn carries the same handle with the resume mode`() = runTest {
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))
        val laterTurn = CloudRouteTurn(
            request = "and now this",
            context = CloudRouteContext(),
            requestId = "req-resume",
            conversationId = "conv-1",
            conversationMode = CloudConversationMode.Resume,
        )

        route(transport).route(laterTurn).toList()

        val json = CloudRouteJson.authenticateAdapter
            .toJson(transport.frames.single() as CloudTurnFrame.Authenticate)
        assertTrue(
            "the SAME handle is carried, so the two turns share one conversation (json=$json)",
            json.contains("\"conversation_id\":\"conv-1\""),
        )
        assertTrue("with the resume mode", json.contains("\"conversation_mode\":\"resume\""))
    }

    @Test
    fun `a turn with no conversation identity omits both fields`() = runTest {
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))

        route(transport).route(turn).toList()

        val json = CloudRouteJson.authenticateAdapter
            .toJson(transport.frames.single() as CloudTurnFrame.Authenticate)
        assertFalse("no conversation means no handle (json=$json)", json.contains("conversation_id"))
        assertFalse("and no mode", json.contains("conversation_mode"))
    }

    @Test
    fun `the encoder never writes client conversation history to a cloud frame`() = runTest {
        // The owning spec forbids `context.recent_conversation` on cloud frames: the Worker owns cloud
        // history. The context here DOES hold entries, so the case distinguishes an intentional
        // omission from an empty one -- asserting the model alone would pass either way.
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))
        val withHistory = CloudRouteTurn(
            request = "and this one",
            context = CloudRouteContext(
                episodeId = "e-1",
                clientPositionMs = 1_000L,
                recentConversation = listOf(
                    CloudRouteConversationEntry(
                        role = CloudRouteConversationEntry.ROLE_USER,
                        text = "what is this podcast about",
                    ),
                    CloudRouteConversationEntry(
                        role = CloudRouteConversationEntry.ROLE_ASSISTANT,
                        text = "it is about birds",
                    ),
                ),
            ),
            requestId = "req-history",
        )

        route(transport).route(withHistory).toList()

        val json = CloudRouteJson.authenticateAdapter
            .toJson(transport.frames.single() as CloudTurnFrame.Authenticate)
        assertFalse(
            "the serialized cloud frame must not carry recent_conversation (json=$json)",
            json.contains("recent_conversation"),
        )
    }

    @Test
    fun `a refused upgrade fails the turn terminally - there is no fallback`() = runTest {
        val transport = FakeTransport(
            listOf(CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = "")),
        )

        val events = route(transport).route(turn).toList()

        // Not swallowed and not retried elsewhere: the error IS the turn's outcome, which is what
        // makes a completed turn evidence that the negotiated path served it.
        assertEquals(
            listOf(CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = "")),
            events,
        )
        assertEquals(1, transport.frames.size)
    }

    @Test
    fun `a failure after delivery is emitted as it is`() = runTest {
        val transport = FakeTransport(
            listOf(
                CloudRouteEvent.Token("partial"),
                CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
            ),
        )

        val events = route(transport).route(turn).toList()

        assertEquals(
            listOf(
                CloudRouteEvent.Token("partial"),
                CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
            ),
            events,
        )
    }

    @Test
    fun `a typed-flow hint travels on the frame and reaches the wire`() = runTest {
        val hint = CloudRouteHint(
            operation = "search_podcasts",
            arguments = mapOf("query" to "the long way"),
        )
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))

        route(transport).route(turn.copy(routeHint = hint)).toList()

        val frame = transport.frames.single() as CloudTurnFrame.Authenticate
        assertEquals(hint, frame.routeHint)
        val json = CloudRouteJson.authenticateAdapter.toJson(frame)
        assertTrue("the hint must reach the wire", json.contains("\"route_hint\""))
        assertTrue(json.contains("search_podcasts"))
    }

    @Test
    fun `a turn without a hint omits the field entirely`() = runTest {
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))

        route(transport).route(turn).toList()

        val frame = transport.frames.single() as CloudTurnFrame.Authenticate
        val json = CloudRouteJson.authenticateAdapter.toJson(frame)
        assertFalse("an absent hint must be omitted, not sent empty", json.contains("route_hint"))
    }

    @Test
    fun `no token opens nothing and fails closed`() = runTest {
        val transport = FakeTransport(listOf(CloudRouteEvent.Connected("pcm_s16le@24k")))

        val events = route(transport, token = null).route(turn).toList()

        assertTrue("no token means no socket", transport.frames.isEmpty())
        assertEquals(
            listOf(CloudRouteEvent.Error(code = CloudRouteErrorCodes.UNAUTHORIZED, message = "")),
            events,
        )
    }
}
