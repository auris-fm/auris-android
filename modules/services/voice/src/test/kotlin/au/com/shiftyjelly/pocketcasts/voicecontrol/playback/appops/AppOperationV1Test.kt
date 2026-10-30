package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Codec contract tests for app-ops/1 (core `cloud-assistant.md`, committed `bf5fb219`). These assert
 * the WIRE rules only; no operation is executed and nothing is advertised.
 */
@RunWith(RobolectricTestRunner::class)
class AppOperationV1Test {
    private val id = "11111111-2222-3333-4444-555555555555"

    private fun frame(extra: String = """ "type": "action", "tool": "playback", "action": "pause", "params": {} """) = """{ "operation_id": "$id", "capability_version": "app-ops/1", $extra }"""

    @Test
    fun `a complete app operation parses and retains its original bytes`() {
        val raw = frame()
        val parsed = AppOperationV1.parse(raw)
        assertTrue(parsed is AppOperationV1.Parsed.Valid)
        val request = (parsed as AppOperationV1.Parsed.Valid).request
        assertEquals("playback", request.tool)
        assertEquals("pause", request.action)
        assertTrue(request.rawBytes.contentEquals(raw.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `an incomplete identity pair is malformed rather than legacy playback`() {
        val onlyId = """{ "type": "action", "operation_id": "$id", "tool": "playback", "action": "pause", "params": {} }"""
        val onlyVersion = """{ "type": "action", "capability_version": "app-ops/1", "tool": "playback", "action": "pause", "params": {} }"""
        assertTrue(AppOperationV1.parse(onlyId) is AppOperationV1.Parsed.Invalid)
        assertTrue(AppOperationV1.parse(onlyVersion) is AppOperationV1.Parsed.Invalid)
    }

    @Test
    fun `an unsupported capability version is refused with a reason token`() {
        val parsed = AppOperationV1.parse(frame().replace("app-ops/1", "app-ops/2"))
        assertEquals("invalid_request", (parsed as AppOperationV1.Parsed.Invalid).reasonToken)
    }

    @Test
    fun `a wrong or missing frame type is refused`() {
        val wrong = frame().replace("\"type\": \"action\"", "\"type\": \"transcript\"")
        assertEquals("invalid_request", (AppOperationV1.parse(wrong) as AppOperationV1.Parsed.Invalid).reasonToken)
        val missing = """{ "operation_id": "$id", "capability_version": "app-ops/1", "tool": "playback", "action": "pause", "params": {} }"""
        assertEquals("invalid_request", (AppOperationV1.parse(missing) as AppOperationV1.Parsed.Invalid).reasonToken)
    }

    @Test
    fun `a non-uuid operation id is not recovered as an identity`() {
        val bad = frame().replace(id, "op-1")
        val parsed = AppOperationV1.parse(bad) as AppOperationV1.Parsed.Invalid
        assertNull(parsed.operationId)
        assertNull(parsed.reasonToken)
    }

    @Test
    fun `missing params is not repaired into an empty object`() {
        val noParams = """{ "type": "action", "operation_id": "$id", "capability_version": "app-ops/1", "tool": "playback", "action": "pause" }"""
        assertEquals("invalid_request", (AppOperationV1.parse(noParams) as AppOperationV1.Parsed.Invalid).reasonToken)
    }

    private fun op(tool: String, action: String, precondition: String? = null): String {
        val base = """{ "type": "action", "operation_id": "$id", "capability_version": "app-ops/1", "tool": "$tool", "action": "$action", "params": {} """
        return if (precondition == null) "$base}" else "$base, \"precondition\": $precondition }"
    }

    @Test
    fun `every real episode relative action requires the episode identity`() {
        // Names taken from the Voice Intents action tables, not invented.
        for (key in listOf(
            "playback.seek_relative",
            "playback.seek_to",
            "playback.next_episode",
            "chapter.by_index",
            "chapter.by_title",
            "chapter.open_link",
            "chapter.query_list",
            "chapter.query_current",
            "chapter.query_count",
            "chapter.query_next",
        )) {
            val (tool, action) = key.split(".")
            val missing = AppOperationV1.parse(op(tool, action)) as AppOperationV1.Parsed.Invalid
            assertEquals("$key must require an episode identity", "invalid_request", missing.reasonToken)
            assertTrue(AppOperationV1.parse(op(tool, action, """{ "episode_id": "ep-1" }""")) is AppOperationV1.Parsed.Valid)
        }
    }

    @Test
    fun `episode-free actions require no precondition`() {
        for (key in listOf("playback.pause", "playback.resume")) {
            val (tool, action) = key.split(".")
            assertTrue("$key should parse without a precondition", AppOperationV1.parse(op(tool, action)) is AppOperationV1.Parsed.Valid)
        }
    }

    @Test
    fun `every real queue mutation requires the queue revision`() {
        for (action in listOf("add_top", "add_bottom", "remove", "move_to_top", "move_to_bottom", "remove_by_podcast", "sort", "clear")) {
            val missing = AppOperationV1.parse(op("queue", action)) as AppOperationV1.Parsed.Invalid
            assertEquals("queue.$action must require a queue revision", "invalid_request", missing.reasonToken)
            assertTrue(AppOperationV1.parse(op("queue", action, """{ "queue_revision": "r1" }""")) is AppOperationV1.Parsed.Valid)
        }
    }

    @Test
    fun `queue queries are not mutations and need no revision`() {
        for (action in listOf("query_contents", "query_next", "query_length", "query_is_queued")) {
            assertTrue(AppOperationV1.parse(op("queue", action)) is AppOperationV1.Parsed.Valid)
        }
    }

    @Test
    fun `an oversized nested collection is refused at any depth`() {
        val nested = JSONArray().apply { repeat(AppOperationV1.MAX_COLLECTION_ITEMS + 1) { put("x") } }
        val inner = JSONObject().put("items", nested)
        val params = JSONObject().put("outer", JSONObject().put("deeper", inner))
        val raw = """{ "type": "action", "operation_id": "$id", "capability_version": "app-ops/1", "tool": "queue", "action": "add", "params": $params, "precondition": { "queue_revision": "r1" } }"""
        assertEquals("invalid_request", (AppOperationV1.parse(raw) as AppOperationV1.Parsed.Invalid).reasonToken)
    }

    @Test
    fun `an oversize envelope with the identity first is terminated unidentified`() {
        val big = "x".repeat(AppOperationV1.MAX_ENVELOPE_BYTES)
        val parsed = AppOperationV1.parse(frame(""" "type": "action", "tool": "playback", "action": "pause", "params": { "blob": "$big" } """))
        assertTrue(parsed is AppOperationV1.Parsed.Invalid)
        // No prefix recovery: an oversize frame is not parsed for an identity at all.
        assertNull((parsed as AppOperationV1.Parsed.Invalid).operationId)
        assertNull(parsed.reasonToken)
    }

    @Test
    fun `an oversize envelope with the identity last is also terminated unidentified`() {
        val padding = "x".repeat(AppOperationV1.MAX_ENVELOPE_BYTES)
        val raw = """{ "type": "action", "params": { "blob": "$padding" }, "operation_id": "$id", "capability_version": "app-ops/1", "tool": "playback", "action": "pause" }"""
        val parsed = AppOperationV1.parse(raw) as AppOperationV1.Parsed.Invalid
        assertNull(parsed.operationId)
        assertNull(parsed.reasonToken)
    }

    @Test
    fun `a malformed frame without a recoverable identity is terminated without guessing`() {
        val parsed = AppOperationV1.parse("{ not json")
        assertTrue(parsed is AppOperationV1.Parsed.Invalid)
        assertNull((parsed as AppOperationV1.Parsed.Invalid).operationId)
        assertNull(parsed.reasonToken)
    }

    @Test
    fun `success requires observed state and no reason`() {
        assertTrue(AppOperationV1.result(id, "succeeded", state = JSONObject().put("playing", false)) != null)
        assertNull(AppOperationV1.result(id, "succeeded"))
        assertNull(AppOperationV1.result(id, "succeeded", reason = "nope", state = JSONObject()))
    }

    @Test
    fun `an empty or non-uuid operation id cannot be published`() {
        assertNull(AppOperationV1.result("", "refused", reason = "unsupported_operation"))
        assertNull(AppOperationV1.result("op-1", "refused", reason = "unsupported_operation"))
    }

    @Test
    fun `non-success requires a machine readable reason and forbids state`() {
        val refused = AppOperationV1.result(id, "refused", reason = "unsupported_operation")
        assertTrue(refused != null)
        val body = JSONObject(refused!!)
        assertEquals("app_operation_result", body.getString("type"))
        assertFalse(body.has("state"))
        assertNull(AppOperationV1.result(id, "refused"))
        assertNull(AppOperationV1.result(id, "refused", reason = "Not A Token"))
        assertNull(AppOperationV1.result(id, "refused", reason = "unsupported", state = JSONObject()))
    }

    @Test
    fun `an oversize or over wide state cannot be published as a success`() {
        assertNull(AppOperationV1.result(id, "succeeded", state = JSONObject().put("blob", "x".repeat(AppOperationV1.MAX_STATE_BYTES))))
        val wide = JSONArray().apply { repeat(AppOperationV1.MAX_COLLECTION_ITEMS + 1) { put("x") } }
        assertNull(AppOperationV1.result(id, "succeeded", state = JSONObject().put("items", wide)))
    }

    @Test
    fun `detail is truncated on a code point boundary within 512 bytes`() {
        val out = AppOperationV1.truncateUtf8("é".repeat(400), AppOperationV1.MAX_DETAIL_BYTES)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= AppOperationV1.MAX_DETAIL_BYTES)
        assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }
}
