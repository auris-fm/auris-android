package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * What a 401 from the account route is allowed to REVOKE.
 *
 * The route only ever verifies the Auris token, so a 401 can only speak about that credential
 * (cloud `bbb9beff`). Handing it to the owner's `invalidateAuthToken` would sign the user out of
 * the very account that minted the token -- a rejected *derived* credential killing the session it
 * derives from. This control asserts both halves at once: the Auris credential IS replaced, and the
 * upstream session is untouched.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSharingAurisCredentialInvalidationTest {

    private lateinit var server: MockWebServer
    private lateinit var state: VoiceSharingAccountState
    private lateinit var owner: UpstreamOwner
    private lateinit var auris: RecordingAurisToken

    // SYNCHRONIZED, and reset per test: this suite's core assertion is an ABSENCE ("nothing was
    // invalidated"), which a list shared with another test instance would silently falsify.
    private val aurisScopeMirrorClears: MutableList<AuthoritySnapshot> = java.util.Collections.synchronizedList(mutableListOf())
    private val aurisRefusals: MutableList<Unit> = java.util.Collections.synchronizedList(mutableListOf())

    private class RecordingAurisToken(var token: String?) : CloudTokenProviding {
        override suspend fun currentToken(): String? = token

        override suspend fun refreshToken() {
            token = null // the real provider drops the refused token and re-mints on demand
        }
    }

    @Before
    fun setUp() {
        aurisScopeMirrorClears.clear()
        aurisRefusals.clear()
        server = MockWebServer()
        server.start()
        state = VoiceSharingAccountState()
        owner = UpstreamOwner()
        auris = RecordingAurisToken("auris-issued-jws")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun operations(): VoiceSharingAccountOperations {
        val api = VoiceSharingAccountApi.create(Retrofit.Builder().baseUrl(server.url("/")), OkHttpClient())
        return VoiceSharingAccountOperations(
            api = api,
            state = state,
            ownership = owner,
            aurisToken = auris,
            onCredentialInvalid = { rejected ->
                // A 401 on this route is news about the AURIS credential only, so this post-commit
                // hook is the VOICE-SCOPE mirror clear the production startup wires -- NOT the
                // owner's repair path. Naming it after the owner's invalidation made an absence
                // assertion here read like "is the session still alive", which it never measured.
                aurisScopeMirrorClears += rejected
            },
            onAurisCredentialRefused = { aurisRefusals += Unit },
        )
    }

    private fun projection(): MockResponse = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"schema_version\":1,\"membership_revision\":\"rev-1\",\"plan\":\"free\"," +
                "\"voice_sharing_enrolled\":false,\"enrollment_epoch\":null," +
                "\"accepted_disclosure_version\":null,\"withdrawal_state\":\"not_enrolled\"}",
        )

    @Test
    fun `a 401 revokes the Auris credential and leaves the upstream session alone`() = runBlocking {
        // The failure this rules out is the one a naive wiring ships: the route only ever sees the
        // Auris token (cloud `bbb9beff`), so handing its 401 to the owner's invalidateAuthToken
        // would sign the user out of the account that MINTED that token -- a rejected derived
        // credential taking the session down with it.
        val ops = operations()

        // One session token for both attempts: refreshProjection fences one operation per session
        // token, so a second call with a NEW token is the "superseded" case, not the 401 case.
        val sessionToken = state.sessionStarted(owner.authoritySnapshot().session)
        server.enqueue(projection())
        assertTrue("the projection must apply before a 401 can speak to a current session", ops.refreshProjection(sessionToken))
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(401))
        val applied = ops.refreshProjection(sessionToken)

        assertEquals(false, applied)
        // Asserted through the callback the operations were actually constructed with. Asking the
        // FAKE whether it was refreshed would pass on a default that happened to call a different
        // provider instance -- a green test that proves nothing about this wiring.
        assertEquals("the refused token must be replaced", 1, aurisRefusals.size)
        // THE ASSERTION THAT MATTERS: the upstream account and its published token are UNTOUCHED.
        // Read from the owner, i.e. the thing that could actually be harmed -- not from a hook.
        assertEquals("and the account stays signed in", "user@example.com", owner.accountName)
        assertEquals("and its credential is not destroyed", "upstream-pocket-casts-token", owner.published)
        assertEquals("the credential incarnation must not advance", 1L, owner.authoritySnapshot().credential)
        assertEquals("the voice-scope mirror is cleared once", 1, aurisScopeMirrorClears.size)
    }
}
