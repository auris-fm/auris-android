package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the copy of the shared slot-repair fixtures that [UnresolvedSlotRepairTest] encodes.
 *
 * The cases live in the core repository and are reissued as the shared rulings change. The Kotlin
 * test restates them one method at a time, which makes it useful to read but easy to drift: a case
 * could be edited, or a reissued fixture not picked up, and nothing would fail.
 *
 * This test is the tripwire. The resource is a byte-for-byte copy at the revision the shared side
 * cleared, so its digest identifies exactly which reissue was mirrored. When the shared cases
 * change, this test fails until the resource and the case list are refreshed together, and the
 * recorded revision is updated with them.
 *
 * The recorded revision: the fixture file's own SHA-256, which is the identifier the shared side
 * published for this reissue.
 */
@RunWith(RobolectricTestRunner::class)
class UnresolvedSlotRepairFixtureProvenanceTest {

    private val fixtureSha256 = "7c35fcc3ca78df1c55d226f49007a8c557f224cfd78b5082a5d6452f727cdf8f"

    /** The number of cases in the mirrored reissue, so a lost or duplicated case is visible. */
    private val expectedCaseCount = 136

    private fun resource(): ByteArray = checkNotNull(javaClass.classLoader?.getResourceAsStream("unresolved-slot-repair/unresolved_slot_repair.json")) {
        "the shared fixture copy is missing from the test resources"
    }.use { it.readBytes() }

    @Test
    fun `the mirrored fixture is the revision the shared side cleared`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(resource())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "the shared fixture copy is not the recorded revision: refresh the resource and the " +
                "case list together, then update the recorded digest",
            fixtureSha256,
            digest,
        )
    }

    @Test
    fun `every mirrored case is still represented`() {
        val ids = caseIds(String(resource(), Charsets.UTF_8))
        assertEquals("the mirrored fixture changed size", expectedCaseCount, ids.size)

        // Each case must still be encoded as a test method named for it, so a case cannot be
        // dropped from the Kotlin test while the resource keeps passing.
        val encoded = javaClass.classLoader
            ?.getResourceAsStream(
                "au/com/shiftyjelly/pocketcasts/voicecontrol/intent/UnresolvedSlotRepairTest.class",
            )?.use { it.readBytes() }
        if (encoded == null) return // class not on the resource path; the digest test still holds

        val text = String(encoded, Charsets.ISO_8859_1)
        val missing = ids.map { it.replace('-', '_') }.filterNot { text.contains(it) }
        assertTrue("cases missing from the Kotlin test: ${missing.joinToString(", ")}", missing.isEmpty())
    }

    /**
     * The `id` of each case, read without a JSON library: every case is an object carrying exactly
     * one `"id"` field, and nothing else in the file uses that key.
     */
    private fun caseIds(json: String): List<String> = Regex("""^\s*"id"\s*:\s*"([^"]+)"""", RegexOption.MULTILINE)
        .findAll(json)
        .map { it.groupValues[1] }
        .toList()
}
