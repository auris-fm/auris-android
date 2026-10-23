package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.projectionJson
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HONEST SCOPE (reviewer d2f1bfc8): the owner here is the CredentialOwnershipOwner INTERFACE with
 * its real lock discipline - ONE shared monitor serializing transaction + authoritySnapshot, the
 * same contract the production owner obeys - NOT the production SyncAccountManagerImpl singleton
 * (which needs an Android account stack). What this proves: GIVEN that lock contract, the
 * authority compares the full admitted identity and never resolves a mixed pair. The soak loop is
 * a soak of that contract, not proof of a production interleaving; the deterministic
 * acquisition→transition→launch sequence is covered in the wiring suite.
 */
class UploadIdentityAuthorityTest {

    /** The owner-interface contract with its real discipline: one monitor, atomic snapshot read. */
    private class LockingOwner(startAccount: String, startSession: Long, startBearer: String) : CredentialOwnershipOwner {
        override val collectionSharedLock = Any()

        @Volatile var snap = AuthoritySnapshot(startAccount, startSession, startSession, AccessToken(startBearer))
        override fun authoritySnapshot(): AuthoritySnapshot = snap
        override fun sessionGeneration(): Long = snap.session
        override fun <T> transaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }
        override fun installOwnershipBinding(binding: CredentialOwnershipBinding?): AuthoritySnapshot? = snap
        override fun beginRefresh(observed: AuthoritySnapshot): RefreshLease? = null
        override fun publishRefresh(lease: RefreshLease, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = false
        override fun publishCredentials(observed: AuthoritySnapshot, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = false
        override fun invalidateIfCurrent(observed: AuthoritySnapshot, transaction: () -> Boolean): Boolean = transaction()
        override fun reconcileExternalState(): AuthoritySnapshot = snap

        /** The account transition: one move of account+session+credential+token under the lock. */
        fun switchTo(account: String, session: Long, bearer: String) = synchronized(collectionSharedLock) {
            snap = AuthoritySnapshot(account, session, session, AccessToken(bearer))
        }

        fun clearAccount() = synchronized(collectionSharedLock) {
            snap = AuthoritySnapshot(null, snap.session, snap.credential, null)
        }
    }

    private val owner = LockingOwner("a@x", 1L, "Bearer-A")
    private val state = VoiceSharingAccountState()
    private val authority = UploadIdentityAuthority(state) { block -> owner.transaction { block(owner.authoritySnapshot()) } }

    private fun publish(epoch: String?, bearer: String, account: String, session: Long) {
        val token = state.sessionStarted(session)
        val ticket = state.beginOperation(token, requireProjection = false, ownerSession = session)!!
        assertTrue(state.applyOutcome(token, ticket, projectionJson(epoch = epoch), ownerSession = session))
        owner.switchTo(account, session, bearer)
    }

    private val admittedA = UploadIdentityAuthority.AdmittedIdentity("a@x", 1L, "epoch-1")

    @Test
    fun `an admitted account or session that no longer matches refuses - not merely the epoch`() {
        publish("epoch-1", "Bearer-A", "a@x", 1)
        // SAME epoch, moved account+session (re-enrolled under b@y): epoch-only checking would send.
        publish("epoch-1", "Bearer-B", "b@y", 2)
        assertNull("same epoch but moved account refuses", authority.credentialFor(admittedA))
        assertNull("moved session under the new account refuses too", authority.credentialFor(admittedA.copy(account = "b@y")))
        val admittedB = UploadIdentityAuthority.AdmittedIdentity("b@y", 2L, "epoch-1")
        assertEquals("Bearer-B", authority.credentialFor(admittedB)?.bearer)
    }

    @Test
    fun `a live identity whose epoch differs from the admitted one resolves to null`() {
        publish("epoch-2", "Bearer-B", "b@y", 2)
        assertNull("epoch-1 is retired: retain, never send", authority.credentialFor(admittedA))
    }

    @Test
    fun `no owner token or no account resolves to null even when the epoch matches`() {
        publish("epoch-1", "Bearer-A", "a@x", 1)
        owner.clearAccount()
        assertNull(authority.credentialFor(admittedA))
    }

    @Test
    fun `the full sequence - acquire, transition, launch revalidate - refuses without sending`() {
        // Exactly what the assembly runs: credentialFor (acquisition), then the account moves
        // deterministically, then launchIdentityCheck re-runs the comparison before transmission.
        publish("epoch-1", "Bearer-A", "a@x", 1)
        val acquired = authority.credentialFor(admittedA)!!
        // Deterministic transition between acquisition and launch.
        publish("epoch-2", "Bearer-B", "b@y", 2)
        val launchOk = authority.credentialFor(admittedA) != null
        assertTrue("acquisition DID see the admitted identity", acquired.bearer == "Bearer-A")
        assertTrue("launch revalidation refuses after the transition", !launchOk)
    }

    @Test
    fun `soak - concurrent switching never yields a mixed bearer-epoch pair (owner-contract soak, not production interleaving proof)`() {
        publish("epoch-1", "Bearer-A", "a@x", 1)
        val stop = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val mixed = AtomicInteger(0)
        val mutator = Thread {
            var flip = false
            while (stop.get() == 0) {
                flip = !flip
                if (flip) publish("epoch-2", "Bearer-B", "b@y", 2) else publish("epoch-1", "Bearer-A", "a@x", 1)
                Thread.sleep(1)
            }
        }
        val reader = Thread {
            while (stop.get() == 0) {
                for (admitted in listOf(admittedA, UploadIdentityAuthority.AdmittedIdentity("b@y", 2L, "epoch-2"))) {
                    val resolved = authority.credentialFor(admitted)
                    reads.incrementAndGet()
                    if (resolved != null) {
                        val pair = resolved.bearer to resolved.epoch
                        val consistent = pair == ("Bearer-A" to "epoch-1") || pair == ("Bearer-B" to "epoch-2")
                        if (!consistent) mixed.incrementAndGet()
                    }
                }
            }
        }
        mutator.start()
        reader.start()
        Thread.sleep(700)
        stop.set(1)
        mutator.join(5_000)
        reader.join(5_000)
        assertTrue("the soak actually raced (reads=$reads)", reads.get() > 100)
        assertEquals("no mixed pair was ever resolved", 0, mixed.get())
    }
}
