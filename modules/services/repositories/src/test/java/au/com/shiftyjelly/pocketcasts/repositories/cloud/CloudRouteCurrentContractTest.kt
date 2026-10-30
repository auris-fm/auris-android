package au.com.shiftyjelly.pocketcasts.repositories.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudRouteCurrentContractTest {
    @Test
    fun `current nested usage is decoded`() {
        assertEquals(CloudRouteEvent.Done(42, 150), CloudRouteEvents.decode("""{"type":"done","outcome":"completed","usage":{"input_tokens":42,"output_tokens":150,"speech":{"amount":null,"unit":"unavailable"}}}"""))
    }

    @Test
    fun `old flat usage is refused`() {
        assertNull(CloudRouteEvents.decode("""{"type":"done","input_tokens":42,"output_tokens":150}"""))
    }

    @Test
    fun `missing usage is refused rather than treated as success`() {
        assertNull(CloudRouteEvents.decode("""{"type":"done","outcome":"completed"}"""))
    }

    @Test
    fun `unknown terminal outcome is refused`() {
        assertNull(CloudRouteEvents.decode("""{"type":"done","outcome":"legacy","usage":{}}"""))
    }

    @Test
    fun `missing or blank negotiated codec is refused`() {
        assertNull(CloudRouteEvents.decode("""{"type":"connected"}"""))
        assertNull(CloudRouteEvents.decode("""{"type":"connected","codec":""}"""))
    }

    @Test
    fun `legacy text token is not a current wire event`() {
        assertNull(CloudRouteEvents.decode("""{"type":"token","text":"old answer"}"""))
    }

    @Test
    fun `missing items cannot become successful empty search results`() {
        assertNull(CloudRouteEvents.decode("""{"type":"result","kind":"episode_results","scope":"library"}"""))
    }

    @Test
    fun `required usage members cannot be defaulted from an empty object`() {
        assertNull(CloudRouteEvents.decode("""{"type":"done","outcome":"completed","usage":{}}"""))
        assertNull(CloudRouteEvents.decode("""{"type":"done","outcome":"completed","usage":{"input_tokens":1,"output_tokens":2}}"""))
    }

    @Test
    fun `known nullable usage and interrupted outcome are preserved`() {
        assertEquals(
            CloudRouteEvent.Done(null, null, CloudRouteEvent.Outcome.Interrupted),
            CloudRouteEvents.decode("""{"type":"done","outcome":"interrupted","usage":{"input_tokens":null,"output_tokens":null,"speech":{"amount":null,"unit":"unavailable"}}}"""),
        )
    }

    @Test
    fun `connected requires the server reservation identity`() {
        assertNull(CloudRouteEvents.decode("""{"type":"connected","codec":"opus@48k"}"""))
        assertNull(CloudRouteEvents.decode("""{"type":"connected","codec":"opus@48k","reservation_id":""}"""))
    }

    @Test
    fun `unsupported result scope and kind are refused`() {
        assertNull(CloudRouteEvents.decode("""{"type":"result","kind":"unknown","scope":"library","items":[]}"""))
        assertNull(CloudRouteEvents.decode("""{"type":"result","kind":"episode_results","scope":"unknown","items":[]}"""))
    }

    @Test
    fun `missing required action fields are refused instead of replaced`() {
        assertNull(CloudRouteEvents.decode("""{"type":"action"}"""))
        assertNull(CloudRouteEvents.decode("""{"type":"action","tool":"playback","action":"pause"}"""))
    }

    @Test
    fun `malformed usage counts and speech amount are refused`() {
        assertNull(CloudRouteEvents.decode("""{"type":"done","outcome":"completed","usage":{"input_tokens":-1,"output_tokens":0,"speech":{"amount":0,"unit":"characters"}}}"""))
        assertNull(CloudRouteEvents.decode("""{"type":"done","outcome":"completed","usage":{"input_tokens":1.5,"output_tokens":0,"speech":{"amount":0,"unit":"characters"}}}"""))
        assertNull(CloudRouteEvents.decode("""{"type":"done","outcome":"completed","usage":{"input_tokens":0,"output_tokens":0,"speech":{"amount":-1,"unit":"characters"}}}"""))
    }
}
