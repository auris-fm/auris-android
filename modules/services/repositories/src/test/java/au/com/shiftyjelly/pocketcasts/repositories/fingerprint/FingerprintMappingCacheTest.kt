package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintMappingCacheTest {
    @Test
    fun `referenceDataVersion is the standard SHA-256 hex of the exact bytes`() {
        // core cloud-assistant.md reference_asset_version: the version is the SHA-256 of the
        // reference data the active map was built from -- the SAME digest the cache validates with,
        // so server and client name the same bytes by construction.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FingerprintMappingCache.referenceDataVersion(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FingerprintMappingCache.referenceDataVersion("abc".toByteArray()),
        )
    }

    @Test
    fun `different reference bytes yield different versions`() {
        val a = FingerprintMappingCache.referenceDataVersion("reference-v1".toByteArray())
        val b = FingerprintMappingCache.referenceDataVersion("reference-v2".toByteArray())
        assertNotEquals(a, b)
        assertEquals(a, FingerprintMappingCache.referenceDataVersion("reference-v1".toByteArray()))
        assertTrue(a.length == 64)
    }
}
