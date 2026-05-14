package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerEmbedderTest {

    @Test
    fun `model loads from assets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        assertTrue("Model should load", embedder.load())
    }

    @Test
    fun `embed produces 192-dim result`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        embedder.load()
        val audio = FloatArray(16000) // 1s silence
        val result = embedder.embed(audio)
        assertNotNull(result)
        assertEquals(192, result!!.size)
    }

    @Test
    fun `same audio produces same embedding`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        embedder.load()
        val audio = FloatArray(16000) { i ->
            (Math.sin(i * 2.0 * Math.PI * 440.0 / 16000.0) * 0.5).toFloat()
        }
        val e1 = embedder.embed(audio)!!
        val e2 = embedder.embed(audio)!!
        for (i in e1.indices) {
            assertEquals(e1[i], e2[i], 1e-6f)
        }
    }
}
