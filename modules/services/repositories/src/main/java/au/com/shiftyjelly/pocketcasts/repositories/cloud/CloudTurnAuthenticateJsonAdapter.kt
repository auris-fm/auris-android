package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import okio.Buffer

/**
 * Serialises the authentication frame, the first message on a turn.
 *
 * Field names follow the spec's frame exactly (`type`, `access_token`, `request_id`, `request`,
 * `context`, `capabilities`, `codecs`); `context` is delegated so its own snake_case annotations
 * keep applying. Absent capabilities are omitted rather than sent empty, which the server accepts.
 */
internal class CloudTurnAuthenticateJsonAdapter(moshi: Moshi) : JsonAdapter<CloudTurnFrame.Authenticate>() {
    private val contextAdapter = moshi.adapter(CloudRouteContext::class.java)
    private val options = JsonReader.Options.of(
        "type",
        "access_token",
        "request_id",
        "request",
        "context",
        "capabilities",
        "codecs",
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
        // Write context as a raw nested JSON object (not an escaped string).
        // Serialize context with its own adapter and emit the resulting JSON
        // object verbatim. The context adapter uses snake_case field names
        // that must be preserved in the wire format.
        val context = value.context
        if (context != null) {
            writer.name("context")
            writeContextAsRaw(writer, context)
        }
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
        writer.endObject()
    }

    /** Emit a [CloudRouteContext] as a raw nested JSON object. */
    private fun writeContextAsRaw(writer: JsonWriter, context: CloudRouteContext) {
        writer.beginObject()
        if (context.recentConversation != null && context.recentConversation.isNotEmpty()) {
            writer.name("recent_conversation")
            writer.beginArray()
            for (entry in context.recentConversation) {
                writer.beginObject()
                writer.name("role").value(entry.role)
                writer.name("text").value(entry.text)
                writer.endObject()
            }
            writer.endArray()
        }
        if (context.episodeId.isNotEmpty()) {
            writer.name("episode_id").value(context.episodeId)
        }
        if (context.podcastId.isNotEmpty()) {
            writer.name("podcast_id").value(context.podcastId)
        }
        if (context.referencePositionMs != 0L) {
            writer.name("reference_position_ms").value(context.referencePositionMs)
        }
        if (context.clientPositionMs != 0L) {
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
