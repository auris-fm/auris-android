package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Decodes a WebSocket text frame into a [CloudRouteEvent] by its `type` discriminator.
 *
 * The SSE path dispatched on `event:` line prefixes; a socket carries whole JSON messages, so the
 * discriminator is inside the payload. Payload shapes are the ones the SSE path already used, so
 * the existing [CloudRouteJson] adapters do the work.
 */
internal object CloudRouteEvents {
    @Suppress("UNCHECKED_CAST") // The flexible map adapter yields Any? for the nested params map.
    fun decode(text: String): CloudRouteEvent? {
        val json = runCatching { JsonReader.of(Buffer().writeUtf8(text)) }.getOrNull() ?: return null
        return runCatching {
            json.beginObject()
            var type: String? = null
            while (json.hasNext()) {
                if (json.selectName(TYPE_OPTIONS) == 0) type = json.nextString() else json.skipValue()
            }
            json.endObject()
            val payload = peel(text)
            when (type) {
                "action" -> CloudRouteJson.flexibleMapAdapter.fromJson(payload)
                    ?.let { map ->
                        CloudRouteEvent.Action(
                            tool = map["tool"] as? String ?: "",
                            action = map["action"] as? String ?: "",
                            params = (map["params"] as? Map<String, Any?>).orEmpty(),
                        )
                    }

                "result" -> CloudRouteJson.resultAdapter.fromJson(payload)
                    ?.let { CloudRouteEvent.Result(it) }

                "done" -> CloudRouteJson.doneAdapter.fromJson(payload)
                    ?.let { CloudRouteEvent.Done(inputTokens = it.inputTokens, outputTokens = it.outputTokens) }

                "auth" -> CloudRouteJson.authResponseAdapter.fromJson(payload)
                    ?.let { CloudRouteEvent.AuthResponse(codec = it.codec.orEmpty()) }

                "error" -> CloudRouteJson.errorAdapter.fromJson(payload)
                    ?.let { CloudRouteEvent.Error(code = it.code, message = it.message) }

                "connected" -> CloudRouteEvent.Connected

                else -> null
            }
        }.getOrNull()
    }

    /** The frame minus its `type` field, for the adapters that do not know about it. */
    private fun peel(text: String): String = text

    private val TYPE_OPTIONS = JsonReader.Options.of("type")
}
