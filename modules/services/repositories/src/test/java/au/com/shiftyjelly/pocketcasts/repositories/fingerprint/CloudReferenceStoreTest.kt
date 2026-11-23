package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class CloudReferenceStoreTest {

    private fun store(): FileCloudReferenceStore = FileCloudReferenceStore(RuntimeEnvironment.getApplication())

    @Test
    fun `save then load returns the payload and etag`() {
        kotlinx.coroutines.runBlocking {
            val store = store()
            store.save("ep_1", byteArrayOf(1, 2, 3), "\"v1\"")
            val cached = store.load("ep_1")
            assertEquals("\"v1\"", cached!!.etag)
            assertArrayEquals(byteArrayOf(1, 2, 3), cached.payload)
        }
    }

    @Test
    fun `a save replaces a previous entry for the same episode`() {
        kotlinx.coroutines.runBlocking {
            val store = store()
            store.save("ep_2", byteArrayOf(9), "\"old\"")
            store.save("ep_2", byteArrayOf(8, 8), "\"new\"")
            val cached = store.load("ep_2")
            assertEquals("\"new\"", cached!!.etag)
            assertArrayEquals(byteArrayOf(8, 8), cached.payload)
        }
    }

    @Test
    fun `an unknown episode loads null`() {
        kotlinx.coroutines.runBlocking {
            assertNull(store().load("never_saved"))
        }
    }

    @Test
    fun `the byte cap prunes oldest entries first`() {
        kotlinx.coroutines.runBlocking {
            val dir = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "fingerprint_cloud_refs")
            // 40 payloads x 8 MB = 320 MB, above the 300 MB total-bytes cap, while the entry count
            // (40) is far below its own cap: only the byte bound can explain the pruning. The final
            // save's prune must bring the total back under the cap, dropping the OLDEST first.
            val bigSize = 8 * 1024 * 1024
            val count = 40
            val big = ByteArray(bigSize)
            repeat(count) { i -> store().save("ep_big_$i", big, "\"v$i\"") }
            // After the last save, the total must be back under the cap and the OLDEST are gone.
            val remaining = dir.listFiles { f -> f.name.endsWith(".payload") }!!.sortedBy { it.name }
            val totalBytes = remaining.sumOf { it.length() }
            assertTrue(
                "the byte cap must hold ($totalBytes bytes across ${remaining.size} entries)",
                totalBytes <= 300L * 1024 * 1024,
            )
            assertTrue("the oldest entries must have been pruned first", !java.io.File(dir, "ep_big_0.payload").exists())
            assertTrue("the newest entry must survive", java.io.File(dir, "ep_big_${count - 1}.payload").exists())
        }
    }

    fun `entries are keyed per episode`() {
        kotlinx.coroutines.runBlocking {
            val store = store()
            store.save("ep_a", byteArrayOf(1), "\"a\"")
            store.save("ep_b", byteArrayOf(2), "\"b\"")
            assertEquals("\"b\"", store.load("ep_b")!!.etag)
            assertEquals("\"a\"", store.load("ep_a")!!.etag)
        }
    }

    @Test
    fun `a save without an etag stores nothing`() {
        kotlinx.coroutines.runBlocking {
            val store = store()
            store.save("ep_c", byteArrayOf(7), null)
            assertNull(store.load("ep_c"))
        }
    }

    @Test
    fun `the payload is stored as raw bytes with an etag sidecar`() {
        kotlinx.coroutines.runBlocking {
            val store = store()
            store.save("ep_raw", byteArrayOf(1, 2, 3, 123), "\"v9\"")
            val dir = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "fingerprint_cloud_refs")
            val payloadFile = java.io.File(dir, "ep_raw.payload")
            assertTrue("payload must be a raw-bytes file, not base64 text", payloadFile.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3, 123), payloadFile.readBytes())
            val etagFile = java.io.File(dir, "ep_raw.etag")
            assertTrue(etagFile.exists())
            assertEquals("\"v9\"", etagFile.readText())
        }
    }

    @Test
    fun `a large payload round-trips without text buffering`() {
        kotlinx.coroutines.runBlocking {
            val store = store()
            val payload = ByteArray(5 shl 20) { (it % 123).toByte() }
            store.save("ep_big", payload, "\"big\"")
            val cached = store.load("ep_big")
            assertEquals("\"big\"", cached!!.etag)
            assertArrayEquals(payload, cached.payload)
        }
    }

    @Test
    fun `a missing etag sidecar loads null`() {
        kotlinx.coroutines.runBlocking {
            val dir = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "fingerprint_cloud_refs")
            dir.mkdirs()
            java.io.File(dir, "ep_orphan.payload").writeBytes(byteArrayOf(1))
            assertNull(store().load("ep_orphan"))
        }
    }
}
