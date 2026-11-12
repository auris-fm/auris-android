package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

class OpenWakeWordDetectorTest {

    @Test
    fun `detector input excludes captured samples before VAD speech onset`() {
        val segment = floatArrayOf(-0.5f, -0.25f, 0.25f, 0.5f)

        val aligned = OpenWakeWordDetector.onsetAlignedSamples(segment, speechOnsetSample = 2)

        assertArrayEquals(floatArrayOf(0.25f, 0.5f), aligned, 0f)
    }

    @Test
    fun `error defaults to false on WakeWordResult`() {
        assertFalse(WakeWordResult(detected = false).error)
    }

    @Test
    fun `unready detector reports error not silent negative`() = runBlocking {
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        `when`(context.assets).thenReturn(assets)
        `when`(assets.open(any())).thenThrow(RuntimeException("no assets in unit test"))

        val detector = OpenWakeWordDetector(context, failingNative())
        assertFalse(detector.isReady)

        val result = detector.detect(FloatArray(16000), 16000)
        assertFalse(result.detected)
        assertTrue(result.error)
    }

    /** Native boundary whose init fails the way an absent runtime does: init false, no throw. */
    private fun failingNative(): WakeWordNative = object : WakeWordNative {
        override fun nativeInit(melModel: ByteArray, embedModel: ByteArray, classifierModel: ByteArray, threshold: Float): Boolean = false

        override fun nativeDetect(samples: FloatArray, sampleRateHz: Int, outOffset: FloatArray): Float = -1f

        override fun nativeRelease() = Unit
    }
}
