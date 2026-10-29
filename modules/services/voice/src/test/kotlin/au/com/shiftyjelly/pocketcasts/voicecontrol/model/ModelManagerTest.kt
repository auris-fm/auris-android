package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    fun `reports not ready when moonshine model not downloaded`() {
        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertFalse(manager.isMoonshineModelReady())
    }

    @Test
    fun `ensureModel refuses success when cached bytes fail the declared digest`() {
        // STALE-CACHE CONTROL (reviewer 8e0f87c8): the in-lock early return must use the
        // VERIFIED predicate, not bare existence -- otherwise a bad-digest cache is labeled
        // with the pinned manifest identity without ever being re-downloaded.
        val manager = ModelManager(context).apply { filesDir = tempDir.root }
        val spec = au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec(
            files = listOf(
                au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelFile(
                    url = "http://localhost:1/unreachable.bin",
                    filename = "ggml-small-q5_1.bin",
                    sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
                    manifestId = "whisper.cpp-ggml-small-q5_1@test",
                ),
            ),
            targetDir = "whisper-model",
        )
        val cached = File(tempDir.root, "whisper-model/ggml-small-q5_1.bin")
        cached.parentFile!!.mkdirs()
        cached.writeText("an older cached artifact with wrong bytes")
        // isModelReady already refuses the stale bytes...
        assertFalse(manager.isModelReady(spec))
        // ...and ensureModel must NOT bless them either: the in-lock check re-verifies, the
        // download attempt fails (unreachable URL), so the result is a FAILURE, never
        // success-over-stale-bytes.
        val result = kotlinx.coroutines.runBlocking { manager.ensureModel(spec) }
        assertTrue("ensureModel must not return success for a stale cache", result.isFailure)
        assertFalse(manager.isModelReady(spec))
    }

    @Test
    fun `reports ready when all moonshine model files exist`() {
        val moonshineDir = File(tempDir.root, "moonshine-model").apply { mkdirs() }
        val files = listOf(
            "adapter.ort",
            "cross_kv.ort",
            "decoder_kv.ort",
            "decoder_kv_with_attention.ort",
            "encoder.ort",
            "frontend.ort",
            "streaming_config.json",
            "tokenizer.bin",
        )
        for (file in files) {
            File(moonshineDir, file).writeText("fake model")
        }

        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        assertTrue(manager.isMoonshineModelReady())
    }
}
