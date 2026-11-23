package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A cached mapping is only reusable when it was built from the reference the caller is holding.
 *
 * The other guards compare bytes: reference hash, size and mtime, audio size, mtime and content
 * sample. None of them says which FORMAT produced the mapping's reference times, so a mapping built
 * against different values would survive an upgrade whenever the reference file happened to be
 * byte-identical — and alignment would resume on a timeline this build cannot reproduce. The format
 * travels with the mapping and must match the reference in hand.
 *
 * The two sources serve different formats, so this is per source, not one global constant: a
 * transcript-derived mapping stays reusable, and an Auris mapping in another format is discarded.
 */
class FingerprintMappingCacheFormatTest {

    private fun payload(format: String) = """
        {
          "format": "$format",
          "total_duration": 100.0,
          "checkpoint_interval": 1,
          "checkpoint_duration": 8,
          "timestamp_quantum": 1,
          "checkpoints": []
        }
    """.trimIndent().toByteArray()

    private class Fixture(val audio: File, val reference: File, val data: ByteArray)

    private fun fixture(format: String): Fixture {
        val dir = Files.createTempDirectory("fp-map-format").toFile()
        dir.deleteOnExit()
        val audio = File(dir, "episode.mp3").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val data = payload(format)
        val reference = File(dir, "episode.fp.json").apply { writeBytes(data) }
        return Fixture(audio, reference, data)
    }

    /** Entries covering the whole reference, so the coverage guard is satisfied and not what rejects. */
    private fun fullEntries() = listOf(
        FingerprintTimingManager.TimeMappingEntry(playbackTime = 0.0, referenceTime = 0.0, score = 0.9f),
        FingerprintTimingManager.TimeMappingEntry(playbackTime = 99.0, referenceTime = 99.0, score = 0.9f),
    )

    private fun saveAndLoad(f: Fixture, auris: AurisReferenceIdentity? = null) =
        FingerprintMappingCache.load(f.audio.absolutePath, f.reference.absolutePath, f.data, auris)

    /** A saved mapping that also recorded which Auris reference contributed to it. */
    private fun saveWithAuris(f: Fixture, auris: AurisReferenceIdentity?) = FingerprintMappingCache.save(
        fullEntries(), f.audio.absolutePath, f.reference.absolutePath, f.data, 100.0, auris,
    )

    @Test
    fun `an Auris mapping saved against the Auris format is reused`() {
        val f = fixture(ReferenceFingerprint.AURIS_FORMAT)
        FingerprintMappingCache.save(
            fullEntries(), f.audio.absolutePath, f.reference.absolutePath, f.data, 100.0,
        )

        val loaded = saveAndLoad(f)

        assertNotNull("a mapping built from the reference in hand must still be reused", loaded)
        assertEquals(2, loaded!!.entries.size)
    }

    @Test
    fun `a transcript mapping stays reusable`() {
        // Transcript sync keeps its own format; that host is not ours to migrate. Its mappings must
        // not be thrown away by a change to what the Auris endpoint serves.
        val f = fixture(ReferenceFingerprint.TRANSCRIPT_FORMAT)
        FingerprintMappingCache.save(
            fullEntries(), f.audio.absolutePath, f.reference.absolutePath, f.data, 100.0,
        )

        assertNotNull("a transcript-derived mapping is still valid", saveAndLoad(f))
    }

    @Test
    fun `a mapping recording another format than the reference in hand is discarded`() {
        val f = fixture(ReferenceFingerprint.AURIS_FORMAT)
        FingerprintMappingCache.save(
            fullEntries(), f.audio.absolutePath, f.reference.absolutePath, f.data, 100.0,
        )
        // Rewrite only the recorded format, leaving every byte-guard intact: this is the upgrade
        // case where the reference file itself has not changed.
        val path = File(FingerprintMappingCache.mappingPath(f.audio.absolutePath))
        val before = path.readText()
        val after = before.replace(
            "\"referenceFormat\":\"${ReferenceFingerprint.AURIS_FORMAT}\"",
            "\"referenceFormat\":\"${ReferenceFingerprint.TRANSCRIPT_FORMAT}\"",
        )
        // A rewrite that silently matched nothing would make this case pass for the wrong reason.
        assertNotEquals("the rewrite must change the recorded format", before, after)
        path.writeText(after)

        assertNull(
            "a mapping built from other values cannot be reused against this reference",
            saveAndLoad(f),
        )
    }

    @Test
    fun `a mapping with no recorded format is discarded`() {
        val f = fixture(ReferenceFingerprint.AURIS_FORMAT)
        FingerprintMappingCache.save(
            fullEntries(), f.audio.absolutePath, f.reference.absolutePath, f.data, 100.0,
        )
        // A cache written before the format was recorded: its provenance is unknown, which is not
        // evidence that it is usable.
        val path = File(FingerprintMappingCache.mappingPath(f.audio.absolutePath))
        val before = path.readText()
        val after = before.replace(Regex(",?\"referenceFormat\":\"[^\"]*\""), "")
        assertNotEquals("the rewrite must remove the recorded format", before, after)
        path.writeText(after)

        assertNull("an unrecorded format is unknown provenance, not a supported one", saveAndLoad(f))
    }

    @Test
    fun `a reference whose format cannot be read discards the mapping`() {
        val f = fixture(ReferenceFingerprint.AURIS_FORMAT)
        FingerprintMappingCache.save(
            fullEntries(), f.audio.absolutePath, f.reference.absolutePath, f.data, 100.0,
        )
        // The reference in hand no longer parses, so nothing establishes what the mapping describes.
        val unreadable = "not json".toByteArray()

        assertNull(saveAndLoad(Fixture(f.audio, f.reference, unreadable)))
    }

    private val aurisRef = AurisReferenceIdentity(ReferenceFingerprint.AURIS_FORMAT, "hash-of-the-auris-reference")

    @Test
    fun `a mapping recorded against the Auris reference in hand stays reusable`() {
        val f = fixture(ReferenceFingerprint.AURIS_FORMAT)
        saveWithAuris(f, aurisRef)

        assertNotNull("the same Auris reference must not invalidate the mapping", saveAndLoad(f, aurisRef))
    }

    @Test
    fun `a mapping recorded against a different Auris reference is discarded`() {
        // The Auris reference was regenerated in place: same format, different values. The byte
        // guards describe the transcript file, which has not changed, so only the recorded hash
        // can catch this.
        val f = fixture(ReferenceFingerprint.AURIS_FORMAT)
        saveWithAuris(f, aurisRef)

        assertNull(
            "a mapping built from another Auris reference cannot be reused",
            saveAndLoad(f, aurisRef.copy(payloadHash = "a-regenerated-reference")),
        )
    }

    @Test
    fun `a mapping recorded against an Auris reference is discarded when none is in use now`() {
        val f = fixture(ReferenceFingerprint.AURIS_FORMAT)
        saveWithAuris(f, aurisRef)

        assertNull("no Auris reference now cannot reuse mapping entries that came from one", saveAndLoad(f, null))
    }

    @Test
    fun `a mapping saved with no Auris reference in use stays reusable`() {
        // Transcript-only alignment: nothing was recorded, so nothing can have gone stale.
        val f = fixture(ReferenceFingerprint.TRANSCRIPT_FORMAT)
        saveWithAuris(f, null)

        assertNotNull("a mapping with no Auris entries is unaffected by the Auris reference", saveAndLoad(f, null))
    }
}
