package au.com.shiftyjelly.pocketcasts.repositories.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** Server-supplied codes must never reach device logs verbatim. */
class CloudRouteErrorCodesTest {

    @Test
    fun `known codes are logged as themselves`() {
        assertEquals(
            CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE,
            CloudRouteErrorCodes.normalizeForLog(CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE),
        )
    }

    @Test
    fun `unknown and null codes collapse to constants`() {
        assertEquals("unrecognized", CloudRouteErrorCodes.normalizeForLog("user_email@example.com in error text"))
        assertEquals("none", CloudRouteErrorCodes.normalizeForLog(null))
    }
}
