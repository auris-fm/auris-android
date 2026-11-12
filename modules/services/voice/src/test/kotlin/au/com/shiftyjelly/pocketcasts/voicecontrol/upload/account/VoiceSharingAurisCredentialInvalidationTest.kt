package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

/**
 * What a 401 from the account route is allowed to REVOKE.
 *
 * The route only ever verifies the Auris token, so a 401 can only speak about that credential.
 * Handing it to the account manager's `invalidateAuthToken` would sign the user out of
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

    private val aurisScopeMirrorClears: MutableList<Unit> = java.util.Collections.synchronizedList(mutableListOf())
    private val aurisRefusals: MutableList<Unit> = java.util.Collections.synchronizedList(mutableListOf())

    private class RecordingAurisToken(var token: String?) : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding {
        override suspend fun currentToken(): String? = token

        override fun <T> withCurrentCachedToken(block: (String?) -> T): T = block(token)

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
            currentAccountName = { owner.accountName },
            aurisToken = auris,
            onCredentialInvalid = { aurisScopeMirrorClears += Unit },
            onAurisCredentialRefused = { aurisRefusals += Unit },
        )
    }

    private fun projection(): MockResponse = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"schema_version\":1,\"plan\":\"free\"," +
                "\"voice_sharing_enrolled\":false,\"enrollment_epoch\":null," +
                "\"accepted_disclosure_version\":null,\"withdrawal_state\":\"not_enrolled\"}",
        )

    @Test
    fun `a 401 revokes the Auris credential and leaves the upstream session alone`() = runBlocking {
        val ops = operations()

        server.enqueue(projection())
        assertTrue("the projection must apply before a 401 can speak to a current session", ops.refreshProjection())
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(401))
        val applied = ops.refreshProjection()

        assertEquals(false, applied)
        assertEquals("the refused token must be replaced", 1, aurisRefusals.size)
        // THE ASSERTION THAT MATTERS: the upstream account and its published token are UNTOUCHED.
        assertEquals("and the account stays signed in", "user@example.com", owner.accountName)
        assertEquals("and its credential is not destroyed", "upstream-pocket-casts-token", owner.published)
        assertEquals("the voice-scope mirror is cleared once", 1, aurisScopeMirrorClears.size)
    }
}
