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
        val text = """{"type":"done","input_tokens":42,"output_tokens":150}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Done)
        val done = event as CloudRouteEvent.Done
        assertEquals(42, done.inputTokens)
        assertEquals(150, done.outputTokens)
    }

    @Test
    fun `decode parses done event with null tokens`() {
        val text = """{"type":"done"}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.Done)
        val done = event as CloudRouteEvent.Done
        assertEquals(null, done.inputTokens)
        assertEquals(null, done.outputTokens)
    }

    @Test
    fun `decode parses auth response event`() {
        val text = """{"type":"auth","codec":"opus@48k"}"""
        val event = CloudRouteEvents.decode(text)
        assert(event is CloudRouteEvent.AuthResponse)
        val auth = event as CloudRouteEvent.AuthResponse
        assertEquals("opus@48k", auth.codec)
    }

    @Test
    fun `decode parses connected event`() {
        val text = """{"type":"connected"}"""
        val event = CloudRouteEvents.decode(text)
        assertEquals(CloudRouteEvent.Connected, event)
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
}
