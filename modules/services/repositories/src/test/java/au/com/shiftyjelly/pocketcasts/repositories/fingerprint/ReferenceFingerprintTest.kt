package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceFingerprintTest {

    @Test
    fun `decode returns null for invalid JSON`() {
        val result = ReferenceFingerprint.decode("not json".toByteArray())
        assertNull(result)
    }

    @Test
    fun `decode returns null for empty data`() {
        val result = ReferenceFingerprint.decode(ByteArray(0))
        assertNull(result)
    }

    @Test
    fun `decode parses any format and leaves the choice to the call site`() {
        // One decoder, two sources: parsing must not decide usability, or a reference from either
        // host would be rejected by the other's constant.
        val json = """
            {
                "format": "unsupported-format",
                "total_duration": 100.0,
                "checkpoint_interval": 10,
                "checkpoint_duration": 8,
                "timestamp_quantum": 1,
                "checkpoints": []
            }
        """.trimIndent()
        val result = ReferenceFingerprint.decode(json.toByteArray())
        assertNotNull(result)
        assertEquals("unsupported-format", result!!.format)
    }

    @Test
    fun `the Auris site rejects a transcript-format reference`() {
        // The two sources serve different formats. A payload in the transcript host's format did not
        // come from the Auris endpoint, so at that site it is absent — the rollout window and a stale
        // cached copy are exactly this case.
        assertNull(
            "a transcript-format payload is not an Auris reference",
            ReferenceFingerprint.decode(payload(ReferenceFingerprint.TRANSCRIPT_FORMAT), ReferenceFingerprint.AURIS_FORMAT),
        )
    }

    @Test
    fun `the transcript site still accepts its own format`() {
        // That host is not ours to migrate; transcript sync must keep working through this change.
        val decoded = ReferenceFingerprint.decode(
            payload(ReferenceFingerprint.TRANSCRIPT_FORMAT),
            ReferenceFingerprint.TRANSCRIPT_FORMAT,
        )
        assertNotNull("a transcript reference must still decode", decoded)
        assertEquals(ReferenceFingerprint.TRANSCRIPT_FORMAT, decoded!!.format)
    }

    @Test
    fun `the transcript site rejects an Auris-format reference`() {
        assertNull(
            ReferenceFingerprint.decode(payload(ReferenceFingerprint.AURIS_FORMAT), ReferenceFingerprint.TRANSCRIPT_FORMAT),
        )
    }

    @Test
    fun `an unknown format is rejected at either site`() {
        for (expected in listOf(ReferenceFingerprint.AURIS_FORMAT, ReferenceFingerprint.TRANSCRIPT_FORMAT)) {
            assertNull(ReferenceFingerprint.decode(payload("fingerprint-something-else"), expected))
        }
    }

    private fun payload(format: String) = """
        {
            "format": "$format",
            "total_duration": 100.0,
            "checkpoint_interval": 1,
            "checkpoint_duration": 8,
            "timestamp_quantum": 1,
            "checkpoints": [[0, "AQIDBA=="]]
        }
    """.trimIndent().toByteArray()

    @Test
    fun `decode returns fingerprint for valid JSON`() {
        val json = """
            {
                "format": "fingerprint-compact-v3",
                "total_duration": 100.0,
                "checkpoint_interval": 10,
                "checkpoint_duration": 8,
                "timestamp_quantum": 1,
                "checkpoints": []
            }
        """.trimIndent()
        val result = ReferenceFingerprint.decode(json.toByteArray())
        assertNotNull(result)
        assertEquals("fingerprint-compact-v3", result!!.format)
        assertEquals(100.0, result.totalDuration, 0.001)
        assertEquals(10, result.checkpointInterval)
        assertEquals(8, result.checkpointDuration)
        assertEquals(1, result.timestampQuantum)
    }

    @Test
    fun `libraryCheckpoints returns empty for no checkpoints`() {
        val fp = ReferenceFingerprint(
            format = "fingerprint-compact-v2",
            totalDuration = 100.0,
            checkpointInterval = 10,
            checkpointDuration = 8,
            timestampQuantum = 1,
            checkpoints = emptyList(),
        )
        assertEquals(emptyList<ReferenceFingerprint.LibraryCheckpoint>(), fp.libraryCheckpoints())
    }

    @Test
    fun `libraryCheckpoints skips malformed entries`() {
        val fp = ReferenceFingerprint(
            format = "fingerprint-compact-v2",
            totalDuration = 100.0,
            checkpointInterval = 10,
            checkpointDuration = 8,
            timestampQuantum = 1,
            checkpoints = listOf(
                listOf<Any>(),
                listOf<Any>(1.0),
                listOf<Any>("not a number", "AQIDBA=="),
            ),
        )
        assertEquals(0, fp.libraryCheckpoints().size)
    }

    @Test
    fun `libraryCheckpoints accumulates timestamps correctly`() {
        val fp = ReferenceFingerprint(
            format = "fingerprint-compact-v2",
            totalDuration = 100.0,
            checkpointInterval = 10,
            checkpointDuration = 8,
            timestampQuantum = 2,
            checkpoints = listOf(
                listOf<Any>(5.0, "AQIDBA=="),
                listOf<Any>(3.0, "BQYHCA=="),
            ),
        )
        val checkpoints = fp.libraryCheckpoints()
        assertEquals(2, checkpoints.size)
        assertEquals(10.0f, checkpoints[0].timestampSeconds)
        assertEquals(16.0f, checkpoints[1].timestampSeconds)
    }

    @Test
    fun `all-one deltas place checkpoint k at k seconds, not at triangular numbers`() {
        // The wire shape the server actually emits: interval 1, quantum 1, every delta 1 after the
        // first. Summing the deltas is right; summing the INDEX (0+1+2+…) also looks monotonic and
        // passes a weaker assertion, so pin the absolute places.
        val fp = ReferenceFingerprint(
            format = "fingerprint-compact-v3",
            totalDuration = 100.0,
            checkpointInterval = 1,
            checkpointDuration = 8,
            timestampQuantum = 1,
            checkpoints = listOf(
                listOf<Any>(0, "AQIDBA=="),
                listOf<Any>(1, "AQIDBA=="),
                listOf<Any>(1, "AQIDBA=="),
                listOf<Any>(1, "AQIDBA=="),
            ),
        )
        assertEquals(
            listOf(0f, 1f, 2f, 3f),
            fp.libraryCheckpoints().map { it.timestampSeconds },
        )
    }

    @Test
    fun `checkpointDurationSeconds converts int to float`() {
        val fp = ReferenceFingerprint(
            format = "fingerprint-compact-v2",
            totalDuration = 100.0,
            checkpointInterval = 10,
            checkpointDuration = 8,
            timestampQuantum = 1,
            checkpoints = emptyList(),
        )
        assertEquals(8.0f, fp.checkpointDurationSeconds)
    }
}
