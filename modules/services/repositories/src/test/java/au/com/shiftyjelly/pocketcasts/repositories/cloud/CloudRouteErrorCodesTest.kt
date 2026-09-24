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
    fun `locally minted codes keep their diagnostic value`() {
        assertEquals(CloudRouteErrorCodes.INVALID_REQUEST, CloudRouteErrorCodes.normalizeForLog("invalid_request"))
        assertEquals("http_503", CloudRouteErrorCodes.normalizeForLog("http_503"))
        assertEquals("http_404", CloudRouteErrorCodes.normalizeForLog("http_404"))
    }

    @Test
    fun `near-miss shapes still collapse`() {
        assertEquals("unrecognized", CloudRouteErrorCodes.normalizeForLog("http_50"))
        assertEquals("unrecognized", CloudRouteErrorCodes.normalizeForLog("http_5033"))
        assertEquals("unrecognized", CloudRouteErrorCodes.normalizeForLog("http_5xx"))
    }

    @Test
    fun `unknown and null codes collapse to constants`() {
        assertEquals("unrecognized", CloudRouteErrorCodes.normalizeForLog("user_email@example.com in error text"))
        assertEquals("none", CloudRouteErrorCodes.normalizeForLog(null))
    }
}
