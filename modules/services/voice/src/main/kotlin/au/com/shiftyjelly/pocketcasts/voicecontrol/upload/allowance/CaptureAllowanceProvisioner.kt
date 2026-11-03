package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * The transport-facing provisioner slice for the server-issued capture allowance
 * (docs/specs/voice-recording-uploads.md, "Server-issued capture allowance"; spec ruling
 * 746cb082): builds the strict request envelope, sends ONE POST, strictly parses the 200 body,
 * and — only on a fully validated batch — composes the per-batch prepaid authority and binds it
 * through the lifecycle. Capture remains fail-closed: until this path provisions a batch,
 * [UnavailableCaptureAllowance] still refuses every capture.
 *
 * CONTRACT POINTS THIS CLASS OWNS:
 * - REQUEST IDENTITY: the request UUID and the enrollment epoch are chosen HERE and the response
 *   MUST echo exactly both; a mismatched echo is a refused document, never a provisioned batch.
 * - FRESHNESS ANCHOR (reviewer 978e8c11): the monotonic elapsed time is captured BEFORE the
 *   transport call and the ORIGINAL anchor is retained on the provisioned grant — expiry is
 *   evaluated against request start, never against installation time, so transit time cannot
 *   extend the server interval.
 * - PER-BATCH LEDGER (746cb082): the batch id keys the ledger area and joins the account
 *   binding; the server day-window id is the shared accounting window; the batch capacity pair
 *   (already identity-checked by the parser) becomes the ledger limits. `bind` is
 *   retire-then-set, so re-provisioning replaces the previous batch authority.
 * - REPLAY ASYMMETRY (spec b002b0d4): ONLY an `issued` response may FIRST-provision a ledger.
 *   A `replayed` response binds an authority with NO establishment power (the ledger's
 *   Unavailable provisioning path): it reopens an INTACT existing batch ledger and refuses a
 *   missing or lost one — a replay can never reset spent capacity after ledger loss.
 * - FAILURE SHAPES: every refusal is a reason; there is no success-shaped failure and no
 *   partial provision (a bind only happens after the full validation chain).
 */
class CaptureAllowanceProvisioner(
    private val api: CaptureAllowanceTransport,
    private val credential: suspend () -> Credential?,
    private val baseArea: File,
    private val lifecycle: CaptureAllowanceLifecycle,
    private val ceilings: Ceilings,
    private val elapsedMs: () -> Long,
    /**
     * REQUIRED atomic install transaction, implemented by the account operations layer. Under
     * ONE owner-transaction fence (the credential store's lock, never released in between) it
     * must: (a) validate the credential generation observed at request time is still live
     * (including request-generation supersession), (b) execute `lifecycle.bind(owner)`, and
     * (c) recheck the retained deadline (`grant` freshness against its original request-start
     * anchor) — returning true only if all held and the bind committed. A Boolean recheck that
     * releases the fence before bind is NOT acceptable: a Boolean callback + external bind lets
     * another thread switch identity in the gap even without suspension. No default: omitting
     * the fence silently omits authority.
     */
    private val beginInstallAttempt: suspend (String) -> Unit,
    /**
     * REQUIRED atomic install transaction (owned by the ops layer / install fence). Under ONE
     * fence it must validate overlapping-attempt supersession ([attemptId] is still the live
     * one), identity (credential generation AND session still live), and the retained deadline
     * ([grant] fresh against its request-start anchor), then bind and — still under the fence —
     * invoke [publish] to record the installed state. Publication INSIDE the fence is what
     * prevents a slower older attempt from overwriting a newer installation after its callback
     * returns.
     */
    private val installAuthority: suspend (
        owner: CaptureAllowanceOwner,
        credentialGeneration: Long,
        observedSession: Long,
        observedEnrollmentEpoch: String,
        attemptId: String,
        grant: Grant,
        publish: () -> Unit,
    ) -> Boolean,
) {

    /**
     * The authenticated identity the provisioner needs, captured under the credential owner.
     * `generation` is the owner-side credential generation observed when this snapshot was
     * taken; the atomic recheck before bind compares exactly this value.
     */
    data class Credential(
        val bearer: String,
        val accountId: String,
        val enrollmentEpoch: String,
        val generation: Long = 0,
        val session: Long = 0,
    )

    /** The transport seam; production binds the real [VoiceSharingAccountApi] method. */
    interface CaptureAllowanceTransport {
        @POST("/api/v1/account/voice-sharing/capture-allowance")
        suspend fun postCaptureAllowance(
            @Header("Authorization") bearer: String?,
            @Body body: okhttp3.RequestBody,
        ): Response<okhttp3.ResponseBody>
    }

    sealed interface Result {
        /** A validated batch is bound and live. Freshness is anchored at REQUEST start. */
        data class Provisioned(
            val batchId: String,
            val windowId: String,
            val disposition: String,
            val requestStartElapsedMs: Long,
            val validForMs: Long,
        ) : Result

        data class Refused(val reason: String) : Result
    }

    /**
     * The live grant's freshness, anchored at the ORIGINAL request start (reviewer 978e8c11):
     * `isFresh` is false at or beyond `validForMs` elapsed since the request started. This is
     * the value the composition/watcher must consult for batch expiry; re-deriving it from
     * bind time would restore time spent in transit.
     */
    @Volatile
    var currentGrant: Grant? = null
        private set

    /** The last composed (and bound) per-batch owner; observation seam for controls and wiring. */
    @Volatile
    var lastComposedOwner: CaptureAllowanceOwner? = null
        private set

    data class Grant(val batchId: String, val requestStartElapsedMs: Long, val validForMs: Long) {
        fun isFresh(nowElapsedMs: Long): Boolean {
            if (requestStartElapsedMs < 0 || nowElapsedMs < 0) return false
            // BACKWARDS CLOCK: a read earlier than the request start is damage, not freshness.
            if (nowElapsedMs < requestStartElapsedMs) return false
            return nowElapsedMs - requestStartElapsedMs < validForMs
        }
    }

    suspend fun provision(): Result {
        val cred = credential() ?: return Result.Refused(REASON_NO_CREDENTIAL)
        val attemptId = UUID.randomUUID().toString()
        // Announce THIS attempt before any transport work: a newer attempt supersedes any
        // older in-flight one at install time.
        beginInstallAttempt(attemptId)
        val start = elapsedMs()
        val requestId = UUID.randomUUID()
        val body = try {
            CaptureAllowanceBatch.requestJson(requestId, cred.enrollmentEpoch)
        } catch (e: IllegalArgumentException) {
            return Result.Refused(REASON_EPOCH_TOO_LONG)
        }
        val response = try {
            api.postCaptureAllowance("Bearer " + cred.bearer, body.toRequestBody(JSON_MEDIA))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Refused(REASON_TRANSPORT)
        }
        // BODY HYGIENE: every exit path closes the response (and error) body — non-200,
        // missing 200 body, oversized Content-Length, read failure — nothing leaks a socket.
        val responseBody = response.body()
        val errorBody = response.errorBody()
        if (response.code() != 200) {
            responseBody?.close()
            errorBody?.close()
            return Result.Refused("http_${response.code()}")
        }
        if (responseBody == null) {
            errorBody?.close()
            return Result.Refused(CaptureAllowanceBatch.REASON_MALFORMED)
        }
        // BOUNDED READ: the envelope ceiling applies to the WIRE, not just the parsed string.
        // Content-Length is checked first when present; the stream is then read with a hard
        // cap so an oversized or endless body can neither balloon memory nor be parsed. A
        // stream-read failure is a refusal, not an exception carrying stale-authority state.
        val contentLength = response.headers()["Content-Length"]?.toLongOrNull()
        if (contentLength != null && contentLength > CaptureAllowanceBatch.MAX_ENVELOPE_BYTES) {
            responseBody.close()
            return Result.Refused(CaptureAllowanceBatch.REASON_OVERSIZED)
        }
        val cap = CaptureAllowanceBatch.MAX_ENVELOPE_BYTES + 1L
        val text: String
        try {
            var oversized = false
            var read: String? = null
            // FINALLY-CLOSE: the explicit body close runs on EVERY exit — oversized, read
            // failure, even a throw from source() acquisition itself.
            try {
                val src = responseBody.source()
                try {
                    val buffered = okio.Buffer()
                    while (buffered.size <= CaptureAllowanceBatch.MAX_ENVELOPE_BYTES) {
                        if (src.read(buffered, cap - buffered.size) == -1L) break
                    }
                    if (buffered.size > CaptureAllowanceBatch.MAX_ENVELOPE_BYTES) {
                        oversized = true
                    } else {
                        read = buffered.readUtf8()
                    }
                } finally {
                    responseBody.close()
                }
            } finally {
                responseBody.close() // idempotent; second close for hygiene clarity
            }
            if (oversized) return Result.Refused(CaptureAllowanceBatch.REASON_OVERSIZED)
            text = read ?: return Result.Refused(CaptureAllowanceBatch.REASON_MALFORMED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Refused(REASON_TRANSPORT)
        }
        return when (val parsed = CaptureAllowanceBatch.parse(text, start, elapsedMs())) {
            is CaptureAllowanceBatch.Parsed.Refused -> Result.Refused(parsed.reason)

            is CaptureAllowanceBatch.Parsed.Batch -> {
                // REQUEST ECHO: the response must answer exactly the request we sent, with the
                // epoch we sent. Anything else is a different conversation wearing a 200.
                if (parsed.requestId != requestId.toString() || parsed.enrollmentEpoch != cred.enrollmentEpoch) {
                    return Result.Refused(REASON_MISMATCH)
                }
                val owner = CaptureAllowanceOwner(
                    // PER-BATCH AREA: the batch id (a parser-validated UUID) keys the ledger
                    // area; the batch id joins the account binding so a batch ledger can never
                    // be read as another batch's or another account's accounting.
                    area = File(baseArea, parsed.batchId),
                    account = cred.accountId + ":" + parsed.batchId,
                    limits = AllowanceLimits(
                        maxDailyRecords = parsed.batchMaxRecords,
                        maxDailyBytes = parsed.batchMaxBytes,
                        maxClipBytes = parsed.maxClipBytes,
                        maxMetadataBytes = parsed.maxMetadataBytes.toInt(),
                        ceilings = ceilings,
                    ),
                    window = AllowanceWindow.Established(parsed.windowId),
                    // REPLAY ASYMMETRY (spec b002b0d4): only `issued` may establish a fresh
                    // ledger. A `replayed` grant binds WITHOUT establishment power, so it can
                    // reopen an intact ledger but can never first-provision — or reset spent
                    // capacity after — a lost one.
                    provisioning = if (parsed.disposition == CaptureAllowanceBatch.DISPOSITION_ISSUED) {
                        AllowanceProvisioning.ServerIssued(batchId = parsed.batchId, windowId = parsed.windowId)
                    } else {
                        AllowanceProvisioning.Unavailable
                    },
                )
                // IDENTITY FENCE: supersession + identity + deadline validation AND
                // installation AND publication all execute inside ONE owner transaction —
                // the provisioner records nothing itself after a suspending callback.
                val grant = Grant(parsed.batchId, start, parsed.validForMs)
                val publish = {
                    lastComposedOwner = owner
                    currentGrant = grant
                }
                if (!installAuthority(owner, cred.generation, cred.session, cred.enrollmentEpoch, attemptId, grant, publish)) {
                    return Result.Refused(REASON_IDENTITY_CHANGED)
                }
                Result.Provisioned(
                    batchId = parsed.batchId,
                    windowId = parsed.windowId,
                    disposition = parsed.disposition,
                    requestStartElapsedMs = start,
                    validForMs = parsed.validForMs,
                )
            }
        }
    }

    companion object {
        const val REASON_NO_CREDENTIAL = "allowance_no_credential"
        const val REASON_EPOCH_TOO_LONG = CaptureAllowanceBatch.REASON_EPOCH_TOO_LONG
        const val REASON_TRANSPORT = "allowance_transport_failed"
        const val REASON_MISMATCH = "allowance_response_mismatch"
        const val REASON_IDENTITY_CHANGED = "allowance_identity_changed"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
