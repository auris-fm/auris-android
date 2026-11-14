package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * The dial seam, observed where it matters: inside a real [WebSocketCloudTurnTransport.exchange].
 *
 * This exists because a control that calls the seam directly proves nothing. I wrote one, mutated
 * the transport to bypass the seam, and it stayed green: nothing in `exchange` was being tested.
 * The control has to enter through `exchange` and watch the socket the transport actually gets.
 */
class TransportDialSeamControlTest {

    private val turn = CloudTurnFrame.Authenticate(
        accessToken = "tok",
        requestId = "req-adopt",
        request = "next episode",
        context = CloudRouteContext(episodeId = "e-1", clientPositionMs = 0L),
        capabilities = emptyList(),
        codecs = listOf("pcm_s16le@24k"),
        conversationId = "11111111-1111-4111-8111-111111111111",
        conversationMode = CloudConversationMode.Create,
    )

    @Test
    fun `exchange asks the dial seam for its socket`() = runTest {
        // The seam IS the adoption point: if exchange never calls it, an early connection can never
        // be used, and the whole early-open path is dead code.
        val adopted = mock<WebSocket>()
        val dialledAt = mutableListOf<Request>()
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        transport.dial = { request: Request, _: WebSocketListener ->
            dialledAt += request
            adopted
        }

        // The mock socket never answers, so nothing arrives: we only care that the dial went
        // through the seam. A timeout means exchange hung, which would itself be a finding.
        val first = withTimeoutOrNull(2_000) {
            transport.exchange(turn).first()
        }

        assertTrue(
            "exchange must obtain its socket through the seam, not dial on its own",
            dialledAt.isNotEmpty(),
        )
        assertTrue("exactly one dial per exchange", dialledAt.size == 1)
        assertTrue(
            "no event should arrive from a socket that never answered, but got $first",
            first == null,
        )
    }
}
