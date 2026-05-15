package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelManagerTest {
    @get:Rule val tempDir = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `reports not ready when models not downloaded`() {
        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertFalse(manager.areModelsReady())
    }

    @Test
    fun `reports ready when both model files exist`() {
        val whisperDir = File(tempDir.root, "whisper-model").apply { mkdirs() }
        val lmDir = File(tempDir.root, "smol-lm-model").apply { mkdirs() }
        File(whisperDir, "ggml-base.bin").writeText("fake model")
        File(lmDir, "smolLM2-360M-instruct-Q4_K_M.gguf").writeText("fake model")

        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertTrue(manager.areModelsReady())
    }

    @Test
    fun `provides correct model file names`() {
        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertEquals("ggml-base.bin", manager.whisperModelFile.name)
        assertEquals("smolLM2-360M-instruct-Q4_K_M.gguf", manager.smolLmModelFile.name)
    }
}
