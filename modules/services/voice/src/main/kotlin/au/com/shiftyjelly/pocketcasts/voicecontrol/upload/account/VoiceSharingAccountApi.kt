package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import org.json.JSONTokener
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST
import timber.log.Timber

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

    @POST("/api/v1/account/voice-sharing/capture-allowance")
    suspend fun postCaptureAllowance(
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
data class DeletionReceipt(val deletionRequestId: String, val status: String, val cutoff: String? = null)

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
 * - A 401 captured while the ORIGINATING session/generation is still current revokes that scoped
 *   voice authority and forces an Auris token refresh. It does NOT invalidate the upstream account
 *   session: this route only ever sees the Auris token, so that is the only credential a 401 can
 *   speak about (cloud `bbb9beff`). A stale 401 -- session superseded or generation moved -- has no
 *   effect on anything. Credential data never appears in generations or failure evidence.
 */
class VoiceSharingAccountOperations(
    private val api: VoiceSharingAccountApi,
    private val state: VoiceSharingAccountState,
    private val ownership: CredentialOwnershipOwner,
    /**
     * THE BEARER SOURCE. The account route verifies Auris-issued tokens only (cloud `84a4d25e`,
     * `bbb9beff`); the upstream Pocket Casts token the owner publishes is `alg=HS256` with no
     * `kid` and that route refuses it structurally. The owner snapshot still decides whether we
     * may act at all -- it just stops being the bearer, which is the whole of the defect.
     */
    private val aurisToken: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding,
    private val onCredentialInvalid: suspend (AuthoritySnapshot) -> Unit,
    /**
     * The AURIS credential's own invalidation, deliberately NOT the owner's `invalidateAuthToken`.
     *
     * A 401 on this route can only mean "the Auris token was refused": the route never sees the
     * upstream session credential (cloud `bbb9beff`). Handing that 401 to the owner would make a
     * rejected *derived* token sign the user out of the account that MINTED it -- and
     * `invalidateIfCurrent` clears `publishedToken` and advances the credential incarnation, so the
     * session is the one that dies, not the Auris token. The owner snapshot stays the session
     * fence; the Auris provider is cleared here instead.
     */
    private val onAurisCredentialRefused: suspend () -> Unit = { aurisToken.refreshToken() },
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Current-session GET: the only way the state gains authority. */
    suspend fun refreshProjection(token: SessionToken): Boolean {
        // OWNER→voice issuance: ticket + authority snapshot under the owner lock.
        val issued = ownership.transaction {
            val live = ownership.authoritySnapshot()
            val ticket = state.beginOperation(token, requireProjection = false, ownerSession = live.session)
            if (ticket == null) null else Triple(live, ticket, Unit)
        } ?: return false
        val (live, ticket, _) = issued
        // ACQUIRE BEFORE THE FREEZE AND REFUSE ON ABSENCE: no Auris credential means no request.
        val bearer = aurisBearer() ?: return false
        val op = frozen(token, ticket, live, bearer, Route.GET, null)
        return when (val outcome = execute(op) { bearer, _ -> api.getVoiceSharing(bearer) }) {
            is Outcome.Body -> {
                val applied = ownership.transaction { state.applyOutcome(token, ticket, outcome.json, ownership.authoritySnapshot().session) }
                state.onRefreshObserved?.invoke(
                    VoiceSharingRefreshDiagnostic(kind = VoiceSharingRefreshDiagnostic.Kind.APPLIED, status = null, bodyBytes = outcome.json.length, requestIds = outcome.requestIds, detail = applied.toString()),
                )
                applied
            }

            else -> {
                state.onRefreshObserved?.invoke(
                    VoiceSharingRefreshDiagnostic(
                        kind = VoiceSharingRefreshDiagnostic.Kind.REJECTED,
                        status = when (outcome) {
                            is Outcome.Conflict -> 409

                            is Outcome.Rejected -> 401

                            // scoped auth rejection ONLY (a real 401)
                            is Outcome.Unavailable -> outcome.status

                            is Outcome.Body -> 200
                        },
                        bodyBytes = (outcome as? Outcome.Conflict)?.json?.length,
                        requestIds = outcome.requestIds,
                        detail = when (outcome) {
                            is Outcome.Unavailable -> "UNAVAILABLE (not an auth rejection; no credential cleared)"
                            else -> outcome::class.simpleName ?: "?"
                        },
                    ),
                )
                false
            }
        }
    }

    suspend fun enroll(token: SessionToken, acceptedDisclosureVersion: String): VoiceSharingMutationResult {
        val issued = ownership.transaction {
            val live = ownership.authoritySnapshot()
            val ticket = state.beginOperation(token, requireProjection = true, ownerSession = live.session)
            if (ticket == null) null else live to ticket
        } ?: return VoiceSharingMutationResult.Failure
        val (live, ticket) = issued
        val bearer = aurisBearer() ?: return VoiceSharingMutationResult.Failure
        val op = frozen(token, ticket, live, bearer, Route.ENROLLMENT, acceptedDisclosureVersion)
        return when (val outcome = execute(op) { bearer, payload -> api.postEnrollment(bearer, payload) }) {
            is Outcome.Body -> appliedOrFailure(op, outcome.json)
            is Outcome.Conflict -> conflictOrFailure(op, outcome.json)
            is Outcome.Rejected, is Outcome.Unavailable -> VoiceSharingMutationResult.Failure
        }
    }

    suspend fun withdraw(token: SessionToken): VoiceSharingMutationResult {
        val issued = ownership.transaction {
            val live = ownership.authoritySnapshot()
            val ticket = state.beginOperation(token, requireProjection = true, ownerSession = live.session)
            if (ticket == null) null else live to ticket
        } ?: return VoiceSharingMutationResult.Failure
        val (live, ticket) = issued
        val bearer = aurisBearer() ?: return VoiceSharingMutationResult.Failure
        val op = frozen(token, ticket, live, bearer, Route.WITHDRAWAL, null)
        return when (val outcome = execute(op) { bearer, payload -> api.postWithdrawal(bearer, payload) }) {
            is Outcome.Body -> appliedOrFailure(op, outcome.json)
            is Outcome.Conflict -> conflictOrFailure(op, outcome.json)
            is Outcome.Rejected, is Outcome.Unavailable -> VoiceSharingMutationResult.Failure
        }
    }

    /** Fenced terminal apply of a 200 body; the outcome carries the ORIGINAL issued context. */
    private fun appliedOrFailure(op: FrozenOperation, json: String): VoiceSharingMutationResult {
        val projection = state.parseProjection(json) ?: return VoiceSharingMutationResult.Failure
        val applied = ownership.transaction { state.applyOutcome(op.token, op.ticket, json, ownership.authoritySnapshot().session) }
        return if (applied) {
            VoiceSharingMutationResult.Applied(projection, op.token, op.ticket)
        } else {
            VoiceSharingMutationResult.Failure
        }
    }

    /**
     * 409 conflict: MembershipConflict ONLY when the body parses strictly AND the issued
     * ticket is still valid and the fenced apply was ACCEPTED. A malformed body or a
     * generation-stale 409 is Failure — never a conflict with a null projection.
     */
    private fun conflictOrFailure(op: FrozenOperation, json: String): VoiceSharingMutationResult {
        val projection = state.parseProjection(json) ?: return VoiceSharingMutationResult.Failure
        val applied = ownership.transaction { state.applyOutcome(op.token, op.ticket, json, ownership.authoritySnapshot().session) }
        if (!applied) return VoiceSharingMutationResult.Failure
        return VoiceSharingMutationResult.MembershipConflict(projection, op.token, op.ticket)
    }

    suspend fun deleteData(token: SessionToken): DeletionReceipt? {
        val issued = ownership.transaction {
            val live = ownership.authoritySnapshot()
            val ticket = state.beginOperation(token, requireProjection = true, ownerSession = live.session)
            if (ticket == null) null else live to ticket
        } ?: return null
        val (live, ticket) = issued
        val bearer = aurisBearer() ?: return null
        val op = frozen(token, ticket, live, bearer, Route.DELETION, null)
        val body = execute(op, expectedStatus = 202) { bearer, payload -> api.deleteData(bearer, payload) }
            .let { (it as? Outcome.Body)?.json } ?: return null
        val receipt = parseDeletionReceipt(body) ?: return null
        // Fence the terminal outcome: a superseded session/generation discards the receipt.
        return if (ownership.transaction { state.isTicketCurrent(token, ticket, ownership.authoritySnapshot().session) }) receipt else null
    }

    /**
     * Acquires the Auris bearer for an operation that has ALREADY been issued a ticket.
     *
     * Refusing here is the whole point: @spec's ruling is that a missing Auris configuration or a
     * missing token must REFUSE sharing, never fall back to the upstream mirror -- a fallback that
     * quietly supplies *some* credential is the defect being fixed, and it looks exactly like
     * success. Returns null when there is nothing to send, and the caller then sends nothing.
     */
    private suspend fun aurisBearer(): String? = aurisToken.currentToken()?.takeIf { it.isNotBlank() }

    private enum class Route { GET, ENROLLMENT, WITHDRAWAL, DELETION }

    private sealed interface Outcome {
        /** Server correlation ids harvested from every HTTP attempt of this call, in order. */
        val requestIds: List<String>

        data class Body(val json: String, override val requestIds: List<String> = emptyList()) : Outcome

        /** Explicit 409 membership conflict: carries the current projection body. */
        data class Conflict(val json: String, override val requestIds: List<String> = emptyList()) : Outcome

        data class Rejected(override val requestIds: List<String> = emptyList()) : Outcome

        /**
         * NON-AUTH refusal: transport failure or an exhausted retry on a 5xx/other status. It is
         * explicitly NOT an authentication rejection — it must never clear credentials or sign the
         * user out (reviewer fe03ebf7). The last observed HTTP status rides along for diagnosis.
         */
        data class Unavailable(val status: Int?, override val requestIds: List<String> = emptyList()) : Outcome
    }

    private fun frozen(token: SessionToken, ticket: OperationTicket, observed: AuthoritySnapshot, aurisBearer: String, route: Route, disclosureVersion: String?): FrozenOperation {
        // The bearer was ACQUIRED before the freeze and is passed in, so the token that goes out
        // is the one taken under the same issuance the ticket belongs to. The owner-published
        // `observed.token` is deliberately NOT consulted: it is the upstream credential this route
        // cannot verify.
        val bearer = "Bearer " + aurisBearer
        // THE KEY ID IS LOGGED WHERE THE BEARER IS ATTACHED, and nowhere else. It answers one
        // question the server cannot answer for us: whether THIS app's token names a signing key
        // staging publishes (cloud 84a4d25e). Only the JOSE header is read -- never the payload,
        // never the signature -- and an unreadable header is reported as absent rather than
        // guessed at. This is a DIAGNOSTIC: it changes no branch, no retry and no credential path.
        logTokenKeyId(aurisBearer)
        val body = when (route) {
            Route.GET -> EMPTY_BODY
            else -> writeBody(ticket, route, disclosureVersion)
        }
        return FrozenOperation(token, ticket, observed, bearer, body, route)
    }

    /**
     * Logs the JOSE header's `kid`/`alg` for the token actually being sent.
     *
     * A JWS has three dot-separated segments and the FIRST is the base64url header. Anything else
     * is opaque and reported as such -- a token that cannot be parsed is not forced through a
     * parser to manufacture a value. Every failure mode (wrong segment count, bad base64, missing
     * `kid`, oversized header, malformed JSON) ends in a stated absence, never an exception: this
     * function must not be able to change how a request behaves.
     */
    private fun logTokenKeyId(tokenValue: String) = logTokenKeyIdLine(tokenValue)

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
        var lastStatus: Int? = null
        // Bound the correlation-id list: one entry per attempt, capped and truncated.
        val requestIds = mutableListOf<String>()
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            // Ticket revalidation happens after every await boundary — ONE owner→voice
            // transaction: the LIVE owner snapshot is read and the ticket checked together
            // inside the same lock (b680934d: no two-transaction split).
            val current = ownership.transaction { state.isTicketCurrent(op.token, op.ticket, ownership.authoritySnapshot().session) }
            if (!current) return Outcome.Rejected(requestIds.toList())
            try {
                val response = call(op.bearer, op.requestBody)
                response.headers()[REQUEST_ID_HEADER]?.take(MAX_REQUEST_ID_CHARS)?.let { requestIds += it }
                when {
                    response.code() == 401 -> {
                        handleUnauthorized(op)
                        return Outcome.Rejected(requestIds.toList())
                    }

                    response.code() == 409 && expectedStatus == 200 -> {
                        // Non-2xx content lives in errorBody() for Response<ResponseBody>.
                        val body = response.errorBody()?.string() ?: response.body()?.string()
                        return if (body.isNullOrBlank()) Outcome.Rejected(requestIds.toList()) else Outcome.Conflict(body, requestIds.toList())
                    }

                    response.code() == expectedStatus -> return Outcome.Body(response.body()!!.string(), requestIds.toList())

                    response.code() in 500..599 && attempt < MAX_ATTEMPTS -> {
                        lastStatus = response.code()
                        continue
                    }

                    // Any other non-2xx is UNAVAILABLE, not an auth rejection: a 5xx that
                    // exhausted its retry, or a status this endpoint never promised.
                    else -> return Outcome.Unavailable(response.code(), requestIds.toList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                when {
                    e.code() == 401 -> {
                        handleUnauthorized(op)
                        return Outcome.Rejected(requestIds.toList())
                    }

                    e.code() == 409 && expectedStatus == 200 -> return Outcome.Rejected(requestIds.toList())

                    // no body via HttpException
                    e.code() in 500..599 && attempt < MAX_ATTEMPTS -> {
                        lastStatus = e.code()
                        continue
                    }

                    // EXHAUSTED 5xx and every other non-401 status are UNAVAILABLE, exactly as on
                    // the body path: never an authentication rejection (reviewer 5bbccf09/ecfdffe5).
                    else -> return Outcome.Unavailable(e.code(), requestIds.toList())
                }
            } catch (_: Exception) {
                if (attempt == MAX_ATTEMPTS) return Outcome.Unavailable(lastStatus, requestIds.toList())
            }
        }
        return Outcome.Unavailable(lastStatus, requestIds.toList())
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
        // ONLY a real 401 reaches here (Outcome.Unavailable never does -- reviewer fe03ebf7).
        //
        // The rejection COMMITS inside one owner transaction: the owner snapshot is compared, the
        // voice scoped check runs, and the state revokes -- all under the owner lock, so a
        // superseding operation cannot slip between the check and the commit (reviewer b702cd53).
        // STALENESS (reviewer b702cd53): the 401 answers the AURIS credential this operation froze.
        // If that credential has since been re-minted, the rejection is history -- it commits
        // nothing, so the live voice authority a newer credential established survives.
        // The owner no longer clears the upstream credential for this route's 401 (it never sees
        // that credential -- cloud bbb9beff); the voice scope is what a committed rejection revokes.
        val frozenBearer = op.bearer ?: return
        // ATOMIC staleness + commit (reviewer 57246e46): the cache-publication monitor is held
        // across the whole comparison-and-commit, so a concurrent re-mint cannot land between
        // them. Staleness FIRST: a 401 answering a bearer that is no longer the cache's current
        // token (superseded mint, switched account, other origin, cleared cache) answers a
        // credential that has already been replaced -- commit NOTHING.
        val committed = aurisToken.withCurrentCachedToken { currentBearer ->
            ownership.transaction {
                "Bearer " + (currentBearer ?: "") == frozenBearer &&
                    state.processUnauthorized(op.token, op.ticket)
            }
        }
        if (!committed) return // effects fire ONLY for a rejection that actually committed
        // Delayed-effect fence, RE-CHECKED AFTER THE SUSPENSION (reviewer 57246e46): the refresh
        // above suspends, and a superseding session can establish its projection while it is in
        // flight -- this rejection's mirror clear must not touch that newer scope. The check runs
        // inside the owner transaction so a concurrent publication cannot slip past it.
        if (ownership.transaction { state.hasCurrentSessionProjection() }) return
        onAurisCredentialRefused() // force a re-mint from the SAME account
        onCredentialInvalid(op.observed) // post-commit notification, NO authority side effects
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

    /**
     * 202 deletion_pending receipt: strict complete parse, exact types/keys, numeric schema.
     * `cutoff` is an OPTIONAL RFC3339 instant (spec ruling 9f7505d6): when present it must parse as
     * a real instant or the body is malformed; when absent the receipt stays valid. Never skipped
     * silently — an unknown key is still rejected.
     */
    private fun parseDeletionReceipt(json: String): DeletionReceipt? = try {
        val reader = JsonReader.of(okio.Buffer().writeUtf8(json))
        reader.beginObject()
        var schemaOk = false
        var id: String? = null
        var status: String? = null
        var cutoff: String? = null
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

                "cutoff" -> {
                    val raw = reader.nonEmptyString() ?: return null
                    // A cutoff that cannot parse as a real instant is a malformed receipt, not an
                    // optional field to ignore.
                    runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return null
                    cutoff = raw
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (reader.peek() != com.squareup.moshi.JsonReader.Token.END_DOCUMENT) return null
        val required = setOf("schema_version", "deletion_request_id", "status")
        if (!schemaOk || !(required.all { it in seen } && seen.all { it in required + "cutoff" })) return null
        if (id == null || status != "deletion_pending") return null
        DeletionReceipt(deletionRequestId = id, status = status, cutoff = cutoff)
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
        val observed: AuthoritySnapshot,
        val bearer: String?,
        val requestBody: okhttp3.RequestBody,
        val route: Route,
    )

    private companion object {
        const val MAX_ATTEMPTS = 2

        /** Server correlation id echoed on every response (cloud `52fe3319`). */
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val MAX_REQUEST_ID_CHARS = 128

        private val jsonMediaTypeStatic = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY: okhttp3.RequestBody = ByteArray(0).toRequestBody(jsonMediaTypeStatic)
    }
}

private fun logTokenKeyIdLine(tokenValue: String) {
    val header = tokenJoseHeader(tokenValue)
    if (header == null) {
        Timber.tag(TAG).i("voice-sharing token kid: kidSource=opaque")
        return
    }
    val kid = header.optString("kid").takeIf { it.isNotEmpty() }
    val alg = header.optString("alg").takeIf { it.isNotEmpty() }
    Timber.tag(TAG).i(
        "voice-sharing token kid: kid=%s alg=%s kidSource=%s",
        kid?.take(MAX_KID_CHARS) ?: "-",
        alg?.take(MAX_KID_CHARS) ?: "-",
        if (kid == null) "absent" else "jwt",
    )
}

/**
 * The token's JOSE header as a JSON object, or null when the token is not a readable JWS.
 *
 * Null means "this is not a token we can read a header from" — it is NOT an empty header, so a
 * caller cannot mistake an opaque token for one whose `kid` happens to be missing.
 */
private fun tokenJoseHeader(tokenValue: String): JSONObject? = try {
    // A JWS is exactly three segments; anything else is not one, and is not parsed to find out.
    val parts = tokenValue.split('.')
    if (parts.size != JWS_SEGMENT_COUNT) return null
    val first = parts[0]
    if (first.isEmpty() || first.length > MAX_HEADER_B64_CHARS) return null
    val decoded = Base64.getUrlDecoder().decode(padBase64Url(first))
    if (decoded.size > MAX_HEADER_BYTES) return null
    // JSONTokener answers a JSONObject for an object, and throws for anything malformed -- both
    // are handled here, so a garbage header cannot reach the request path as an exception.
    JSONTokener(String(decoded, Charsets.UTF_8)).nextValue() as? JSONObject
} catch (_: Exception) {
    null
}

/** base64url without padding is legal in a JWS; the JDK decoder requires it. */
private fun padBase64Url(value: String): String = when (value.length % 4) {
    2 -> "$value=="
    3 -> "$value="
    else -> value
}

/** Bounds for the key-id diagnostic: a JOSE header is tiny, so anything larger is not one. */
private const val TAG = "VoiceSharingAccount"
private const val JWS_SEGMENT_COUNT = 3
private const val MAX_HEADER_B64_CHARS = 4096
private const val MAX_HEADER_BYTES = 2048
private const val MAX_KID_CHARS = 128
