package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parser failures must not carry payload-derived text into logs. */
class CloudRouteSseParserTest {

    @Test
    fun `every malformed payload failure is content-free`() {
        val secret = "user_secret_marker_1234"
        // Different failure shapes: Moshi syntax errors are IOException
        // subclasses, shape mismatches are RuntimeExceptions, and one is a
        // valid document with trailing content.
        val payloads = listOf(
            "{\"text\": $secret-not-json}",
            "{\"text\": [$secret]}",
            "{}",
            "{\"text\":\"ok\"} $secret",
        )

        payloads.forEach { payload ->
            val body = "event: token\ndata: $payload\n\n"
            try {
                CloudRouteSseParser().parse(BufferedReader(StringReader(body))) { }
                fail("expected failure for payload: $payload")
            } catch (error: Exception) {
                assertFalse(
                    "message must not echo payload content ($payload)",
                    error.message.orEmpty().contains(secret),
                )
                assertFalse(
                    "cause must not echo payload content ($payload)",
                    error.cause?.message.orEmpty().contains(secret),
                )
            }
        }
    }
}
