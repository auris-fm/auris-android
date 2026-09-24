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
    fun `the serialized conversation fits the budget, not just its text`() {
        // Roles and JSON framing are part of what the bound is stated against.
        val fitted = CloudRouteLimits.clampConversation(
            listOf(
                entry("a".repeat(2700), role = CloudRouteConversationEntry.ROLE_ASSISTANT),
                entry("b".repeat(2700), role = CloudRouteConversationEntry.ROLE_USER),
                entry("c".repeat(2700), role = CloudRouteConversationEntry.ROLE_ASSISTANT),
            ),
        )
        val serialized = fitted.joinToString(prefix = "[", postfix = "]") {
            "{\"role\":\"${it.role}\",\"text\":\"${it.text}\"}"
        }
        assertTrue(
            "serialized ${serialized.toByteArray(Charsets.UTF_8).size} must fit",
            serialized.toByteArray(Charsets.UTF_8).size <= CloudRouteLimits.MAX_CONVERSATION_BYTES,
        )
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
    fun `escaping is accounted for in the byte budget`() {
        // Newlines and quotes each cost a byte when encoded — an answer full of
        // them must still fit once marshalled.
        val escapable = "line\n".repeat(700) + "\"quoted\"".repeat(100)
        val clamped = CloudRouteLimits.clampConversation(listOf(entry(escapable, role = CloudRouteConversationEntry.ROLE_ASSISTANT)))

        val serialized = clamped.joinToString(prefix = "[", postfix = "]") {
            "{\"role\":\"${it.role}\",\"text\":${jsonString(it.text)}}"
        }
        assertTrue(
            "serialized ${serialized.toByteArray(Charsets.UTF_8).size} bytes must fit",
            serialized.toByteArray(Charsets.UTF_8).size <= CloudRouteLimits.MAX_CONVERSATION_BYTES,
        )
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "\uD83C\uDFA7" // 🎧 (two code units)
        val clamped = CloudRouteLimits.clampConversation(
            listOf(entry(emoji.repeat(CloudRouteLimits.MAX_CONVERSATION_BYTES / 4))),
        )

        val text = clamped.single().text
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= CloudRouteLimits.MAX_CONVERSATION_BYTES)
        assertTrue(!text.contains('\uFFFD'))
        assertTrue(Character.isLowSurrogate(text.last()) || !Character.isSurrogate(text.last()))
    }

    /** Minimal JSON string escaping for the assertion above. */
    private fun jsonString(value: String): String {
        val out = StringBuilder("\"")
        value.forEach { c ->
            when (c) {
                '\"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                else -> out.append(c)
            }
        }
        return out.append("\"").toString()
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(CloudRouteLimits.clampConversation(emptyList()).isEmpty())
    }
}
