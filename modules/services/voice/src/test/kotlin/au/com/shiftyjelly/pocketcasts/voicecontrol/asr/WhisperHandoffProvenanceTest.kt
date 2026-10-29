package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P1 WHISPER HANDOFF CONTROLS (reviewer a00bb77d): the artifact identity is NOT established by
 * this backend hashing anything — it arrives with the file ModelManager verified. These controls
 * pin the boundary rules without any native load:
 *  - an UNVERIFIED plain path assignment can never name an artifact;
 *  - a verified handoff alone is not evidence either until this backend's own native init ran;
 *  - changing the file clears the identity; release clears it; a failed init clears it.
 *
 * The `WhisperNative` calls only happen inside `ensureReady`/`release`; the failing load path is
 * exercised in a JVM without the native library, which is what production sees when the native
 * library is missing.
 *
 * STALE-EVIDENCE SEEDING (reviewer 98ed63b3): a control that starts from an already-clear state
 * cannot detect a MISSING clear. The release/failure controls therefore seed "a successful load
 * happened" through a narrowly scoped reflection helper and then exercise the cleanup paths. That
 * seeded state is a TEST-ONLY premise; it is NOT evidence that a real native load ever succeeded,
 * and this file still proves nothing about native initialization.
 */
class WhisperHandoffProvenanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifestId = "whisper.cpp-ggml-small-q5_1@5359861c739e955e79d9a303bcbc70fb988958b1"

    private fun modelFile(name: String = "ggml-small-q5_1.bin"): File = tmp.newFile(name).apply { writeBytes(ByteArray(64)) }

    /**
     * TEST-ONLY: seeds the post-successful-load state so cleanup paths can be exercised without a
     * native load. Not evidence of a real load; it exists precisely so a missing clear is visible.
     */
    private fun WhisperCppBackend.seedPriorLoadEvidence(manifestId: String) {
        WhisperCppBackend::class.java.getDeclaredField("loadVerified").apply {
            isAccessible = true
            setBoolean(this@seedPriorLoadEvidence, true)
        }
        WhisperCppBackend::class.java.getDeclaredField("handoffManifestId").apply {
            isAccessible = true
            set(this@seedPriorLoadEvidence, manifestId)
        }
    }

    @Test
    fun `a plain path assignment never names an artifact`() {
        val backend = WhisperCppBackend()
        backend.setModelFile(modelFile())
        assertNull("a path alone carries no verification", backend.loadedModelRef())
    }

    @Test
    fun `a verified handoff alone is not evidence until this backend loaded that same file`() {
        val backend = WhisperCppBackend()
        backend.setVerifiedModelFile(modelFile(), manifestId)
        // No successful native init of this backend has happened.
        assertNull("the handoff is a premise, not load evidence", backend.loadedModelRef())
    }

    @Test
    fun `a failed initialization clears prior load evidence`() {
        val backend = WhisperCppBackend()
        backend.setVerifiedModelFile(modelFile(), manifestId)
        // SEEDED: pretend a load had succeeded, so a MISSING clear would be observable.
        backend.seedPriorLoadEvidence(manifestId)
        val outcome = runCatching { kotlinx.coroutines.runBlocking { backend.ensureReady() } }
        // This JVM has no native library, so `init` throws rather than returning false; the test
        // states which path it exercised instead of assuming a boolean `false` was reached.
        assertTrue(
            "expected a native-link failure, got: $outcome",
            outcome.exceptionOrNull() is NoClassDefFoundError ||
                outcome.exceptionOrNull() is UnsatisfiedLinkError ||
                outcome.getOrNull()?.isFailure == true,
        )
        assertNull("a failed init cannot leave the seeded evidence standing", backend.loadedModelRef())
    }

    @Test
    fun `a missing or empty model file also clears prior load evidence`() {
        val backend = WhisperCppBackend()
        backend.setVerifiedModelFile(tmp.newFile("empty.bin"), manifestId) // zero bytes, exists
        backend.seedPriorLoadEvidence(manifestId)
        val outcome = kotlinx.coroutines.runBlocking { backend.ensureReady() }
        // The empty-file guard returns BEFORE any native call, so no link failure can mask this.
        assertTrue("the empty-file guard must return failure, got: $outcome", outcome.isFailure)
        assertNull("the missing/empty-file return must clear it too", backend.loadedModelRef())
    }

    @Test
    fun `changing the file drops the identity it was verified for`() {
        val backend = WhisperCppBackend()
        backend.setVerifiedModelFile(modelFile(), manifestId)
        // The SAME backend is re-wired to another file without a verification: the old identity
        // must not survive the change.
        backend.setModelFile(modelFile("other.bin"))
        assertNull("an identity belongs to one verified file", backend.loadedModelRef())
    }

    @Test
    fun `release drops prior load evidence even when the native free throws`() {
        val backend = WhisperCppBackend()
        backend.setVerifiedModelFile(modelFile(), manifestId)
        // SEEDED: a stale `true` would survive a clear placed AFTER the native free.
        backend.seedPriorLoadEvidence(manifestId)
        runCatching { backend.release() }
        assertNull("release must clear provenance before/around the native free", backend.loadedModelRef())
    }

    @Test
    fun `the pinned manifest identity is the one the handoff delivers`() {
        // The identity this backend can cite is exactly the pinned one in its own requirement —
        // no invented version, no filename-derived label.
        val pinned = WhisperCppBackend().requiredModel.files.single().manifestId
        assertEquals("the requirement pins the citable identity", manifestId, pinned)
    }
}
