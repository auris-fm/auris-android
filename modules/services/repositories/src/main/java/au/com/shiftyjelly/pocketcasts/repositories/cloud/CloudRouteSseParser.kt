package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.io.BufferedReader
import java.io.IOException
import kotlinx.coroutines.CancellationException
import timber.log.Timber

internal data class CloudRouteSseParseResult(
    val events: List<CloudRouteEvent>,
    val truncated: Boolean,
)

internal class CloudRouteSseParser {

    /**
     * Streaming parse: dispatches each complete SSE event to [onEvent] as it
     * is read, so `play_quote` actions and tokens can flow mid-turn instead
     * of waiting for the whole body. Returns true when the body ended
     * abnormally (mid-event connection loss).
     */
    fun parse(body: BufferedReader, onEvent: (CloudRouteEvent) -> Unit): Boolean {
        var eventName: String? = null
        val dataLines = mutableListOf<String>()
        var truncated = false

        while (true) {
            val rawLine = try {
                body.readLine()
            } catch (error: IOException) {
                truncated = true
                break
            } ?: break

            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                dispatchSafe(eventName, dataLines, onEvent)
                eventName = null
                dataLines.clear()
                continue
            }
            if (line.startsWith(":")) {
                continue
            }
            when {
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
            }
        }

        if (!truncated) {
            dispatchSafe(eventName, dataLines, onEvent)
        }

        return truncated
    }

    /**
     * Payload/shape failures (Moshi throws both IOException and
     * RuntimeException hierarchies) are normalised to IOException so the
     * client maps every parse failure to `invalid_response` — none can
     * escape as a silent RuntimeException.
     */
    private fun dispatchSafe(
        eventName: String?,
        dataLines: List<String>,
        onEvent: (CloudRouteEvent) -> Unit,
    ) {
        try {
            dispatch(eventName, dataLines)?.let(onEvent)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Every parse failure is normalised, including IOException
            // subclasses: Moshi's syntax errors are IOExceptions whose message
            // can echo payload text, so rethrowing them unchanged would defeat
            // the content-free guarantee. No cause is attached either — Timber
            // prints cause messages.
            throw IOException("Malformed SSE payload")
        }
    }

    fun parse(body: BufferedReader): CloudRouteSseParseResult {
        val events = mutableListOf<CloudRouteEvent>()
        val truncated = parse(body, events::add)
        return CloudRouteSseParseResult(events = events, truncated = truncated)
    }

    private fun dispatch(eventName: String?, dataLines: List<String>): CloudRouteEvent? {
        if (eventName == null && dataLines.isEmpty()) return null
        val data = dataLines.joinToString("\n")
        if (data.isEmpty()) return null

        // SSE spec: a frame with data but no event: line is a "message"
        // event, not an unknown one.
        val name = eventName ?: "message"
        return when (name) {
            "action" -> parseActionPayload(data)

            "token" -> {
                val payload = CloudRouteJson.tokenAdapter.fromJson(data)
                    ?: throw IOException("Invalid token payload")
                CloudRouteEvent.Token(payload.text)
            }

            "done" -> {
                val payload = CloudRouteJson.doneAdapter.fromJson(data)
                    ?: throw IOException("Invalid done payload")
                CloudRouteEvent.Done(payload.inputTokens, payload.outputTokens)
            }

            "error" -> {
                val payload = CloudRouteJson.errorAdapter.fromJson(data)
                    ?: throw IOException("Invalid error payload")
                CloudRouteEvent.Error(payload.code, payload.message)
            }

            "result" -> {
                // Capability-negotiated event: a payload shape this client
                // does not understand (a server-side addition to the result
                // model) must not abort a turn for clients that never asked
                // for the capability — skip it like an unknown event.
                val payload = runCatching { CloudRouteJson.resultAdapter.fromJson(data) }.getOrNull()
                if (payload == null) {
                    Timber.w("Skipping unparseable result payload (%d bytes)", data.toByteArray(Charsets.UTF_8).size)
                    null
                } else {
                    CloudRouteEvent.Result(payload)
                }
            }

            else -> {
                // Forward compatibility: a server-added event type must not
                // kill every turn (and must not surface as connection_lost).
                // Shape only, never content: the payload is server text that
                // may echo user context.
                // No server text at all — not even bounded: an event name is
                // whatever the server sends and could itself be user content
                // (an address, an identifier). The two sizes are what is left:
                // they distinguish an empty/new-shape frame from a substantial
                // one when diagnosing a forward-compat skip, and they carry
                // none of the content.
                Timber.w(
                    "Unknown SSE event (name %d chars, payload %d bytes)",
                    name.length,
                    data.toByteArray(Charsets.UTF_8).size,
                )
                null
            }
        }
    }

    private fun parseActionPayload(data: String): CloudRouteEvent.Action {
        val map = CloudRouteJson.flexibleMapAdapter.fromJson(data)
            ?: throw IOException("Invalid action payload")
        val tool = map["tool"] as? String ?: throw IOException("Missing action tool")
        val action = map["action"] as? String ?: throw IOException("Missing action name")

        @Suppress("UNCHECKED_CAST")
        val params = (map["params"] as? Map<String, Any?>) ?: emptyMap()
        return CloudRouteEvent.Action(tool = tool, action = action, params = params)
    }
}
