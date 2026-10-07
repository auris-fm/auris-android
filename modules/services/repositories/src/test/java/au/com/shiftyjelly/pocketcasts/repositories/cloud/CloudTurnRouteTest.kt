package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
