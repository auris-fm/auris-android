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
    private val ownership: CredentialOwnershipOwner,
    private val aurisToken: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding,
    private val onCredentialInvalid: (AuthoritySnapshot) -> Unit,
    private val onAurisCredentialRefused: suspend () -> Unit = { aurisToken.refreshToken() },
    private val savedSharingChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore? = null,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
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
    suspend fun setVoiceSharing(enrolled: Boolean, acceptedDisclosureVersion: String?): VoiceSharingMutationResult {
        if (enrolled && acceptedDisclosureVersion.isNullOrBlank()) return VoiceSharingMutationResult.Failure
        val (ownerAccount, ownerSession) = ownerNow()
        val request = issue(Route.SWITCH, if (enrolled) acceptedDisclosureVersion else null, ownerAccount, ownerSession)
            ?: return VoiceSharingMutationResult.Failure
        return when (val outcome = execute(request.op) { b, payload -> api.putVoiceSharing(b, payload) }) {
            is Outcome.Body -> appliedOrFailure(outcome.json, request.issued)
            is Outcome.Done, is Outcome.Rejected, is Outcome.Unavailable -> VoiceSharingMutationResult.Failure
        }
    }
    suspend fun enroll(acceptedDisclosureVersion: String): VoiceSharingMutationResult = setVoiceSharing(enrolled = true, acceptedDisclosureVersion = acceptedDisclosureVersion)
    suspend fun withdraw(): VoiceSharingMutationResult = setVoiceSharing(enrolled = false, acceptedDisclosureVersion = null)
    private fun appliedOrFailure(json: String, issued: Issued): VoiceSharingMutationResult {
        val projection = state.parseProjection(json) ?: return VoiceSharingMutationResult.Failure
        return if (apply(json, issued)) VoiceSharingMutationResult.Applied(projection) else VoiceSharingMutationResult.Failure
    }
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
            android.util.Log.w("VoiceSharingChoice", "voice-sharing choice persist: outcome=failed latched")
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
        val session: Long,
        val publication: Long,
    )
    private fun ownerNow(): Pair<String?, Long> = ownership.transaction {
        val snapshot = ownership.authoritySnapshot()
        snapshot.accountName to snapshot.session
    }
    private suspend fun issue(route: Route, disclosureVersion: String?): IssuedRequest? {
        val (accountName, session) = ownerNow()
        return issue(route, disclosureVersion, accountName, session)
    }
    private suspend fun issue(route: Route, disclosureVersion: String?, accountName: String?, session: Long): IssuedRequest? {
        val (liveAccount, liveSession) = ownerNow()
        if (liveAccount != accountName || liveSession != session) return null
        val bearer = aurisBearer() ?: return null
        return ownership.transaction {
            val snapshot = ownership.authoritySnapshot()
            if (snapshot.accountName != accountName || snapshot.session != session) return@transaction null
            val op = frozen(bearer, route, disclosureVersion) ?: return@transaction null
            IssuedRequest(op, Issued(accountName, session, state.publicationCount()))
        }
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
        val committed = aurisToken.withCurrentCachedToken { currentBearer ->
            ownership.credentialTransaction { "Bearer " + (currentBearer ?: "") == frozenBearer }
        }
        if (!committed) return // effects fire ONLY for a rejection that actually committed
        onAurisCredentialRefused() // force a re-mint from the SAME account
        ownership.credentialTransaction { onCredentialInvalid(ownership.authoritySnapshot()) }
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
