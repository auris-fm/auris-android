package au.com.shiftyjelly.pocketcasts.repositories.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [CloudRouteEvents] — specifically the [CloudRouteEvents.decode]
 * method and its internal [CloudRouteEvents.peel] JSON stripping logic.
 */
class CloudRouteEventsTest {

    @Test
    fun `decode parses action event with params`() {
        val text = """{"type":"action","tool":"playback","action":"seek_relative","params":{"delta_seconds":30,"direction":"forward"}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Action)
        val action = event as CloudRouteEvent.Action
        assertEquals("playback", action.tool)
        assertEquals("seek_relative", action.action)
        assertEquals("30", action.params["delta_seconds"]?.toString())
        assertEquals("forward", action.params["direction"])
    }

    @Test
    fun `decode parses result event`() {
        // Flat, not nested: frames.ts ResultFrame is `{type:"result"} & payload`, and
        // session.ts merges with `{ type: event, ...payload }`. CloudSearchResults is the payload.
        val text = """{"type":"result","kind":"episode_results","scope":"library","items":[]}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Result)
    }

    @Test
    fun `decode parses done event with nullable tokens`() {
        val text = """{"type":"done","outcome":"completed","usage":{"input_tokens":42,"output_tokens":150,"speech":{"amount":0,"unit":"characters"}}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Done)
        val done = event as CloudRouteEvent.Done
        assertEquals(42, done.inputTokens)
        assertEquals(150, done.outputTokens)
    }

    @Test
    fun `decode parses done event with null tokens`() {
        val text = """{"type":"done","outcome":"interrupted","usage":{"input_tokens":null,"output_tokens":null,"speech":{"amount":null,"unit":"unavailable"}}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Done)
        val done = event as CloudRouteEvent.Done
        assertEquals(null, done.inputTokens)
        assertEquals(null, done.outputTokens)
        assertEquals(CloudRouteEvent.Outcome.Interrupted, done.outcome)
    }

    @Test
    fun `decode parses connected event and carries its negotiated codec`() {
        // The handshake is where the codec arrives - the protocol has no `auth` frame.
        val text = """{"type":"connected","codec":"opus@48k","reservation_id":"r-1"}"""
        val event = CloudRouteEvents.decode(text)
        assertEquals(CloudRouteEvent.Connected(codec = "opus@48k"), event)
    }

    @Test
    fun `decode rejects a connected frame without a codec`() {
        val event = CloudRouteEvents.decode("""{"type":"connected"}""")
        assertEquals(null, event)
    }

    @Test
    fun `decode parses error event`() {
        val text = """{"type":"error","code":"invalid_response","message":"bad payload"}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Error)
        val err = event as CloudRouteEvent.Error
        assertEquals("invalid_response", err.code)
        assertEquals("bad payload", err.message)
    }

    @Test
    fun `decode returns null for unknown type`() {
        val text = """{"type":"unknown","data":true}"""
        val event = CloudRouteEvents.decode(text)
        assertEquals(null, event)
    }

    @Test
    fun `decode returns null for invalid JSON`() {
        val event = CloudRouteEvents.decode("{invalid json}")
        assertEquals(null, event)
    }

    @Test
    fun `decode returns null for empty string`() {
        val event = CloudRouteEvents.decode("")
        assertEquals(null, event)
    }

    // --- App-operation protocol v1 recognition ---------------------------------------------------
    //
    // Presence of EITHER identity field makes a frame v1-shaped. That ordering is the whole point:
    // the legacy action and the deterministic hold carry neither field, so recognition cannot capture
    // them, and a PARTIAL identity pair cannot fall through to either.

    private fun opJson(identity: String, extra: String = """ "tool":"playback","action":"pause","params":{},"type":"action" """) = """{$identity$extra}"""

    @Test
    fun `a complete v1 identity decodes as an app operation carrying the delivered bytes`() {
        val text = """{"operation_id":"11111111-2222-3333-4444-555555555555","capability_version":"app-ops/1","type":"action","tool":"playback","action":"pause","params":{"x":1}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.AppOperation) { "expected AppOperation, got $event" }
        event as CloudRouteEvent.AppOperation
        assertEquals("11111111-2222-3333-4444-555555555555", event.operationId)
        assertEquals("app-ops/1", event.capabilityVersion)
        // The replay contract is defined over the ORIGINAL bytes, so they must survive decoding.
        assertEquals(text, event.rawBytes.decodeToString())
    }

    @Test
    fun `operation_id alone is malformed and never falls back to a legacy action`() {
        val event = CloudRouteEvents.decode(opJson(""""operation_id":"11111111-2222-3333-4444-555555555555","""))
        assert(event is CloudRouteEvent.MalformedAppOperation) { "expected malformed, got $event" }
        assertEquals("11111111-2222-3333-4444-555555555555", (event as CloudRouteEvent.MalformedAppOperation).operationId)
    }

    @Test
    fun `capability_version alone is malformed with no identity`() {
        val event = CloudRouteEvents.decode(opJson(""""capability_version":"app-ops/1","""))
        assert(event is CloudRouteEvent.MalformedAppOperation) { "expected malformed, got $event" }
        // No recoverable identity, so there is nothing to address a refusal to.
        assertEquals(null, (event as CloudRouteEvent.MalformedAppOperation).operationId)
    }

    @Test
    fun `an oversize v1 envelope is malformed and unidentified before any parse`() {
        val padding = "x".repeat(17 * 1024)
        val text = """{"operation_id":"11111111-2222-3333-4444-555555555555","capability_version":"app-ops/1","type":"action","tool":"playback","action":"pause","params":{"p":"$padding"}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.MalformedAppOperation) { "expected malformed, got $event" }
        // Deliberately unidentified: no prefix scan, no guessed identity out of a partial frame.
        assertEquals(null, (event as CloudRouteEvent.MalformedAppOperation).operationId)
    }

    @Test
    fun `a hold frame carrying turn_id is not captured by v1 recognition`() {
        val text = """{"type":"action","turn_id":"t-1","tool":"playback","action":"pause","params":{}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Action) { "expected legacy action, got $event" }
        assertEquals("t-1", (event as CloudRouteEvent.Action).turnId)
    }

    @Test
    fun `a legacy action with no identity fields is decoded exactly as before`() {
        val text = """{"type":"action","tool":"playback","action":"seek_relative","params":{"delta_seconds":30}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Action) { "expected legacy action, got $event" }
        assertEquals(null, (event as CloudRouteEvent.Action).turnId)
    }

    // --- App-operation identity recognition: bound first, fail closed ------------------------------

    @Test
    fun `an identity name deep in the document is still recognised by the single parse`() {
        // Identity is read from the parsed TOP-LEVEL names, so position within the object is irrelevant
        // and there is no scan bound to fall off.
        val filler = (1..80).joinToString(",") { "\"f$it\":$it" }
        val text = """{"type":"action",$filler,"operation_id":"op-1","capability_version":"app-ops/1","params":{}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event !is CloudRouteEvent.Action) { "an identity-bearing frame must never decode as legacy action" }
        assert(event is CloudRouteEvent.MalformedAppOperation || event is CloudRouteEvent.AppOperation)
    }

    @Test
    fun `a large value before the identity fields does not hide them`() {
        val big = "x".repeat(8 * 1024)
        val text = """{"type":"action","blob":"$big","operation_id":"op-1","capability_version":"app-ops/1","params":{}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event !is CloudRouteEvent.Action) { "a large value must not hide an identity NAME" }
    }

    @Test
    fun `oversize text is rejected before parsing and yields no identity`() {
        // The bound is the whole rule for oversized text: it is never parsed, so it cannot execute and
        // cannot yield a recovered id. Whether it carried identity is not decided, and not needed.
        val big = "x".repeat(CloudRouteEvents.MAX_APP_OPERATION_ENVELOPE_BYTES)
        val text = """{"operation_id":"op-1","capability_version":"app-ops/1","type":"action","padding":"$big"}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.MalformedAppOperation)
        assert((event as CloudRouteEvent.MalformedAppOperation).operationId == null) {
            "an oversize envelope is not parsed, so it cannot yield a recovered id"
        }
    }

    @Test
    fun `an oversize legacy action is refused rather than executed`() {
        // The bound protects the parser, not identity: nothing oversized is parsed, so nothing
        // oversized can reach playback. It is answered unidentified rather than silently ignored, so
        // the server is not left waiting on a frame the client refused to read.
        val big = "x".repeat(CloudRouteEvents.MAX_APP_OPERATION_ENVELOPE_BYTES)
        val text = """{"type":"action","tool":"playback","action":"pause","params":{},"padding":"$big"}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.MalformedAppOperation)
        assert(event !is CloudRouteEvent.Action) { "an oversized frame must never execute" }
    }

    @Test
    fun `unparseable text is dropped, and can never execute as playback`() {
        val text = """{type:"action","tool":"playback","action":"pause",operation_id:"op-1"}"""
        assert(CloudRouteEvents.decode(text) == null) { "malformed text must not become legacy playback" }
    }

    @Test
    fun `a truncated document carrying identity is dropped, never executed`() {
        val text = """{"type":"action","operation_id":"op-1","params":{"a":"""
        assert(CloudRouteEvents.decode(text) == null)
    }

    @Test
    fun `an identity name nested under params is not an envelope identity`() {
        // Data inside params is not the frame's identity: only the TOP-LEVEL names decide.
        val text = """{"type":"action","tool":"playback","action":"pause","params":{"operation_id":"x"}}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Action) { "a nested name must not promote a legacy frame to v1" }
    }

    @Test
    fun `a legacy frame is unaffected by the bound`() {
        val text = """{"type":"action","tool":"playback","action":"pause","params":{}}"""
        assert(CloudRouteEvents.decode(text) is CloudRouteEvent.Action)
    }

    @Test
    fun `an empty frame is dropped`() {
        assert(CloudRouteEvents.decode("") == null)
        assert(CloudRouteEvents.decode("   ") == null)
    }

    @Test
    fun `a non-object document is dropped`() {
        assert(CloudRouteEvents.decode("[]") == null)
        assert(CloudRouteEvents.decode("\"just a string\"") == null)
    }
}
