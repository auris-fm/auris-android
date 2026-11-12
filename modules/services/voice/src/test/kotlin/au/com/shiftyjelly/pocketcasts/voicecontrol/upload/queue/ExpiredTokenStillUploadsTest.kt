package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A recording waits for a token, not for a cache entry nobody refreshes.
 *
 * The uploader used to read the token cache and skip the recording when the cache was empty. An
 * expired entry leaves that cache empty, and nothing in the loop would ever refill it, so every
 * queued recording would sit there indefinitely while the loop looked idle - the stall the contract
 * forbids. The sender is built by the same function the app uses, so reverting that function to a
 * cache peek turns this red.
 */
class ExpiredTokenStillUploadsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an expired cached token is refreshed and the recording reaches the server`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()
        try {
            val queue = FolderQueue(tmp.newFolder("q"), 32, {})
            queue.publish(
                captureId = UUID.randomUUID().toString(),
                audioWriter = { it.writeBytes(ByteArray(8)) },
                metadataBytes = """{"account":"a@b.c","enrollment_epoch":"e"}""".toByteArray(),
                nowMs = 1_000L,
            )

            // A cache holding only an expired entry: it yields nothing until something asks the
            // source for a token, which is the distinction this control pins.
            val cached = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val acquisitions = AtomicInteger(0)
            val source = object : CloudTokenProviding {
                override suspend fun currentToken(): String? = cached.get() ?: "fresh".also {
                    acquisitions.incrementAndGet()
                    cached.set(it)
                }
            }

            val uploader = VoiceUploadQueueModule.buildUploader(
                endpoint = server.url("/api/v1/voice-recordings").toString(),
                client = OkHttpClient(),
                tokenSource = source,
                eligible = { true },
                queue = queue,
            )

            val answer = uploader.send(queue.recordings().single())

            assertEquals(201, answer)
            assertEquals("the expired entry must be replaced, not waited on", 1, acquisitions.get())
            assertEquals("and the request must actually reach the server", 1, server.requestCount)
            // Deletion is the loop's decision on seeing the answer, not the sender's.
        } finally {
            server.shutdown()
        }
    }
}
