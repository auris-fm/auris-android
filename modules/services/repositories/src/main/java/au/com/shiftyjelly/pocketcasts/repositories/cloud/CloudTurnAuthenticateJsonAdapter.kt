package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import okio.Buffer

/**
 * Serialises the authentication frame, the first message on a turn.
 *
 * Field names follow the spec's frame (`type`, `access_token`, `request_id`, `request`, `context`,
 * `capabilities`, `codecs`, and the optional `route_hint`); `context` and `route_hint` are delegated
 * so their own annotations keep applying. Absent capabilities and an absent hint are omitted rather
 * than sent empty, which the server accepts.
 */
internal class CloudTurnAuthenticateJsonAdapter(moshi: Moshi) : JsonAdapter<CloudTurnFrame.Authenticate>() {
    private val contextAdapter = moshi.adapter(CloudRouteContext::class.java)
    private val hintAdapter = moshi.adapter(CloudRouteHint::class.java)
    private val options = JsonReader.Options.of(
        "type",
        "access_token",
        "request_id",
        "request",
        "context",
        "capabilities",
        "codecs",
        "route_hint",
    )

    override fun toJson(writer: JsonWriter, value: CloudTurnFrame.Authenticate?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("type").value("authenticate")
        writer.name("access_token").value(value.accessToken)
        writer.name("request_id").value(value.requestId)
        writer.name("request").value(value.request)
        value.sourceLanguage?.let { writer.name("source_language").value(it) }
        // Write context as a raw nested JSON object (not an escaped string).
        // Serialize context with its own adapter and emit the resulting JSON
        // object verbatim. The context adapter uses snake_case field names
        // that must be preserved in the wire format.
        val context = value.context
        writer.name("context")
        writeContextAsRaw(writer, context)
        if (value.capabilities.isNotEmpty()) {
            writer.name("capabilities")
            writer.beginArray()
            value.capabilities.forEach { writer.value(it) }
            writer.endArray()
        }
        writer.name("codecs")
        writer.beginArray()
        value.codecs.forEach { writer.value(it) }
        writer.endArray()
        // Optional: omitted entirely when no typed-UI flow supplied one, so a turn without a hint
        // is byte-identical to the frame that existed before the field.
        value.routeHint?.let { hint ->
            writer.name("route_hint")
            hintAdapter.toJson(writer, hint)
        }
        // The conversation admission fields travel with the turn's own captured identity. Both are
        // written or neither: a mode without a handle (or a handle without a mode) is not a state the
        // contract admits, so it is omitted rather than half-sent.
        val conversationId = value.conversationId
        val conversationMode = value.conversationMode
        if (conversationId != null && conversationMode != null) {
            writer.name("conversation_id").value(conversationId)
            writer.name("conversation_mode").value(conversationMode)
        }
        writer.endObject()
    }

    /** Emit a [CloudRouteContext] as a raw nested JSON object. */
    private fun writeContextAsRaw(writer: JsonWriter, context: CloudRouteContext) {
        writer.beginObject()
        // NO CLIENT CONVERSATION HISTORY ON A CLOUD FRAME. The owning spec forbids
        // `context.recent_conversation` here and requires a wire-omission assertion: the Worker owns
        // cloud conversation history, so a client copy would duplicate it and could diverge from the
        // conversation the server maintains. The field is deliberately NOT written even when the
        // context carries entries -- conversation identity says WHICH conversation the turn belongs to.
        //
        // Local conversation memory is unaffected: it serves the local intent path, not this encoder.
        // Each field is written only when it HAS a value. An absent episode, podcast, position or
        // reference is omitted rather than defaulted: a zero position is a real instant (the very
        // start) and an empty id claims a subject, so neither can stand in for "none".
        if (context.episodeId?.isNotEmpty() == true) {
            writer.name("episode_id").value(context.episodeId)
        }
        if (context.podcastId?.isNotEmpty() == true) {
            writer.name("podcast_id").value(context.podcastId)
        }
        if (context.referencePositionMs != null) {
            writer.name("reference_position_ms").value(context.referencePositionMs)
        }
        if (context.clientPositionMs != null) {
            writer.name("client_position_ms").value(context.clientPositionMs)
        }
        if (context.recentReferencePositions.isNotEmpty()) {
            writer.name("recent_reference_positions")
            writer.beginArray()
            for (pos in context.recentReferencePositions) {
                writer.value(pos)
            }
            writer.endArray()
        }
        if (context.previousReferencePositionMs != null) {
            writer.name("previous_reference_position_ms").value(context.previousReferencePositionMs)
        }
        writer.endObject()
    }

    override fun fromJson(reader: JsonReader): CloudTurnFrame.Authenticate {
        // The client only ever writes this frame; reading it exists so Moshi is satisfied.
        reader.skipValue()
        throw UnsupportedOperationException("the client does not read authentication frames")
    }
}
