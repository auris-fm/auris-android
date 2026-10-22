package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import java.io.File

/**
 * The ONE production composition of transport, caller and token-scoped resolver.
 *
 * Until now the caller took `attemptUpload` / `dispositionFor` / `responseFor` / `retryAfterFor` as
 * separate seams and the tests satisfied each by hand, so nothing in the product actually ran
 * caller -> HTTP -> token settlement. This class is that path, and it is the only place a real
 * [ContributionUploadTransport] is wired to a real [ContributionRetryCaller].
 *
 * Discipline this class exists to preserve, all of it enforced elsewhere and merely ROUTED here:
 * - the transport response is classified once, by the transport's owning table, and its `Accepted` payload
 *   is the receipt handed to validation (never a Boolean that could delete on its own);
 * - a cancellation (attempt lease cancelled, or the authorization epoch gone) is checked BEFORE any byte
 *   leaves and again before the outcome is settled, so a response that arrives after revocation is
 *   settled as an unconfirmed outcome rather than an acceptance;
 * - the caller still owns the attempt token and performs every mutation through it.
 */
class ContributionUploadAssembly(
    private val store: ContributionStore,
    private val transport: ContributionUploadTransport,
    /**
     * Supplies the durable capture for a capture id: the audio file, the record that describes it, and the
     * account epoch the record was signed under. Null means "not on disk any more", which is not an
     * acceptance and not a transport outcome - the attempt is held (retained) rather than released.
     */
    private val captureFor: (captureId: String) -> Capture?,
    clock: () -> Long,
    policyAllows: () -> Boolean,
    networkAvailable: () -> Boolean,
    /** Called after a VALIDATED acceptance releases the entry; never on an unvalidated one. */
    private val onAccepted: (generation: Long, captureId: String) -> Unit = { _, _ -> },
    /**
     * Binds a live call's cancellation to the owner of this attempt's authorization (the launch lease).
     * Without this the transport only has a Boolean to poll, and a blocked write never polls.
     */
    private val bindCancel: ((() -> Unit) -> Unit)? = null,
) {
    /** A durable capture plus the record that claims it. */
    data class Capture(val record: RecordNode.Obj, val audio: File)

    /**
     * The last transport answer per capture id. The caller's seams are synchronous and read-after-attempt;
     * keeping this here means the caller never has to re-derive a disposition from a second request.
     */
    private val lastResponse = object : LinkedHashMap<String, ContributionUploadTransport.Response>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ContributionUploadTransport.Response>?): Boolean = size > MAX_TRACKED
    }

    // The receipt must echo the digest the store recorded for THIS capture; the validator reads it from
    // the same queue authority that will be mutated, so a receipt for different bytes never releases.
    private val resolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store))

    val caller: ContributionRetryCaller = ContributionRetryCaller(
        store = store,
        clock = clock,
        policyAllows = policyAllows,
        networkAvailable = networkAvailable,
        ackResolver = resolver,
        // One attempt: refuse before any byte leaves, then send the metadata-first envelope and remember
        // the classified answer. `true` here means only "a receipt came back", never "the capture is safe
        // to delete" - deletion happens solely through the resolver's validation.
        attemptUpload = { _, captureId, isRevoked ->
            val capture = captureFor(captureId)
            if (capture == null) {
                // A local "nothing to send" is not a transport answer: the caller settles the thrown
                // attempt as ambiguous (retain), which is what a missing capture deserves.
                throw CaptureUnavailable(captureId)
            }
            if (isRevoked()) {
                throw AttemptCancelled(captureId)
            }
            // The revocation signal now reaches INSIDE the upload: the transport checks it before the
            // first byte, per request-body chunk, and around the response read, so a withdrawal stops a
            // transfer already in flight instead of only being noticed after it finishes.
            // The lease owns this attempt's authorization, so the live call is bound to it: a revocation
            // then cancels the CALL rather than only flipping a flag the blocked writer cannot read.
            val response = transport.upload(
                captureId,
                capture.record,
                capture.audio,
                isCancelled = isRevoked,
                onRegistered = { handle -> bindCancel?.invoke { transport.cancelRegistered(handle) } },
            )
            lastResponse[captureId] = response
            // A cancelled attempt has NO answer to settle from, whatever the socket happened to return.
            if (response is ContributionUploadTransport.Response.Cancelled) throw AttemptCancelled(captureId)
            // Cancellation observed again before settlement: the response may belong to a revoked owner.
            if (isRevoked()) throw AttemptCancelled(captureId)
            response is ContributionUploadTransport.Response.Accepted
        },
        onSuccess = onAccepted,
        // The owning table's terminal family is the ONLY source of terminal disposal.
        dispositionFor = { _, captureId -> lastResponse[captureId] },
        // Only a 2xx body can be a receipt, and only the 2xx body is offered to validation.
        responseFor = { _, captureId ->
            (lastResponse[captureId] as? ContributionUploadTransport.Response.Accepted)?.payload
        },
        retryAfterFor = { _, captureId ->
            (lastResponse[captureId] as? ContributionUploadTransport.Response.Retryable)?.retryAfterS
        },
        // The status the receipt arrived on: validation pairs it with the receipt's own status, so a 200
        // claiming `accepted` (or a 201 claiming `duplicate`) never releases.
        httpStatusFor = { _, captureId ->
            (lastResponse[captureId] as? ContributionUploadTransport.Response.Accepted)?.statusCode
        },
    )

    /** Poll once. Present so callers do not reach past the assembly for anything. */
    fun poll(): ContributionRetryCaller.PollResult = caller.pollOnce()

    /** A revoked attempt, detected before or after the wire exchange. Never an acceptance. */
    class AttemptCancelled(val captureId: String) : Exception("attempt cancelled: $captureId") {
        override fun fillInStackTrace(): Throwable = this
    }

    /** No durable capture to send. Settled as an unconfirmed outcome, never as a release. */
    class CaptureUnavailable(val captureId: String) : Exception("no capture on disk: $captureId") {
        override fun fillInStackTrace(): Throwable = this
    }

    private companion object {
        /** Bounded: this cache exists for the duration of one attempt's settlement, not as storage. */
        const val MAX_TRACKED = 32
    }
}
