package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Auris account routes (Go-owned; docs/specs/voice-recording-uploads.md lines 50-53). Bodies
 * are exact-contract JSON; responses are strict-parsed by the projection state. RED SKELETON
 * for the operations slice: every operation fails closed.
 */
interface VoiceSharingAccountApi {

    @GET("/api/v1/account/voice-sharing")
    suspend fun getVoiceSharing(): okhttp3.ResponseBody

    @POST("/api/v1/account/voice-sharing/enrollment")
    suspend fun postEnrollment(@Body body: okhttp3.RequestBody): okhttp3.ResponseBody

    @POST("/api/v1/account/voice-sharing/withdrawal")
    suspend fun postWithdrawal(@Body body: okhttp3.RequestBody): okhttp3.ResponseBody

    @DELETE("/api/v1/account/voice-sharing/data")
    suspend fun deleteData(@Body body: okhttp3.RequestBody): retrofit2.Response<okhttp3.ResponseBody>

    companion object {
        fun create(builder: Retrofit.Builder, client: OkHttpClient): VoiceSharingAccountApi = builder.client(client).build().create(VoiceSharingAccountApi::class.java)
    }
}

/** 202 receipt of the separate deletion workflow: distinct from withdrawal. */
data class DeletionReceipt(val deletionRequestId: String, val status: String)

/** RED SKELETON (operations slice): all authority paths fail closed. */
class VoiceSharingAccountOperations(
    private val api: VoiceSharingAccountApi,
    private val state: VoiceSharingAccountState,
) {
    suspend fun refreshProjection(token: SessionToken): Boolean = false // RED

    suspend fun enroll(token: SessionToken, acceptedDisclosureVersion: String): Boolean = false // RED

    suspend fun withdraw(token: SessionToken): Boolean = false // RED

    suspend fun deleteData(token: SessionToken): DeletionReceipt? = null // RED
}
