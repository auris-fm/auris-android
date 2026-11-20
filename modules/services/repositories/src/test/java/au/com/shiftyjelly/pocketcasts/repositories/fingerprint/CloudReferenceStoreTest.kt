package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertArrayEquals
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
        val store = store()
        store.save("ep_1", byteArrayOf(1, 2, 3), "\"v1\"")
        val cached = store.load("ep_1")
        assertEquals("\"v1\"", cached!!.etag)
        assertArrayEquals(byteArrayOf(1, 2, 3), cached.payload)
    }

    @Test
    fun `a save replaces a previous entry for the same episode`() {
        val store = store()
        store.save("ep_2", byteArrayOf(9), "\"old\"")
        store.save("ep_2", byteArrayOf(8, 8), "\"new\"")
        val cached = store.load("ep_2")
        assertEquals("\"new\"", cached!!.etag)
        assertArrayEquals(byteArrayOf(8, 8), cached.payload)
    }

    @Test
    fun `an unknown episode loads null`() {
        assertNull(store().load("never_saved"))
    }

    @Test
    fun `entries are keyed per episode`() {
        val store = store()
        store.save("ep_a", byteArrayOf(1), "\"a\"")
        store.save("ep_b", byteArrayOf(2), "\"b\"")
        assertEquals("\"b\"", store.load("ep_b")!!.etag)
        assertEquals("\"a\"", store.load("ep_a")!!.etag)
    }

    @Test
    fun `a save without an etag stores nothing`() {
        val store = store()
        store.save("ep_c", byteArrayOf(7), null)
        assertNull(store.load("ep_c"))
    }
}
