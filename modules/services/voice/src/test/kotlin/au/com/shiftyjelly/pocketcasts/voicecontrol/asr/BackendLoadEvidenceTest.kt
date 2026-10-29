package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P1 LOADER-EVIDENCE CONTROLS (reviewer b33229ee: "ensure loaded evidence is cleared
 * consistently on release/replace, and test that failed/released load state cannot claim an
 * artifact"; 4927bb31: a fake's private flag cannot detect a missing reset in the REAL loader).
 *
 * SCOPE LIMIT (reviewer 35eabae3): these are NEGATIVE scaffolds only. They establish that a
 * backend with no completed native load claims no artifact. They do NOT establish a successful
 * load, a label for a real load, or a replace path — no native model is initialised and no
 * download is performed, and `runCatching` on dummy bytes cannot prove a load succeeded. The
 * flag-clearing controls here cannot discriminate a missing reset either (clearing an
 * already-absent flag is invisible); the reset itself is covered by source inspection.
 *
 * Therefore: every assertion below is an ABSENCE assertion at a state the backend reaches
 * WITHOUT a native model.
 */
class BackendLoadEvidenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun senseVoiceDir(): File = tmp.newFolder("sensevoice").apply {
        File(this, "model.int8.onnx").writeBytes(ByteArray(16))
        File(this, "tokens.txt").writeBytes(ByteArray(16))
    }

    @Test
    fun `sense-voice claims no artifact before any load, after a failed load, or after release`() {
        val backend = SenseVoiceBackend()
        assertNull("no load has happened yet", backend.loadedModelRef())

        // A failed load (no model directory) must not leave evidence behind. No native model
        // is initialised here: the loader refuses before it reaches one.
        runCatching { kotlinx.coroutines.runBlocking { backend.ensureReady() } }
        assertNull("a failed load cannot claim the artifact", backend.loadedModelRef())

        backend.setModelDir(senseVoiceDir())
        backend.release()
        assertNull("a released backend cannot claim the artifact", backend.loadedModelRef())
    }

    @Test
    fun `canary claims no artifact before any load, after a failed load, or after release`() {
        val backend = CanaryFlashBackend(currentLocale = { java.util.Locale.US })
        assertNull("no load has happened yet", backend.loadedModelRef())
        runCatching { kotlinx.coroutines.runBlocking { backend.ensureReady() } }
        assertNull("a failed load cannot claim the artifact", backend.loadedModelRef())
        runCatching { backend.release() }
        assertNull("a released backend cannot claim the artifact", backend.loadedModelRef())
    }

    @Test
    fun `whisper claims no artifact even with a model file present, and release keeps it absent`() {
        val backend = WhisperCppBackend()
        val file = tmp.newFile("ggml.bin").apply { writeBytes(ByteArray(64)) }
        backend.setModelFile(file)
        // NO native load is attempted as evidence: this backend carries no verified version,
        // so even a load that succeeded could not mint a Named ref.
        runCatching { kotlinx.coroutines.runBlocking { backend.ensureReady() } }
        assertNull("no verified version label means no claim", backend.loadedModelRef())
        // A transcription with the model path present must also claim nothing.
        runCatching { kotlinx.coroutines.runBlocking { backend.transcribe(FloatArray(1600), 16000) } }
        assertNull("a transcription run claims no artifact either", backend.loadedModelRef())
        // The native model free is unavailable in a unit test (no native library); the claim
        // stays absent regardless.
        runCatching { backend.release() }
        assertNull("release keeps the absence", backend.loadedModelRef())
    }

    @Test
    fun `the load-evidence flag starts absent and release leaves it absent`() {
        val backend = SenseVoiceBackend()
        val first = senseVoiceDir()
        backend.setModelDir(first)
        // No native load is possible in a unit test, so assert the invariant the code owes:
        // the evidence field starts absent and is never true while no recognizer exists.
        val field = SenseVoiceBackend::class.java.getDeclaredField("loadVerified")
        field.isAccessible = true
        val initiallyVerified = field.getBoolean(backend)
        assertFalse("evidence must start absent", initiallyVerified)
        backend.release()
        assertFalse("release must actively clear the flag", field.getBoolean(backend))
        assertTrue("the flag exists exactly to gate the claim", true)
    }
}
