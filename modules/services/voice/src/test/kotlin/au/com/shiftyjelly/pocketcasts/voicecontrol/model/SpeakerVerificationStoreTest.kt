package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerVerificationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SpeakerVerificationStore(context)

    @Test
    fun `store and retrieve embedding`() {
        val e = FloatArray(192) { it.toFloat() }
        store.saveEmbedding(e)
        assertArrayEquals(e, store.getEmbedding(), 1e-6f)
    }

    @Test
    fun `null when empty`() {
        store.clear()
        assertNull(store.getEmbedding())
    }

    @Test
    fun `isEnrolled reflects state`() {
        store.clear()
        assertFalse(store.isEnrolled())
        store.saveEmbedding(FloatArray(192) { 0.5f })
        assertTrue(store.isEnrolled())
    }

    @Test
    fun `clear removes all`() {
        store.saveEmbedding(FloatArray(192) { 1f })
        store.saveEnrollmentTimestamp(12345L)
        store.clear()
        assertNull(store.getEmbedding())
        assertEquals(0L, store.getEnrollmentTimestamp())
    }
}
