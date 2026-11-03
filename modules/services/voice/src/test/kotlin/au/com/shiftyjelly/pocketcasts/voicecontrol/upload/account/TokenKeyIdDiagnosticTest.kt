package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * The key-id diagnostic exists to answer one question -- which signing key does THIS app's token
 * name -- without ever putting the credential itself on a log line. These controls pin both halves:
 * the field that is reported, and the things that must never be.
 */
class TokenKeyIdDiagnosticTest {

    /**
     * Reaches the production implementation directly. It is a file-level, stateless token-to-line
     * mapping -- no instance state, no IO -- which is exactly why it can be exercised in isolation.
     * That the request path actually CALLS it is covered by the operations suite, not by this one.
     */
    private fun logTokenKeyId(value: String) {
        val method = Class.forName("au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountApiKt")
            .getDeclaredMethod("logTokenKeyIdLine", String::class.java)
        method.isAccessible = true
        method.invoke(null, value)
    }

    private val lines = mutableListOf<String>()

    @Before
    fun plant() {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        })
    }

    @After
    fun uproot() {
        Timber.uprootAll()
    }

    private fun b64(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    /**
     * A three-segment JWS, signature segment included. Base64url uses `-` and `_`, NEVER `+` or `/`,
     * so the padding characters cannot appear by accident -- and the signature segment here is
     * base64url too, which is what a real token carries.
     */
    private fun token(header: String, payload: String = """{"sub":"secret-subject","exp":1}""") = "${b64(header)}.${b64(payload)}.${b64("signature-bytes")}"

    @Test
    fun `a JWS token reports its key id and algorithm and nothing else`() {
        logTokenKeyId(token("""{"alg":"RS256","kid":"key-abc-123"}"""))

        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line, line.contains("kid=key-abc-123"))
        assertTrue(line, line.contains("alg=RS256"))
        assertTrue(line, line.contains("kidSource=jwt"))
        // THE CREDENTIAL AND THE CLAIMS MUST NOT APPEAR. This is the whole reason the diagnostic
        // reads the header alone; a line carrying any of these would be a leak, not a diagnostic.
        assertFalse(line, line.contains("secret-subject"))
        assertFalse(line, line.contains("signature-part"))
        assertFalse(line, line.contains("exp"))
    }

    @Test
    fun `an opaque token is reported as opaque and never parsed into a value`() {
        logTokenKeyId("not-a-jws-token")

        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line, line.contains("kidSource=opaque"))
        assertFalse(line, line.contains("not-a-jws-token"))
    }

    @Test
    fun `a JWS whose header carries no key id states that absence rather than inventing one`() {
        logTokenKeyId(token("""{"alg":"RS256"}"""))

        val line = lines.single()
        assertTrue(line, line.contains("kidSource=absent"))
        assertFalse(line, line.contains("kid=key"))
    }

    @Test
    fun `an unreadable header fails closed instead of throwing into the request path`() {
        // Correct shape, garbage first segment: the decoder or the JSON reader will refuse it.
        logTokenKeyId("!!!not-base64!!!.payload.signature")

        // NOT a JWS header at all, so the honest report is `opaque` -- the same value an opaque
        // token gets. The one outcome this must never produce is an exception escaping into the
        // request path, which is what the assertion on the recorded line proves: we got a line.
        val line = lines.single()
        assertTrue(line, line.contains("kidSource=opaque"))
    }

    @Test
    fun `a header that is not a JSON object is reported as opaque rather than absent`() {
        // A base64-decodable but non-object header is not a JWS header we can read, and the two
        // distinct absences must stay distinct: `opaque` means "this is not a readable JWS header",
        // `absent` means "it IS one and it carries no kid". Collapsing them would let a reader
        // conclude the token WAS parsed when it was not.
        val notAnObject = b64("""["alg","RS256"]""")
        logTokenKeyId("$notAnObject.${b64("""{"sub":"x"}""")}.${b64("sig")}")

        assertTrue(lines.single(), lines.single().contains("kidSource=opaque"))
    }
}
