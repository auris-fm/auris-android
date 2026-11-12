package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * THE UPLOAD USER-AGENT (spec on the `platform=` log line): the server classifies each request's
 * platform from its User-Agent. The account routes go through the cached client and already send
 * `Pocket Casts/Android/<version>`; the upload sender is built from the RAW client, which carries
 * NO User-Agent — staging logged those uploads as `platform=unknown`. The sender must send the
 * same scoped internal agent the account routes use.
 */
class UploadUserAgentTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `an upload carries the platform user-agent`() = runBlocking {
        val queue = FolderQueue(tmp.newFolder("q"), 32, {})
        queue.publish(
            captureId = UUID.randomUUID().toString(),
            audioWriter = { it.writeBytes(ByteArray(8)) },
            metadataBytes = """{"account":"a@b.c","enrollment_epoch":"e"}""".toByteArray(),
            nowMs = 1_000L,
        )
        val uploader = VoiceUploadQueueModule.buildUploader(
            endpoint = server.url("/api/v1/voice-recordings").toString(),
            client = OkHttpClient(), // the RAW client: no UA interceptor of its own
            tokenSource = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding {
                override suspend fun currentToken(): String? = "token-a"
            },
            eligible = { true },
        )

        assertEquals(201, uploader.send(queue.recordings().single()))

        val recorded = server.takeRequest()
        val ua = recorded.getHeader("User-Agent")
        assertTrue(
            "the upload must carry the platform agent, got: $ua",
            ua != null && ua.startsWith("Pocket Casts/Android/"),
        )
    }
}
