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

                "error" -> CloudRouteJson.errorAdapter.fromJson(payload)
                    ?.let { CloudRouteEvent.Error(code = it.code, message = it.message) }

                // The handshake carries the negotiated codec; there is no separate `auth` frame.
                "connected" -> CloudRouteJson.connectedAdapter.fromJson(payload)
                    ?.let { CloudRouteEvent.Connected(codec = it.codec.orEmpty()) }

                else -> null
            }
        }.getOrNull()
    }

    /**
     * Return the JSON frame without its `type` discriminator so adapters that do not expect
     * `type` can parse the remaining fields cleanly.
     */
    private fun peel(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith('{')) return text
        val json = runCatching { JsonReader.of(Buffer().writeUtf8(trimmed)) }.getOrNull() ?: return text
        return try {
            json.beginObject()
            val sb = StringBuilder().append("{")
            var first = true
            while (json.hasNext()) {
                val name = json.nextName()
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(escapeJsonString(name)).append('"').append(':')
                copyValue(json, sb)
            }
            json.endObject()
            sb.append('}').toString()
        } catch (_: Exception) {
            text
        }
    }

    private fun copyValue(reader: JsonReader, sb: StringBuilder) {
        when (reader.peek()) {
            JsonReader.Token.NULL -> sb.append("null")

            JsonReader.Token.BOOLEAN -> sb.append(if (reader.nextBoolean()) "true" else "false")

            JsonReader.Token.NUMBER -> sb.append(reader.nextString())

            JsonReader.Token.STRING -> sb.append('"').append(escapeJsonString(reader.nextString())).append('"')

            JsonReader.Token.BEGIN_OBJECT -> {
                sb.append('{')
                reader.beginObject()
                var first = true
                while (reader.hasNext()) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(escapeJsonString(reader.nextName())).append('"').append(':')
                    copyValue(reader, sb)
                }
                reader.endObject()
                sb.append('}')
            }

            JsonReader.Token.BEGIN_ARRAY -> {
                sb.append('[')
                reader.beginArray()
                var first = true
                while (reader.hasNext()) {
                    if (!first) sb.append(',')
                    first = false
                    copyValue(reader, sb)
                }
                reader.endArray()
                sb.append(']')
            }

            else -> {
                sb.append("null")
                reader.skipValue()
            }
        }
    }

    private fun escapeJsonString(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }

    private val TYPE_OPTIONS = JsonReader.Options.of("type")
}
