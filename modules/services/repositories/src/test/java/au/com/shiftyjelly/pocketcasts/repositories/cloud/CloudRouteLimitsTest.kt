package au.com.shiftyjelly.pocketcasts.repositories.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-side bounds applied before a turn is sent (cloud-assistant.md):
 * recent conversation is at most four entries and at most 8 KiB UTF-8.
 */
class CloudRouteLimitsTest {

    private fun entry(text: String, role: String = CloudRouteConversationEntry.ROLE_USER) = CloudRouteConversationEntry(role, text)

    @Test
    fun `keeps at most four newest entries`() {
        val clamped = CloudRouteLimits.clampConversation(
            (1..6).map { entry("turn $it") },
        )

        assertEquals(CloudRouteLimits.MAX_CONVERSATION_ENTRIES, clamped.size)
        assertEquals(listOf("turn 3", "turn 4", "turn 5", "turn 6"), clamped.map { it.text })
    }

    @Test
    fun `drops oldest entries until within the byte budget`() {
        val big = "x".repeat(3 * 1024)
        val clamped = CloudRouteLimits.clampConversation(
            listOf(entry(big), entry(big), entry(big), entry("recent")),
        )

        val totalBytes = clamped.sumOf { it.text.toByteArray(Charsets.UTF_8).size }
        assertTrue("total $totalBytes must fit", totalBytes <= CloudRouteLimits.MAX_CONVERSATION_BYTES)
        assertEquals("recent", clamped.last().text)
    }

    @Test
    fun `truncates a single oversized entry without splitting a character`() {
        val oversized = "é".repeat(CloudRouteLimits.MAX_CONVERSATION_BYTES)
        val clamped = CloudRouteLimits.clampConversation(listOf(entry(oversized)))

        val text = clamped.single().text
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= CloudRouteLimits.MAX_CONVERSATION_BYTES)
        assertTrue(text.isNotEmpty())
        // No replacement characters from a split multi-byte sequence.
        assertTrue(!text.contains('\uFFFD'))
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(CloudRouteLimits.clampConversation(emptyList()).isEmpty())
    }
}
