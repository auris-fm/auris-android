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
    fun `reports not ready when model not downloaded`() {
        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertFalse(manager.isModelReady())
    }

    @Test
    fun `reports ready when model file exists`() {
        val lmDir = File(tempDir.root, "smol-lm-model").apply { mkdirs() }
        File(lmDir, "smolLM2-360M-instruct-Q4_K_M.gguf").writeText("fake model")

        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertTrue(manager.isModelReady())
    }

    @Test
    fun `provides correct model file name`() {
        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertEquals("smolLM2-360M-instruct-Q4_K_M.gguf", manager.smolLmModelFile.name)
    }
}
