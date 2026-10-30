package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelFile
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import java.io.File
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
    fun `reports a backend model as ready only when every required file is present`() {
        val manager = ModelManager(context).apply { filesDir = tempDir.root }
        val spec = ModelSpec(
            files = listOf(ModelFile(url = "https://example/a.ort", filename = "a.ort")),
            targetDir = "sensevoice-model",
        )
        assertFalse(manager.isModelReady(spec))

        val dir = File(tempDir.root, "sensevoice-model").apply { mkdirs() }
        File(dir, "a.ort").writeText("fake model")

        assertTrue(manager.isModelReady(spec))
    }
}
