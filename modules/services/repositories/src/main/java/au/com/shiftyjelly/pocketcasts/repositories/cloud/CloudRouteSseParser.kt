package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.io.BufferedReader
import java.io.IOException

internal data class CloudRouteSseParseResult(
    val events: List<CloudRouteEvent>,
    val truncated: Boolean,
)

internal class CloudRouteSseParser {
    fun parse(body: BufferedReader): CloudRouteSseParseResult {
        val events = mutableListOf<CloudRouteEvent>()
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
                dispatch(eventName, dataLines)?.let(events::add)
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
            dispatch(eventName, dataLines)?.let(events::add)
        }

        return CloudRouteSseParseResult(events = events, truncated = truncated)
    }

    private fun dispatch(eventName: String?, dataLines: List<String>): CloudRouteEvent? {
        if (eventName == null && dataLines.isEmpty()) return null
        val data = dataLines.joinToString("\n")
        if (data.isEmpty()) return null

        return when (eventName) {
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

            else -> throw IOException("Unknown SSE event: $eventName")
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
