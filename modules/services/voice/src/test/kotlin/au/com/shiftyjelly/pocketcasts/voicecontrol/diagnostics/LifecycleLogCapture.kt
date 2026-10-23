package au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics

import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Reads the production writer's support-bundle buffer, without replacing its producer or sink. */
class LifecycleLogCapture : AutoCloseable {
    private val directory = Files.createTempDirectory("voice-lifecycle-").toFile()
    init {
        flush()
        LogBuffer.setup(directory.absolutePath)
    }
    fun lines(): List<String> {
        flush()
        return ByteArrayOutputStream().also(LogBuffer::output).toString(Charsets.UTF_8).lines().filter { it.contains("event=") }
    }
    override fun close() {
        flush()
        directory.deleteRecursively()
    }
    private fun flush() = runBlocking { withTimeout(3_000) { VoiceLifecycleDiagnostics.flushForTest() } }
}
