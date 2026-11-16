package au.com.shiftyjelly.pocketcasts.repositories.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `codes recognised only for normalization keep their diagnostic value`() {
        // `invalid_request` and `http_<status>` are never minted by the client, only
        // recognised for log normalization. Against the constant, not a fresh literal: a
        // rename then fails here instead of silently breaking the allowlist.
        assertEquals(
            CloudRouteErrorCodes.INVALID_REQUEST,
            CloudRouteErrorCodes.normalizeForLog(CloudRouteErrorCodes.INVALID_REQUEST),
        )
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
    fun `a speech failure keeps its own name in the log`() {
        // The service ends a turn with this when synthesis fails. Collapsing it to `unrecognized`
        // hid the cause: a device run logged `turn error code=unrecognized` for what was actually
        // a speech outage, and the diagnosis needed the server's logs to recover it.
        assertEquals(
            CloudRouteErrorCodes.SPEECH_UNAVAILABLE,
            CloudRouteErrorCodes.normalizeForLog(CloudRouteErrorCodes.SPEECH_UNAVAILABLE),
        )
    }

    @Test
    fun `the persisted trace and the log recognise the same vocabulary`() {
        // Two allowlists drifted once: a speech outage was named in the Timber line and bounded to
        // "unknown" in the lifecycle trace. One question, asked of one place, cannot drift.
        for (code in CloudRouteErrorCodes.KNOWN) {
            assertTrue("$code must be persistable", CloudRouteErrorCodes.isRecognised(code))
        }
        assertFalse("an unseen code stays out", CloudRouteErrorCodes.isRecognised("user_email@example.com"))
        assertFalse("a status shape is not a code", CloudRouteErrorCodes.isRecognised("http_503"))
    }

    @Test
    fun `unknown and null codes collapse to constants`() {
        assertEquals("unrecognized", CloudRouteErrorCodes.normalizeForLog("user_email@example.com in error text"))
        assertEquals("none", CloudRouteErrorCodes.normalizeForLog(null))
    }
}
