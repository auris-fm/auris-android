package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.projectionJson
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity switch control drives a REAL VoiceSharingAccountState through the REAL owner-lock
 * transaction shape: a mutator flips account+epoch+token as ONE transaction while readers resolve
 * credentials for admitted epochs. The invariant under test: every resolved (bearer, epoch) is one
 * of the consistently published pairs - a mixture (old bearer, new epoch) is unconstructible when
 * the projection and the token are read inside the same transaction.
 */
class UploadIdentityAuthorityTest {

    private val lock = Any()

    @Volatile private var liveSnapshot = AuthoritySnapshot(accountName = "a@x", session = 1, credential = 1, token = AccessToken("Bearer-A"))

    /** Mirrors the production transaction: the snapshot is computed and returned under the lock. */
    private fun <T> underOwnerTransaction(block: (AuthoritySnapshot) -> T): T = synchronized(lock) { block(liveSnapshot) }

    private fun publish(epoch: String?, bearer: String, account: String, session: Long) = synchronized(lock) {
        val st = state
        val token = st.sessionStarted(session)
        val ticket = st.beginOperation(token, requireProjection = false, ownerSession = session)!!
        assertTrue(st.applyOutcome(token, ticket, projectionJson(epoch = epoch), ownerSession = session))
        liveSnapshot = AuthoritySnapshot(account, session, session, AccessToken(bearer))
    }

    private val state = VoiceSharingAccountState()
    private val authority = UploadIdentityAuthority(state, ::underOwnerTransaction)

    @Test
    fun `a switch inside acquisition never yields a mixed bearer-epoch pair`() {
        publish("epoch-1", "Bearer-A", "a@x", 1)
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        val mixed = AtomicInteger(0)

        val mutator = Thread {
            var flip = false
            while (!stop.get()) {
                flip = !flip
                if (flip) publish("epoch-2", "Bearer-B", "b@x", 2) else publish("epoch-1", "Bearer-A", "a@x", 1)
                Thread.sleep(1)
            }
        }
        val reader = Thread {
            while (!stop.get()) {
                for (admitted in listOf("epoch-1", "epoch-2")) {
                    val resolved = authority.credentialFor(admitted)
                    reads.incrementAndGet()
                    when {
                        // No live identity for this epoch: retaining is the only correct outcome.
                        liveSnapshot.accountName == null -> assertNull(resolved)

                        resolved != null -> {
                            val consistent =
                                (resolved == ("Bearer-A" to "epoch-1")) || (resolved == ("Bearer-B" to "epoch-2"))
                            if (!consistent) mixed.incrementAndGet()
                        }
                    }
                }
            }
        }
        mutator.start()
        reader.start()
        Thread.sleep(700)
        stop.set(true)
        mutator.join(5_000)
        reader.join(5_000)

        assertTrue("the control actually raced (reads=$reads)", reads.get() > 100)
        assertTrue("no mixed pair was ever resolved (mixed=${mixed.get()})", mixed.get() == 0)
    }

    @Test
    fun `a live identity whose epoch differs from the admitted one resolves to null`() {
        publish("epoch-2", "Bearer-B", "b@x", 2)
        assertNull("epoch-1 is retired: retain, never send", authority.credentialFor("epoch-1"))
        assertTrue(authority.credentialFor("epoch-2") == ("Bearer-B" to "epoch-2"))
    }

    @Test
    fun `no owner token resolves to null even when the epoch matches`() {
        publish("epoch-1", "Bearer-A", "a@x", 1)
        liveSnapshot = AuthoritySnapshot("a@x", 1, 1, null)
        assertNull(authority.credentialFor("epoch-1"))
    }
}
