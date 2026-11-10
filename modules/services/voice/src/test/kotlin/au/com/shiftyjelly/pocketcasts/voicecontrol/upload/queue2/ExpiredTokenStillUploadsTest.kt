package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A recording waits for a token, not for a cache entry nobody refreshes.
 *
 * The uploader used to read the token cache and skip the recording when it was empty. An expired
 * entry leaves that cache empty, and nothing in the loop would ever refill it, so every queued
 * recording would sit there indefinitely while the loop reported nothing. A stall of that shape is
 * exactly what the contract forbids. Asking for a token instead means an expired entry is replaced
 * the same way a rejected one is.
 */
class ExpiredTokenStillUploadsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an expired cached token is refreshed and the recording reaches the wire`() = runBlocking {
        val queue = FolderQueue(tmp.newFolder("q"), 32, {})

        // Publish one real recording so the loop has something to send.
        queue.publish(
            captureId = UUID.randomUUID().toString(),
            audioWriter = { it.writeBytes(ByteArray(8)) },
            metadataBytes = """{"account":"a@b.c","enrollment_epoch":"e"}""".toByteArray(),
            nowMs = 1_000L,
        )
        assertEquals(1, queue.count())

        // The cache starts expired: asking the cache yields nothing, which is what the loop used
        // to treat as "no attempt".
        // A cache that starts expired: a peek yields nothing until something asks for a token.
        val cache = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val token: suspend () -> String? = suspend {
            cache.get() ?: "fresh".also {
                fetches.incrementAndGet()
                cache.set(it)
            }
        }

        val sent = mutableListOf<String>()
        val loop = UploaderLoop(
            queue = queue,
            send = { recording ->
                // Same shape as the real sender: ask for a token, then send.
                val bearer = token()
                if (bearer == null) {
                    null
                } else {
                    sent.add(recording.captureId)
                    201
                }
            },
            refreshSignIn = {},
            now = { 10_000L },
        )

        val result = loop.runOnce()

        assertEquals("an expired cache entry must be replaced, not waited on", 1, fetches.get())
        assertEquals("and the recording must actually reach the wire", 1, sent.size)
        assertEquals(1, result.accepted)
        assertEquals(0, queue.count())
    }
}
