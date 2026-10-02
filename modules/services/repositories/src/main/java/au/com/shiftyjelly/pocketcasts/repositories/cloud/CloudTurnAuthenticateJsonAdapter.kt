package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi

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
        "type", "access_token", "request_id", "request", "context", "capabilities", "codecs",
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
        writer.name("context").jsonValue(contextAdapter.toJson(value.context))
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

    override fun fromJson(reader: JsonReader): CloudTurnFrame.Authenticate {
        // The client only ever writes this frame; reading it exists so Moshi is satisfied.
        reader.skipValue()
        throw UnsupportedOperationException("the client does not read authentication frames")
    }
}
