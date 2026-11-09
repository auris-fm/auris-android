package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import timber.log.Timber

/**
 * Resolves a transport ACK into the ONE local ownership transition it authorizes (spec task73; reviewer
 * coordination 7c5b344d). The caller records the attempt, posts the capture, and hands the classified
||||||| /tmp/mfb
 * Resolves a transport ack into the one local ownership transition it authorizes (spec task73; reviewer
 * coordination 7c5b344d). The caller records the attempt, posts the capture, and hands the classified
 * response here. Local ownership is removed ONLY on validated authoritative acceptance or confirmed
 * terminal disposal, and ONLY while the completion's generation is STILL valid - a stale completion
 * after an epoch/account change changes nothing locally.
 *
 * Lost/ambiguous responses NEVER remove ownership: the capture may or may not have reached the server,
 * so the entry is retained and made retry-eligible again; the capture is IMMUTABLE (fixed id + digest),
 * so the retry re-posts the identical bytes and the server deduplicates by capture id.
 */
class ContributionAckResolver(
    private val store: ContributionStore,
    private val validator: AcceptValidator,
) {

    /** Classified transport responses. The transport seam produces these; nothing else does. */
    enum class Ack {
        /** Server accepted: carries the authoritative payload that must validate before any drop. */
        ACCEPTED,

        /** Response lost or unclassifiable: the upload MAY have succeeded. Never a local removal. */
        AMBIGUOUS,

        /** Server saw the attempt and asked for a retry. */
        RETRYABLE,

        /** Server confirmed terminal disposal (permanent reject / account-level refusal). */
        TERMINAL_DISPOSAL,

        /**
         * Server rejected the REQUEST (400 invalid_request): nothing is known about the bytes.
         * Retain repairable bytes and stop serving the entry; never abandon/delete (spec 5c811f83).
         */
        UNSERVICED,
    }

    sealed class Resolution {
        /** Local ownership removed under a still-valid generation. */
        object Released : Resolution()

        /** Ownership retained; the entry may retry after the re-armed boundary. */
        data class Retained(val retryAtMs: Long?) : Resolution()

        /** The completion's generation is stale: NOTHING changes locally. */
        object StaleEpoch : Resolution()

        /** ACCEPTED payload failed authoritative validation: treated exactly like AMBIGUOUS. */
        data class UnvalidatedAccept(val retryAtMs: Long?) : Resolution()
    }

    /**
     * Authoritative-validation seam: returns true only when [payload] proves the server durably accepted
     * THIS capture (e.g. echoes the capture id and the recorded digest). Production supplies the real
     * check; tests pin that an unvalidated ACCEPTED never releases ownership.
     */
    fun interface AcceptValidator {
        /**
         * [httpStatus] is the status the payload arrived on; the receipt's own status must PAIR with it
         * (201/accepted, 200/duplicate). Null means no status was supplied, which never validates.
         */
        fun validate(captureId: String, payload: ByteArray?, httpStatus: Int?): Boolean
    }

    /**
     * TOKEN-SCOPED resolution (spec 442bfbd7): the completion carries the immutable attempt token from
     * atomic pickup, and EVERY mutation validates-and-mutates against that token in ONE owner
     * transaction. A same-generation successor admitted between the response and this call is never
     * touched. A RETIRED token authorizes RETRY-BOUNDARY disposal only (when no successor owns the
     * slot); it never authorizes acceptance or release - release additionally requires the attempt's
     * own epoch to still be valid.
     */
    fun resolveForAttempt(
        token: ContributionStore.AdmittedAttempt,
        ack: Ack,
        payload: ByteArray? = null,
        retryAfterS: Long? = null,
        httpStatus: Int? = null,
    ): Resolution = logResolution(token, ack, httpStatus, resolveDisposition(token, ack, payload, retryAfterS, httpStatus))

    /**
     * MONITORING (spec 827c5962): ONE line per resolution, on every path. Before this the resolver was
     * completely silent, so a device log could not distinguish a deletion from a retention.
     * CONTENT-FREE: opaque capture id, the real HTTP status, and the FIXED protocol code only - never a
     * server-supplied free-form string, payload, credential, transcript or audio.
     */
    private fun logResolution(
        token: ContributionStore.AdmittedAttempt,
        ack: Ack,
        httpStatus: Int?,
        resolution: Resolution,
    ): Resolution {
        val outcome = when (resolution) {
            is Resolution.Released -> "released"
            is Resolution.Retained -> "retained"
            is Resolution.UnvalidatedAccept -> "retained-unvalidated"
            is Resolution.StaleEpoch -> "stale-epoch"
        }
        Timber.tag(TAG).i(
            "voice-upload settle: capture=%s attempt=%d ack=%s http=%s outcome=%s",
            token.captureId,
            token.attemptNumber,
            ack.name,
            httpStatus?.toString() ?: "none",
            outcome,
        )
        return resolution
    }

    private fun resolveDisposition(
        token: ContributionStore.AdmittedAttempt,
        ack: Ack,
        payload: ByteArray?,
        retryAfterS: Long?,
        httpStatus: Int?,
    ): Resolution = when (ack) {
        Ack.ACCEPTED ->
            if (!validator.validate(token.captureId, payload, httpStatus)) {
                val r = store.scheduleRetryForAdmittedAttempt(token, retryAfterS)
                if (r != null) Resolution.UnvalidatedAccept(r) else Resolution.StaleEpoch
            } else {
                if (store.releaseForAdmittedAttempt(token)) {
                    Resolution.Released
                } else {
                    Resolution.StaleEpoch
                }
            }

        Ack.AMBIGUOUS, Ack.RETRYABLE -> {
            val retryAt = store.scheduleRetryForAdmittedAttempt(token, retryAfterS)
            if (retryAt != null) Resolution.Retained(retryAt) else Resolution.StaleEpoch
        }

        Ack.TERMINAL_DISPOSAL ->
            // TERMINAL IS TERMINAL (spec 33c1e27a). The server now reopens on retry (Cloud 8637a0fd), so a
            // 409 means the recording was disposed or its bytes differ - both genuinely terminal. The
            // former 409-after-5xx hold was an interim workaround for the server disposing a reservation
            // when its OWN attempt failed; with that fixed, holding would keep indestructible debris.
||||||| /tmp/mfb
            // Terminal is terminal. The server now reopens on retry (Cloud 8637a0fd), so a
            // 409 means the recording was disposed or its bytes differ - both genuinely terminal. The
            // Former 409-after-5xx hold was an interim workaround for the server disposing a reservation
            // When its OWN attempt failed; with that fixed, holding would keep indestructible debris.
            if (store.releaseForAdmittedAttempt(token, ContributionStore.TerminalReason.SERVER_TERMINAL)) {
                Resolution.Released
            } else {
                Resolution.StaleEpoch
            }

        Ack.UNSERVICED -> {
            // DELETE AT ONCE (spec 1a0d3ffb, amending the 5c811f83 retention ruling): a server
            // PERMANENT refusal (400 invalid_request and its class) means the recording can never
            // be served, so a retained entry only fills the queue until it cannot drain - the
            // measured 44-entry full-queue outage. Deletion goes through the store's reconciled
            // purge with every control intact:
            // - identity proof: releaseForAdmittedAttempt deletes only while THIS attempt still
            //   owns the entry (a stale token deletes nothing -> StaleEpoch) - the proven absence
            //   of an active upload;
            // - durability: deleteLocked writes the durable disqualification intent FIRST and a
            //   failed intent write retains + reports (uncertainty is never resolved by deletion -
            //   the 5c811f83 principle survives for the uncertain case);
            // - failure controls: partial file deletions keep the residual bytes owned and charged
            //   for a later cleanup, and the truthful file-level outcome is logged inside
            //   deleteLocked. The client's own record check rejecting an entry already deletes it
            //   at once on recovery (INVALID_ON_RECOVERY).
            if (store.releaseForAdmittedAttempt(token, ContributionStore.TerminalReason.PERMANENT_REFUSAL)) {
                Resolution.Released
            } else {
                Resolution.StaleEpoch
            }
        }
    }

    /** Distinguishes a failed marker publication (retain, report) from a dead token (nothing changed). */
    private fun failureLatched(token: ContributionStore.AdmittedAttempt): Boolean = runCatching { !store.attemptIdentityHolds(token.generation, token.captureId, token.attemptNumber) }.getOrDefault(false)

    private fun retain(captureId: String, generation: Long, retryAfterS: Long?): Resolution {
        if (!store.fence.isValid(generation)) return Resolution.StaleEpoch
        val retryAt = runCatching { store.scheduleRetryAfterFailure(generation, captureId, retryAfterS) }.getOrNull()
        return Resolution.Retained(retryAt)
    }
}

private const val TAG = "VoiceUploadSettle"
