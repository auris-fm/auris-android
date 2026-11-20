package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class CloudReferenceStoreTest {

    private fun store(): FileCloudReferenceStore =
        FileCloudReferenceStore(RuntimeEnvironment.getApplication())

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
