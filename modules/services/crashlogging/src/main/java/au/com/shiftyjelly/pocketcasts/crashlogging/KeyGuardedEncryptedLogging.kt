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
 * BOTH enqueue and upload are guarded, and both are needed. Enqueue stops new logs
 * from being recorded; upload stops rows persisted by an EARLIER build that had a
 * real key from being encrypted now that the key is gone -- InitializeRemoteLogging
 * calls uploadEncryptedLogs unconditionally at startup, so without that override a
 * downgrade to an unconfigured build crashes on launch.
 *
 * Reset and the result flow stay delegated: they touch local persistence only, and
 * refusing them would strand stale in-progress rows for no benefit.
 *
 * [tryEnqueueLog] reports acceptance so callers describing a log elsewhere depend on
 * the capability, not on recognising this type.
 */
class KeyGuardedEncryptedLogging(
    private val delegate: EncryptedLogging,
    encryptedLoggingKey: String,
) : EncryptedLogging by delegate,
    LogQueueing {

    /** Whether this instance will queue or upload logs with the configured key. */
    val acceptsLogs: Boolean = runCatching {
        Base64.decode(encryptedLoggingKey, Base64.DEFAULT).size == PUBLIC_KEY_BYTES
    }.getOrDefault(false)

    override fun enqueueSendingEncryptedLogs(uuid: String, file: File, shouldUploadImmediately: Boolean) {
        tryEnqueueLog(uuid, file, shouldUploadImmediately)
    }

    override fun tryEnqueueLog(uuid: String, file: File, shouldUploadImmediately: Boolean): Boolean {
        if (!acceptsLogs) return false
        delegate.enqueueSendingEncryptedLogs(uuid, file, shouldUploadImmediately)
        return true
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
