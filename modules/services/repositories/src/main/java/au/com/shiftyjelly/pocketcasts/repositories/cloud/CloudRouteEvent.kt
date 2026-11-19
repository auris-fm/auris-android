package au.com.shiftyjelly.pocketcasts.repositories.cloud

sealed class CloudRouteEvent {
    data class Action(
        val tool: String,
        val action: String,
        val params: Map<String, Any?>,
        /**
         * The turn a deterministic HOLD belongs to, or null for a user action.
         *
         * A hold is identified by carrying this INSTEAD of an operation id: the absence of an
         * operation id says only "not a user action", which is not enough to answer the rule the
         * contract requires at terminal time -- release nothing unless THIS turn took the hold, and
         * never release a successor's. Without the identity that rule can only be inferred from
         * arrival order, which breaks the moment two turns overlap.
         *
         * The server omits it on user actions, so null means "this is an app operation, answer it
         * with a typed result"; non-null means "this is the turn's own hold, and it takes no result".
         */
        val turnId: String? = null,
    ) : CloudRouteEvent()

    enum class Outcome {
        Completed,
        Interrupted,
    }

    data class Done(
        // Required on the wire, nullable when the server cannot measure usage.
        val inputTokens: Int?,
        val outputTokens: Int?,
        val outcome: Outcome = Outcome.Completed,
    ) : CloudRouteEvent()

    data class Error(
        val code: String,
        val message: String,
    ) : CloudRouteEvent()

    /**
     * One binary audio frame from the cloud route, in the negotiated codec. The client plays these
     * rather than synthesising speech for a cloud answer.
     */
    class AudioFrame(
        val codec: String,
        val bytes: ByteArray,
    ) : CloudRouteEvent() {
        override fun equals(other: Any?): Boolean = this === other || (other is AudioFrame && codec == other.codec && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * codec.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "AudioFrame(codec=$codec, bytes=${bytes.size})"
    }

    /** Negotiated structured discovery results (only when advertised). */
    data class Result(val results: CloudSearchResults) : CloudRouteEvent()

    /**
     * The server's acknowledgement that this turn was ADMITTED, not merely that the socket upgraded.
     * It is sent only once admission succeeds, and carries the codec negotiated for the binary audio
     * path plus the reservation the turn's later accounting refers to
     * (`{"type":"connected","codec":...,"reservation_id":...}`) — the only place the codec arrives,
     * since the protocol has no separate `auth` frame. A refused turn therefore never sees this: it
     * gets an `error` frame alone, so receiving it means the request is running.
     */
    data class Connected(val codec: String) : CloudRouteEvent()
}
