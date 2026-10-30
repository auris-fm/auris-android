package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi

/**
 * Serialises the app-operation result frame (core `cloud-assistant.md`, "App-operation protocol v1").
 *
 * Its own adapter rather than a change to the authentication one: the authentication frame's bytes are
 * pinned by the existing transport tests, and nothing about this frame requires touching them.
 *
 * [CloudTurnFrame.AppOperationResult.payloadJson] is the ALREADY-SERIALISED v1 result document from
 * `AppOperationV1.result(...)`, so it is written verbatim as nested JSON. Re-encoding it through a
 * map would silently reorder or retype fields the server's digest covers.
 */
internal class CloudTurnAppOperationResultJsonAdapter(
    moshi: Moshi,
) : JsonAdapter<CloudTurnFrame.AppOperationResult>() {
    private val options: JsonReader.Options = JsonReader.Options.of(
        "type",
        "request_id",
        "operation_id",
        "result",
    )

    override fun toJson(writer: JsonWriter, value: CloudTurnFrame.AppOperationResult?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("type").value(CloudRouteAppOps.RESULT_TYPE)
        writer.name("request_id").value(value.requestId)
        writer.name("operation_id").value(value.operationId)
        writer.name("result")
        writer.jsonValue(value.payloadJson)
        writer.endObject()
    }

    override fun fromJson(reader: JsonReader): CloudTurnFrame.AppOperationResult = throw UnsupportedOperationException("result frames are send-only")
}
