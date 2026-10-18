package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Auris account routes (Go-owned; docs/specs/voice-recording-uploads.md lines 50-53). The
 * bearer actually sent is passed explicitly per call and captured by the operations layer for
 * 401 handling; responses carry the exact HTTP status for endpoint-status enforcement.
 */
interface VoiceSharingAccountApi {

    @GET("/api/v1/account/voice-sharing")
    suspend fun getVoiceSharing(@Header("Authorization") bearer: String?): Response<ResponseBody>

    @POST("/api/v1/account/voice-sharing/enrollment")
    suspend fun postEnrollment(
        @Header("Authorization") bearer: String?,
        @Body body: okhttp3.RequestBody,
    ): Response<ResponseBody>

    @POST("/api/v1/account/voice-sharing/withdrawal")
    suspend fun postWithdrawal(
        @Header("Authorization") bearer: String?,
        @Body body: okhttp3.RequestBody,
    ): Response<ResponseBody>

    // Retrofit rejects @Body on @DELETE; @HTTP(hasBody = true) carries the contract request_id.
    @HTTP(method = "DELETE", path = "/api/v1/account/voice-sharing/data", hasBody = true)
    suspend fun deleteData(
        @Header("Authorization") bearer: String?,
        @Body body: okhttp3.RequestBody,
    ): Response<ResponseBody>

    companion object {
        fun create(builder: Retrofit.Builder, client: OkHttpClient): VoiceSharingAccountApi = builder.client(client).build().create(VoiceSharingAccountApi::class.java)
    }
}

/** 202 receipt of the separate deletion workflow: distinct from withdrawal, which never deletes. */
data class DeletionReceipt(val deletionRequestId: String, val status: String)

/**
 * Account operations (Task 2; reviewer rounds 3-4). Contract:
 *
 * - PER-OPERATION GENERATION supplements the session token: every operation entry captures its
 *   generation BEFORE the await; only the currently-issued generation may apply an outcome, so
 *   a later same-session GET/intent fences an older in-flight response (withdrawal included).
 * - The freeze atomically validates the ISSUED session and captures the projection; retries
 *   reuse the ORIGINAL request_id and byte-identical body (built once with Moshi's JsonWriter,
 *   so quotes/backslashes in opaque values are properly escaped).
 * - Exact endpoint statuses: membership routes 200, deletion 202. Transport failures and 503
 *   retry within bounds; 401/403/409/422 are terminal with no retry.
 * - A 401 captured with the SAME bearer while the ORIGINATING session is still current
 *   invalidates that scoped authority (session end + credential effect). A stale 401 — session
 *   superseded or bearer changed — has no effect on any session or credential. Credential data
 *   never appears in generations or failure evidence.
 */
class VoiceSharingAccountOperations(
    private val api: VoiceSharingAccountApi,
    private val state: VoiceSharingAccountState,
    private val bearerProvider: suspend () -> String? = { null },
    private val onCredentialInvalid: suspend (String) -> Unit = {},
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val generation = AtomicLong(0)

    /** Current-session GET: the only way the state gains authority. */
    suspend fun refreshProjection(token: SessionToken): Boolean {
        // No freeze: the GET itself creates authority; it only needs the issued session.
        if (!state.isCurrentSession(token)) return false
        val op = frozenGetContext(token)
        val body = execute(op, op.requestBody) { bearer, _ -> api.getVoiceSharing(bearer) }
        return body != null && isCurrent(op) && state.applySessionResponse(token, body)
    }

    suspend fun enroll(token: SessionToken, acceptedDisclosureVersion: String): Boolean {
        val op = freeze(token, disclosureVersion = acceptedDisclosureVersion) ?: return false
        val body = execute(op, op.requestBody) { bearer, payload -> api.postEnrollment(bearer, payload) }
        return body != null && isCurrent(op) && state.applySessionResponse(token, body)
    }

    suspend fun withdraw(token: SessionToken): Boolean {
        val op = freeze(token) ?: return false
        val body = execute(op, op.requestBody) { bearer, payload -> api.postWithdrawal(bearer, payload) }
        return body != null && isCurrent(op) && state.applySessionResponse(token, body)
    }

    suspend fun deleteData(token: SessionToken): DeletionReceipt? {
        val op = freeze(token) ?: return null
        val body = execute(op, op.requestBody, expectedStatus = 202) { bearer, payload -> api.deleteData(bearer, payload) }
            ?: return null
        val receipt = parseDeletionReceipt(body) ?: return null
        // Fence the terminal outcome too: a session superseded mid-flight discards the receipt.
        return if (isCurrent(op)) receipt else null
    }

    /** Atomically validates the issued session and captures the operation identity. */
    private suspend fun freeze(token: SessionToken, disclosureVersion: String? = null): FrozenOperation? {
        if (!state.isCurrentSession(token)) return null
        val projection = state.projection.value ?: return null // no GET, no authority, no call
        return frozenContext(token, disclosureVersion)
    }

    /** Captures generation + bearer + body; mutations require an existing projection. */
    private suspend fun frozenGetContext(token: SessionToken): FrozenOperation = FrozenOperation(
        generation = generation.incrementAndGet(),
        token = token,
        bearer = bearerProvider(),
        requestBody = EMPTY_BODY,
    )

    private suspend fun frozenContext(token: SessionToken, disclosureVersion: String? = null): FrozenOperation {
        val projection = state.projection.value ?: throw IllegalStateException("projection required")
        val requestId = UUID.randomUUID().toString()
        val gen = generation.incrementAndGet()
        val body = writeBody(
            requestId = requestId,
            expectedRevision = projection.membershipRevision,
            epoch = projection.enrollmentEpoch,
            disclosureVersion = disclosureVersion,
        )
        return FrozenOperation(
            generation = gen,
            token = token,
            bearer = bearerProvider(), // captured BEFORE the await: the bearer actually sent
            requestBody = body,
        )
    }

    private fun isCurrent(op: FrozenOperation): Boolean = op.generation == generation.get() && state.isCurrentSession(op.token)

    /**
     * Sends with bounded immutable retry (transport/503 only), enforcing the exact endpoint
     * status; terminal outcomes (401/403/409/422, unexpected statuses) return null without
     * retry. Returns the response body as a string, or null.
     */
    private suspend fun execute(
        op: FrozenOperation,
        payload: okhttp3.RequestBody,
        expectedStatus: Int = 200,
        call: suspend (String?, okhttp3.RequestBody) -> Response<ResponseBody>,
    ): String? {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                val response = call(op.bearer, op.requestBody)
                if (response.code() == 401) {
                    handleUnauthorized(op)
                    return null
                }
                if (response.code() == expectedStatus) return response.body()!!.string()
                if (response.code() in 500..599 && attempt < MAX_ATTEMPTS) continue // retryable
                return null // 401/403/409/422 and any other status: terminal, no retry
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    handleUnauthorized(op)
                    return null
                }
                if (e.code() in 500..599 && attempt < MAX_ATTEMPTS) continue // retryable server error
                return null // terminal HTTP statuses: no retry
            } catch (_: Exception) {
                if (attempt == MAX_ATTEMPTS) return null // transport: bounded retry exhausted
            }
        }
        return null
    }

    /**
     * Scoped 401 authority invalidation: only when the ORIGINATING session is still current and
     * the captured bearer is still the current credential. Evidence is a fixed literal — the
     * bearer never enters generations, callbacks, or logs.
     */
    private suspend fun handleUnauthorized(op: FrozenOperation) {
        if (!isCurrent(op)) return
        val currentBearer = bearerProvider()
        if (currentBearer == null || currentBearer != op.bearer) return
        state.sessionEnded()
        onCredentialInvalid("credential_invalidated")
    }

    /** Established JSON writer: opaque values are properly escaped, built exactly once. */
    private fun writeBody(
        requestId: String,
        expectedRevision: String,
        epoch: String?,
        disclosureVersion: String?,
    ): okhttp3.RequestBody {
        val buffer = okio.Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        writer.name("request_id").value(requestId)
        writer.name("expected_membership_revision").value(expectedRevision)
        if (disclosureVersion != null) {
            writer.name("accepted_disclosure_version").value(disclosureVersion)
        }
        writer.name("enrollment_epoch")
        if (epoch != null) writer.value(epoch) else writer.nullValue()
        writer.endObject()
        writer.close()
        return buffer.readByteString().toRequestBody(jsonMediaType)
    }

    /** 202 deletion_pending receipt: strict complete parse, exact types/keys, numeric schema. */
    private fun parseDeletionReceipt(json: String): DeletionReceipt? = try {
        val reader = JsonReader.of(okio.Buffer().writeUtf8(json))
        reader.beginObject()
        var schemaOk = false
        var id: String? = null
        var status: String? = null
        val seen = HashSet<String>()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (!seen.add(key)) return null // decoded duplicate detection
            when (key) {
                "schema_version" -> {
                    if (reader.peek() != com.squareup.moshi.JsonReader.Token.NUMBER) return null
                    val lexeme = SchemaVersionLexeme.extract(json)
                    val value = reader.nextDouble()
                    schemaOk = value == 1.0 && lexeme != null && java.math.BigDecimal(lexeme).compareTo(java.math.BigDecimal.ONE) == 0
                }

                "deletion_request_id" -> id = reader.nonEmptyString()

                "status" -> status = reader.nonEmptyString()

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (reader.peek() != com.squareup.moshi.JsonReader.Token.END_DOCUMENT) return null
        if (!schemaOk || seen != setOf("schema_version", "deletion_request_id", "status")) return null
        if (id == null || status != "deletion_pending") return null
        DeletionReceipt(deletionRequestId = id, status = status)
    } catch (_: Exception) {
        null
    }

    private fun com.squareup.moshi.JsonReader.nonEmptyString(): String? {
        if (peek() != com.squareup.moshi.JsonReader.Token.STRING) return null
        return nextString().ifEmpty { null }
    }

    /** Everything an operation needs, frozen at creation time — before the first await. */
    private data class FrozenOperation(
        val generation: Long,
        val token: SessionToken,
        val bearer: String?,
        val requestBody: okhttp3.RequestBody,
    )

    private companion object {
        const val MAX_ATTEMPTS = 2
        private val jsonMediaTypeStatic = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY: okhttp3.RequestBody = ByteArray(0).toRequestBody(jsonMediaTypeStatic)
    }
}
