package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.sync.TokenErrorNotification
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * THE ORDINARY STARTUP ACQUISITION (spec ecc411b8): `initializeOnAppStart` previously installed
 * binding/listeners but never fetched the projection, and `refreshProjection` was reachable only
 * from the Settings surface — so "first genuine ordinary answer on reopen" was not actually wired,
 * and a spoken phrase would predictably refuse with no-saved-choice.
 *
 * WHAT IS CLAIMED:
 * 1. A signed-in cold start with NO saved choice for the account performs EXACTLY ONE ordinary
 *    projection GET, through the EXISTING funnel, and the applied answer persists the saved choice
 *    (here: the server's real not-enrolled state). No Settings visit, no toggle.
 * 2. A saved KNOWN choice (any state) collects immediately: the startup performs NO fetch — the
 *    this-process GET is never a prerequisite.
 *
 * NOT claimed: that a failed/refused fetch fabricates consent (nothing is saved; the next process
 * launch may attempt once more — once per process per account, never a loop), and that any
 * admission changed: off choices, local stops and account fences are separate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupOrdinaryChoiceAcquisitionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun realAuth(): SyncAccountManagerImpl = SyncAccountManagerImpl(
        tokenErrorNotification = TokenErrorNotification(mock(EventHorizon::class.java), context),
        accountManager = AccountManager.get(context),
    )

    private fun addAccount(auth: SyncAccountManagerImpl, email: String = "user@example.com", token: String = "t1") {
        auth.addAccount(email, "uuid-1", RefreshToken("refresh-1"), AccessToken(token), au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity.PocketCasts)
    }

    private fun projectionJson(): String = buildString {
        append("{\n\"schema_version\":1,")
        append("\n\"plan\":\"free\",")
        append("\n\"voice_sharing_enrolled\":false,")
        append("\n\"enrollment_epoch\":null,")
        append("\n\"accepted_disclosure_version\":null,")
        append("\n\"withdrawal_state\":\"not_enrolled\"}")
    }

    private fun startup(auth: SyncAccountManagerImpl, server: MockWebServer, store: SavedSharingChoiceStore): VoiceSharingAccountStartup {
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient()).build()
        return VoiceSharingAccountStartup(
            ownership = auth,
            context = context,
            retrofit = Lazy { retrofit },
            // The SAME Auris-scoped stub the other owner-transaction controls use: the bearer is
            // minted from the real owner so the ordinary GET actually leaves for the server.
            aurisToken = ownerToken(auth),
            savedSharingChoice = store,
        )
    }

    private fun awaitSavedChoice(store: SavedSharingChoiceStore, account: String): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? {
        // The acquisition runs on its own dispatcher; the SERVER REQUEST is awaited deterministically
        // (takeRequest), so this poll only covers the local apply, which follows it directly.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            store.read(account)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `a signed-in cold start with no saved choice performs exactly one ordinary refresh and persists its answer`() {
        val auth = realAuth()
        addAccount(auth)
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        val account = "user@example.com"
        assertNull("precondition: no saved choice for this account", store.read(account))

        startup(auth, server, store).initializeOnAppStart()

        // The ONE ordinary GET actually left the device.
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("the startup must fetch the projection once when no choice is saved", request)
        assertEquals("/api/v1/account/voice-sharing", request!!.path)

        // The applied answer mirrors into the saved choice through the existing funnel.
        val saved = awaitSavedChoice(store, account)
        assertNotNull("the fetched answer must persist as the account's saved choice", saved)
        assertEquals(SharingChoiceState.NOT_ENROLLED, saved!!.state)
        assertEquals("the saved record must carry THIS account", account, saved.account)

        // ONCE per process, not a loop: no further request arrives.
        assertNull(
            "the acquisition must not poll",
            server.takeRequest(300, TimeUnit.MILLISECONDS),
        )
        assertEquals("exactly one fetch per process per account", 1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `a saved known choice collects immediately with no fetch prerequisite`() {
        val auth = realAuth()
        addAccount(auth)
        val server = MockWebServer().apply { start() }
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        val account = "user@example.com"
        assertTrue("precondition: write a saved (withdrawn) choice", store.recordWithdrawn(account))
        assertNotNull(store.read(account))

        startup(auth, server, store).initializeOnAppStart()

        assertNull(
            "a saved known choice must never trigger a startup fetch",
            server.takeRequest(1, TimeUnit.SECONDS),
        )
        assertEquals(0, server.requestCount)
        server.shutdown()
    }
}
