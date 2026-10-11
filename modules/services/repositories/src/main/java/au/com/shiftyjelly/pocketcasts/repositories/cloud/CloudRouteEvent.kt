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

    data class Token(val text: String) : CloudRouteEvent()

    data class Done(
        // Nullable: the server may omit either count, and the analytics sink takes them nullable.
        val inputTokens: Int?,
        val outputTokens: Int?,
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
     * WebSocket handshake acknowledgement. It carries the codec the server negotiated for the
     * binary audio path (`{"type":"connected","codec":...,"reservation_id":...}`), which is the
     * only place that value arrives: the protocol has no separate `auth` frame.
     */
    data class Connected(val codec: String) : CloudRouteEvent()
}
