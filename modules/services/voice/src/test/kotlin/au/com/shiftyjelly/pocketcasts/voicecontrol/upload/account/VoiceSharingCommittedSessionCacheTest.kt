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
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity as PcLoginIdentity

/**
 * The committed-session cache the settings surface's presentation fence reads instead of a live
 * authority lookup.
 *
 * The authority read is a Binder call; the settings surface used to run it on the main thread at
 * construction and once per projection emission. This control proves the cache:
 * 1. Construction performs exactly ONE authority read to seed it.
 * 2. Repeated `lastCommittedSession()` reads perform NO further authority reads — a main-thread
 *    render fence cannot cost an IPC.
 * 3. Each projection-fence commit re-reads the authority exactly once and updates the cache, so
 *    the presentation fence tracks commits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSharingCommittedSessionCacheTest {

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
    fun `the committed session cache seeds once, reads for free, and tracks commits`() {
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

        // Construction seeded the cache with exactly one authority read.
        val afterConstruction = owner.reads.get()
        assertEquals(1, afterConstruction)
        val session = startup.lastCommittedSession()

        // The presentation fence may read it at render frequency: no authority read lands.
        repeat(1_000) { assertEquals(session, startup.lastCommittedSession()) }
        assertEquals("lastCommittedSession must be a cached read, not a lookup", afterConstruction, owner.reads.get())

        // A projection commit re-reads the authority exactly once and updates the cache.
        val projection = checkNotNull(projectionJson())
        startup.state.publish(projection)
        assertEquals(afterConstruction + 1, owner.reads.get())
        assertEquals(realAuth.authoritySnapshot().session, startup.lastCommittedSession())

        // And the free-read property still holds after the update.
        repeat(1_000) { startup.lastCommittedSession() }
        assertEquals(afterConstruction + 1, owner.reads.get())
        server.shutdown()
    }
}
