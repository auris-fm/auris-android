package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

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
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import timber.log.Timber

interface VoiceSharingAccountApi {
    @GET("/api/v1/account/voice-sharing")
    suspend fun getVoiceSharing(@Header("Authorization") bearer: String?): Response<ResponseBody>

    @PUT("/api/v1/account/voice-sharing")
    suspend fun putVoiceSharing(
        @Header("Authorization") bearer: String?,
        @Body body: okhttp3.RequestBody,
    ): Response<ResponseBody>

    @DELETE("/api/v1/account/voice-sharing/data")
    suspend fun deleteData(
        @Header("Authorization") bearer: String?,
    ): Response<ResponseBody>

    companion object {
        fun create(builder: Retrofit.Builder, client: OkHttpClient): VoiceSharingAccountApi = builder.client(client).build().create(VoiceSharingAccountApi::class.java)

        fun create(retrofit: Retrofit): VoiceSharingAccountApi = retrofit.create(VoiceSharingAccountApi::class.java)
    }
}

sealed interface VoiceSharingMutationResult {
    data class Applied(
        val projection: VoiceSharingAccountProjection,
    ) : VoiceSharingMutationResult

    data object Failure : VoiceSharingMutationResult
}

class VoiceSharingAccountOperations(
    private val api: VoiceSharingAccountApi,
    private val state: VoiceSharingAccountState,
    private val currentAccountName: () -> String?,
    private val aurisToken: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding,
    private val onCredentialInvalid: () -> Unit = {},
    private val onAurisCredentialRefused: suspend () -> Unit = { aurisToken.refreshToken() },
    private val savedSharingChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore? = null,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun refreshProjection(): Boolean {
        val request = issue(Route.GET, null) ?: return false
        return when (val outcome = execute(request.op) { b, _ -> api.getVoiceSharing(b) }) {
            is Outcome.Body -> {
                val applied = apply(outcome.json, request.issued, fromRefresh = true)
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

    suspend fun setVoiceSharing(
        enrolled: Boolean,
        acceptedDisclosureVersion: String?,
        applyUnderGuard: ((() -> Boolean) -> Boolean) = { it() },
    ): VoiceSharingMutationResult {
        if (enrolled && acceptedDisclosureVersion.isNullOrBlank()) return VoiceSharingMutationResult.Failure
        val request = issue(Route.SWITCH, if (enrolled) acceptedDisclosureVersion else null)
            ?: return VoiceSharingMutationResult.Failure
        return when (val outcome = execute(request.op) { b, payload -> api.putVoiceSharing(b, payload) }) {
            is Outcome.Body -> appliedOrFailure(outcome.json, request.issued, applyUnderGuard)
            is Outcome.Done, is Outcome.Rejected, is Outcome.Unavailable -> VoiceSharingMutationResult.Failure
        }
    }

    /**
     * [applyUnderGuard] is the calling surface's supersession fence (Reviewer, integrated control):
     * a withdrawal deliberately does not queue behind an in-flight enrollment, so a superseded
     * enrollment's PUT answer can land AFTER the withdrawal applied. The CALLER'S effects (publish +
     * persist) are handed THROUGH this guard, which runs them under the fence's own lock together
     * with the currency decision (Reviewer on `561d3c5b6e`: no check-then-write gap) — not a bare
     * boolean probe followed by unlocked effects.
     */
    suspend fun enroll(
        acceptedDisclosureVersion: String,
        applyUnderGuard: ((() -> Boolean) -> Boolean) = { it() },
    ): VoiceSharingMutationResult = setVoiceSharing(enrolled = true, acceptedDisclosureVersion = acceptedDisclosureVersion, applyUnderGuard = applyUnderGuard)

    suspend fun withdraw(applyUnderGuard: ((() -> Boolean) -> Boolean) = { it() }): VoiceSharingMutationResult = setVoiceSharing(enrolled = false, acceptedDisclosureVersion = null, applyUnderGuard = applyUnderGuard)

    private fun appliedOrFailure(json: String, issued: Issued, applyUnderGuard: (() -> Boolean) -> Boolean): VoiceSharingMutationResult {
        val projection = state.parseProjection(json) ?: return VoiceSharingMutationResult.Failure
        return if (apply(json, issued, fromRefresh = false, applyUnderGuard = applyUnderGuard)) {
            VoiceSharingMutationResult.Applied(projection)
        } else VoiceSharingMutationResult.Failure
    }

    /**
     * The publication-count fence is a REFRESH fence only. A refresh answer that lands after a newer
     * publication is stale and is discarded — that is what keeps a pre-withdrawal GET from
     * republishing enrollment. A MUTATION's own answer (the owner's tap) is never stale in that
     * sense: it carries the account fence, and a refresh that merely landed earlier must not throw
     * it away.
     */
    private fun apply(
        json: String,
        issued: Issued,
        fromRefresh: Boolean,
        applyUnderGuard: (() -> Boolean) -> Boolean = { it() },
    ): Boolean {
        // ONE account read for the whole decision (bot review 5f2dd8e55b): a second read could
        // land after an account switch and persist the old account's answer under the new one.
        val account = currentAccountName()
        if (account != issued.accountName) {
            return false // a different account owns the answer now
        }
        if (fromRefresh && state.publicationCount() != issued.publication) {
            return false // a newer answer already landed
        }
        val projection = state.parseProjection(json) ?: return false
        val wouldEnroll = projection.voiceSharingEnrolled &&
            projection.enrollmentEpoch != null &&
            projection.acceptedDisclosureVersion != null
        // THE LOCAL STOP IS THE DURABLE WITHDRAWN RECORD. A refresh (GET) must never release a
        // WITHDRAWN record into ENROLLED: only an explicit new opt-in tap may do that. A stale
        // pre-withdrawal GET answering ENROLLED therefore cannot turn admission back on.
        if (fromRefresh && wouldEnroll &&
            account != null &&
            savedSharingChoice?.read(account)?.state == au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.WITHDRAWN
        ) {
            return false
        }
        // THE MUTATION EFFECTS RUN INSIDE THE GUARD (Reviewer on `561d3c5b6e`): publish and persist
        // are handed to [applyUnderGuard], which for controller-driven mutations is the fence's
        // applyIfCurrent — decision and effects in ONE critical section, so a supersession cannot
        // land between validation and publication. A refresh (fence-free) applies directly.
        val publishAndPersist = {
            state.publish(projection)
            persistSavedChoiceLocked(account, projection)
            true
        }
        return if (fromRefresh) publishAndPersist() else applyUnderGuard(publishAndPersist)
    }

    private fun persistSavedChoiceLocked(account: String?, projection: VoiceSharingAccountProjection) {
        val store = savedSharingChoice ?: return
        if (account.isNullOrBlank()) return
        val persisted = when {
            projection.voiceSharingEnrolled && projection.enrollmentEpoch != null &&
                projection.acceptedDisclosureVersion != null ->
                store.recordEnrolled(account, projection.enrollmentEpoch, projection.acceptedDisclosureVersion)

            projection.withdrawalState == WithdrawalState.WITHDRAWN ->
                store.recordWithdrawn(account)

            else -> store.recordNotEnrolled(account)
        }
        if (persisted == false) {
            // Logged ONCE here (reviewer nit ddf08a2cc7-c): the persistence itself no longer logs
            // a failed commit. Prefs keep the new value in memory even on a failed commit, so
            // there is no latch to diagnose: this is observability only.
            android.util.Log.w("VoiceSharingChoice", "voice-sharing choice persist: commit failed; in-memory value stands")
        }
    }

    suspend fun deleteData(): Boolean {
        val request = issue(Route.DELETION, null) ?: return false
        val outcome = execute(request.op, expectedStatus = 204) { b, _ -> api.deleteData(b) }
        return outcome is Outcome.Done
    }

    private suspend fun aurisBearer(): String? = aurisToken.currentToken()?.takeIf { it.isNotBlank() }

    private data class Issued(
        val accountName: String?,
        val publication: Long,
    )

    private suspend fun issue(route: Route, disclosureVersion: String?): IssuedRequest? {
        val accountName = currentAccountName()
        val bearer = aurisBearer() ?: return null
        // The account can change while the bearer is being obtained; never rebind one account's
        // credential to another.
        if (currentAccountName() != accountName) return null
        val op = frozen(bearer, route, disclosureVersion) ?: return null
        return IssuedRequest(op, Issued(accountName, state.publicationCount()))
    }

    private class IssuedRequest(val op: FrozenOperation, val issued: Issued)

    private enum class Route { GET, SWITCH, DELETION }

    private sealed interface Outcome {
        val requestIds: List<String>

        data class Body(val json: String, override val requestIds: List<String> = emptyList()) : Outcome
        data class Done(override val requestIds: List<String> = emptyList()) : Outcome
        data class Rejected(override val requestIds: List<String> = emptyList()) : Outcome
        data class Unavailable(val status: Int?, override val requestIds: List<String> = emptyList()) : Outcome
    }

    private fun frozen(aurisBearer: String, route: Route, disclosureVersion: String?, projection: VoiceSharingAccountProjection? = state.projection.value): FrozenOperation? {
        val bearer = "Bearer " + aurisBearer
        logTokenKeyId(aurisBearer)
        val body = when (route) {
            Route.GET -> EMPTY_BODY
            Route.DELETION -> EMPTY_BODY
            else -> writeBody(enrolled = disclosureVersion != null, projection = projection, route = route, disclosureVersion = disclosureVersion)
        }
        return FrozenOperation(bearer, body, route)
    }

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

    private suspend fun handleUnauthorized(op: FrozenOperation) {
        val frozenBearer = op.bearer
        // The 401 staleness fence: a bearer from a superseded mint, a switched account, a different
        // origin, or a cleared cache is NOT current, and a rejection answering it must commit
        // nothing.
        val committed = aurisToken.withCurrentCachedToken { currentBearer -> "Bearer " + (currentBearer ?: "") == frozenBearer }
        if (!committed) return // effects fire ONLY for a rejection that actually committed
        onAurisCredentialRefused() // force a re-mint from the SAME account
        onCredentialInvalid()
    }

    private fun writeBody(projection: VoiceSharingAccountProjection?, route: Route, disclosureVersion: String?, enrolled: Boolean = false): okhttp3.RequestBody {
        val requestId = UUID.randomUUID().toString()
        val buffer = okio.Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        if (route != Route.SWITCH) writer.name("request_id").value(requestId)
        when (route) {
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

    private fun com.squareup.moshi.JsonReader.nonEmptyString(): String? {
        if (peek() != com.squareup.moshi.JsonReader.Token.STRING) return null
        return nextString().ifEmpty { null }
    }

    private data class FrozenOperation(
        val bearer: String?,
        val requestBody: okhttp3.RequestBody,
        val route: Route,
    )

    private companion object {
        const val MAX_ATTEMPTS = 2
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

private fun tokenJoseHeader(tokenValue: String): JSONObject? = try {
    val parts = tokenValue.split('.')
    if (parts.size != JWS_SEGMENT_COUNT) return null
    val first = parts[0]
    if (first.isEmpty() || first.length > MAX_HEADER_B64_CHARS) return null
    val decoded = Base64.getUrlDecoder().decode(padBase64Url(first))
    if (decoded.size > MAX_HEADER_BYTES) return null
    JSONTokener(String(decoded, Charsets.UTF_8)).nextValue() as? JSONObject
} catch (_: Exception) {
    null
}

private fun logTokenKeyId(tokenValue: String) = logTokenKeyIdLine(tokenValue)

private fun padBase64Url(value: String): String = when (value.length % 4) {
    2 -> "$value=="
    3 -> "$value="
    else -> value
}

private const val TAG = "VoiceSharingAccount"
private const val JWS_SEGMENT_COUNT = 3
private const val MAX_HEADER_B64_CHARS = 4096
private const val MAX_HEADER_BYTES = 2048
private const val MAX_KID_CHARS = 128
