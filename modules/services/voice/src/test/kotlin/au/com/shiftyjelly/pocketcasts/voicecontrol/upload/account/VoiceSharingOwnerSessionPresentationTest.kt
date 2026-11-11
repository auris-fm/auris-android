package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.sync.TokenErrorNotification
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity as PcLoginIdentity

/**
 * The presentation-side session source (reviewer simplification S1): the settings surface reads
 * the owner's OWN session-generation atomic — no committed-session cache exists any more.
 *
 * The control proves what the cache was bought for and what the deletion must not lose:
 * 1. Construction performs ZERO authority reads (no cache seeding).
 * 2. `ownerSessionGeneration()` tracks a live session move IMMEDIATELY, with no projection
 *    commit and no authority-move hook — the atomic cannot go stale.
 * 3. A projection commit still delivers the current generation to the enrollment-evidence
 *    listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSharingOwnerSessionPresentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Counts every authority read the startup performs; delegates to the real owner. */
    private class CountingOwner(private val delegate: SyncAccountManagerImpl) : CredentialOwnershipOwner by delegate {
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        override fun authoritySnapshot(): AuthoritySnapshot {
            reads.incrementAndGet()
            return delegate.authoritySnapshot()
        }
    }

    private fun projectionJson(): VoiceSharingAccountProjection? = VoiceSharingAccountState().parseProjection(
        buildString {
            append("{\n\"schema_version\":1,")
            append("\n\"plan\":\"free\",")
            append("\n\"voice_sharing_enrolled\":false,")
            append("\n\"enrollment_epoch\":null,")
            append("\n\"accepted_disclosure_version\":null,")
            append("\n\"withdrawal_state\":\"not_enrolled\"}")
        },
    )

    @Test
    fun `presentation reads the live session generation - no cache, no seed, no staleness`() {
        val realAuth = SyncAccountManagerImpl(
            tokenErrorNotification = TokenErrorNotification(mock(EventHorizon::class.java), context),
            accountManager = AccountManager.get(context),
        )
        realAuth.addAccount(
            "user@example.com",
            "uuid-1",
            RefreshToken("refresh-1"),
            AccessToken("t1"),
            PcLoginIdentity.PocketCasts,
        )
        val owner = CountingOwner(realAuth)
        val server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient()).build()
        val startup = VoiceSharingAccountStartup(
            ownership = owner,
            context = context,
            retrofit = Lazy { retrofit },
            aurisToken = ownerToken(realAuth),
            savedSharingChoice = SavedSharingChoiceStore(InMemoryChoicePersistence()),
        )

        // Construction performs NO authority read: there is no cache to seed.
        assertEquals("no cache seeding: construction must not read the authority", 0, owner.reads.get())

        // Presentation reads the live generation...
        val session = startup.ownerSessionGeneration()
        assertEquals(realAuth.authoritySnapshot().session, session)

        // ...and a live session move (a new account, no projection commit, no authority-move
        // hook) is visible on the NEXT read: the atomic cannot go stale.
        realAuth.addAccount(
            "user2@example.com",
            "uuid-2",
            RefreshToken("refresh-2"),
            AccessToken("t2"),
            PcLoginIdentity.PocketCasts,
        )
        assertEquals("a session move must be visible immediately", realAuth.authoritySnapshot().session, startup.ownerSessionGeneration())
        assertNotEquals(session, startup.ownerSessionGeneration())

        // A projection commit still delivers the CURRENT generation downstream.
        var evidenceSession = -1L
        startup.enrollmentEvidenceListener = { _, sessionArg -> evidenceSession = sessionArg }
        startup.state.publish(checkNotNull(projectionJson()))
        assertEquals(realAuth.authoritySnapshot().session, evidenceSession)
        server.shutdown()
    }
}
