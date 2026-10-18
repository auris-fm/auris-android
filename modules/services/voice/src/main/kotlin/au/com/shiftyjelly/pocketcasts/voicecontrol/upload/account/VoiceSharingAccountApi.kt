package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.util.UUID
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

        /** Production overload: the prebuilt gateway retrofit (gateway API URL + cached client). */
        fun create(retrofit: Retrofit): VoiceSharingAccountApi = retrofit.create(VoiceSharingAccountApi::class.java)
    }
}

/**
 * Typed mutation outcomes (reviewer 290e76ea): membership operations never conflate
 * applied / conflict / failure into a Boolean. A 409 conflict is a DISTINCT public outcome
 * carrying the strictly parsed server-current projection; the operation itself was not
 * accepted (no auto re-enroll / no false success).
 */
sealed interface VoiceSharingMutationResult {
    /**
     * Terminal 200 apply. Carries the IMMUTABLE ORIGINAL issued operation context (ticket +
     * session token), NOT fresh-sampled state, so a caller resuming after this outcome can
     * fence its own continuation against exactly the operation that applied.
     */
    data class Applied(
        val projection: VoiceSharingAccountProjection,
        val token: SessionToken,
        val ticket: OperationTicket,
    ) : VoiceSharingMutationResult

    /**
     * 409 membership conflict, returned ONLY when the server-current projection parses
     * strictly AND the issued ticket was still valid at apply time (the apply was accepted).
     * Carries the original issued context for caller resume fencing.
     */
    data class MembershipConflict(
        val currentProjection: VoiceSharingAccountProjection,
        val token: SessionToken,
        val ticket: OperationTicket,
    ) : VoiceSharingMutationResult

    data object Failure : VoiceSharingMutationResult
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
    private val ownership: VoiceSharingCredentialOwnership,
    private val credentialSource: suspend () -> CredentialIncarnation?,
    private val onCredentialInvalid: suspend (CredentialIncarnation) -> Unit,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Current-session GET: the only way the state gains authority. */
    suspend fun refreshProjection(token: SessionToken): Boolean {
        // The GET itself creates authority: no projection precondition.
        val ticket = state.beginOperation(token, requireProjection = false) ?: return false
        val op = frozen(token, ticket, Route.GET, null)
        return when (val outcome = execute(op) { bearer, _ -> api.getVoiceSharing(bearer) }) {
            is Outcome.Body -> state.applyOutcome(token, ticket, outcome.json)
            else -> false
        }
    }

    suspend fun enroll(token: SessionToken, acceptedDisclosureVersion: String): VoiceSharingMutationResult {
        val ticket = state.beginOperation(token, requireProjection = true) ?: return VoiceSharingMutationResult.Failure
        val op = frozen(token, ticket, Route.ENROLLMENT, acceptedDisclosureVersion)
        return when (val outcome = execute(op) { bearer, payload -> api.postEnrollment(bearer, payload) }) {
            is Outcome.Body -> appliedOrFailure(token, ticket, outcome.json)
            is Outcome.Conflict -> conflictOrFailure(token, ticket, outcome.json)
            is Outcome.Rejected -> VoiceSharingMutationResult.Failure
        }
    }

    suspend fun withdraw(token: SessionToken): VoiceSharingMutationResult {
        val ticket = state.beginOperation(token, requireProjection = true) ?: return VoiceSharingMutationResult.Failure
        val op = frozen(token, ticket, Route.WITHDRAWAL, null)
        return when (val outcome = execute(op) { bearer, payload -> api.postWithdrawal(bearer, payload) }) {
            is Outcome.Body -> appliedOrFailure(token, ticket, outcome.json)
            is Outcome.Conflict -> conflictOrFailure(token, ticket, outcome.json)
            is Outcome.Rejected -> VoiceSharingMutationResult.Failure
        }
    }

    /** Fenced terminal apply of a 200 body; the outcome carries the ORIGINAL issued context. */
    private fun appliedOrFailure(token: SessionToken, ticket: OperationTicket, json: String): VoiceSharingMutationResult {
        val projection = state.parseProjection(json) ?: return VoiceSharingMutationResult.Failure
        return if (state.applyOutcome(token, ticket, json)) {
            VoiceSharingMutationResult.Applied(projection, token, ticket)
        } else {
            VoiceSharingMutationResult.Failure
        }
    }

    /**
     * 409 conflict: MembershipConflict ONLY when the body parses strictly AND the issued
     * ticket is still valid and the fenced apply was ACCEPTED. A malformed body or a
     * generation-stale 409 is Failure — never a conflict with a null projection.
     */
    private fun conflictOrFailure(token: SessionToken, ticket: OperationTicket, json: String): VoiceSharingMutationResult {
        val projection = state.parseProjection(json) ?: return VoiceSharingMutationResult.Failure
        if (!state.applyOutcome(token, ticket, json)) return VoiceSharingMutationResult.Failure
        return VoiceSharingMutationResult.MembershipConflict(projection, token, ticket)
    }

    suspend fun deleteData(token: SessionToken): DeletionReceipt? {
        val ticket = state.beginOperation(token, requireProjection = true) ?: return null
        val op = frozen(token, ticket, Route.DELETION, null)
        val body = execute(op, expectedStatus = 202) { bearer, payload -> api.deleteData(bearer, payload) }
            .let { (it as? Outcome.Body)?.json } ?: return null
        val receipt = parseDeletionReceipt(body) ?: return null
        // Fence the terminal outcome: a superseded session/generation discards the receipt.
        return if (state.isTicketCurrent(token, ticket)) receipt else null
    }

    private enum class Route { GET, ENROLLMENT, WITHDRAWAL, DELETION }

    private sealed interface Outcome {
        data class Body(val json: String) : Outcome

        /** Explicit 409 membership conflict: carries the current projection body. */
        data class Conflict(val json: String) : Outcome

        data object Rejected : Outcome
    }

    private suspend fun frozen(token: SessionToken, ticket: OperationTicket, route: Route, disclosureVersion: String?): FrozenOperation {
        // The credential INCARNATION (not a raw string) is captured at freeze — production
        // sources it from the REAL token owner (VoiceSharingAccountAuthBinding.syncCredential).
        val credential = credentialSource()
        val bearer = credential?.bearer
        // Revalidate the issued ticket AFTER awaiting the credential, before the first send.
        if (!state.isTicketCurrent(token, ticket)) {
            return FrozenOperation(token, ticket, credential, bearer, EMPTY_BODY, route)
        }
        val body = when (route) {
            Route.GET -> EMPTY_BODY
            else -> writeBody(ticket, route, disclosureVersion)
        }
        return FrozenOperation(token, ticket, credential, bearer, body, route)
    }

    /**
     * Sends with bounded immutable retry (transport/5xx only), enforcing the exact endpoint
     * status and revalidating the ticket before EVERY send. 401 scoped invalidation; 409
     * surfaces as [Outcome.Conflict]; other statuses terminal.
     */
    private suspend fun execute(
        op: FrozenOperation,
        expectedStatus: Int = 200,
        call: suspend (String?, okhttp3.RequestBody) -> Response<ResponseBody>,
    ): Outcome {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            // Ticket revalidation happens after every await boundary (bearer included).
            if (!state.isTicketCurrent(op.token, op.ticket)) return Outcome.Rejected
            try {
                val response = call(op.bearer, op.requestBody)
                when {
                    response.code() == 401 -> {
                        handleUnauthorized(op)
                        return Outcome.Rejected
                    }

                    response.code() == 409 && expectedStatus == 200 -> {
                        // Non-2xx content lives in errorBody() for Response<ResponseBody>.
                        val body = response.errorBody()?.string() ?: response.body()?.string()
                        return if (body.isNullOrBlank()) Outcome.Rejected else Outcome.Conflict(body)
                    }

                    response.code() == expectedStatus -> return Outcome.Body(response.body()!!.string())

                    response.code() in 500..599 && attempt < MAX_ATTEMPTS -> continue

                    else -> return Outcome.Rejected
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                when {
                    e.code() == 401 -> {
                        handleUnauthorized(op)
                        return Outcome.Rejected
                    }

                    e.code() == 409 && expectedStatus == 200 -> return Outcome.Rejected

                    // no body via HttpException
                    e.code() in 500..599 && attempt < MAX_ATTEMPTS -> continue

                    else -> return Outcome.Rejected
                }
            } catch (_: Exception) {
                if (attempt == MAX_ATTEMPTS) return Outcome.Rejected
            }
        }
        return Outcome.Rejected
    }

    /**
     * Scoped 401 invalidation as ONE COORDINATED OWNER TRANSACTION: the credential owner
     * compares the rejected incarnation, runs the scoped state check (ticket generation +
     * session), and clears the credential — all serialized under the credential store's
     * single transaction lock. Rotation can therefore neither split the two invalidations
     * nor slip between them: a stale rejection invalidates NOTHING (fresh incarnation AND
     * fresh session authority both survive), and an accepted one invalidates both together.
     * The effect fires only when the transaction committed, and carries the rejected
     * incarnation — never a raw bearer, never logged.
     */
    private suspend fun handleUnauthorized(op: FrozenOperation) {
        val rejected = op.credential ?: return
        val committed = ownership.invalidateIfCurrent(rejected) {
            state.processUnauthorized(op.token, op.ticket)
        }
        if (committed) onCredentialInvalid(rejected)
    }

    /** Endpoint-EXACT bodies from ONLY the ticket-captured values, built once, byte-identical retries. */
    private fun writeBody(ticket: OperationTicket, route: Route, disclosureVersion: String?): okhttp3.RequestBody {
        val projection = ticket.projection
        val requestId = UUID.randomUUID().toString()
        val buffer = okio.Buffer()
        val writer = JsonWriter.of(buffer)
        if (route == Route.WITHDRAWAL) writer.setSerializeNulls(true) // withdrawal requires explicit null epoch
        writer.beginObject()
        writer.name("request_id").value(requestId)
        when (route) {
            Route.ENROLLMENT, Route.WITHDRAWAL -> {
                writer.name("expected_membership_revision").value(projection?.membershipRevision ?: "")
                if (route == Route.ENROLLMENT && disclosureVersion != null) {
                    writer.name("accepted_disclosure_version").value(disclosureVersion)
                }
                if (route == Route.WITHDRAWAL) {
                    writer.name("enrollment_epoch")
                    val epoch = projection?.enrollmentEpoch
                    if (epoch != null) writer.value(epoch) else writer.nullValue()
                }
            }

            else -> Unit // GET sends nothing; deletion carries ONLY the request_id
        }
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
                    val lexeme = reader.nextString() // NUMBER lexeme preserved exactly
                    schemaOk = java.math.BigDecimal(lexeme).compareTo(java.math.BigDecimal.ONE) == 0
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
        val token: SessionToken,
        val ticket: OperationTicket,
        val credential: CredentialIncarnation?,
        val bearer: String?,
        val requestBody: okhttp3.RequestBody,
        val route: Route,
    )

    private companion object {
        const val MAX_ATTEMPTS = 2
        private val jsonMediaTypeStatic = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY: okhttp3.RequestBody = ByteArray(0).toRequestBody(jsonMediaTypeStatic)
    }
}
