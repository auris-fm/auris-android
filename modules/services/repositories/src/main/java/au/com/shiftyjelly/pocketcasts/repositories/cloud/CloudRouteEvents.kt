package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Decodes a WebSocket text frame into a [CloudRouteEvent] by its `type` discriminator.
 *
 * A socket carries whole JSON messages, so the discriminator is inside the payload rather than on
 * a line prefix; the existing [CloudRouteJson] adapters do the decoding.
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
                            tool = (map["tool"] as? String)?.takeIf { it.isNotBlank() } ?: return null,
                            action = (map["action"] as? String)?.takeIf { it.isNotBlank() } ?: return null,
                            params = map["params"] as? Map<String, Any?> ?: return null,
                            // Read from the frame, and absent on user actions -- the distinction the
                            // contract turns on. Not defaulted to a sentinel: an unprefixed absence is
                            // what makes "not a user action" different from "this turn's hold".
                            turnId = map["turn_id"] as? String,
                        )
                    }

                "result" -> CloudRouteJson.resultAdapter.fromJson(payload)
                    ?.takeIf {
                        it.kind == CloudSearchResults.KIND_EPISODE_RESULTS &&
                            it.scope in setOf(CloudSearchResults.SCOPE_LIBRARY, CloudSearchResults.SCOPE_GLOBAL, CloudSearchResults.SCOPE_CURRENT_EPISODE)
                    }
                    ?.let { CloudRouteEvent.Result(it) }

                "done" -> decodeDone(payload)

                "error" -> CloudRouteJson.errorAdapter.fromJson(payload)
                    ?.let { CloudRouteEvent.Error(code = it.code, message = it.message) }

                // The handshake carries the negotiated codec; there is no separate `auth` frame.
                "connected" -> CloudRouteJson.connectedAdapter.fromJson(payload)
                    ?.takeIf { it.codec.isNotBlank() && it.reservationId.isNotBlank() }
                    ?.let { CloudRouteEvent.Connected(codec = it.codec) }

                else -> null
            }
        }.getOrNull()
    }

    /** Required nullable members must be present: omission is not measured unknown usage. */
    private fun decodeDone(payload: String): CloudRouteEvent.Done? {
        val frame = CloudRouteJson.flexibleMapAdapter.fromJson(payload) ?: return null
        val outcome = when (frame["outcome"]) {
            "completed" -> CloudRouteEvent.Outcome.Completed
            "interrupted" -> CloudRouteEvent.Outcome.Interrupted
            else -> return null
        }
        val usage = frame["usage"] as? Map<*, *> ?: return null
        if (!usage.keys.containsAll(listOf("input_tokens", "output_tokens", "speech"))) return null
        val speech = usage["speech"] as? Map<*, *> ?: return null
        if (!speech.containsKey("amount") || (speech["unit"] as? String).isNullOrBlank()) return null
        val amount = speech["amount"]
        if (amount != null && (amount !is Number || !amount.toDouble().isFinite() || amount.toDouble() < 0)) return null
        val input = usage["input_tokens"]
        val output = usage["output_tokens"]
        if (!validCount(input) || !validCount(output)) return null
        return CloudRouteEvent.Done((input as? Number)?.toInt(), (output as? Number)?.toInt(), outcome)
    }

    private fun validCount(value: Any?): Boolean = value == null ||
        (value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..Int.MAX_VALUE.toDouble() && value.toDouble() == value.toInt().toDouble())

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
