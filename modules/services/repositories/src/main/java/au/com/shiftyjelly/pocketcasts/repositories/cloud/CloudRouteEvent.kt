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

    /**
     * An app-operation request (core `cloud-assistant.md`, "App-operation protocol v1").
     *
     * Distinct from [Action] because the replay contract is defined over the ORIGINAL serialized
     * bytes, which a decoded parameter map has already lost. A v1 frame never degrades into [Action]:
     * the legacy playback stream and the deterministic hold carry neither identity field.
     */
    data class AppOperation(
        val operationId: String,
        val capabilityVersion: String,
        val tool: String,
        val action: String,
        val params: Map<String, Any?>,
        val precondition: Map<String, Any?>?,
        /** The complete envelope exactly as delivered, for identity binding and digesting. */
        val rawBytes: ByteArray,
    ) : CloudRouteEvent() {
        override fun equals(other: Any?): Boolean = this === other || (
            other is AppOperation && operationId == other.operationId &&
                capabilityVersion == other.capabilityVersion && tool == other.tool &&
                action == other.action && params == other.params && precondition == other.precondition &&
                rawBytes.contentEquals(other.rawBytes)
            )

        override fun hashCode(): Int {
            var result = operationId.hashCode()
            result = 31 * result + capabilityVersion.hashCode()
            result = 31 * result + tool.hashCode()
            result = 31 * result + action.hashCode()
            result = 31 * result + params.hashCode()
            result = 31 * result + (precondition?.hashCode() ?: 0)
            result = 31 * result + rawBytes.contentHashCode()
            return result
        }

        override fun toString(): String = "AppOperation(operationId=$operationId, tool=$tool, action=$action, bytes=${rawBytes.size})"
    }

    /**
     * A frame that CLAIMS app-operation identity by either identity field but cannot be executed:
     * malformed fields, oversize envelope, or an incomplete identity pair.
     *
     * Separate from [Error] so the sink can answer it — with the recovered operation id, when one is
     * bounded and valid — instead of ending the turn. It is never a fallback to legacy playback.
     */
    class MalformedAppOperation(
        val operationId: String?,
        val rawBytes: ByteArray,
    ) : CloudRouteEvent() {
        override fun toString(): String = "MalformedAppOperation(operationId=$operationId, bytes=${rawBytes.size})"
    }

    enum class Outcome(val wireValue: String) {
        Completed("completed"),
        Interrupted("interrupted"),
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
     * WebSocket handshake acknowledgement. It carries the codec the server negotiated for the
     * binary audio path (`{"type":"connected","codec":...,"reservation_id":...}`), which is the
     * only place that value arrives: the protocol has no separate `auth` frame.
     */
    data class Connected(val codec: String) : CloudRouteEvent()
}
