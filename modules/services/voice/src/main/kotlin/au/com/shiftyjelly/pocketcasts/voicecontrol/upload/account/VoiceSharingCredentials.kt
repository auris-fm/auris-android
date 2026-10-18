package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.concurrent.atomic.AtomicReference

/**
 * A credential INCARNATION: an opaque, reference-identity handle for one published bearer
 * value. The bearer string is only readable inside the account boundary; nothing else is
 * derived from it and it is never logged or serialized.
 */
class CredentialIncarnation internal constructor(internal val bearer: String?)

/**
 * Production credential owner for the voice-sharing account boundary (reviewer 8f1d23c4):
 * the state lock cannot make an ALREADY-READ current bearer atomic with credential rotation,
 * so the compare-and-invalidate is owned HERE, tied to the rejected credential's incarnation.
 * Rotation is itself atomic: whoever rotates last wins, and a stale rejection can never
 * invalidate a fresh incarnation.
 */
class VoiceSharingCredentialStore {

    private val current = AtomicReference<CredentialIncarnation?>(null)

    /** Publishes a new incarnation for `bearer` (null clears). Rotation is atomic. */
    fun publish(bearer: String?): CredentialIncarnation? = current.updateAndGet { previous ->
        when {
            bearer == null -> null

            previous?.bearer == bearer -> previous

            // same bearer: SAME incarnation, not a new one
            else -> CredentialIncarnation(bearer)
        }
    }

    fun current(): CredentialIncarnation? = current.get()

    /** ATOMIC compare-and-invalidate: true only if `rejected` is STILL the current incarnation. */
    fun invalidateIfCurrent(rejected: CredentialIncarnation): Boolean = current.compareAndSet(rejected, null)
}
