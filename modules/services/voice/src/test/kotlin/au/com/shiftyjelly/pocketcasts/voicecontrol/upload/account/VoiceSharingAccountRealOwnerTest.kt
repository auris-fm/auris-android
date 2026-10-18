package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.sync.TokenErrorNotification
import com.automattic.eventhorizon.EventHorizon
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * REAL-owner integration controls for the owner-transaction contract (design v2/v3, reviewers
 * 504da9ea/f1d41730): the boundary runs against the actual SyncAccountManagerImpl over a
 * Robolectric AccountManager. Determinism comes from in-process owner writes and MockWebServer
 * dispatch ordering — never wall-clock sleeps.
 *
 * Matrix: T1 late-A-refresh-after-B-login; T2 same-account relogin; T3 install/seed ordering +
 * fail-closed bootstrap; T4 genuine refresh preserves session; T5 delayed cache copy never
 * becomes authority; T6 401 exactly once; T7 same-session out-of-order refresh completion;
 * T8 rejected-refresh publication refused (owner contract; the caller must not hand the token
 * back); plus synchronous signOut revocation and external-removal reconciliation.
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
        return auth
    }

    private fun addAccount(auth: SyncAccountManagerImpl, email: String = "user@example.com", token: String = "t1") {
        auth.addAccount(email, "uuid-1", RefreshToken("refresh-1"), AccessToken(token), au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity.PocketCasts)
    }

    private fun bindingFor(ownership: CredentialOwnershipOwner, state: VoiceSharingAccountState): VoiceSharingAccountAuthBinding = VoiceSharingAccountAuthBinding(ownership, VoiceSharingAccountSessionCoordinator(state, VoiceSharingCredentialStore()))

    private fun operations(ownership: CredentialOwnershipOwner, state: VoiceSharingAccountState, server: MockWebServer): VoiceSharingAccountOperations = VoiceSharingAccountOperations(
        api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient()),
        state = state,
        ownership = ownership,
    ) { }

    private fun projectionJson(): String = buildString {
        append("{\n\"schema_version\":1,")
        append("\n\"membership_revision\":\"rev-1\",")
        append("\n\"plan\":\"free\",")
        append("\n\"voice_sharing_enrolled\":false,")
        append("\n\"enrollment_epoch\":null,")
        append("\n\"accepted_disclosure_version\":null,")
        append("\n\"withdrawal_state\":\"not_enrolled\"}")
    }

    private fun currentToken(state: VoiceSharingAccountState) = state.currentSessionTokenForTest()!!

    @Test
    fun `T6 - a committed 401 destroys the REAL published token and advances the credential EXACTLY once`() = runBlocking {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        binding.install()
        val server = MockWebServer().apply { start() }
        val operations = operations(auth, state, server)
        val token = currentToken(state)

        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(operations.refreshProjection(token))
        assertTrue(operations.enroll(token, acceptedDisclosureVersion = "v3") is VoiceSharingMutationResult.Failure)

        // The owner transaction cleared the PUBLISHED credential and advanced the incarnation once.
        assertNull(auth.authoritySnapshot().token)
        assertEquals(2L, auth.credentialGeneration())
        assertFalse(state.hasCurrentSessionProjection())
        server.shutdown()
    }

    @Test
    fun `a REAL signOut ends the session SYNCHRONOUSLY in the owner transaction`() = runBlocking {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        binding.install()
        val server = MockWebServer().apply { start() }
        val operations = operations(auth, state, server)
        val token = currentToken(state)
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        assertTrue(state.hasCurrentSessionProjection())

        auth.signOut() // REAL platform sign-out: binding.onSessionEnded fires INSIDE the transaction

        assertFalse(state.hasCurrentSessionProjection()) // revoked in-transaction, not at collector timing
        assertNull(auth.authoritySnapshot().token)
        // A NEW operation cannot arm: no account authority remains.
        assertNull(auth.authoritySnapshot().accountName)
        server.shutdown()
    }

    @Test
    fun `T1 - a LATE A refresh after B login is REFUSED and never writes into B`() {
        val auth = realAuth()
        addAccount(auth, email = "a@example.com", token = "tA")
        val lease = auth.beginRefresh(auth.getAccount()!!)!! // A's refresh admission, BEFORE the "network work"

        auth.signOut()
        addAccount(auth, email = "b@example.com", token = "tB") // B logs in while A's refresh is in flight

        assertFalse(auth.publishRefresh(lease, RefreshToken("rA2"), AccessToken("tA2")))
        // B's published authority is untouched; A's token never entered the owner.
        val snapshot = auth.authoritySnapshot()
        assertEquals("b@example.com", snapshot.accountName)
        assertEquals("tB", snapshot.token!!.value)
        assertEquals(3L, snapshot.credential) // only A-login + B-login moved it; the refused refresh did NOT
        serverShutdownNothing()
    }

    @Test
    fun `T2 - same-account relogin fences the OLD session's apply and arms the NEW incarnation`() {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        binding.install()
        val oldToken = currentToken(state)
        val oldOwnerSession = auth.sessionGeneration()
        val oldTicket = state.beginOperation(oldToken, requireProjection = false, ownerSession = oldOwnerSession)!!

        auth.signOut()
        addAccount(auth) // SAME email relogin: a NEW session incarnation
        val newToken = currentToken(state)
        assertTrue(auth.sessionGeneration() != oldOwnerSession)

        // The old session's in-flight apply is refused; the new session operates normally.
        assertFalse(state.applyOutcome(oldToken, oldTicket, projectionJson(), oldOwnerSession))
        val newOwnerSession = auth.sessionGeneration()
        assertTrue(state.applyOutcome(newToken, state.beginOperation(newToken, requireProjection = false, ownerSession = newOwnerSession)!!, projectionJson(), newOwnerSession))
        assertTrue(state.isCurrentSession(newToken))
        assertFalse(state.isCurrentSession(oldToken))
    }

    @Test
    fun `T3 - install seeds the binding UNDER the owner lock and is FAIL-CLOSED without an account`() {
        val auth = realAuth() // no account added
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        assertNull(binding.install()) // fail-closed: no account/token, no authority
        assertNull(state.currentSessionTokenForTest())

        addAccount(auth)
        assertNotNull(binding.install())
        assertNotNull(state.currentSessionTokenForTest()) // armed synchronously
        assertEquals("Bearer t1", (binding.coordinator.credentials.current())!!.bearer)
    }

    @Test
    fun `T4 - a GENUINE refresh publishes and PRESERVES the session incarnation`() {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        binding.install()
        val token = currentToken(state)
        val sessionBefore = auth.sessionGeneration()

        val lease = auth.beginRefresh(auth.getAccount()!!)!!
        assertTrue(auth.publishRefresh(lease, RefreshToken("r2"), AccessToken("t2")))

        assertEquals(sessionBefore, auth.sessionGeneration()) // refresh NEVER moves the session
        assertEquals(2L, auth.credentialGeneration())
        assertEquals("t2", auth.authoritySnapshot().token!!.value)
        assertTrue(state.isCurrentSession(token)) // no session lifecycle callback fired
        serverShutdownNothing()
    }

    @Test
    fun `T5 - a delayed platform cache copy of a STALE token NEVER becomes authority`() {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        bindingFor(auth, state).install()

        val lease = auth.beginRefresh(auth.getAccount()!!)!!
        assertTrue(auth.publishRefresh(lease, RefreshToken("r2"), AccessToken("t2")))

        // Simulate the platform's delayed internal cache copy writing the OLD value.
        val account = auth.getAccount()!!
        val platform = AccountManager.get(context)
        platform.setAuthToken(account, au.com.shiftyjelly.pocketcasts.preferences.AccountConstants.TOKEN_TYPE, "t1")

        // Authority is the OWNER-PUBLISHED snapshot, never a bare cache read.
        assertEquals("t2", auth.authoritySnapshot().token!!.value)
        serverShutdownNothing()
    }

    @Test
    fun `T7 - SAME-SESSION out-of-order refresh completion cannot overwrite a newer credential`() {
        val auth = realAuth()
        addAccount(auth)
        bindingFor(auth, VoiceSharingAccountState()).install()

        val lease1 = auth.beginRefresh(auth.getAccount()!!)!!
        val lease2 = auth.beginRefresh(auth.getAccount()!!)!! // two concurrent refreshes, same admission state
        assertTrue(auth.publishRefresh(lease2, RefreshToken("r2"), AccessToken("t2"))) // newer completes first
        assertFalse(auth.publishRefresh(lease1, RefreshToken("r1"), AccessToken("t1-old"))) // older completion REFUSED
        assertEquals("t2", auth.authoritySnapshot().token!!.value)
        assertEquals(2L, auth.credentialGeneration())
        serverShutdownNothing()
    }

    @Test
    fun `T8 - a REFUSED publication hands NO token back - the caller cannot publish a rejected refresh`() {
        val auth = realAuth()
        addAccount(auth, token = "t1")
        val lease = auth.beginRefresh(auth.getAccount()!!)!!
        auth.signOut() // session superseded while "in flight"
        addAccount(auth, token = "t2")

        assertFalse(auth.publishRefresh(lease, RefreshToken("rX"), AccessToken("tX")))
        assertEquals("t2", auth.authoritySnapshot().token!!.value) // the refused token never entered the owner
        serverShutdownNothing()
    }

    @Test
    fun `external account removal is reconciled at the owner and revokes SUBSCRIBED state`() = runBlocking {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        binding.install()
        val server = MockWebServer().apply { start() }
        val operations = operations(auth, state, server)
        val token = currentToken(state)
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))

        // EXTERNAL removal (system settings): the platform account vanishes behind our back.
        val platform = AccountManager.get(context)
        platform.removeAccountExplicitly(auth.getAccount()!!)

        // Observed asynchronously: the reconciliation drives the subscribed revocation ONCE.
        auth.reconcileExternalState()
        assertFalse(state.hasCurrentSessionProjection())
        assertNull(auth.authoritySnapshot().token)

        // Idempotent: a second reconciliation changes nothing.
        auth.reconcileExternalState()
        assertFalse(state.hasCurrentSessionProjection())
        server.shutdown()
    }

    @Test
    fun `R1 - an account switch BEFORE refresh admission REFUSES - A inputs never read for B`() {
        val auth = realAuth()
        addAccount(auth, email = "a@example.com", token = "tA")
        // B replaces A BEFORE the admission is even attempted.
        auth.signOut()
        addAccount(auth, email = "b@example.com", token = "tB")

        val accountA = android.accounts.Account("a@example.com", au.com.shiftyjelly.pocketcasts.preferences.AccountConstants.ACCOUNT_TYPE)
        assertNull(auth.beginRefresh(accountA)) // ORIGINAL account validation: refused pre-network
        assertEquals("tB", auth.authoritySnapshot().token!!.value)
        serverShutdownNothing()
    }

    @Test
    fun `R2 - external REPLACEMENT reconciles FIRST in every authority transaction - never B-name-with-A-token`() = runBlocking {
        val auth = realAuth()
        addAccount(auth, email = "a@example.com", token = "tA")
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        binding.install()
        val token = currentToken(state)
        val server = MockWebServer().apply { start() }
        val operations = operations(auth, state, server)
        server.enqueue(MockResponse().setResponseCode(200).setBody(projectionJson()))
        assertTrue(operations.refreshProjection(token))
        val observed = auth.authoritySnapshot()

        // EXTERNAL replacement: A removed, B added by the RAW platform, entirely behind the
        // owner's back (the owner's addAccount is a LOGIN and would legitimately publish).
        val platform = AccountManager.get(context)
        platform.removeAccountExplicitly(auth.getAccount()!!)
        val accountB = android.accounts.Account("b@example.com", au.com.shiftyjelly.pocketcasts.preferences.AccountConstants.ACCOUNT_TYPE)
        platform.addAccountExplicitly(accountB, "rB", null)
        platform.setAuthToken(accountB, au.com.shiftyjelly.pocketcasts.preferences.AccountConstants.TOKEN_TYPE, "tB")

        // The NEXT authority transaction reconciles FIRST: session revoked, stale 401 refused —
        // even though the observed snapshot's account/session/credential "exist" again under B.
        assertFalse(auth.invalidateIfCurrent(observed) { true })
        assertFalse(state.hasCurrentSessionProjection()) // A's session revoked by reconciliation
        assertEquals("b@example.com", auth.authoritySnapshot().accountName)
        assertNull(auth.authoritySnapshot().token) // B's platform token NEVER auto-adopted
        server.shutdown()
    }

    @Test
    fun `R3 - removal revokes the session INDEPENDENT of token presence (credential already invalidated)`() {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        binding.install()
        assertNotNull(state.currentSessionTokenForTest())

        auth.invalidateAccessToken() // the 401-equivalent: credential invalidated, session STILL armed
        assertNull(auth.authoritySnapshot().token)
        assertNotNull(state.currentSessionTokenForTest())

        // External removal observed afterwards: the STALE session is still revoked.
        val platform = AccountManager.get(context)
        platform.removeAccountExplicitly(auth.getAccount()!!)
        auth.reconcileExternalState()
        assertNull(state.currentSessionTokenForTest())
        assertEquals(null, auth.authoritySnapshot().accountName)
    }

    @Test
    fun `R4 - install is an ONCE bootstrap - rebinding seeds the ledger, null seed REVOKES`() {
        val auth = realAuth()
        addAccount(auth)
        val state = VoiceSharingAccountState()
        val binding = bindingFor(auth, state)
        val first = binding.install()
        assertEquals("t1", first!!.token!!.value)
        val credentialAtInstall = auth.credentialGeneration()

        val second = binding.install() // REBIND: seeds the RETAINED ledger, NO cache re-adoption
        assertEquals("t1", second!!.token!!.value)
        assertEquals(credentialAtInstall, auth.credentialGeneration()) // no adoption write
        assertTrue(state.isCurrentSession(currentToken(state))) // no spurious session churn

        assertNull(binding.install(null)) // explicit null seed REVOKES the previous binding
        assertNull(state.currentSessionTokenForTest())
        assertNull(auth.authoritySnapshot().token)
    }

    @Test
    fun `R5 - publishCredentials commits BOTH tokens in ONE credential incarnation move`() {
        val auth = realAuth()
        addAccount(auth)
        bindingFor(auth, VoiceSharingAccountState()).install()
        val credentialBefore = auth.credentialGeneration()

        assertTrue(auth.publishCredentials(RefreshToken("r2"), AccessToken("t2")))
        val snapshot = auth.authoritySnapshot()
        assertEquals("t2", snapshot.token!!.value)
        assertEquals(credentialBefore + 1, snapshot.credential) // ONE move, not two writes
        serverShutdownNothing()
    }

    private fun serverShutdownNothing() { /* no server in pure owner controls */ }
}
