package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Resolves a transport ACK into the ONE local ownership transition it authorizes (spec task73; reviewer
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
        fun validate(captureId: String, payload: ByteArray?): Boolean
    }

    fun resolve(
        generation: Long,
        captureId: String,
        ack: Ack,
        payload: ByteArray? = null,
        retryAfterS: Long? = null,
    ): Resolution = when (ack) {
        Ack.ACCEPTED ->
            if (!validator.validate(captureId, payload)) {
                // An acceptance we cannot VALIDATE is not an acceptance: treat as ambiguous.
                retain(captureId, generation, retryAfterS).let { r ->
                    if (r is Resolution.Retained) Resolution.UnvalidatedAccept(r.retryAtMs) else r
                }
            } else {
                release(generation, captureId, ContributionStore.TerminalReason.UPLOAD_ACCEPTED)
            }

        Ack.AMBIGUOUS, Ack.RETRYABLE -> retain(captureId, generation, retryAfterS)

        Ack.TERMINAL_DISPOSAL -> release(generation, captureId, ContributionStore.TerminalReason.SERVER_TERMINAL)
    }

    private fun release(generation: Long, captureId: String, reason: ContributionStore.TerminalReason): Resolution = if (!store.fence.isValid(generation)) {
        // Stale completion (epoch/account changed since the attempt): the local queue is the
        // successor's or a revoked one - neither authorizes THIS completion to mutate it.
        Resolution.StaleEpoch
    } else {
        if (store.dropEntry(generation, captureId, reason)) {
            Resolution.Released
        } else {
            Resolution.StaleEpoch
        }
    }

    private fun retain(captureId: String, generation: Long, retryAfterS: Long?): Resolution {
        if (!store.fence.isValid(generation)) return Resolution.StaleEpoch
        val retryAt = runCatching { store.scheduleRetryAfterFailure(generation, captureId, retryAfterS) }.getOrNull()
        return Resolution.Retained(retryAt)
    }
}
