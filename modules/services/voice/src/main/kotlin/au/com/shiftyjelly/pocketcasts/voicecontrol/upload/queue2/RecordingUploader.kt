package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends one recording as a single multipart request and reports the status code.
 *
 * The wire shape is the server's, exactly:
 * - `metadata` is a plain form field with NO filename. With a filename it arrives as a file and the
 *   server refuses the request, so the absence is part of the contract.
 * - `audio` is a file part named `recording.wav` with type `audio/wav`.
 * - The body has a known length, so the request states `Content-Length`. The server refuses a
 *   request that does not say how large it is.
 *
 * The call is cancellable. A stalled write is not released by a timeout on the coroutine, because
 * the write blocks in the socket and never returns to be checked; only cancelling the HTTP call
 * itself closes the connection underneath it. That is why this goes through OkHttp with a real
 * `Call` handle rather than a blocking connection whose cleanup runs after the block returns.
 */
class RecordingUploader(
    private val endpoint: String,
    private val client: OkHttpClient,
    private val authorization: suspend () -> String?,
    /**
     * Whether the recording may still be sent, re-read after the credential arrives.
     *
     * Acquiring a token can take a network round trip, and the user's answer can change during it.
     * The loop checked these facts before calling, but that check is a snapshot: if sharing was
     * withdrawn while the token was being obtained, sending on that stale answer would upload data
     * the user has just withdrawn. So the same facts are asked again here, and a refusal leaves the
     * recording unsent for the loop to delete.
     */
    private val eligible: (FolderQueue.Recording) -> Boolean = { true },
) {
    /**
     * The call currently in flight, so it can be cancelled from outside this coroutine.
     *
     * One upload runs at a time by construction, so a single slot is enough; it is null whenever
     * nothing is being sent.
     */
    private val inFlight = AtomicReference<okhttp3.Call?>(null)

    /**
     * Abandon whatever is in flight. Called when sharing is turned off: deleting the queued folder
     * is not enough, because the request has already been handed to the socket.
     */
    fun cancelInFlight() {
        inFlight.getAndSet(null)?.cancel()
    }

    suspend fun send(recording: FolderQueue.Recording): Int? {
        // Absent and expired are the same fact here: there is no usable credential. Asking for a
        // token goes through the normal acquisition path, which serves a fresh cached one or mints
        // a new one; only a real failure to obtain one returns null, and that is a retry, not a
        // recording that sits unmoving forever behind a cache entry nobody refreshes.
        val bearer = authorization() ?: return null
        // Re-checked after acquisition and before a call exists, so a withdrawal that lands during
        // acquisition cannot slip out: there is nothing in flight to cancel yet, and this sees it.
        if (!eligible(recording)) return null
        val metadata: ByteArray = recording.metadata.readBytes()
        val audioBytes: ByteArray = recording.audio.readBytes()
        if (metadata.size > PlainRecordLimits.MAX_METADATA_BYTES) return null
        if (audioBytes.size > PlainRecordLimits.MAX_AUDIO_BYTES) return null

        val boundary = "auris-" + UUID.randomUUID().toString().replace("-", "")
        val body = buildBody(boundary, metadata, audioBytes)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            .build()

        val call = client.newCall(request)
        inFlight.set(call)
        return try {
            withContext(Dispatchers.IO) {
                // The client carries the whole-call deadline; cancelling here closes the connection
                // under a blocked write, which no coroutine timeout can do.
                call.execute().use { it.code }
            }
        } catch (t: Throwable) {
            // No answer, or cancelled. The loop treats that as retryable, never as a refusal.
            null
        } finally {
            inFlight.compareAndSet(call, null)
        }
    }

    /** Exposed so a test can assert the exact bytes against the wire contract. */
    internal fun buildBodyForTest(boundary: String, metadata: ByteArray, audio: ByteArray): ByteArray = buildBody(boundary, metadata, audio)

    /** The exact multipart body, so its length is known before it is sent. */
    private fun buildBody(boundary: String, metadata: ByteArray, audio: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(metadata.size + audio.size + 512)
        // Metadata: a plain field. No filename, or the server sees a file and refuses the request.
        out.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"metadata\"\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/json\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(metadata)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        // Audio: a file part, with the filename the server requires.
        out.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
        out.write(
            "Content-Disposition: form-data; name=\"audio\"; filename=\"recording.wav\"\r\n".toByteArray(Charsets.UTF_8),
        )
        out.write("Content-Type: audio/wav\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(audio)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.write(("--$boundary--\r\n").toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    companion object {
        /** The whole call, including the upload write, gets this long and no longer. */
        const val CALL_TIMEOUT_MS = 60_000L
    }
}

/** The server's own bounds, so nothing here can send a request it would refuse on size alone. */
internal object PlainRecordLimits {
    const val MAX_METADATA_BYTES = 65_536
    const val MAX_AUDIO_BYTES = 1_920_044
}
