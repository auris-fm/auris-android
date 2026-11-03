package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

/**
 * WHICH credential this app presents to the account route.
 *
 * The Pixel's live capture (cloud `84a4d25e`, the `kid` line) established that the app was sending
 * the Pocket Casts account token -- `alg=HS256`, no `kid` -- to a route that only verifies
 * Auris-issued tokens. Staging refused it correctly and no re-login could ever have helped. The
 * fix is selection, not acquisition: [AurisTokenProvider] already mints and refreshes correctly.
 *
 * These controls pin the property that must hold afterwards, and they deliberately assert the
 * NEGATIVE case as hard as the happy one: @cloud's warning was that "the account request uses the
 * Auris provider" is satisfiable while still leaving a stale bearer alive across a logout, which
 * would be a worse bug than the one being fixed.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSharingAurisBearerTest {

    private lateinit var server: MockWebServer
    private lateinit var state: VoiceSharingAccountState
    private lateinit var owner: UpstreamOwner

    /**
     * An Auris provider whose current token is set by the test. It records calls so the controls
     * can tell "asked the Auris provider" from "reused some other mirror".
     */
    private class FakeAurisToken(var token: String?) : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
        val currentCalls = mutableListOf<Unit>()
        val refreshCalls = mutableListOf<Unit>()
        override suspend fun currentToken(): String? {
            currentCalls += Unit
            return token
        }

        override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(token)
        override suspend fun refreshToken() {
            refreshCalls += Unit
        }
    }

    /**
     * Minimal owner: one account, one session, one published token. Enough to exercise the bearer
     * seam without re-testing the ownership boundary that [VoiceSharingAccountOperationsTest] owns.
     */

    @Before
    fun setUp() {
        // Robolectric may reuse one classloader across test instances, so this class's instance
        // state is reset explicitly rather than assumed fresh -- a leaked callback list from an
        // earlier run makes an absence assertion ("nothing was invalidated") read the wrong list.
        ownerInvalidations.clear()
        aurisRefusals.clear()
        server = MockWebServer()
        server.start()
        state = VoiceSharingAccountState()
        owner = UpstreamOwner()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val ownerInvalidations: MutableList<AuthoritySnapshot> = java.util.Collections.synchronizedList(mutableListOf())
    private val aurisRefusals: MutableList<Unit> = java.util.Collections.synchronizedList(mutableListOf())

    private fun operations(auris: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding): VoiceSharingAccountOperations {
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        return VoiceSharingAccountOperations(
            api = api,
            state = state,
            ownership = owner,
            aurisToken = auris,
            onCredentialInvalid = { rejected -> ownerInvalidations += rejected },
            onAurisCredentialRefused = { aurisRefusals += Unit },
        )
    }

    /**
     * A body the STRICT parser accepts: exact key set, exact schema literal, and the consistency
     * rule for `not_enrolled` (enrolled=false, no epoch). Anything looser makes `refreshProjection`
     * fail before the request is even sent, which would let these controls pass for the wrong reason.
     */
    private fun projection(): MockResponse = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"schema_version\":1,\"membership_revision\":\"rev-1\",\"plan\":\"free\"," +
                "\"voice_sharing_enrolled\":false,\"enrollment_epoch\":null," +
                "\"accepted_disclosure_version\":null,\"withdrawal_state\":\"not_enrolled\"}",
        )

    private fun authorizationOf(request: RecordedRequest): String? = request.getHeader("Authorization")

    @Test
    fun `the account request carries the Auris token, not the owner's published mirror`() = runBlocking {
        // The two are deliberately different values: if the operations still read the mirror, the
        // recorded header says so and this control fails rather than passing on a coincidence.
        val auris = FakeAurisToken("auris-issued-jws")

        val sent = AtomicReference<String?>()
        val ops = operations(auris)
        server.enqueue(projection())
        val token = state.sessionStarted(owner.authoritySnapshot().session)
        ops.refreshProjection(token)
        sent.set(authorizationOf(server.takeRequest()))

        assertEquals("Bearer auris-issued-jws", sent.get())
        assertFalse("the upstream mirror must not be what we send", sent.get()!!.contains("upstream-pocket-casts-token"))
    }

    @Test
    fun `a token minted for one account is never served after the account switches`() = runBlocking {
        // cloud's warning, turned into an assertion: the failure mode is a token surviving the
        // lifecycle it belongs to. The fake provider models the REAL one's credential-keyed cache:
        // an account change makes the previous token unavailable, and the operations must then
        // send nothing rather than replay account A's bearer to account B.
        val auris = FakeAurisToken("token-for-account-A")
        val ops = operations(auris)

        // Account A: sends.
        server.enqueue(projection())
        ops.refreshProjection(state.sessionStarted(owner.authoritySnapshot().session))
        assertEquals("Bearer token-for-account-A", authorizationOf(server.takeRequest()))

        // Switch to account B. The provider (like the real one) drops the previous credential.
        owner.switchAccount(to = "other@example.com", token = "upstream-B")
        auris.token = null

        val after = AtomicReference<Boolean>()
        after.set(ops.refreshProjection(state.sessionStarted(owner.authoritySnapshot().session)))

        assertEquals("nothing to serve means the operation is refused", false, after.get())
        assertEquals("no second request may be sent with a stale bearer", 1, server.requestCount)
    }

    @Test
    fun `after logout no Auris bearer is retrievable and nothing is sent`() = runBlocking {
        val auris = FakeAurisToken("token-for-account-A")
        val ops = operations(auris)

        owner.logOut()
        auris.token = null

        val applied = ops.refreshProjection(state.sessionStarted(owner.authoritySnapshot().session))

        assertEquals(false, applied)
        assertEquals("a logged-out session must not reach the network at all", 0, server.requestCount)
    }

    @Test
    fun `a missing Auris token refuses rather than falling back to the upstream mirror`() = runBlocking {
        // The exact defect class being fixed: a fallback that quietly supplies *some* credential.
        // Refusing is the only outcome that cannot be mistaken for success.
        val auris = FakeAurisToken(null)
        val ops = operations(auris)

        val applied = ops.refreshProjection(state.sessionStarted(owner.authoritySnapshot().session))

        assertEquals(false, applied)
        assertEquals("no request at all, so no bearer could have been attached", 0, server.requestCount)
    }
}

/**
 * Minimal owner: one account, one session, one published upstream token. Shared by the bearer and
 * invalidation suites so both speak about the SAME shape of boundary.
 */
internal class UpstreamOwner(
    @Volatile var accountName: String? = "user@example.com",
    @Volatile var published: String? = "upstream-pocket-casts-token",
) : CredentialOwnershipOwner {
    override val collectionSharedLock = Any()
    private val lock = Any()
    private var session = 1L

    @Volatile var binding: CredentialOwnershipBinding? = null

    override fun authoritySnapshot(): AuthoritySnapshot = synchronized(lock) {
        AuthoritySnapshot(accountName, session, 1L, published?.let(::AccessToken))
    }

    override fun installOwnershipBinding(binding: CredentialOwnershipBinding?): AuthoritySnapshot? = synchronized(lock) {
        this.binding = binding
        binding?.onSessionStarted(session)
        authoritySnapshot()
    }

    override fun beginRefresh(observed: AuthoritySnapshot): RefreshLease? = null

    override fun publishRefresh(
        lease: RefreshLease,
        refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken,
        accessToken: AccessToken,
    ): Boolean = false

    // The mirror-clear exchange class (reviewer 82a9958d): credential lock only.

    override fun <T> credentialTransaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }

    override fun invalidateIfCurrent(observed: AuthoritySnapshot, transaction: () -> Boolean): Boolean = synchronized(lock) { if (observed.accountName != accountName) false else transaction() }

    override fun reconcileExternalState(): AuthoritySnapshot = authoritySnapshot()

    override fun publishCredentials(
        observed: AuthoritySnapshot,
        refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken,
        accessToken: AccessToken,
    ): Boolean = false

    override fun sessionGeneration(): Long = synchronized(lock) { session }

    /** Account switch: a DIFFERENT account under a NEW session, same process. */
    fun switchAccount(to: String, token: String) = synchronized(lock) {
        accountName = to
        published = token
        session += 1
    }

    fun logOut() = synchronized(lock) {
        accountName = null
        published = null
        session += 1
    }
}
