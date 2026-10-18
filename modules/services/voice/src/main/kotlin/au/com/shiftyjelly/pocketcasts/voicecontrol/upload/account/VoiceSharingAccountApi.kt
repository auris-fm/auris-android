package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Auris account routes (Go-owned; docs/specs/voice-recording-uploads.md lines 50-53). Bearer
 * identity comes from the existing authenticated stack; bodies are exact-contract JSON built
 * here; responses are strict-parsed by the projection state (schema_version 1, exact types).
 */
interface VoiceSharingAccountApi {

    @GET("/api/v1/account/voice-sharing")
    suspend fun getVoiceSharing(): okhttp3.ResponseBody

    @POST("/api/v1/account/voice-sharing/enrollment")
    suspend fun postEnrollment(@Body body: okhttp3.RequestBody): okhttp3.ResponseBody

    @POST("/api/v1/account/voice-sharing/withdrawal")
    suspend fun postWithdrawal(@Body body: okhttp3.RequestBody): okhttp3.ResponseBody

    // Retrofit rejects @Body on @DELETE; @HTTP(hasBody = true) carries the contract request_id.
    @retrofit2.http.HTTP(method = "DELETE", path = "/api/v1/account/voice-sharing/data", hasBody = true)
    suspend fun deleteData(@Body body: okhttp3.RequestBody): okhttp3.ResponseBody

    companion object {
        fun create(builder: Retrofit.Builder, client: OkHttpClient): VoiceSharingAccountApi = builder.client(client).build().create(VoiceSharingAccountApi::class.java)
    }
}

/** 202 receipt of the separate deletion workflow: distinct from withdrawal, which never deletes. */
data class DeletionReceipt(val deletionRequestId: String, val status: String)

/**
 * Account operations (Task 2). Per-operation contract:
 *
 * - A current-session GET projection must exist BEFORE any authority operation; without one,
 *   no network call is issued at all.
 * - The operation identity is frozen at creation, before the first await: request_id UUID,
 *   expected_membership_revision from the current projection, nullable epoch. A bounded
 *   immutable retry reuses the SAME request_id and revision.
 * - Responses are applied through [VoiceSharingAccountState.applySessionResponse] with the
 *   token captured BEFORE the await, so a session ended or superseded while a call is in
 *   flight can never have its response applied (the state rejects by token reference).
 * - Withdrawal applies a membership projection and never deletes; deletion is a distinct
 *   operation returning a 202 deletion_pending receipt and never touches the projection.
 * - Never serializes credentials; request bodies carry only contract fields.
 */
class VoiceSharingAccountOperations(
    private val api: VoiceSharingAccountApi,
    private val state: VoiceSharingAccountState,
    private val bearerProvider: suspend () -> String? = { null }, // RED: unused
    private val onCredentialInvalid: suspend (String) -> Unit = {}, // RED: unused
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Current-session GET: the only way the state gains authority. */
    suspend fun refreshProjection(token: SessionToken): Boolean = try {
        state.applySessionResponse(token, api.getVoiceSharing().string())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    suspend fun enroll(token: SessionToken, acceptedDisclosureVersion: String): Boolean {
        val op = frozen(disclosureVersion = acceptedDisclosureVersion) ?: return false
        val body = withBoundedRetry { api.postEnrollment(requestBody(op, includeEpoch = false)) }
        return body != null && state.applySessionResponse(token, body)
    }

    suspend fun withdraw(token: SessionToken): Boolean {
        val op = frozen() ?: return false
        val body = withBoundedRetry { api.postWithdrawal(requestBody(op, includeEpoch = true)) }
        return body != null && state.applySessionResponse(token, body)
    }

    suspend fun deleteData(token: SessionToken): DeletionReceipt? {
        val op = frozen() ?: return null
        val body = withBoundedRetry { api.deleteData(requestBody(op, deletion = true)) } ?: return null
        return parseDeletionReceipt(body)
    }

    /** Authoritative preconditions, frozen BEFORE any await. */
    private fun frozen(disclosureVersion: String? = null): FrozenOperation? {
        val projection = state.projection.value ?: return null // no GET, no authority, no call
        return FrozenOperation(
            requestId = UUID.randomUUID().toString(),
            expectedRevision = projection.membershipRevision,
            epoch = projection.enrollmentEpoch,
            disclosureVersion = disclosureVersion,
        )
    }

    /** Bounded immutable retry: transport failures retry once with the SAME frozen body. */
    private suspend fun withBoundedRetry(call: suspend () -> okhttp3.ResponseBody): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return call().string()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (attempt == MAX_ATTEMPTS - 1) return null
            }
        }
        return null
    }

    private fun requestBody(op: FrozenOperation, includeEpoch: Boolean = false, deletion: Boolean = false): okhttp3.RequestBody = buildString {
        append("{\"request_id\":\"").append(op.requestId).append("\"")
        if (!deletion) {
            append(",\"expected_membership_revision\":\"").append(op.expectedRevision).append("\"")
            if (op.disclosureVersion != null) {
                append(",\"accepted_disclosure_version\":\"").append(op.disclosureVersion).append("\"")
            }
            if (includeEpoch) {
                append(",\"enrollment_epoch\":")
                if (op.epoch != null) append("\"").append(op.epoch).append("\"") else append("null")
            }
        }
        append("}")
    }.toRequestBody(jsonMediaType)

    /** 202 deletion_pending receipt: schema_version 1, exact key set, strict values. */
    private fun parseDeletionReceipt(json: String): DeletionReceipt? = try {
        val obj = org.json.JSONObject(json)
        val keys = obj.keys().asSequence().toSet()
        if (keys != setOf("schema_version", "deletion_request_id", "status")) {
            null
        } else if (obj.isNull("schema_version") || obj.getInt("schema_version") != 1) {
            null
        } else {
            val id = obj.getString("deletion_request_id")
            val status = obj.getString("status")
            if (id.isEmpty() || status != "deletion_pending") null else DeletionReceipt(id, status)
        }
    } catch (_: Exception) {
        null
    }

    /** Everything an operation needs, frozen at creation time — before the first await. */
    private data class FrozenOperation(
        val requestId: String,
        val expectedRevision: String,
        val epoch: String?,
        val disclosureVersion: String?,
    )

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
