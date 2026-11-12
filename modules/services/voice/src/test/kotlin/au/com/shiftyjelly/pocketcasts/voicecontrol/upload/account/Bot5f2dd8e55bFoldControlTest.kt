package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.sync.TokenErrorNotification
import au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * Controls for the bot review of `5f2dd8e55b` (fold commit):
 *
 * 1. ONE account read per answer (blocking finding, `apply`): the admission check and the
 *    persist must use the SAME account snapshot, so an account switch landing between the two
 *    reads cannot persist the old account's ENROLLED answer under the new account's name.
 * 2. Session end clears the projection (blocking finding, `VoiceSharingAccountStartup`): the
 *    row and "Delete shared data" read the projection, so it must die with the session instead
 *    of rendering the previous account's enrollment for the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Bot5f2dd8e55bFoldControlTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun realAuth(): SyncAccountManagerImpl = SyncAccountManagerImpl(
        tokenErrorNotification = TokenErrorNotification(mock(EventHorizon::class.java), context),
        accountManager = AccountManager.get(context),
    )

    private fun addAccount(auth: SyncAccountManagerImpl, email: String) {
        auth.addAccount(email, "uuid-$email", RefreshToken("refresh-$email"), AccessToken("token-$email"), LoginIdentity.PocketCasts)
    }

    private fun enrolledJson(): String = """
        {"schema_version":1,"plan":"free","voice_sharing_enrolled":true,
         "enrollment_epoch":"epoch-1","accepted_disclosure_version":"voice-sharing-v1",
         "withdrawal_state":"active"}
    """.trimIndent()

    // ---- Control 1: single account snapshot across check and persist -------------------------

    @Test
    fun `an answer persists under the account read once, not a later different one`() {
        val server = MockWebServer().apply { start() }
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient()).build())
        val state = VoiceSharingAccountState()
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(enrolledJson())
        }

        val reads = java.util.concurrent.atomic.AtomicInteger()
        // switchAfterNextRead: once flipped, the FIRST read still answers account-a and every
        // LATER read answers account-b — a switch landing between the old code's two reads.
        val switchAfterNextRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val operations = VoiceSharingAccountOperations(
            api = api,
            state = state,
            currentAccountName = {
                val n = reads.getAndIncrement()
                // issue() and the answer's own admission read answer a; anything AFTER that
                // single snapshot (the old code's second read at persist time, after issue()'s
                // own two) answers b.
                if (switchAfterNextRead.get() && n >= 3) "b@example.com" else "a@example.com"
            },
            aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
                override suspend fun currentToken(): String? = "auris-token"
                override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block("auris-token")
            },
            savedSharingChoice = store,
        )

        var result: VoiceSharingMutationResult
        runBlocking {
            // A live projection is a precondition for the PUT (the switch reads the current state).
            switchAfterNextRead.set(false)
            assertTrue("the current state must be readable", operations.refreshProjection())
            reads.set(0)
            switchAfterNextRead.set(true)
            result = operations.setVoiceSharing(enrolled = true, acceptedDisclosureVersion = "voice-sharing-v1")
        }

        assertEquals("the mutation answer must apply", VoiceSharingMutationResult.Applied::class, result::class)
        assertEquals(
            "the ENROLLED record must sit under the answer's OWN account, never a later read's",
            SharingChoiceState.ENROLLED,
            store.read("a@example.com")?.state,
        )
        assertNull("no record may be written for the switched-to account", store.read("b@example.com"))
        server.shutdown()
    }

    // ---- Control 2: session end clears the projection -----------------------------------------

    @Test
    fun `an account switch clears the previous session's projection`() {
        val auth = realAuth()
        addAccount(auth, "a@example.com")
        val server = MockWebServer().apply { start() }
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient()).build()
        val startup = VoiceSharingAccountStartup(
            syncAccountManager = auth,
            context = context,
            retrofit = Lazy { retrofit },
            aurisToken = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
                override suspend fun currentToken(): String? = "auris-token"
                override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block("auris-token")
            },
            savedSharingChoice = store,
        )
        startup.initializeOnAppStart()

        // The old session had a live projection (as after any applied answer).
                startup.state.publish(VoiceSharingAccountProjectionTestFixtures.enrolled()!!)

        // A real session end: the signed-in email disappears (logout).
        auth.signOut()
        startup.reconcileAndNotifySessionEnd()

        assertNull(
            "the projection must be cleared on session end so the next account never renders the previous one",
            startup.state.projection.value,
        )
        server.shutdown()
    }
}

object VoiceSharingAccountProjectionTestFixtures {
    fun enrolled(): VoiceSharingAccountProjection? = ProjectionJsonParser.parse(
        """
        {"schema_version":1,"plan":"free","voice_sharing_enrolled":true,
         "enrollment_epoch":"epoch-1","accepted_disclosure_version":"voice-sharing-v1",
         "withdrawal_state":"active"}
        """.trimIndent(),
    )
}
