package au.com.shiftyjelly.pocketcasts.repositories.cloud

sealed class CloudRouteEvent {
    data class Action(
        val tool: String,
        val action: String,
        val params: Map<String, Any?>,
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
