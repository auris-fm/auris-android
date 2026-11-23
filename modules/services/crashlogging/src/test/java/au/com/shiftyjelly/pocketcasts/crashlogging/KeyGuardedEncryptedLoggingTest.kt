package au.com.shiftyjelly.pocketcasts.crashlogging

import android.util.Base64
import au.com.shiftyjelly.pocketcasts.crashlogging.fakes.FakeEncryptedLogging
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An empty or wrong-length ENCRYPTION_KEY must not reach the SDK's upload path.
 *
 * encryptedlogging 1.1.1 checks the recipient key with Box.Checker.checkPublicKey,
 * which accepts ONLY 32 bytes, and throws IllegalArgumentException otherwise. That
 * throw happens inside EncryptedLogStore.uploadEncryptedLog, whose catch covers
 * UnsatisfiedLinkError alone, on a SupervisorJob + Dispatchers.IO scope with no
 * CoroutineExceptionHandler -- so it reaches the thread's default handler and kills
 * the process. The release builds this key from a secret that defaults to "", so
 * "no secret configured" must mean "no upload", never "upload and crash".
 *
 * Robolectric because the guard decodes with android.util.Base64, exactly as the
 * SDK does (AutomatticEncryptedLogging calls Base64.decode(key, 0)); a JVM stub
 * would test a different decoder than production uses.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
internal class KeyGuardedEncryptedLoggingTest {

    private val delegate = FakeEncryptedLogging()
    private val file = File("debug.log")

    private fun keyOfLength(bytes: Int): String = Base64.encodeToString(ByteArray(bytes) { it.toByte() }, Base64.NO_WRAP)

    private fun guarded(key: String) = KeyGuardedEncryptedLogging(delegate, key)

    @Test
    fun `an empty key does not enqueue logs`() {
        guarded("").enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)

        assertTrue(delegate.toUpload.isEmpty())
    }

    @Test
    fun `an empty key does not upload logs`() {
        // Queue through the delegate directly: the guard must also stop an upload
        // of anything already persisted by an earlier build that had a real key.
        delegate.enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)

        guarded("").uploadEncryptedLogs()

        assertTrue(delegate.uploaded.isEmpty())
    }

    @Test
    fun `a key that is not 32 bytes does not enqueue logs`() {
        for (length in listOf(1, 16, 31, 33, 64)) {
            val fake = FakeEncryptedLogging()
            KeyGuardedEncryptedLogging(fake, keyOfLength(length))
                .enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)

            assertTrue("length $length must be refused", fake.toUpload.isEmpty())
        }
    }

    @Test
    fun `a key that is not 32 bytes does not upload logs`() {
        for (length in listOf(1, 16, 31, 33, 64)) {
            val fake = FakeEncryptedLogging()
            fake.enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)

            KeyGuardedEncryptedLogging(fake, keyOfLength(length)).uploadEncryptedLogs()

            assertTrue("length $length must be refused", fake.uploaded.isEmpty())
        }
    }

    @Test
    fun `a valid 32-byte key enqueues and uploads normally`() {
        val sut = guarded(keyOfLength(32))

        sut.enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)
        assertEquals(1, delegate.toUpload.size)

        sut.uploadEncryptedLogs()
        assertEquals(1, delegate.uploaded.size)
    }

    @Test
    fun `a key that decodes to nothing is refused`() {
        // android.util.Base64 is lenient: it SKIPS characters outside the alphabet
        // rather than throwing ("not!valid!base64!!" decodes to 10 bytes). So the
        // length check is what protects us, and an all-invalid key decodes to zero.
        val sut = guarded("!!!!")

        sut.enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)
        sut.uploadEncryptedLogs()

        assertTrue(delegate.toUpload.isEmpty())
        assertTrue(delegate.uploaded.isEmpty())
    }

    @Test
    fun `a truncated key that still decodes is refused`() {
        // A secret cut in half by a transport problem decodes fine but is short.
        // This is the #71 failure shape (wrapped/truncated base64) applied here.
        val full = keyOfLength(32)
        val sut = guarded(full.substring(0, full.length / 2))

        sut.enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)
        sut.uploadEncryptedLogs()

        assertTrue(delegate.toUpload.isEmpty())
        assertTrue(delegate.uploaded.isEmpty())
    }

    @Test
    fun `resetting upload states still works without a key`() {
        // Reset only touches local persistence, never the key, so refusing it
        // would leave stale in-progress rows behind for no reason.
        delegate.enqueueSendingEncryptedLogs("uuid", file, shouldUploadImmediately = true)

        guarded("").resetUploadStates()

        assertEquals(FakeEncryptedLogging.UploadState.NOT_STARTED, delegate.toUpload.single().second)
    }

    @Test
    fun `the guard reports whether it will accept logs`() {
        // The crash-data provider needs this to avoid attaching a log uuid that
        // points at a log which will never be queued.
        assertFalse(guarded("").acceptsLogs)
        assertFalse(guarded(keyOfLength(31)).acceptsLogs)
        assertTrue(guarded(keyOfLength(32)).acceptsLogs)
    }

    @Test
    fun `observing upload results still works without a key`() {
        // Delegation must survive the guard: collectors subscribe at startup
        // regardless of whether a key is configured.
        assertNotNull(guarded("").observeEncryptedLogsUploadResult())
    }
}
