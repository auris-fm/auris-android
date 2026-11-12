package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

/**
 * WHICH credential this app presents to the account route.
 *
 * A live capture on device (the `kid` line) established that the app was sending
 * the Pocket Casts account token -- `alg=HS256`, no `kid` -- to a route that only verifies
 * Auris-issued tokens. Staging refused it correctly and no re-login could ever have helped. The
 * fix is selection, not acquisition: the Auris provider already mints and refreshes correctly.
 *
 * These controls pin the property that must hold afterwards, and they deliberately assert the
 * NEGATIVE case as hard as the happy one: the measured warning was that "the account request uses the
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

    @Before
    fun setUp() {
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

    private val ownerInvalidations: MutableList<Unit> = java.util.Collections.synchronizedList(mutableListOf())
    private val aurisRefusals: MutableList<Unit> = java.util.Collections.synchronizedList(mutableListOf())

    private fun operations(auris: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding): VoiceSharingAccountOperations {
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        return VoiceSharingAccountOperations(
            api = api,
            state = state,
            currentAccountName = { owner.accountName },
            aurisToken = auris,
            onCredentialInvalid = { ownerInvalidations += Unit },
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
            "{\"schema_version\":1,\"plan\":\"free\"," +
                "\"voice_sharing_enrolled\":false,\"enrollment_epoch\":null," +
                "\"accepted_disclosure_version\":null,\"withdrawal_state\":\"not_enrolled\"}",
        )

    private fun authorizationOf(request: RecordedRequest): String? = request.getHeader("Authorization")

    @Test
    fun `the account request carries the Auris token, not the owner's published mirror`() = runBlocking {
        val auris = FakeAurisToken("auris-issued-jws")

        val sent = AtomicReference<String?>()
        val ops = operations(auris)
        server.enqueue(projection())
        ops.refreshProjection()
        sent.set(authorizationOf(server.takeRequest()))

        assertEquals("Bearer auris-issued-jws", sent.get())
        assertFalse("the upstream mirror must not be what we send", sent.get()!!.contains("upstream-pocket-casts-token"))
    }

    @Test
    fun `a token minted for one account is never served after the account switches`() = runBlocking {
        val auris = FakeAurisToken("token-for-account-A")
        val ops = operations(auris)

        server.enqueue(projection())
        ops.refreshProjection()
        assertEquals("Bearer token-for-account-A", authorizationOf(server.takeRequest()))

        owner.switchAccount(to = "other@example.com", token = "upstream-B")
        auris.token = null

        val after = ops.refreshProjection()

        assertEquals("nothing to serve means the operation is refused", false, after)
        assertEquals("no second request may be sent with a stale bearer", 1, server.requestCount)
    }

    @Test
    fun `after logout no Auris bearer is retrievable and nothing is sent`() = runBlocking {
        val auris = FakeAurisToken("token-for-account-A")
        val ops = operations(auris)

        owner.logOut()
        auris.token = null

        val applied = ops.refreshProjection()

        assertEquals(false, applied)
        assertEquals("a logged-out session must not reach the network at all", 0, server.requestCount)
    }

    @Test
    fun `a missing Auris token refuses rather than falling back to the upstream mirror`() = runBlocking {
        val auris = FakeAurisToken(null)
        val ops = operations(auris)

        val applied = ops.refreshProjection()

        assertEquals(false, applied)
        assertEquals("no request at all, so no bearer could have been attached", 0, server.requestCount)
    }
}

/**
 * Minimal owner: one account, one published upstream token. Shared by the bearer and invalidation
 * suites so both speak about the SAME shape of boundary.
 */
internal class UpstreamOwner(
    @Volatile var accountName: String? = "user@example.com",
    @Volatile var published: String? = "upstream-pocket-casts-token",
) {
    /** Account switch: a DIFFERENT account, same process. */
    fun switchAccount(to: String, token: String) {
        accountName = to
        published = token
    }

    fun logOut() {
        accountName = null
        published = null
    }
}
