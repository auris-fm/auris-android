package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.io.BufferedReader
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser failure contract: every payload failure is normalised to one
 * content-free message with no cause, and a cancellation delivered through the
 * event callback is rethrown untouched rather than laundered into a parse
 * failure (which the client would report to the user as `invalid_response`).
 */
class CloudRouteSseParserTest {

    private fun parse(body: String, onEvent: (CloudRouteEvent) -> Unit = {}) {
        CloudRouteSseParser().parse(BufferedReader(StringReader(body)), onEvent)
    }

    @Test
    fun `every malformed payload failure is normalised and content-free`() {
        val secret = "user_secret_marker_1234"
        // Four failure shapes: Moshi syntax error, shape mismatch, missing
        // required field, trailing content after a complete document.
        val payloads = listOf(
            "{\"text\": $secret-not-json}",
            "{\"text\": [$secret]}",
            "{}",
            "{\"text\":\"ok\"} $secret",
        )

        payloads.forEach { payload ->
            val outcome = runCatching { parse("event: token\ndata: $payload\n\n") }

            assertTrue("expected failure for payload: $payload", outcome.isFailure)
            val error = outcome.exceptionOrNull()!!
            // Exact constants, not substring searches: these are what a
            // regression would change (the pre-fix code rethrew the original
            // IOException and attached a payload-derived cause).
            assertEquals("Malformed SSE payload", error.message)
            assertNull("no payload-derived cause may be attached", error.cause)
            assertTrue(error is java.io.IOException)
        }
    }

    @Test
    fun `a cancellation through the event callback is rethrown untouched`() {
        val outcome = runCatching {
            parse("event: token\ndata: {\"text\":\"hello\"}\n\n") { throw CancellationException("superseded") }
        }

        val error = outcome.exceptionOrNull()
        // CancellationException is an IllegalStateException: before this
        // boundary it was laundered into IOException and reported as
        // invalid_response, i.e. a superseded turn heard an error message.
        assertTrue("cancellation must not be wrapped", error is CancellationException)
    }

    @Test
    fun `known events still parse`() {
        val events = mutableListOf<CloudRouteEvent>()
        parse(
            """
            event: token
            data: {"text":"hi"}

            event: done
            data: {"input_tokens":1,"output_tokens":2}
            """.trimIndent(),
            events::add,
        )

        assertEquals(
            listOf(CloudRouteEvent.Token("hi"), CloudRouteEvent.Done(1, 2)),
            events,
        )
    }
}
