package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import timber.log.Timber

/**
 * Auris account routes (Go-owned; docs/specs/voice-recording-uploads.md lines 50-53). The
 * bearer actually sent is passed explicitly per call and captured by the operations layer for
 * 401 handling; responses carry the exact HTTP status for endpoint-status enforcement.
 */
interface VoiceSharingAccountApi {

    @GET("/api/v1/account/voice-sharing")
    suspend fun getVoiceSharing(@Header("Authorization") bearer: String?): Response<ResponseBody>

    /**
     * THE SWITCH (spec 0264051b): one desired-state request. `If-Match` carries the last-read
     * `membership_revision` as an HTTP precondition, so a stale device is refused by the server
     * instead of overwriting a newer choice.
     * the switch: one desired-state request. `If-Match` carries the last-read
     * `membership_revision` as an HTTP precondition, so a stale device is refused by the server
     * instead of overwriting a newer choice.
     */
    @PUT("/api/v1/account/voice-sharing")
    suspend fun putVoiceSharing(
        @Header("Authorization") bearer: String?,
        @Body body: okhttp3.RequestBody,
    ): Response<ResponseBody>

    /**
     * Deletion is synchronous: it answers 204 with no body, and nothing is retried on the client's
     * side once the server has said it happened. There is no body to send and no receipt to parse.
     */
    @DELETE("/api/v1/account/voice-sharing/data")
    suspend fun deleteData(
        @Header("Authorization") bearer: String?,
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
    /** Terminal 200 apply: the projection the server returned and published. */
    data class Applied(
        val projection: VoiceSharingAccountProjection,
    ) : VoiceSharingMutationResult

    data object Failure : VoiceSharingMutationResult
}

/**
 * Account operations (the consent path, spec 06392988). Contract:
 *
 * - Authorization is ORDINARY: every request carries the Auris-issued signed-in bearer, read at
 *   send time. There is no separate sharing identity, session token or operation ticket; an
 *   operation that loses its account simply fails.
 * - A request body is built ONCE from the projection observed at issue, so retries are
 *   byte-identical (same request_id) and never re-read state after an await.
 * - Exact endpoint statuses: membership routes 200, deletion 202. Transport failures and 503
 *   retry within bounds; 401/403/409/422 are terminal with no retry.
 * - A 401 revokes only the AURIS credential (forcing a re-mint from the SAME account) and drops
 *   the mirror. It never touches the upstream Pocket Casts session: a rejected derived token must
 *   not sign the user out of the account that minted it.
 * byte-identical (same request_id) and never re-read state after an await.
 * - Exact endpoint statuses: membership routes 200, deletion 202. Transport failures and 503
 * retry within bounds; 401/403/409/422 are terminal with no retry.
 * - A 401 revokes only the auris credential (forcing a re-mint from the same account) and drops
 * the mirror. It never touches the upstream Pocket Casts session: a rejected derived token must
 * not sign the user out of the account that minted it.
 * - A response is discarded when a NEWER projection publication already landed (the store's
 *   publication count), so a slow answer can never overwrite newer membership state.
 */
class VoiceSharingAccountOperations(
    private val api: VoiceSharingAccountApi,
    private val state: VoiceSharingAccountState,
    private val ownership: CredentialOwnershipOwner,
    /**
     * THE BEARER SOURCE. The account route verifies Auris-issued tokens only (cloud `84a4d25e`,
     * `bbb9beff`); the upstream Pocket Casts token the owner publishes is `alg=HS256` with no
     * `kid` and that route refuses it structurally.
     * the bearer source. The account route verifies Auris-issued tokens only (cloud `84a4d25e`,
     * `bbb9beff`); the upstream Pocket Casts token the owner publishes is `alg=HS256` with no
     * `kid` and that route refuses it structurally.
     */
    private val aurisToken: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding,
    /** Mirror clear after a committed 401 (synchronous, no authority effect). */
    private val onCredentialInvalid: (AuthoritySnapshot) -> Unit,
    /**
     * The AURIS credential's own invalidation, deliberately NOT the owner's `invalidateAuthToken`:
     * a 401 on this route can only speak about the derived Auris token.
     */
    private val onAurisCredentialRefused: suspend () -> Unit = { aurisToken.refreshToken() },
    /**
     * THE DURABLE MIRROR OF THE USER'S OWN CHOICE (spec 1af5e705). Every genuine server answer that
     * [apply] accepts lands here too, so the saved choice tracks exactly what the account flow
     * obtained: enroll responses write the enrolled record, withdrawal responses write the stopped
     * one. No polling, no extra request - the same answer that feeds the Settings display.
     */
    private val savedSharingChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore? = null,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Current-session GET: the way the state gains authority. */
    suspend fun refreshProjection(): Boolean {
        val request = issue(Route.GET, null) ?: return false
        return when (val outcome = execute(request.op) { b, _ -> api.getVoiceSharing(b) }) {
            is Outcome.Body -> {
                val applied = apply(outcome.json, request.issued)
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
                            is Outcome.Rejected -> 401
                            is Outcome.Unavailable -> outcome.status
                            is Outcome.Body, is Outcome.Done -> 200
                        },
                        bodyBytes = null,
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

    /**
     * THE SWITCH: send the state the user picked. On/off are the same request, so a repeat or a
     * resend after a network failure is safe by construction — the server derives whether to start
     * an epoch, keep it, or withdraw.
     *
     * [acceptedDisclosureVersion] is required when turning sharing ON; the server refuses an opt-in
     * that names no accepted disclosure, so this refuses to send one rather than letting the record
     * of what the user agreed to be absent.
     */
    suspend fun setVoiceSharing(enrolled: Boolean, acceptedDisclosureVersion: String?): VoiceSharingMutationResult {
        if (enrolled && acceptedDisclosureVersion.isNullOrBlank()) return VoiceSharingMutationResult.Failure
        // ONE INTENT, ONE OWNER (reviewer 54627e05): the user's choice is expressed under the account
        // that is signed in NOW, and the read-first step below suspends — a switch to another account
        // can land while it runs. Capturing the owner here means B can never receive a choice A
        // expressed: the switch is refused instead of being issued under the new session.
        val (ownerAccount, ownerSession) = ownerNow()
        // THE PRECONDITION IS REQUIRED (spec e3cfc8ae): the server refuses a PUT that names no
        // revision, so a client holding none READS FIRST and then sends that revision. The read is
        // the ordinary GET, so its answer also becomes the current projection — a switch issued on a
        // stale screen then fails its own precondition instead of overwriting a newer choice. The
        // read of the projection happens INSIDE that owner check, so a read that lands for a
        // superseded account cannot be borrowed by the new one.
        if (state.projection.value?.membershipRevision == null) {
            if (!refreshProjection()) return VoiceSharingMutationResult.Failure
            // A read that landed for a SUPERSEDED account must not be borrowed: the choice was
            // expressed under [ownerAccount], so the session must still be that one here.
            val (accountNow, sessionNow) = ownerNow()
            if (accountNow != ownerAccount || sessionNow != ownerSession) return VoiceSharingMutationResult.Failure
        }
        // The precondition is required: the server refuses a PUT that names no
        // Revision, so a client holding none reads first and then sends that revision. The read is
        // The ordinary GET, so its answer also becomes the current projection — a switch issued on a
        // Stale screen then fails its own precondition instead of overwriting a newer choice. The
        // Read of the projection happens INSIDE that owner check, so a read that lands for a
        // Superseded account cannot be borrowed by the new one.
        if (state.projection.value?.membershipRevision == null) {
            if (!refreshProjection()) return VoiceSharingMutationResult.Failure
            // A read that landed for a SUPERSEDED account must not be borrowed: the choice was
            // Expressed under [ownerAccount], so the session must still be that one here.
            val (accountNow, sessionNow) = ownerNow()
            if (accountNow != ownerAccount || sessionNow != ownerSession) return VoiceSharingMutationResult.Failure
        }
        val request = issue(Route.SWITCH, if (enrolled) acceptedDisclosureVersion else null, ownerAccount, ownerSession)
            ?: return VoiceSharingMutationResult.Failure
        return when (val outcome = execute(request.op) { b, payload -> api.putVoiceSharing(b, payload) }) {
            is Outcome.Body -> appliedOrFailure(outcome.json, request.issued)
            is Outcome.Done, is Outcome.Rejected, is Outcome.Unavailable -> VoiceSharingMutationResult.Failure
        }
    }

    suspend fun enroll(acceptedDisclosureVersion: String): VoiceSharingMutationResult = setVoiceSharing(enrolled = true, acceptedDisclosureVersion = acceptedDisclosureVersion)

    suspend fun withdraw(): VoiceSharingMutationResult = setVoiceSharing(enrolled = false, acceptedDisclosureVersion = null)

    /** Terminal apply of a 200 body; the newest publication wins. */
    private fun appliedOrFailure(json: String, issued: Issued): VoiceSharingMutationResult {
        val projection = state.parseProjection(json) ?: return VoiceSharingMutationResult.Failure
        return if (apply(json, issued)) VoiceSharingMutationResult.Applied(projection) else VoiceSharingMutationResult.Failure
    }

    /**
     * Publishes an answer ONLY for the account that asked and ONLY if nothing newer landed while it
     * was in flight. The validation and the write are ONE owner critical section, so an answer that
     * belongs to a previous account — or that a newer answer already superseded — can never become
     * the current projection. Without this, a slow request for account A could publish A's
     * enrollment as B's consent.
     */
    private fun apply(json: String, issued: Issued): Boolean = ownership.transaction {
        val current = ownership.authoritySnapshot()
        if (current.accountName != issued.accountName || current.session != issued.session) {
            return@transaction false // a different account (or a new login) owns the answer now
        }
        if (state.publicationCount() != issued.publication) {
            return@transaction false // a newer answer already landed
        }
        val projection = state.parseProjection(json) ?: return@transaction false
        state.publish(projection)
        persistSavedChoiceLocked(current.accountName, projection)
        true
    }

    /**
     * Persist the saved choice INSIDE the same owner transaction that accepted the answer, so the
     * durable record and the account it belongs to can never disagree. A failed write does not undo
     * the server answer, but admission fails closed on the missing record rather than silently
     * resuming, and the failure is logged rather than swallowed.
     */
    private fun persistSavedChoiceLocked(account: String?, projection: VoiceSharingAccountProjection) {
        val store = savedSharingChoice ?: return
        if (account.isNullOrBlank()) return
        val persisted = when {
            projection.voiceSharingEnrolled && projection.enrollmentEpoch != null &&
                projection.acceptedDisclosureVersion != null ->
                store.recordEnrolled(account, projection.enrollmentEpoch, projection.acceptedDisclosureVersion)

            projection.withdrawalState == WithdrawalState.WITHDRAWN ->
                store.recordWithdrawn(account)

            // NOT_ENROLLED is the account's ACTUAL state: persist it, or a stale ENROLLED record
            // would keep admitting captures the server has already closed (@spec 3f1e939a).
            else -> store.recordNotEnrolled(account)
        }
        if (persisted == false) {
            // Not just a log line: the store LATCHES the failure and refuses consent reads until a
            // write succeeds, so a stale record cannot capture through a silent file.
            android.util.Log.w("VoiceSharingChoice", "voice-sharing choice persist: outcome=failed latched")
        }
    }

    /**
     * Delete the account's accepted recordings.
     *
     * Returns true when the server confirmed it. A synchronous 204 means the work is done, so there
     * is no receipt, no request id and no second step to follow.
     */
    suspend fun deleteData(): Boolean {
        val request = issue(Route.DELETION, null) ?: return false
        val outcome = execute(request.op, expectedStatus = 204) { b, _ -> api.deleteData(b) }
        return outcome is Outcome.Done
    }

    /**
     * Acquires the Auris bearer. Refusing here is the whole point: a missing Auris configuration
     * or a missing token must REFUSE sharing, never fall back to the upstream mirror — a fallback
     * that quietly supplies *some* credential is the defect being fixed and looks like success.
     */
    private suspend fun aurisBearer(): String? = aurisToken.currentToken()?.takeIf { it.isNotBlank() }

    /**
     * What must still hold when an answer is applied: the ORDINARY account ownership observed when
     * the request was ISSUED, and the publication value the answer is allowed to replace.
     *
     * Both are captured BEFORE the request goes out, because the interesting changes happen while
     * it is in flight: a logout or an account switch, or a newer answer landing first. Neither can
     * be seen by reading state after the await — that read describes the moment of return, not the
     * moment of issue.
     */
    private data class Issued(
        val accountName: String?,
        val session: Long,
        val publication: Long,
    )

    /** Captures the ordinary owner this request will be issued for. */
    private fun ownerNow(): Pair<String?, Long> = ownership.transaction {
        val snapshot = ownership.authoritySnapshot()
        snapshot.accountName to snapshot.session
    }

    /**
     * Issues one request under ONE ordinary owner, refusing rather than rebinding.
     *
     * The bearer acquisition SUSPENDS, so another thread can sign a different account in while it
     * runs. Acquiring first and capturing afterwards would send A's bearer under B's captured
     * ownership — that interleaving needs no suspension at all, just a switch on another thread.
     * So the owner is captured BEFORE the bearer and must still be the same account and session
     * AFTER it: a bearer that no longer belongs to the captured owner is DISCARDED, never
     * relabelled. A same-account token refresh passes, because it moves the credential, not the
     * session.
     */
    private suspend fun issue(route: Route, disclosureVersion: String?): IssuedRequest? {
        val (accountName, session) = ownerNow()
        return issue(route, disclosureVersion, accountName, session)
    }

    /**
     * [issue] for a request whose intent was expressed under an ALREADY CAPTURED owner (the switch's
     * read-first step). The captured owner must still be the live one, both before the bearer is
     * acquired and after it, or the request is refused rather than relabelled onto a new session.
     * [issue] for a request whose intent was expressed under an already captured owner (the switch's
     * read-first step). The captured owner must still be the live one, both before the bearer is
     * acquired and after it, or the request is refused rather than relabelled onto a new session.
     */
    private suspend fun issue(route: Route, disclosureVersion: String?, accountName: String?, session: Long): IssuedRequest? {
        val (liveAccount, liveSession) = ownerNow()
        if (liveAccount != accountName || liveSession != session) return null
        val bearer = aurisBearer() ?: return null
        return ownership.transaction {
            val snapshot = ownership.authoritySnapshot()
            if (snapshot.accountName != accountName || snapshot.session != session) return@transaction null
            // The precondition is READ inside this same transaction: frozen() refuses a SWITCH without
            // one, and a projection cleared during bearer acquisition must not degrade into an
            // unconditional PUT.
            // The precondition is read inside this same transaction: frozen() refuses a switch without
            // One, and a projection cleared during bearer acquisition must not degrade into an
            // Unconditional PUT.
            val op = frozen(bearer, route, disclosureVersion) ?: return@transaction null
            IssuedRequest(op, Issued(accountName, session, state.publicationCount()))
        }
    }

    private class IssuedRequest(val op: FrozenOperation, val issued: Issued)

    private enum class Route { GET, SWITCH, DELETION }

    private sealed interface Outcome {
        /** Server correlation ids harvested from every HTTP attempt of this call, in order. */
        val requestIds: List<String>

        data class Body(val json: String, override val requestIds: List<String> = emptyList()) : Outcome

        /** The expected status with no body: a synchronous 204, where the work is already done. */
        data class Done(override val requestIds: List<String> = emptyList()) : Outcome

        data class Rejected(override val requestIds: List<String> = emptyList()) : Outcome

        /**
         * NON-AUTH refusal: transport failure or an exhausted retry on a 5xx/other status. It is
         * explicitly NOT an authentication rejection — it must never clear credentials or sign the
         * user out. The last observed HTTP status rides along for diagnosis.
         */
        data class Unavailable(val status: Int?, override val requestIds: List<String> = emptyList()) : Outcome
    }

    private fun frozen(aurisBearer: String, route: Route, disclosureVersion: String?, projection: VoiceSharingAccountProjection? = state.projection.value): FrozenOperation? {
        val bearer = "Bearer " + aurisBearer
        // THE KEY ID IS LOGGED WHERE THE BEARER IS ATTACHED, and nowhere else: it answers whether
        // THIS app's token names a signing key staging publishes (cloud 84a4d25e). Only the JOSE
        // header is read, and a diagnostic changes no branch, no retry and no credential path.
        // The key ID is logged where the bearer is attached, and nowhere else: it answers whether
        // This app's token names a signing key staging publishes (cloud 84a4d25e). Only the jose
        // Header is read, and a diagnostic changes no branch, no retry and no credential path.
        logTokenKeyId(aurisBearer)
        val body = when (route) {
            Route.GET -> EMPTY_BODY

            // Synchronous deletion: no body, no request id.
            Route.DELETION -> EMPTY_BODY

            // The desired state IS "a disclosure was accepted": turning on names one, turning off
            // names none. Both are the same request shape.
            else -> writeBody(enrolled = disclosureVersion != null, projection = projection, route = route, disclosureVersion = disclosureVersion)
        }
        val ifMatch = projection?.membershipRevision?.takeIf { it.isNotBlank() }
        // A SWITCH NEVER LEAVES WITHOUT ITS PRECONDITION (spec e3cfc8ae, reviewer 54627e05): the
        // revision can disappear between the read-first step and this construction — a lifecycle
        // clear empties the projection — and sending the PUT anyway would override another device's
        // newer choice. The refusal happens HERE, at request construction, so no caller can forget
        // the check.
        if (route == Route.SWITCH && ifMatch == null) return null
        return FrozenOperation(bearer, body, route, ifMatch)
        val ifMatch = projection?.membershipRevision?.takeIf { it.isNotBlank() }
        // A switch never leaves without its precondition: the
        // Revision can disappear between the read-first step and this construction — a lifecycle
        // Clear empties the projection — and sending the PUT anyway would override another device's
        // Newer choice. The refusal happens HERE, at request construction, so no caller can forget
        // The check.
        if (route == Route.SWITCH && ifMatch == null) return null
        return FrozenOperation(bearer, body, route, ifMatch)
        return FrozenOperation(bearer, body, route)
    }

    /** Sends with bounded immutable retry (transport/5xx only), enforcing the exact endpoint status. */
    private suspend fun execute(
        op: FrozenOperation,
        expectedStatus: Int = 200,
        call: suspend (String?, okhttp3.RequestBody) -> Response<ResponseBody>,
    ): Outcome {
        var attempt = 0
        var lastStatus: Int? = null
        val requestIds = mutableListOf<String>()
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                val response = call(op.bearer, op.requestBody)
                response.headers()[REQUEST_ID_HEADER]?.take(MAX_REQUEST_ID_CHARS)?.let { requestIds += it }
                when {
                    response.code() == 401 -> {
                        handleUnauthorized(op)
                        return Outcome.Rejected(requestIds.toList())
                    }

                    // A 204 has no body at all, so reading it would throw on a success.
                    response.code() == expectedStatus ->
                        return if (expectedStatus == 204) {
                            Outcome.Done(requestIds.toList())
                        } else {
                            Outcome.Body(response.body()!!.string(), requestIds.toList())
                        }

                    response.code() in 500..599 && attempt < MAX_ATTEMPTS -> {
                        lastStatus = response.code()
                        continue
                    }

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

                    e.code() in 500..599 && attempt < MAX_ATTEMPTS -> {
                        lastStatus = e.code()
                        continue
                    }

                    else -> return Outcome.Unavailable(e.code(), requestIds.toList())
                }
            } catch (_: Exception) {
                if (attempt == MAX_ATTEMPTS) return Outcome.Unavailable(lastStatus, requestIds.toList())
            }
        }
        return Outcome.Unavailable(lastStatus, requestIds.toList())
    }

    /**
     * A real 401 answers the AURIS credential this operation froze. The staleness check and the
     * effects run under the credential owner's monitor, so a concurrent re-mint cannot land between
     * them: if the cache already moved on, the rejection is history and commits NOTHING.
     */
    private suspend fun handleUnauthorized(op: FrozenOperation) {
        val frozenBearer = op.bearer
        val committed = aurisToken.withCurrentCachedToken { currentBearer ->
            ownership.credentialTransaction { "Bearer " + (currentBearer ?: "") == frozenBearer }
        }
        if (!committed) return // effects fire ONLY for a rejection that actually committed
        onAurisCredentialRefused() // force a re-mint from the SAME account
        ownership.credentialTransaction { onCredentialInvalid(ownership.authoritySnapshot()) }
    }

    /** Endpoint-EXACT bodies from the observed projection, built once, byte-identical retries. */
    private fun writeBody(projection: VoiceSharingAccountProjection?, route: Route, disclosureVersion: String?, enrolled: Boolean = false): okhttp3.RequestBody {
        val requestId = UUID.randomUUID().toString()
        val buffer = okio.Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        // The SWITCH body is EXACT (spec e3cfc8ae): the server refuses unknown fields, and it derives
        // idempotency from the desired state itself, so no correlation id is sent. The deletion route
        // keeps its `request_id` — that receipt is the server's answer to one particular request.
        if (route != Route.SWITCH) writer.name("request_id").value(requestId)
        when (route) {
            // The desired state, and nothing the server can derive itself: no epoch, no expected
            // revision in the body (that precondition IS the If-Match header).
            Route.SWITCH -> {
                writer.name("enrolled").value(enrolled)
                if (enrolled) writer.name("disclosure_version").value(disclosureVersion)
            }

            else -> Unit // GET sends nothing; deletion carries ONLY the request_id
        }
        writer.endObject()
        writer.close()
        return buffer.readByteString().toRequestBody(jsonMediaType)
    }

    /**
     * 202 deletion_pending receipt: strict complete parse, exact types/keys, numeric schema.
     * `cutoff` is an optional RFC3339 instant: when present it must parse as
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

    /**
     * 202 deletion_pending receipt: strict complete parse, exact types/keys, numeric schema.
     * `cutoff` is an optional RFC3339 instant: when present it must parse as
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
                    // Optional field to ignore.
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
        val bearer: String?,
        val requestBody: okhttp3.RequestBody,
        val route: Route,
        /**
         * The last-read `membership_revision`, sent as `If-Match`. Null only for the GET itself: a
         * SWITCH never leaves without a revision, because `setVoiceSharing` reads first when it
         * holds none (spec e3cfc8ae).
         */
        val ifMatch: String? = null,
        /**
         * The last-read `membership_revision`, sent as `If-Match`. Null only for the GET itself: a
         * SWITCH never leaves without a revision, because `setVoiceSharing` reads first when it
         * holds none.
         */
        val ifMatch: String? = null,
    )

    private companion object {
        const val MAX_ATTEMPTS = 2

        /** Server correlation id echoed on every response. */
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

private fun logTokenKeyId(tokenValue: String) = logTokenKeyIdLine(tokenValue)

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
