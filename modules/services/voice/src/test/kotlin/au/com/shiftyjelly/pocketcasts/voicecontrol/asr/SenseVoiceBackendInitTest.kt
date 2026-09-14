package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the gate-restart ASR outage: engine.stop() releases the
 * backend, and the restart path re-runs ensureReady() — which must rebuild the
 * recognizer (idempotently while the model dir is unchanged) instead of leaving
 * transcribe() silently returning empty forever.
 */
class SenseVoiceBackendInitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRecognizer : OfflineAsrRecognizer {
        var released = false
        override fun transcribe(samples: FloatArray, sampleRateHz: Int): String = "<|en|>pause playback"
        override fun release() {
            released = true
        }
    }

    private fun modelDir(): File {
        val dir = tmp.newFolder("sensevoice-model-${System.nanoTime()}")
        File(dir, "model.int8.onnx").writeBytes(byteArrayOf(1))
        File(dir, "tokens.txt").writeBytes(byteArrayOf(2))
        return dir
    }

    private fun backend(calls: MutableList<Int>): SenseVoiceBackend {
        val b = SenseVoiceBackend()
        b.recognizerFactory = { _, _ -> calls += 1; FakeRecognizer() }
        return b
    }

    @Test
    fun `ensureReady builds recognizer once for unchanged model dir`() = runTest {
        val calls = mutableListOf<Int>()
        val b = backend(calls)
        b.setModelDir(modelDir())

        assertTrue(b.ensureReady().isSuccess)
        assertTrue(b.ensureReady().isSuccess)
        assertEquals(1, calls.size)
    }

    @Test
    fun `ensureReady rebuilds after release so engine restart recovers`() = runTest {
        val calls = mutableListOf<Int>()
        val b = backend(calls)
        b.setModelDir(modelDir())

        assertTrue(b.ensureReady().isSuccess)
        b.release()
        assertTrue(b.ensureReady().isSuccess)
        assertEquals(2, calls.size)
    }

    @Test
    fun `ensureReady rebuilds when model dir changes`() = runTest {
        val calls = mutableListOf<Int>()
        val b = backend(calls)
        b.setModelDir(modelDir())
        assertTrue(b.ensureReady().isSuccess)
        b.setModelDir(modelDir())
        assertTrue(b.ensureReady().isSuccess)
        assertEquals(2, calls.size)
    }

    @Test
    fun `transcribe uses initialized recognizer and strips lang tag`() = runTest {
        val b = backend(mutableListOf())
        b.setModelDir(modelDir())
        assertTrue(b.ensureReady().isSuccess)

        val result = b.transcribe(FloatArray(16000), 16000)
        assertEquals("pause playback", result.text)
        assertEquals("en", result.detectedLanguage)
    }

    @Test
    fun `transcribe returns empty result when never initialized`() = runTest {
        val b = backend(mutableListOf())
        val result = b.transcribe(FloatArray(16000), 16000)
        assertEquals("", result.text)
        assertEquals(null, result.detectedLanguage)
    }

    @Test
    fun `transcribe returns empty result when model files missing`() = runTest {
        val b = backend(mutableListOf())
        val emptyDir = tmp.newFolder("empty-${System.nanoTime()}")
        b.setModelDir(emptyDir)
        assertTrue(b.ensureReady().isFailure)
        val result = b.transcribe(FloatArray(16000), 16000)
        assertEquals("", result.text)
        assertNotNull(result)
    }
}
