package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.sync.TokenErrorNotification
import au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity
import com.automattic.eventhorizon.EventHorizon
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * REAL-owner integration controls (reviewer a75df7ef / 2008ea39): the boundary runs against the
 * actual SyncAccountManagerImpl over a scripted Robolectric AccountManager. Determinism comes
 * from INTERNAL barriers (CompletableDeferred between the request boundary and the credential
 * write), NOT from wall-clock sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSharingAccountRealOwnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun realAuth(): SyncAccountManagerImpl {
        val auth = SyncAccountManagerImpl(
            tokenErrorNotification = TokenErrorNotification(mock(EventHorizon::class.java), context),
            accountManager = AccountManager.get(context),
        )
        auth.addAccount("user@example.com", "uuid-1", RefreshToken("refresh-1"), AccessToken("t1"), LoginIdentity.PocketCasts)
        return auth
    }

    private fun peekToken(auth: SyncAccountManagerImpl): String? = auth.accessTokenSnapshot()?.token?.value

    /** Keep the projection body EXACTLY as the other account controls parse it. */
    private fun projectionJson(): String = buildString {
        append("{\"schema_version\":1,")
        append("\"membership_revision\":\"rev-1\",")
        append("\"plan\":\"free\",")
        append("\"voice_sharing_enrolled\":false,")
        append("\"enrollment_epoch\":null,")
        append("\"accepted_disclosure_version\":null,")
        append("\"withdrawal_state\":\"not_enrolled\"}")
    }

    private fun bindingFor(auth: SyncAccountManagerImpl, state: VoiceSharingAccountState): VoiceSharingAccountAuthBinding = VoiceSharingAccountAuthBinding(auth, auth, VoiceSharingAccountSessionCoordinator(state, VoiceSharingCredentialStore()))

    private fun operations(binding: VoiceSharingAccountAuthBinding, state: VoiceSharingAccountState, server: MockWebServer): VoiceSharingAccountOperations = VoiceSharingAccountOperations(
        api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
        state = state,
        ownership = binding,
        credentialSource = { binding.syncCredential() },
        onCredentialInvalid = { },
    )

    @Test
    fun `a committed 401 destroys the REAL platform token and advances the generation EXACTLY once`() = runBlocking {
        val auth = realAuth()
        val state = VoiceSharingAccountState()
        val server = MockWebServer().apply { start() }
        val binding = bindingFor(auth, state)
        val operations = operations(binding, state, server)
        val token = binding.loginFromAuth() // the session is born at the REAL owner's session incarnation

        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(operations.refreshProjection(token))
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)

        // The REAL platform token is destroyed and the real generation advanced EXACTLY once
        // (account creation is write #1; the committed 401 is write #2).
        assertNull(peekToken(auth))
        assertEquals(2L, auth.credentialGeneration())
        assertFalse(state.hasCurrentSessionProjection()) // scoped session cleared with it
        server.shutdown()
    }

    @Test
    fun `a REAL refresh write between the freeze and the 401 is fenced - fresh token AND armed session survive`() = runBlocking {
        val auth = realAuth()
        val state = VoiceSharingAccountState()
        val server = MockWebServer().apply { start() }
        val release = CompletableFuture<Unit>()
        val requestSeen = CompletableFuture<Boolean>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/account/voice-sharing") == true) {
                    return MockResponse().setResponseCode(200).setBody(projectionJson())
                }
                requestSeen.complete(true)
                release.get(10, TimeUnit.SECONDS) // deterministic internal barrier, no sleeps
                return MockResponse().setResponseCode(401)
            }
        }
        val binding = bindingFor(auth, state)
        val operations = operations(binding, state, server)
        val token = binding.loginFromAuth() // the session is born at the REAL owner's session incarnation

        assertTrue(operations.refreshProjection(token))
        val result = java.util.concurrent.CompletableFuture.supplyAsync {
            runBlocking { operations.enroll(token, acceptedDisclosureVersion = "v3") }
        }
        assertTrue(requestSeen.get(10, TimeUnit.SECONDS))
        // REAL refresh write: the platform token rotates BEHIND the in-flight request.
        auth.setAccessToken(AccessToken("t2"))
        release.complete(Unit)
        val outcome = result.get(15, TimeUnit.SECONDS)

        assertTrue(outcome is VoiceSharingMutationResult.Failure)
        assertEquals("t2", peekToken(auth)) // the FRESH real credential survived the stale 401
        assertTrue(state.hasCurrentSessionProjection()) // ...and the scoped session with it
        // Only the REAL refresh advanced the real generation (write #1 add, #2 refresh); the
        // fenced stale 401 advanced NOTHING.
        assertEquals(2L, auth.credentialGeneration())
        server.shutdown()
    }

    @Test
    fun `a REAL signOut revokes the projection SYNCHRONOUSLY at the next authority read`() = runBlocking {
        val auth = realAuth()
        val state = VoiceSharingAccountState()
        val server = MockWebServer().apply { start() }
        val binding = bindingFor(auth, state)
        val operations = operations(binding, state, server)
        val token = binding.loginFromAuth()

        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        assertTrue(state.hasCurrentSessionProjection())

        auth.signOut() // REAL platform sign-out: owner session incarnation moves NOW

        // The next authority read revokes synchronously - no network, no collector timing.
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)
        assertFalse(state.hasCurrentSessionProjection())
        assertEquals(1, server.requestCount) // ONLY the arming GET happened - no request left the boundary
        server.shutdown()
    }

    @Test
    fun `an EXTERNAL platform rotation inside the owner peek-commit gap refuses the WHOLE transaction`() = runBlocking {
        val auth = realAuth()
        val state = VoiceSharingAccountState()
        val server = MockWebServer().apply { start() }
        val binding = bindingFor(auth, state)
        val operations = operations(binding, state, server)
        val token = binding.loginFromAuth()
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))

        val ticket = state.beginOperation(token, requireProjection = true)!!
        val rejected = binding.syncCredential()!! // exactly the incarnation an in-flight op freezes
        val platform = AccountManager.get(context)
        val account = auth.getAccount()!!
        val committed = binding.invalidateIfCurrent(rejected) { commitGuard ->
            // EXTERNAL rotation, bypassing the ownership contract entirely (the raw platform
            // write an authenticator could make) — INSIDE the peek->commit gap.
            platform.setAuthToken(account, AccountConstants.TOKEN_TYPE, "t2")
            state.processUnauthorized(token, ticket, commitGuard)
        }

        // The commit guard saw the rotation at the commit INSTANT: NOTHING was cleared.
        assertFalse(committed)
        assertEquals("t2", peekToken(auth)) // fresh EXTERNAL credential intact
        assertTrue(state.hasCurrentSessionProjection()) // armed state intact
        assertEquals(1L, auth.credentialGeneration()) // the owner generation never moved
        server.shutdown()
    }

    @Test
    fun `without an external rotation the same transaction COMMITS - guard passes, both cleared`() = runBlocking {
        val auth = realAuth()
        val state = VoiceSharingAccountState()
        val server = MockWebServer().apply { start() }
        val binding = bindingFor(auth, state)
        val operations = operations(binding, state, server)
        val token = binding.loginFromAuth()
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))

        val ticket = state.beginOperation(token, requireProjection = true)!!
        val rejected = binding.syncCredential()!!
        val committed = binding.invalidateIfCurrent(rejected) { commitGuard ->
            state.processUnauthorized(token, ticket, commitGuard)
        }

        assertTrue(committed)
        assertNull(peekToken(auth)) // the REAL platform token invalidated
        assertEquals(2L, auth.credentialGeneration())
        assertFalse(state.hasCurrentSessionProjection()) // scoped state cleared WITH it
        server.shutdown()
    }

    @Test
    fun `session incarnation is DISTINCT from credential generation - refresh never, login-logout always`() {
        val auth = realAuth()
        val sessionAtCreation = auth.sessionGeneration()
        assertEquals(1L, auth.credentialGeneration())

        auth.setAccessToken(AccessToken("t2")) // refresh: credential-only
        assertEquals(2L, auth.credentialGeneration())
        assertEquals(sessionAtCreation, auth.sessionGeneration())

        auth.signOut() // logout: BOTH incarnations move
        assertEquals(3L, auth.credentialGeneration())
        assertEquals(sessionAtCreation + 1, auth.sessionGeneration())
        assertNull(auth.accessTokenSnapshot())

        auth.addAccount("user@example.com", "uuid-1", RefreshToken("refresh-1"), AccessToken("t1"), LoginIdentity.PocketCasts)
        assertEquals(4L, auth.credentialGeneration())
        assertEquals(sessionAtCreation + 2, auth.sessionGeneration())
    }
}
