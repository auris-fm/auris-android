package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE RAW CREDENTIAL SCHEME CONTRACT (spec 85719944, Worker 79a6c81a measurement).
 *
 * The measured 08:54–56Z 401 bursts were classified `missing_bearer` at the Worker: the actual
 * presented Authorization header carried the RAW Auris token — no `Bearer ` scheme — because both
 * credential sources (`peekCurrentToken()` and the frozen assembled override) supply raw token
 * values, and the transport set the header byte-exact from them. The Worker requires
 * `/^Bearer (.+)$/` BEFORE token verification, so a raw token is indistinguishable from an absent
 * credential on the wire.
 *
 * This control observes the REAL outgoing request header over a real loopback exchange, using a
 * SYNTHETIC RAW token (not an already-prefixed fixture):
 *  - a raw token from the authorization supplier is sent as exactly one `Bearer ` scheme;
 *  - a raw token from the frozen override (the assembled path) is sent as exactly one scheme;
 *  - a value already carrying the scheme passes through byte-exact (never doubled).
 * No token or header values are logged.
 */
class UploadBearerSchemeControlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val receivedAuth = mutableListOf<String>()
    private var port = 0

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/contributions") { exchange: HttpExchange ->
            receivedAuth += exchange.requestHeaders.getFirst("Authorization")
            // A 401 answer keeps the exchange minimal; the observed header is the assertion surface.
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun transport(rawCredential: () -> String?) = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = rawCredential,
        callTimeoutMs = 10_000,
    )

    private fun ownedCapture(): Pair<String, OwnedAudio> {
        val id = UUID.randomUUID().toString()
        val file = File(tmp.root, "$id.wav")
        file.writeBytes(ByteArray(64) { it.toByte() })
        val owned = OwnedAudio(
            java.io.FileInputStream(file),
            file.length(),
            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
        )
        return id to owned
    }

    private fun rawTokenRecord(): String = """{"x":1}"""

    @Test
    fun `a raw synthetic token from the authorization supplier is sent with exactly one Bearer scheme`() {
        val transport = transport { "raw-synthetic-token-no-scheme" }
        val (id, owned) = ownedCapture()
        owned.use {
            transport.upload(id, recordNode(id), it)
        }
        assertTrue("PREMISE: the request reached the server", receivedAuth.isNotEmpty())
        assertEquals(
            "the raw credential must gain exactly one Bearer scheme at the request boundary",
            "Bearer raw-synthetic-token-no-scheme",
            receivedAuth.last(),
        )
    }

    @Test
    fun `a raw synthetic token from the frozen override is sent with exactly one Bearer scheme`() {
        val transport = transport { null } // the supplier refuses; the override carries the credential
        val (id, owned) = ownedCapture()
        owned.use {
            transport.upload(id, recordNode(id), it, authorizationOverride = "raw-frozen-override-token")
        }
        assertTrue("PREMISE: the request reached the server", receivedAuth.isNotEmpty())
        assertEquals(
            "the frozen override raw credential must gain exactly one Bearer scheme at the request boundary",
            "Bearer raw-frozen-override-token",
            receivedAuth.last(),
        )
    }

    @Test
    fun `a credential already carrying the scheme passes through byte-exact - never doubled`() {
        val transport = transport { "Bearer already-schemed-token" }
        val (id, owned) = ownedCapture()
        owned.use {
            transport.upload(id, recordNode(id), it)
        }
        assertTrue("PREMISE: the request reached the server", receivedAuth.isNotEmpty())
        assertEquals(
            "a schemed credential must not be double-prefixed",
            "Bearer already-schemed-token",
            receivedAuth.last(),
        )
    }

    /** Minimal record node: only the envelope construction needs a valid object. */
    private fun recordNode(captureId: String): RecordNode.Obj = RecordNode.Obj(
        mapOf(
            "schema_version" to RecordNode.ofInt(1),
            "capture_id" to RecordNode.Str(captureId),
            "audio" to RecordNode.Obj(
                mapOf(
                    "sha256" to RecordNode.Str("0".repeat(64)),
                    "byte_count" to RecordNode.ofInt(64),
                ),
            ),
        ),
    )
}
