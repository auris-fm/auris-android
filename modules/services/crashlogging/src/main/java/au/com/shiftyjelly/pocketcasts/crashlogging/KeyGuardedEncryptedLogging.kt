package au.com.shiftyjelly.pocketcasts.crashlogging

import android.util.Base64
import com.automattic.encryptedlogging.EncryptedLogging
import java.io.File

/**
 * Refuses to queue or upload encrypted logs unless the configured key is usable.
 *
 * The release reads ENCRYPTION_KEY from a secret that defaults to "", so an
 * unconfigured build carries an empty key. encryptedlogging 1.1.1 validates the
 * recipient key with Box.Checker.checkPublicKey, which accepts ONLY 32 bytes, and
 * throws IllegalArgumentException otherwise. That throw is inside
 * EncryptedLogStore.uploadEncryptedLog, whose catch covers UnsatisfiedLinkError
 * alone, running on a SupervisorJob + Dispatchers.IO scope with no
 * CoroutineExceptionHandler -- so it reaches the thread's default handler and
 * kills the process. Nothing about "no secret" should be able to do that.
 *
 * Only the two entry points that lead to encryption are guarded. Resetting upload
 * states and observing results touch local persistence only, so they stay working:
 * refusing them would strand stale in-progress rows for no benefit.
 */
class KeyGuardedEncryptedLogging(
    private val delegate: EncryptedLogging,
    encryptedLoggingKey: String,
) : EncryptedLogging by delegate {

    /**
     * Whether this instance will queue or upload logs. Callers that describe a log
     * to the outside world (the crash-data provider's `uuid` extra) must consult
     * this, or they point triage at a log that will never exist.
     */
    val acceptsLogs: Boolean = runCatching {
        Base64.decode(encryptedLoggingKey, Base64.DEFAULT).size == PUBLIC_KEY_BYTES
    }.getOrDefault(false)

    override fun enqueueSendingEncryptedLogs(uuid: String, file: File, shouldUploadImmediately: Boolean) {
        if (!acceptsLogs) return
        delegate.enqueueSendingEncryptedLogs(uuid, file, shouldUploadImmediately)
    }

    override fun uploadEncryptedLogs() {
        if (!acceptsLogs) return
        delegate.uploadEncryptedLogs()
    }

    private companion object {
        /** libsodium's crypto_box public key length; the SDK accepts no other. */
        const val PUBLIC_KEY_BYTES = 32
    }
}
