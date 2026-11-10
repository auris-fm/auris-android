package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Withdrawing during token acquisition still stops the upload.
 *
 * The loop checks eligibility, then the sender obtains a credential, and only then sends. That
 * acquisition can take a network round trip, and the user's answer can change inside it. The
 * window matters because cancellation cannot close it: there is no call to cancel yet, so nothing
 * in flight would notice. The sender therefore asks the same question again once the credential
 * has arrived, and a withdrawal that landed in between keeps the recording off the wire.
 */
class WithdrawalDuringTokenFetchTest {
    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()

    @Test
    fun `sharing turned off while the token is being fetched sends nothing`() = runBlocking {
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

            val sharingOn = AtomicBoolean(true)
            val fetching = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            // The sender is built by the same production function the app uses, so this control
            // exercises the real acquisition and recheck order rather than a stand-in.
            val uploader = VoiceUploadQueueModule.buildUploader(
                endpoint = server.url("/api/v1/voice-recordings").toString(),
                client = OkHttpClient(),
                tokenSource = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding {
                    override suspend fun currentToken(): String {
                        fetching.complete(Unit)
                        release.await()
                        return "a-token"
                    }
                },
                eligible = { sharingOn.get() },
            )

            val recording = queue.recordings().single()
            val sending = async { uploader.send(recording) }

            fetching.await()
            // The user withdraws while the credential is still being obtained.
            sharingOn.set(false)
            release.complete(Unit)

            assertEquals("a withdrawal during acquisition must keep it off the wire", null, sending.await())
            assertEquals("and no request may reach the server", 0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
