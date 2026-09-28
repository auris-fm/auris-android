package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The token source follows cutover while the app is running. */
class RoutingCloudTokenProvidingTest {

    private class FixedToken(private val token: String?) : CloudTokenProviding {
        override suspend fun currentToken(): String? = token
    }

    @Test
    fun `enabling cutover switches to the Auris token without rebuilding the graph`() = runBlocking {
        var cutoverActive = false
        val routing = RoutingCloudTokenProviding(
            isAurisActive = { cutoverActive },
            auris = FixedToken("auris-token"),
            legacy = FixedToken("user_uuid"),
        )

        assertEquals("user_uuid", routing.currentToken())

        cutoverActive = true
        assertEquals("auris-token", routing.currentToken())

        cutoverActive = false
        assertEquals("user_uuid", routing.currentToken())
    }

    @Test
    fun `a build with no environment never reaches the Auris provider`() = runBlocking {
        val routing = RoutingCloudTokenProviding(
            isAurisActive = { false },
            auris = FixedToken("auris-token"),
            legacy = FixedToken(null),
        )

        assertEquals(null, routing.currentToken())
    }
}
