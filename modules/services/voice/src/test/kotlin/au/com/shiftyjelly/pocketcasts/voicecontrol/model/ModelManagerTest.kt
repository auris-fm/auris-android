package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelFile
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `a completed ASR model download alone does not report Ready`() {
        val body = "fake model bytes".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.ort") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val manager = ModelManager(context).apply { filesDir = tempDir.root }
            val spec = ModelSpec(
                files = listOf(
                    ModelFile(url = "http://127.0.0.1:${server.address.port}/a.ort", filename = "a.ort"),
                ),
                targetDir = "sensevoice-model",
            )
            val result = runBlocking { manager.ensureModel(spec) }
            assertTrue(result.isSuccess)
            // The ASR model is only half the set: the LFM model downloads after it, so the
            // screen must not show Ready before ensureLfmModel succeeds. The per-file
            // progress report stays, but no Ready claim is made here.
            val state = manager.downloadState.value
            assertFalse("ASR download alone must not claim Ready, was $state", state is ModelDownloadState.Ready)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `downloadModel reports Failed when the download cannot connect`() {
        val manager = ModelManager(context).apply { filesDir = tempDir.root }
        val spec = ModelSpec(
            files = listOf(
                ModelFile(url = "http://127.0.0.1:1/a.ort", filename = "a.ort"),
            ),
            targetDir = "sensevoice-model",
        )
        val result = runBlocking { manager.ensureModel(spec) }
        assertTrue(result.isFailure)
        val state = manager.downloadState.value
        assertTrue("expected Failed, was $state", state is ModelDownloadState.Failed)
        assertTrue((state as ModelDownloadState.Failed).reason.isNotBlank())
    }
}
