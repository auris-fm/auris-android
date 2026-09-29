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
    fun `refresh is forwarded to whichever provider currentToken would use`() = runBlocking {
        var aurisRefreshes = 0
        var legacyRefreshes = 0
        val auris = object : CloudTokenProviding {
            override suspend fun currentToken(): String? = "auris-token"

            override suspend fun refreshToken(): String? {
                aurisRefreshes++
                return "auris-token-2"
            }
        }
        val legacy = object : CloudTokenProviding {
            override suspend fun currentToken(): String? = "user_uuid"

            override suspend fun refreshToken(): String? {
                legacyRefreshes++
                return "user_uuid-2"
            }
        }
        var aurisActive = true
        val routing = RoutingCloudTokenProviding(
            isAurisActive = { aurisActive },
            auris = auris,
            legacy = legacy,
        )

        // Inheriting the interface default would return the token the route just refused, which is
        // what made the caller's retry a no-op; the forward has to follow the same branch as
        // currentToken, including when the branch changes.
        assertEquals("auris-token-2", routing.refreshToken())
        assertEquals(1, aurisRefreshes)
        assertEquals(0, legacyRefreshes)

        aurisActive = false
        assertEquals("user_uuid-2", routing.refreshToken())
        assertEquals(1, legacyRefreshes)
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
