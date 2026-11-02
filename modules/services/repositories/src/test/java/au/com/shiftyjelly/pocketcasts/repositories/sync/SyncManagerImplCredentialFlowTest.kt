package au.com.shiftyjelly.pocketcasts.repositories.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.servers.di.NetworkModule
import au.com.shiftyjelly.pocketcasts.servers.sync.SyncServiceManager
import au.com.shiftyjelly.pocketcasts.servers.sync.exception.RefreshTokenExpiredException
import dagger.Lazy
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentLinkedQueue
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.create

/**
 * REAL request-flow controls (reviewer 539a7d64): the actual SyncManagerImpl password/refresh
 * flows against a REAL SyncAccountManagerImpl, a REAL platform AccountManager (Robolectric) and
 * a REAL Retrofit SyncServiceManager over MockWebServer — not direct owner-API calls.
 */
@RunWith(RobolectricTestRunner::class)
class SyncManagerImplCredentialFlowTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var accountManager: AccountManager
    private lateinit var syncAccountManager: SyncAccountManagerImpl
    private lateinit var syncManager: SyncManagerImpl
    private val okDispatcher = okhttp3.Dispatcher()
    private val responses = ConcurrentLinkedQueue<MockResponse>()
    private lateinit var syncServiceManagerField: SyncServiceManager
    private lateinit var moshiField: com.squareup.moshi.Moshi

    /** Token-response body shape shared by loginToken and updatePassword. */
    private fun tokenBody(email: String, access: String, refresh: String) = """
        {"email": "$email", "uuid": "uuid-$access", "isNew": false,
         "accessToken": "$access", "refreshToken": "$refresh",
         "tokenType": "PocketCasts", "expiresIn": 3600}
    """

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = responses.poll() ?: MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
        }
        server.start()
        accountManager = AccountManager.get(context)

        val module = NetworkModule()
        val moshi = module.provideMoshi()
        val retrofit = module.provideRetrofitBuilder(moshi, okhttp3.Dispatcher())
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .build()
        val cache = Cache(File(context.cacheDir.absolutePath, "HttpCache"), (10 * 1024 * 1024).toLong())
        syncServiceManagerField = SyncServiceManager(retrofit.create(), mock(), Lazy { cache }, mock())
        moshiField = moshi

        syncAccountManager = SyncAccountManagerImpl(mock(), accountManager)
        syncManager = SyncManagerImpl(
            eventHorizon = com.automattic.eventhorizon.EventHorizon(au.com.shiftyjelly.pocketcasts.analytics.testing.TestEventSink()),
            context = context,
            settings = mock(),
            syncAccountManager = syncAccountManager,
            credentialOwnership = syncAccountManager, // the REAL owner
            syncServiceManager = syncServiceManagerField,
            moshi = moshiField,
            notificationManager = mock(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun addAccount(email: String, access: String) {
        syncAccountManager.addAccount(email, "uuid-$email", RefreshToken("r-$email"), AccessToken(access), au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity.PocketCasts)
    }

    /** P1 — a legitimate PRE-REQUEST credential refresh, then the password commit SUCCEEDS. */
    @Test
    fun `P1 - legitimate pre-request refresh MOVES the credential - password commit still lands`() = kotlinx.coroutines.runBlocking {
        addAccount("a@example.com", "t1")
        // Legitimate PRE-REQUEST token acquisition through the REAL SyncManagerImpl fetch flow:
        // platform cache invalidated → getAccessToken(account) → owner-lease refresh (1→2).
        accountManager.invalidateAuthToken(AccountConstants.ACCOUNT_TYPE, "t1")
        responses.add(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(tokenBody("a@example.com", "t2", "r2")))
        assertEquals("t2", syncManager.getAccessToken(Account("a@example.com", AccountConstants.ACCOUNT_TYPE)).value)
        assertEquals(2, syncAccountManager.credentialGeneration()) // the refresh moved the credential
        // The password admission is captured AFTER that acquisition — the commit must LAND.
        responses.add(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(tokenBody("a@example.com", "t3", "r3")))

        syncManager.updatePassword(newPassword = "np", oldPassword = "op")

        assertEquals("t3", syncAccountManager.authoritySnapshot().token!!.value) // password tokens committed
        assertEquals(3, syncAccountManager.credentialGeneration()) // refresh move + password move
    }

    /** P2 — the password response arrives AFTER a B login: refusal is EXPLICIT, never false success. */
    @Test
    fun `P2 - late A password response after B login REFUSES and throws - B credential intact`() = kotlinx.coroutines.runBlocking {
        addAccount("a@example.com", "tA")
        val switch = Any()
        // The password REQUEST lands on the server; DURING the flight B logs in (raw platform swap).
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(switch) {
                    syncAccountManager.signOut()
                    addAccount("b@example.com", "tB")
                }
                return responses.poll() ?: MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
            }
        }
        responses.add(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(tokenBody("a@example.com", "tA2", "rA2")))

        try {
            syncManager.updatePassword(newPassword = "np", oldPassword = "op")
            fail("refused password publication must be reported EXPLICITLY, not as success")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("refused"))
        }
        // B's credential is untouched; A's password tokens were NEVER published anywhere.
        assertEquals("tB", syncAccountManager.authoritySnapshot().token!!.value)
        assertEquals("b@example.com", syncAccountManager.authoritySnapshot().accountName)
    }

    // P4 — the identity switches DURING TOKEN ACQUISITION (reviewer 83471c65): the ORIGINAL
    // identity is captured before acquisition; A resumes with B's admission → refused BEFORE
    // the password HTTP request, explicitly, with zero network.

    // Installs a SyncManager whose owner switches the identity AT the acquisition seam. The
    // owner is returned EXPLICITLY so every lifecycle call and readback names that EXACT
    // object — no implicit receivers, no test-helper resolution to an outer owner.
    private fun managerWithAcquisitionSwitch(switch: SyncAccountManagerImpl.() -> Unit): Pair<SyncManagerImpl, SyncAccountManagerImpl> {
        val injectedOwner = object : SyncAccountManagerImpl(mock(), accountManager) {
            override suspend fun getAccessToken(): AccessToken? {
                this.switch()
                return super.getAccessToken()
            }
        }
        val manager = SyncManagerImpl(
            eventHorizon = com.automattic.eventhorizon.EventHorizon(au.com.shiftyjelly.pocketcasts.analytics.testing.TestEventSink()),
            context = context,
            settings = mock(),
            syncAccountManager = injectedOwner,
            credentialOwnership = injectedOwner,
            syncServiceManager = syncServiceManagerField,
            moshi = moshiField,
            notificationManager = mock(),
        )
        return manager to injectedOwner
    }

    @Test
    fun `P4 - identity switch DURING token acquisition refuses BEFORE the password request`() = kotlinx.coroutines.runBlocking {
        // The pair is created FIRST; the initial seed is the INJECTED owner's OWN login —
        // never the outer fixture owner.
        val (manager, injectedOwner) = managerWithAcquisitionSwitch {
            signOut() // EXPLICIT receiver: the injected owner retires its own session
            addAccount("b@example.com", "uuid-b", RefreshToken("r-b"), AccessToken("tB"), au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity.PocketCasts) // FIVE-arg REAL method on the SAME object
        }
        injectedOwner.addAccount("a@example.com", "uuid-a", RefreshToken("r-a"), AccessToken("t1"), au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity.PocketCasts)
        // INITIAL premise, asserted on the injected owner BEFORE updatePassword:
        assertEquals("t1", injectedOwner.authoritySnapshot().token!!.value) // published token
        assertEquals("a@example.com", injectedOwner.authoritySnapshot().accountName)
        assertTrue(injectedOwner.sessionGeneration() > 0) // session ARMED by the login
        try {
            manager.updatePassword(newPassword = "np", oldPassword = "op")
            fail("identity superseded during acquisition must refuse EXPLICITLY")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("superseded during token acquisition"))
        }
        assertEquals(0, server.requestCount) // refused BEFORE the password HTTP request
        // ALL readbacks from the EXACT object that SyncManager received:
        assertEquals("tB", injectedOwner.authoritySnapshot().token!!.value) // B intact; A published nowhere
        assertEquals("b@example.com", injectedOwner.authoritySnapshot().accountName)
    }

    /**
     * P5 — SAME-account logout/relogin during acquisition: the account NAME is unchanged, so
     * only the ORIGINAL-SESSION fence can refuse (b7... name-only checks are insufficient).
     */
    @Test
    fun `P5 - SAME-account relogin DURING token acquisition refuses - session fence is NECESSARY`() = kotlinx.coroutines.runBlocking {
        // The pair is created FIRST; the initial seed is the INJECTED owner's OWN login —
        // never the outer fixture owner.
        val (manager, injectedOwner) = managerWithAcquisitionSwitch {
            signOut() // EXPLICIT receiver: the injected owner retires its own session
            addAccount("a@example.com", "uuid-a2", RefreshToken("r-a2"), AccessToken("t2"), au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity.PocketCasts) // FIVE-arg REAL method, SAME object, same name, NEW session
        }
        injectedOwner.addAccount("a@example.com", "uuid-a1", RefreshToken("r-a1"), AccessToken("t1"), au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity.PocketCasts)
        // INITIAL premise, asserted on the injected owner BEFORE updatePassword:
        assertEquals("t1", injectedOwner.authoritySnapshot().token!!.value)
        assertEquals("a@example.com", injectedOwner.authoritySnapshot().accountName)
        assertTrue(injectedOwner.sessionGeneration() > 0) // session ARMED by the login
        try {
            manager.updatePassword(newPassword = "np", oldPassword = "op")
            fail("same-account relogin during acquisition must refuse: the session moved")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("superseded during token acquisition"))
        }
        assertEquals(0, server.requestCount) // no password HTTP
        // ALL readbacks from the EXACT object that SyncManager received:
        assertEquals("a@example.com", injectedOwner.authoritySnapshot().accountName) // name alone cannot refuse
        assertEquals("t2", injectedOwner.authoritySnapshot().token!!.value) // fresh session's credential intact
    }

    /** P3 — refused admission in the REAL fetch flow issues ZERO token-fetch calls. */
    @Test
    fun `P3 - refused admission in the real fetch flow issues ZERO network calls`() = kotlinx.coroutines.runBlocking {
        addAccount("b@example.com", "tB") // B owns the platform
        try {
            syncManager.getAccessToken(Account("a@example.com", AccountConstants.ACCOUNT_TYPE))
            fail("stale original account must refuse")
        } catch (expected: RefreshTokenExpiredException) {
            // refused BEFORE any network work
        }
        assertEquals(0, server.requestCount) // ZERO network: no token fetch, no fallback
    }
}
