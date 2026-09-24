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
    fun `malformed payload failure is content-free`() {
        val secret = "user_secret_marker_1234"
        val body = "event: token\ndata: {\"text\": $secret-not-json}\n\n"

        try {
            CloudRouteSseParser().parse(BufferedReader(StringReader(body))) { }
            fail("expected the malformed payload to fail the parse")
        } catch (error: Exception) {
            assertFalse(
                "exception message must not echo payload content",
                error.message.orEmpty().contains(secret),
            )
            assertTrue(
                "cause must not carry payload-derived text either",
                error.cause?.message.orEmpty().let { !it.contains(secret) },
            )
        }
    }
}
