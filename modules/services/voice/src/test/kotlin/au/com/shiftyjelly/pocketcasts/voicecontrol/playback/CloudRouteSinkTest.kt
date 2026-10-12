package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteCapabilities
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteConversationEntry
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteErrorCodes
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteLimits
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudSearchEvidenceItem
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudSearchResults
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30])
@kotlinx.coroutines.ExperimentalCoroutinesApi
class CloudRouteSinkTest {

    private val playbackContext = PlaybackContext(
        episodeId = "episode-id",
        podcastId = "podcast-id",
        referencePositionMs = 1_230_000L,
        clientPositionMs = 50_000L,
    )

    @Test
    fun `a discovery turn after saved reference state carries no playback positions at all`() = runTest {
        // @reviewer's stateful case: the saved state accumulates reference positions while an episode
        // IS playing, and the builder merged them in unconditionally -- so a later turn with no episode
        // still carried positions belonging to a recording it is not about, which the server rejects.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        // Recorded BEFORE the turn, which is the whole point: the saved history exists when the
        // builder runs, so a case that recorded it during the route would leave the state empty and
        // could not see the merge it is about.
        deps.cloudPlaybackContextState.record(
            referencePositionMs = 100L,
            previousReferencePositionMs = 300L,
        )
        val sink = deps.sink()

        // A discovery turn: nothing is playing.
        sink.routeToCloud(
            "what should I listen to",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(
                episodeId = null,
                podcastId = null,
                clientPositionMs = null,
                referencePositionMs = null,
            ),
        )

        val turn = deps.routeTurns.last()
        assertNull("no episode means no episode id", turn.context.episodeId)
        assertNull("and no client position", turn.context.clientPositionMs)
        assertNull("and no reference position", turn.context.referencePositionMs)
        assertTrue(
            "and the saved reference history must not be reintroduced " +
                "(recent=${turn.context.recentReferencePositions})",
            turn.context.recentReferencePositions.isEmpty(),
        )
        assertNull(
            "nor a saved previous reference",
            turn.context.previousReferencePositionMs,
        )
    }

    @Test
    fun `a turn with a current episode keeps its saved reference history`() = runTest {
        // The positive control @spec asked for: with an episode current, the saved reference history is
        // retained, so the omission above is about the missing episode rather than about the builder
        // dropping positions unconditionally.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        // Recorded BEFORE the turn: the saved history is what this case asserts is retained.
        deps.cloudPlaybackContextState.record(
            referencePositionMs = 100L,
            previousReferencePositionMs = 300L,
        )
        val sink = deps.sink()

        sink.routeToCloud(
            "what happened",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(
                episodeId = "episode-1",
                podcastId = "podcast-1",
                clientPositionMs = 5_000L,
            ),
        )

        val turn = deps.routeTurns.last()
        assertEquals("episode-1", turn.context.episodeId)
        assertEquals(5_000L, turn.context.clientPositionMs)
        assertTrue(
            "an episode's saved reference history is retained " +
                "(recent=${turn.context.recentReferencePositions})",
            turn.context.recentReferencePositions.isNotEmpty() ||
                turn.context.previousReferencePositionMs != null,
        )
    }

    @Test
    fun `upstream throw during collect still restores auto pause`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flow {
                    emit(CloudRouteEvent.Token("partial "))
                    throw IllegalStateException("upstream blew up")
                },
            ),
        )
        val sink = deps.sink()

        val failure = runCatching {
            sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext)
        }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `cancellation during collect still restores auto pause`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flow {
                    emit(CloudRouteEvent.Token("partial "))
                    awaitCancellation()
                },
            ),
        )
        val sink = deps.sink()

        val cancelled = runCatching {
            withTimeout(1_000) {
                sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext)
            }
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)

        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `empty base url returns coming soon without calling route`() = runTest {
        val deps = TestDeps(baseUrl = "")
        val sink = deps.sink()

        val response = sink.routeToCloud("summarize", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("Cloud processing is coming soon"), response)
        assertTrue(deps.routeCalls.isEmpty())
        assertTrue(deps.playback.calls.isEmpty())
    }

    @Test
    fun `buffers token texts and speaks once on done`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Token("She "),
                    CloudRouteEvent.Token("is arguing."),
                    CloudRouteEvent.Done(inputTokens = 10, outputTokens = 5),
                ),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("She is arguing."), response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
        assertEquals(
            listOf(CloudRouteAnalyticsCall("done", 10, 5)),
            deps.analytics.calls,
        )
    }

    @Test
    fun `play_quote records previous position on the reference timeline`() = runTest {
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.referenceTime(60_000)).thenReturn(45.0)
        whenever(fingerprint.playbackTimeMs(45_000.0)).thenReturn(45_000)
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            cloudPlaybackContextState = state,
            clientPositionMs = 60_000L,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play the quote", VoiceIntent.CloudTier.Premium, playbackContext)

        // preQuote is playback-timeline; the context state must carry the
        // reference-timeline conversion (45s reference == 60s playback here).
        assertEquals(45_000L, state.snapshot().previousReferencePositionMs)
        // stop_quote recovery still uses the playback-timeline position.
        assertTrue(deps.playback.calls.contains("seekTo:45"))
        assertTrue(deps.playback.calls.contains("resume"))
    }

    @Test
    fun `unmapped referenceTime clears previous instead of resending a stale value`() = runTest {
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.referenceTime(60_000)).thenReturn(null)
        whenever(fingerprint.playbackTimeMs(45_000.0)).thenReturn(45_000)
        val state = CloudPlaybackContextState(
            recentReferencePositions = emptyList(),
            previousReferencePositionMs = 300L,
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            cloudPlaybackContextState = state,
            clientPositionMs = 60_000L,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play the quote", VoiceIntent.CloudTier.Premium, playbackContext)

        // Turn 2+ regression guard: null must clear, not retain turn 1's value.
        assertEquals(null, state.snapshot().previousReferencePositionMs)
    }

    @Test
    fun `a no-answer error from the service is spoken rather than left silent`() = runTest {
        // The service ends with `error no_answer`; the existing code-to-template mapping carries it.
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Error(code = CloudRouteErrorCodes.NO_ANSWER, message = ""),
                CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0),
            ),
        )

        val response = deps.sink().routeToCloud("what?", VoiceIntent.CloudTier.Free, playbackContext)

        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `a turn that neither speaks nor acts says the request could not be completed`() = runTest {
        // No tokens and no action: an unexpected empty turn, not a search that found nothing.
        // The server's guard should make this unreachable; this covers old servers and regressions.
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0)),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("what?", VoiceIntent.CloudTier.Free, playbackContext)

        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `an empty turn on a non-English device falls back to the error earcon`() = runTest {
        // Templates are English-only by design: base-language prose is never spoken on a device
        // set to another language, so the earcon carries the same message.
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0)),
            locale = java.util.Locale.FRENCH,
        )

        val response = deps.sink().routeToCloud("quoi?", VoiceIntent.CloudTier.Free, playbackContext)

        // Deliberately changed: the earcon was the fallback when the client had nothing to say.
        // It now has its own words for a code it doesn't know, so the user hears which happened
        // rather than guessing from a tone.
        // Templates are English-only, so another locale keeps the earcon: base-language prose is
        // never spoken on a device set to another language.
        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), response)
    }

    @Test
    fun `action-only stream returns silent on done`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action("playback", "pause", emptyMap()),
                CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("pause", VoiceIntent.CloudTier.Free, playbackContext)

        assertEquals(VoiceResponse.Silent, response)
        assertTrue(deps.playback.calls.contains("pause"))
    }

    @Test
    fun `seek_to uses fingerprint mapping when available`() = runTest {
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(45_000)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(deps.playback.calls.contains("seekTo:45"))
    }

    @Test
    fun `an ordinary position_seconds seek uses sink time directly, with no mapping`() = runTest {
        // voice-intents: "Ordinary seek_to.position_seconds already uses sink seconds." It must reach
        // the sink as produced: no fingerprint lookup, no unit conversion, and the value equals the
        // request -- which is what distinguishes this form from the reference form.
        // The mapping returns a DIFFERENT value from the input, so a consulting implementation
        // cannot coincidentally agree. A mapping that returned the same number, or null (whose
        // fallback is the input), would let the two behaviours pass the same assertion.
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.playbackTimeMs(any())).thenReturn(-1)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("position_seconds" to 300),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("jump to five minutes", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(
            "an ordinary sink-time seek must reach the sink unchanged, without a mapping " +
                "(calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:300"),
        )
    }

    @Test
    fun `a negative position_seconds resolves as an offset back from the episode end`() = runTest {
        // voice-intents: "negative values are offsets back from the episode end." Clamping to zero
        // would turn "30 s from the end" into "the beginning" -- a different instruction.
        val deps = TestDeps(
            episodeDurationSeconds = 1800,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("position_seconds" to -30),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(
            "a negative position resolves against the episode end (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1770"),
        )
    }

    @Test
    fun `both reference and ordinary targets refuse rather than preferring one`() = runTest {
        // The two forms are mutually exclusive. Testing them in sequence would express precedence
        // where the contract requires exactly one, so a call carrying both would execute instead of
        // being refused -- and it would execute against whichever this code happened to try first.
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf(
                        "position_seconds" to 999,
                        "reference_position_ms" to 1_000L,
                    ),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        deps.sink().routeToCloud("seek", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(
            "a call carrying both forms must be refused (calls=${deps.playback.calls})",
            deps.playback.calls.none { it.startsWith("seekTo") },
        )
    }

    @Test
    fun `both forms present refuses even when only one value parses`() = runTest {
        // Presence is what claims a form, not parseability. Here `position_seconds` is present but
        // not a number, so a check on the PARSED values would read it as absent and execute the valid
        // reference form -- supplying two forms and executing one.
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf(
                        "position_seconds" to "not-a-number",
                        "reference_position_ms" to 1_000L,
                    ),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        deps.sink().routeToCloud("seek", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(
            "two present forms must refuse even when one does not parse (calls=${deps.playback.calls})",
            deps.playback.calls.none { it.startsWith("seekTo") },
        )
    }

    @Test
    fun `a single present form that does not parse refuses without mutating`() = runTest {
        // One form, unparseable: nothing to execute, and nothing may be tried in its place.
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("position_seconds" to "not-a-number"),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        deps.sink().routeToCloud("seek", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(
            "an unparseable single form must refuse (calls=${deps.playback.calls})",
            deps.playback.calls.none { it.startsWith("seekTo") },
        )
    }

    @Test
    fun `neither target refuses`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = emptyMap(),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        deps.sink().routeToCloud("seek nowhere", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(
            "a seek with no target must be refused (calls=${deps.playback.calls})",
            deps.playback.calls.none { it.startsWith("seekTo") },
        )
    }

    @Test
    fun `a reference seek with no usable mapping is refused, not approximated`() = runTest {
        // `timeline-alignment`: reference_position_ms is null when alignment is unavailable and the
        // SERVER falls back to client_position_ms. Treating the reference milliseconds as sink time
        // assumes the timelines coincide, which is what dynamic ad insertion breaks -- the seek would
        // land on the wrong audio and report success. Nothing authorizes the substitution, so the
        // action is refused.
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(
            "an unmapped reference target must not be seeked to (calls=${deps.playback.calls})",
            deps.playback.calls.none { it.startsWith("seekTo") },
        )
    }

    @Test
    fun `play_quote captures position seeks and resumes`() = runTest {
        val deps = TestDeps(
            clientPositionMs = 60_000L,
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "play_quote",
                        params = mapOf("reference_position_ms" to 900_000L),
                    ),
                    CloudRouteEvent.Done(1, 0),
                ),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play quote", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            // 900_000 ms on the REFERENCE timeline maps to 945 s on the sink timeline: the mapping's
            // 45 s offset is what distinguishes a reference seek from the reference ms used directly.
            listOf("pause", "seekTo:945", "resume"),
            deps.playback.calls,
        )
    }

    @Test
    fun `stop_quote restores pre-quote playback position`() = runTest {
        val deps = TestDeps(
            clientPositionMs = 60_000L,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 900_000L),
                ),
                CloudRouteEvent.Action("playback", "stop_quote", emptyMap()),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("quote", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(deps.playback.calls.contains("seekTo:60"))
    }

    @Test
    fun `unknown tool and action are ignored, so a blank done still reports the turn`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Action("effects", "set_speed", mapOf("speed" to 2.0)),
                    CloudRouteEvent.Action("playback", "next_episode", emptyMap()),
                    CloudRouteEvent.Done(1, 0),
                ),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Deliberately changed: an *ignored* action is not a delivery, so this turn neither spoke
        // nor did anything and must report that rather than stay silent.
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `a budget refusal speaks its own line rather than the generic one`() = runTest {
        // The shared fixture supplies both the code's line and the generic one, so this asserts
        // which the sink picks. The quota case is already covered by
        // `a server-supplied message is never spoken...` below, which uses the same fixture.
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Error(
                    code = "budget_exceeded",
                    message = "We've hit our daily budget.",
                ),
            ),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            VoiceResponse.Spoken("The assistant has reached its limit right now."),
            response,
        )
        assertNotEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `error clears token buffer restores auto pause and speaks message`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Token("partial"),
                    CloudRouteEvent.Error(code = "connection_lost", message = "Connection lost"),
                ),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Deliberately changed: "Connection lost" was the server's message; the client now
        // speaks its own line for the code.
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
        assertEquals(listOf(CloudRouteAnalyticsCall("error", null, null)), deps.analytics.calls)
    }

    @Test
    fun `error with empty message speaks the client generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Error(code = "invalid_request", message = ""),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Deliberately changed: an empty message is no longer a reason to fall silent; the
        // client now has its own words for a code it doesn't know.
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `error does not roll back seeks`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 500_000L),
                ),
                CloudRouteEvent.Error(code = "connection_lost", message = "lost"),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // The reference target maps through the offset: 500 s on the reference timeline is 545 s on
        // the sink's. Asserting the mapped value is what shows the mapping was applied at all.
        assertTrue("the action was executed (calls=${deps.playback.calls})", deps.playback.calls.contains("seekTo:545"))
        assertTrue(!deps.playback.calls.contains("seekTo:50"))
    }

    @Test
    fun `builds route context enriched from sink state`() = runTest {
        val state = CloudPlaybackContextState(
            recentReferencePositions = listOf(100L, 200L),
            previousReferencePositionMs = 300L,
        )
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            events = flowOf(CloudRouteEvent.Done(1, 0)),
        )
        val sink = deps.sink()

        sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            CloudRouteContext(
                episodeId = "episode-id",
                podcastId = "podcast-id",
                referencePositionMs = 1_230_000L,
                clientPositionMs = 50_000L,
                recentReferencePositions = listOf(100L, 200L),
                previousReferencePositionMs = 300L,
            ),
            deps.routeContexts.single(),
        )
    }

    @Test
    fun `each turn sends a distinct non-blank request id`() = runTest {
        val deps = TestDeps()
        val sink = deps.sink()

        sink.routeToCloud("one", VoiceIntent.CloudTier.Premium, playbackContext)
        sink.routeToCloud("two", VoiceIntent.CloudTier.Premium, playbackContext)

        val ids = deps.routeTurns.map { it.requestId }
        assertEquals(2, ids.size)
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `capabilities are advertised only when a renderer is present`() = runTest {
        val withoutRenderer = TestDeps()
        withoutRenderer.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)
        assertTrue(withoutRenderer.routeTurns.single().capabilities.isEmpty())

        val withRenderer = TestDeps(renderer = RecordingRenderer())
        withRenderer.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)
        assertEquals(
            listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1),
            withRenderer.routeTurns.single().capabilities,
        )
    }

    @Test
    fun `free text turns carry no route hint and typed entry supplies one`() = runTest {
        val deps = TestDeps()
        val sink = deps.sink()

        sink.routeToCloud("find beginner investing episodes", VoiceIntent.CloudTier.Premium, playbackContext)
        assertEquals(null, deps.routeTurns.single().routeHint)

        val hint = CloudRouteHint(operation = "search_episodes", arguments = mapOf("query" to "investing"))
        sink.routeToCloudWithHint(
            "find beginner investing episodes",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            hint,
        )
        assertEquals(hint, deps.routeTurns.last().routeHint)
    }

    @Test
    fun `newer turn cancels the superseded in-flight turn`() = runTest {
        val firstTurnStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val deps = TestDeps(
            routeInvoker = { turn ->
                if (turn.request == "first") {
                    flow {
                        firstTurnStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            firstCancelled.complete(Unit)
                            throw cancellation
                        }
                    }
                } else {
                    flowOf(CloudRouteEvent.Done(1, 1))
                }
            },
        )
        val sink = deps.sink()

        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext) }
        firstTurnStarted.await()
        sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext)
        firstCancelled.await()
        assertTrue(first.isCancelled)
    }

    @Test
    fun `result event renders items and never auto-plays`() = runTest {
        val renderer = RecordingRenderer()
        val deps = TestDeps(
            renderer = renderer,
            events = flowOf(
                CloudRouteEvent.Result(
                    CloudSearchResults(
                        kind = CloudSearchResults.KIND_EPISODE_RESULTS,
                        scope = CloudSearchResults.SCOPE_GLOBAL,
                        items = listOf(
                            CloudSearchEvidenceItem(
                                evidenceId = "e1",
                                episodeId = "ep-1",
                                podcastId = "pod-1",
                                title = "Investing 101",
                                playable = true,
                                seekable = true,
                            ),
                        ),
                    ),
                ),
                CloudRouteEvent.Done(0, 0),
            ),
        )

        deps.sink().routeToCloud("find investing", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(1, renderer.rendered.size)
        assertEquals("ep-1", renderer.rendered.single().items.single().episodeId)
        assertTrue(renderer.empties.isEmpty())
        assertTrue(deps.playback.calls.none { it.startsWith("seekTo") })
    }

    @Test
    fun `unknown result kind is ignored for forward compatibility`() = runTest {
        val renderer = RecordingRenderer()
        val deps = TestDeps(
            renderer = renderer,
            events = flowOf(
                CloudRouteEvent.Result(
                    CloudSearchResults(
                        kind = "future_kind_v9",
                        scope = CloudSearchResults.SCOPE_GLOBAL,
                        items = listOf(
                            CloudSearchEvidenceItem(evidenceId = "e1", episodeId = "ep-1", playable = true, seekable = true),
                        ),
                    ),
                ),
                CloudRouteEvent.Done(0, 0),
            ),
        )

        deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(renderer.rendered.isEmpty())
        assertTrue(renderer.empties.isEmpty())
        assertEquals(0, renderer.unavailableCount)
    }

    @Test
    fun `empty result renders the empty state not the unavailable state`() = runTest {
        val renderer = RecordingRenderer()
        val deps = TestDeps(
            renderer = renderer,
            events = flowOf(
                CloudRouteEvent.Result(
                    CloudSearchResults(
                        kind = CloudSearchResults.KIND_EPISODE_RESULTS,
                        scope = CloudSearchResults.SCOPE_LIBRARY,
                    ),
                ),
                CloudRouteEvent.Done(0, 0),
            ),
        )

        deps.sink().routeToCloud("nothing matches", VoiceIntent.CloudTier.Premium, playbackContext)

        assertTrue(renderer.rendered.isEmpty())
        assertEquals(listOf(CloudSearchResults.SCOPE_LIBRARY), renderer.empties)
        assertTrue(renderer.unavailableCount == 0)
    }

    @Test
    fun `completed turn records bounded conversation context for the next turn`() = runTest {
        val memory = CloudConversationMemory()
        val deps = TestDeps(conversationMemory = memory, events = flowOf(CloudRouteEvent.Token("an answer"), CloudRouteEvent.Done(1, 1)))
        val sink = deps.sink()

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)
        sink.routeToCloud("a follow-up", VoiceIntent.CloudTier.Premium, playbackContext)

        // The first turn omitted the field; the second carries the recorded exchange.
        assertEquals(null, deps.routeTurns.first().context.recentConversation)
        assertEquals(
            listOf(
                CloudRouteConversationEntry.ROLE_USER to "a question",
                CloudRouteConversationEntry.ROLE_ASSISTANT to "an answer",
            ),
            deps.routeTurns.last().context.recentConversation!!.map { it.role to it.text },
        )
        // Never exceeds the spec bound.
        assertTrue(deps.routeTurns.last().context.recentConversation!!.size <= CloudRouteLimits.MAX_CONVERSATION_ENTRIES)
    }

    @Test
    fun `interrupted mixed stream keeps executed actions and clears buffered text`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "seek_to",
                        params = mapOf("reference_position_ms" to 500_000L),
                    ),
                    CloudRouteEvent.Token("She was about to"),
                    CloudRouteEvent.Error(code = "provider_error", message = "The assistant hit an error."),
                ),
            ),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // The executed seek is not rolled back; the partial speech is dropped.
        assertTrue(
            "the executed seek is the MAPPED sink position (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:545"),
        )
        // Deliberately changed: the client speaks its own words for the code, never the
        // server's message (which here was the same sentence by coincidence).
        assertEquals(VoiceResponse.Spoken("The assistant couldn't finish that request."), response)
        // Auto-pause is restored exactly once.
        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun `unseekable result items never produce a timed jump`() = runTest {
        val renderer = RecordingRenderer()
        val deps = TestDeps(
            renderer = renderer,
            events = flowOf(
                CloudRouteEvent.Result(
                    CloudSearchResults(
                        kind = CloudSearchResults.KIND_EPISODE_RESULTS,
                        scope = CloudSearchResults.SCOPE_GLOBAL,
                        items = listOf(
                            // Identified but unaligned, and a discovery-only entry.
                            CloudSearchEvidenceItem(
                                evidenceId = "e1",
                                episodeId = "ep-1",
                                playable = true,
                                seekable = false,
                            ),
                            CloudSearchEvidenceItem(
                                evidenceId = "e2",
                                episodeId = null,
                                playable = false,
                                seekable = false,
                            ),
                        ),
                    ),
                ),
                CloudRouteEvent.Done(0, 0),
            ),
        )

        deps.sink().routeToCloud("find something", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(1, renderer.rendered.size)
        assertTrue(deps.playback.calls.none { it.startsWith("seekTo") })
        // Discovery-only entry keeps a null episode id for catalog resolution.
        assertEquals(null, renderer.rendered.single().items.last().episodeId)
    }

    @Test
    fun `retrieval_unavailable renders once and is not also spoken`() = runTest {
        val renderer = RecordingRenderer()
        val deps = TestDeps(
            renderer = renderer,
            events = flowOf(
                CloudRouteEvent.Error(
                    code = "retrieval_unavailable",
                    message = "I can't reach the podcast evidence right now.",
                ),
            ),
        )

        val response = deps.sink().routeToCloud("what did she mean?", VoiceIntent.CloudTier.Premium, playbackContext)

        // Deliberately changed: the rendered state has already told the user, so no sound or
        // sentence follows it. A spoken line on top said the same thing twice.
        assertEquals(VoiceResponse.Silent, response)
        assertTrue(deps.playback.calls.none { it.startsWith("seekTo") })
        assertTrue(renderer.rendered.isEmpty())
        // Unavailable evidence is a rendered state too, not only spoken.
        assertEquals(1, renderer.unavailableCount)
    }

    @Test
    fun `a token that expires between turns fails the next turn closed`() = runTest {
        var token: String? = "user_test"
        val deps = TestDeps(
            routeInvoker = { _ ->
                if (token == null) {
                    // Client-minted code, no prose: the sink localises it, and
                    // `unauthorized` has no template by design, so it earcons.
                    flowOf(CloudRouteEvent.Error("unauthorized", ""))
                } else {
                    flowOf(CloudRouteEvent.Token("answer"), CloudRouteEvent.Done(1, 1))
                }
            },
        )
        val sink = deps.sink()

        // Turn 1 succeeds while the token is valid.
        assertEquals(
            VoiceResponse.Spoken("answer"),
            sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext),
        )

        // The token expires mid-session: the next turn fails closed and no
        // playback action is attempted.
        token = null
        val second = sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext)

        // Deliberately changed: a rejected credential is the service being unavailable, which the
        // owner asked to hear as words rather than a tone.
        assertEquals(VoiceResponse.Spoken("Service is unavailable at the moment."), second)
        assertTrue(deps.playback.calls.none { it.startsWith("seekTo") })
    }

    @Test
    fun `cancellation during the initial pause still restores audio`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val deps = TestDeps(routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 1))))
        deps.playback.pauseGate = gate
        val sink = deps.sink()

        // The player is already paused when cancellation lands mid-call: the
        // restore must still run, or audio stays paused with no owner.
        val turn = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.pauseStarted.await()
        turn.cancel()
        gate.complete(Unit)
        turn.join()

        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun `a superseded turn's action cannot resume the player under the new turn`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "play_quote",
                        params = mapOf("reference_position_ms" to 45_000_000L),
                    ),
                    CloudRouteEvent.Done(1, 0),
                ),
            ),
        )
        deps.playback.resumeGate = gate
        val sink = deps.sink()

        // Turn A: auto-pause, then its play_quote action resumes — suspending
        // inside the player while A still owns it.
        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.resumeStarted.await()

        // B must not register/pause until A's action has finished with the player.
        val second = launch { sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext) }
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            listOf("pause", "resume", "pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
    }

    @Test
    fun `a successor failing after hand-off still restores the predecessor's pause`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(
            routeInvoker = { turn ->
                when (turn.request) {
                    // Predecessor: takes the hold via its delivery commitment, then stays in flight
                    // until superseded.
                    "first" -> flow {
                        emit(
                            CloudRouteEvent.Action(
                                tool = "playback",
                                action = "pause",
                                params = emptyMap(),
                                turnId = turn.requestId,
                            ),
                        )
                        awaitCancellation()
                    }

                    else -> error("successor failed during setup")
                }
            },
        )
        val sink = deps.sink()

        val first = launch { runCatching { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext) } }
        deps.playback.pauseStarted.await()
        val second = launch { runCatching { sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext) } }
        first.join()
        second.join()

        // The player was paused by the predecessor; ownership moved to the
        // successor, whose setup failed before its own pause. The obligation
        // travels with ownership, so the successor still restores it.
        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun `a setup failure with no predecessor leaves no stale ownership`() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        val deps = TestDeps(
            routeInvoker = { turn ->
                if (fail) error("route invoker failed during setup")
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "pause",
                        params = emptyMap(),
                        turnId = turn.requestId,
                    ),
                    CloudRouteEvent.Token("answer"),
                    CloudRouteEvent.Done(1, 1),
                )
            },
        )
        val sink = deps.sink()

        // Nothing was paused before the failure, so the only claim this test
        // makes is about ownership: a later turn registers cleanly.
        val first = launch { runCatching { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext) } }
        first.join()

        fail = false
        val second = sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("answer"), second)
        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun `a newer turn cannot pause while an older turn's restore is suspended`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(routeInvoker = withHold(flowOf(CloudRouteEvent.Token("a"), CloudRouteEvent.Done(1, 1))))
        val gate = CompletableDeferred<Unit>()
        deps.playback.resumeGate = gate
        val sink = deps.sink()

        // Turn A runs to completion, entering its restore — which suspends
        // inside resume() (play-queue load) while still owning the player.
        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.resumeStarted.await()

        // Turn B starts during that suspension: its registration and pause must
        // wait for A's restore to finish, or B's answer streams over resumed audio.
        val second = launch { sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext) }
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            listOf("pause", "resume", "pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
    }

    @Test
    fun `a superseded turn never restores audio owned by the newer turn`() = runTest(UnconfinedTestDispatcher()) {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val deps = TestDeps(
            routeInvoker = { turn ->
                if (turn.request == "first") {
                    flow {
                        emit(
                            CloudRouteEvent.Action(
                                tool = "playback",
                                action = "pause",
                                params = emptyMap(),
                                turnId = turn.requestId,
                            ),
                        )
                        firstStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            firstCancelled.complete(Unit)
                            throw cancellation
                        }
                    }
                } else {
                    flowOf(CloudRouteEvent.Done(1, 1))
                }
            },
        )
        val sink = deps.sink()

        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext) }
        firstStarted.await()
        sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext)
        firstCancelled.await()
        first.join()

        // Turn one paused; the successor inherited the already-paused player, so
        // it does not pause again (pausing a paused player is the defect that
        // started playback for the owner), and it restores once at the end. The
        // superseded turn must NOT resume — otherwise it resumes audio the newer
        // turn still needs quiet, and the answer streams over playing audio.
        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun `a translated template is not spoken under a locale that has no translation`() = runTest {
        // The English template is present as the default resource, and Android
        // falls back to it for every locale — so the guard, not the resource
        // lookup, is what stops English being spoken to a Korean user.
        val deps = TestDeps(
            locale = java.util.Locale.KOREAN,
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), response)
    }

    @Test
    fun `the no-gateway message is an earcon where no translation exists`() = runTest {
        val deps = TestDeps(baseUrl = "", locale = java.util.Locale.JAPANESE)

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), response)
    }

    @Test
    fun `a client code with a template is spoken in the localized wording`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Localized template, not the client's own prose and not the code.
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `a whitespace-only server message is treated as absent`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "   ")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Whitespace is not a message: it must resolve the localized template
        // rather than being spoken as silence.
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `a client code without a template falls back to the client generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "invalid_response", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Internal diagnostics are a sound, never English prose to a
        // non-English user.
        // Deliberately changed: this is the fallback the owner asked for — the client's own
        // words for a code it doesn't know, rather than a tone the user has to interpret.
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `a server-supplied message is never spoken, only a template or the generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "limit_exceeded", message = "You've used 10/10 free requests today.")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // The whole point: the server's message is not spoken at all — it can carry upstream
        // detail, as the captured turn did — even when it is a sentence written for the user.
        // The code's own local line is spoken instead.
        assertEquals(VoiceResponse.Spoken("You've reached today's free limit."), response)
    }

    @Test
    fun `a whitespace-only answer is not spoken as whitespace but reports the incomplete turn`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Token("   "),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        val sink = deps.sink()
        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Deliberately changed: the rule that blank is not *speech* still holds — "   " is never
        // sent to the TTS engine — but a turn that delivers no usable answer no longer leaves the
        // user in silence. It reports the turn as incomplete, which is a different concern from
        // speaking the whitespace itself.
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)

        // And it is not worth remembering either: what follows carries no
        // conversation context, which is only true if nothing was recorded.
        sink.routeToCloud("a follow-up", VoiceIntent.CloudTier.Premium, playbackContext)
        // The premise first: null is also the expected value for turn one, so
        // without this the next line stays green even if the turn never routed.
        assertEquals("a follow-up", deps.routeTurns.last().request)
        assertEquals(null, deps.routeTurns.last().context.recentConversation)
    }

    @Test
    fun `a whitespace-only template is not spoken`() = runTest {
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(mapOf("cloud_error_connection_lost" to "   ")),
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), response)
    }

    @Test
    fun `a throwing pause does not leave a stale obligation to resume`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Action(tool = "playback", action = "pause", params = emptyMap()),
                    CloudRouteEvent.Done(1, 0),
                ),
            ),
        )
        // Call 1 is the turn's own auto-pause; call 2 is the explicit action.
        deps.playback.throwOnPauseCall = 2
        val sink = deps.sink()

        val outcome = runCatching { sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext) }

        // Without this the assertions below would also hold if the action's
        // pause had silently succeeded — the calls list would look the same.
        assertEquals("pause failed", outcome.exceptionOrNull()?.message)
        // The explicit pause cleared the obligation before suspending, so even
        // though the call threw there is nothing to undo: the user asked for a
        // pause and the turn must not resume over it. (The first "pause" is the
        // turn's own auto-pause; the second is the action that threw.)
        assertEquals(listOf("pause", "pause"), deps.playback.calls)
    }

    @Test
    fun `a turn while the host is paused neither pauses nor resumes`() = runTest {
        // The owner's run: the player was not playing, the turn failed, and
        // playback started — because the turn claimed a pause it never made.
        val deps = TestDeps(hostPlaying = false, events = flowOf(CloudRouteEvent.Done(1, 0)))

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            "a paused host must be left exactly as it was",
            emptyList<String>(),
            deps.playback.calls,
        )
    }

    @Test
    fun `a turn that inherits a paused host does not resume it after privacy closed mid-turn`() = runTest {
        // The inherited-pause gap (#4236499389): the obligation latch is sampled only when the turn
        // takes its own pause, so a turn arriving to an ALREADY-paused host records nothing and its
        // privacy guard reads `atPause == null` -- always false. The user then presses play during
        // the answer, privacy closes mid-turn, and the turn's restore path must still not resume:
        // the user's play command plus a privacy closure both stand regardless of who paused first.
        val deps = TestDeps(hostPlaying = false, events = flowOf(CloudRouteEvent.Done(1, 0)))
        deps.privacyClosed = true

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            "a privacy closure during an inherited-pause turn must not end in playback",
            emptyList<String>(),
            deps.playback.calls,
        )
    }

    @Test
    fun `a turn-owned pause delivered after the closure still releases its own hold`() = runTest {
        // The caller-path outcome for the ownership fix: the turn takes its hold, backgrounding closes
        // the window, and the sink's own pause is the stop an observer would see. Because the sink
        // declared that stop as turn-owned, no playback-stop closure is recorded, so the completed
        // answer releases its own hold and resumes the playback that was previously running.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()

        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.pauseStarted.await()

        // Backgrounding closes the window; the turn's own pause is what stopped playback, and it was
        // declared turn-owned, so no playback-stop closure arises from it.
        deps.privacyClosed = true
        deps.playback.pauseGate?.complete(Unit)
        turn.join()

        assertEquals(
            "a turn-owned pause must not strand the hold it was taken for",
            listOf("pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
    }

    @Test
    fun `a closure without a playback stop releases the turn hold and resumes`() = runTest {
        // The hold policy (Voice Intents 657-659): the turn releases its OWN hold when the answer
        // completes, unless playback actually stopped or a user pause superseded it. A visibility
        // closure -- backgrounding -- ends grace without touching playback, so it must not strand
        // the pause: the answer finished, the hold is the turn's, and playback was running before.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()

        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.pauseStarted.await()

        // Backgrounding alone: the closure count rises, but the host's audio never stopped, so no
        // playback-stop closure is recorded. The completion must then release the hold.
        deps.privacyClosed = true
        deps.privacyClosures += 1
        deps.playback.pauseGate?.complete(Unit)
        turn.join()

        assertEquals(
            "a visibility closure without a playback stop must not strand the turn's pause",
            listOf("pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
    }

    @Test
    fun `a closure WITH an observed playback stop still withholds the resume`() = runTest {
        // The other half: when the host's audio genuinely stopped during the closure, the hold is
        // not released -- resuming would start audio the user's environment ended.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()

        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.pauseStarted.await()

        // The closure coincides with a playback stop: the observed-stop count rises too.
        deps.privacyClosed = true
        deps.privacyClosures += 1
        deps.playbackStopClosures += 1
        deps.playback.pauseGate?.complete(Unit)
        turn.join()

        assertEquals(
            "a closure with an observed playback stop must withhold the resume",
            listOf("pause"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
    }

    @Test
    fun `a turn while the host is playing pauses and restores it`() = runTest {
        // The server sends its delivery commitment for a long answer; that frame is what takes the
        // user's place, so the pause does not happen without it.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(listOf("pause", "resume"), deps.playback.calls)
        assertTrue("playback is where it started", deps.host.playing)
    }

    @Test
    fun `a user command during the pause suspension blocks the restore`() = runTest(UnconfinedTestDispatcher()) {
        // The interleaving a post-pause sample would swallow: the user acts while
        // the turn's own pause is still in flight, so the sample taken afterwards
        // would already include their command and the restore would pass.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()

        val route = launch { deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.pauseStarted.await()

        // The user presses pause while our pause is suspended.
        deps.commandRevision += 1
        deps.playback.pauseGate?.complete(Unit)
        route.join()

        assertEquals("the user's command stands", listOf("pause"), deps.playback.calls)
    }

    @Test
    fun `a user pause during an answer hold survives a FAILED termination`() = runTest {
        // The owning rule is "a user pause and an answer hold have separate ownership", and it must
        // hold on every way the turn ends, not only a clean one. A failed turn takes the same restore
        // path (`finally`), so a user who paused while the answer was in flight keeps their pause --
        // otherwise the failure would silently undo a deliberate user action.
        lateinit var deps: TestDeps
        deps = TestDeps(
            hostPlaying = true,
            routeInvoker = { turn ->
                kotlinx.coroutines.flow.flow {
                    // The delivery commitment takes the user's place first.
                    emit(
                        CloudRouteEvent.Action(
                            tool = "playback",
                            action = "pause",
                            params = emptyMap(),
                            turnId = turn.requestId,
                        ),
                    )
                    // The user presses pause while the answer is in flight, then the turn FAILS
                    // instead of completing. Both terminations share the restore path.
                    deps.commandRevision += 1
                    throw RuntimeException("provider error")
                }
            },
        )
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()

        // The turn's failure is the scenario, so it is caught here: what is asserted is the player's
        // final state, which is what the user's pause depends on.
        val turn = launch {
            runCatching { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        }
        deps.playback.pauseStarted.await()
        deps.playback.pauseGate?.complete(Unit)
        turn.join()

        assertEquals(
            "a user pause must survive a failed termination (calls=${deps.playback.calls})",
            listOf("pause"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
    }

    @Test
    fun `a cloud-requested pause during an answer hold supersedes it without a revision bump`() = runTest {
        // The assigned case: the pause is an APP OPERATION the cloud asked for during an answer hold,
        // not a manual playback command. It goes through the sink's own pause, which carries the voice
        // source and therefore does NOT advance the playback command revision -- so a restore that
        // only compares revisions would resume over the user's pause and undo it.
        lateinit var deps: TestDeps
        deps = TestDeps(
            hostPlaying = true,
            routeInvoker = { turn ->
                kotlinx.coroutines.flow.flow {
                    // The delivery commitment takes the answer's hold...
                    emit(
                        CloudRouteEvent.Action(
                            tool = "playback",
                            action = "pause",
                            params = emptyMap(),
                            turnId = turn.requestId,
                        ),
                    )
                    // ...and the cloud then requests a pause as an APP OPERATION (no turn id).
                    emit(
                        CloudRouteEvent.Action(
                            tool = "playback",
                            action = "pause",
                            params = emptyMap(),
                        ),
                    )
                    emit(CloudRouteEvent.Done(1, 0))
                }
            },
        )

        deps.sink().routeToCloud("pause it", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            "the app-operation pause must supersede the hold, not be resumed over " +
                "(calls=${deps.playback.calls})",
            listOf("pause", "pause"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
        // The fixture holds the command revision CONSTANT so ownership is the only axis under
        // test: supersession is proved by the refusal above, not by the revision. No assertion is
        // made on deps.commandRevision because only the test writes it -- asserting on it would
        // restate the fixture rather than a production fact, and an assertion that cannot fail is
        // worse than an absent one because it reads as coverage. (Measured at 2f28ce664.)
    }

    @Test
    fun `a turn does not override a user pause that changed nothing`() = runTest {
        // The case a state sample cannot see: the player is already paused (by
        // the turn), the user presses pause, and nothing about the state
        // changes. Only the command revision records that they acted.
        lateinit var deps: TestDeps
        deps = TestDeps(
            hostPlaying = true,
            routeInvoker = { turn ->
                kotlinx.coroutines.flow.flow {
                    // The delivery commitment takes the user's place; the user's pause then supersedes it.
                    emit(
                        CloudRouteEvent.Action(
                            tool = "playback",
                            action = "pause",
                            params = emptyMap(),
                            turnId = turn.requestId,
                        ),
                    )
                    deps.commandRevision += 1
                    emit(CloudRouteEvent.Done(1, 0))
                }
            },
        )

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals("the user's pause stands", listOf("pause"), deps.playback.calls)
    }

    @Test
    fun `a turn does not override playback the user resumed while it ran`() = runTest {
        lateinit var deps: TestDeps
        deps = TestDeps(
            hostPlaying = true,
            routeInvoker = { turn ->
                kotlinx.coroutines.flow.flow {
                    emit(
                        CloudRouteEvent.Action(
                            tool = "playback",
                            action = "pause",
                            params = emptyMap(),
                            turnId = turn.requestId,
                        ),
                    )
                    // The user presses play while the cloud turn is in flight:
                    // a command, and a state change.
                    deps.commandRevision += 1
                    deps.host.playing = true
                    emit(CloudRouteEvent.Done(1, 0))
                }
            },
        )

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        // The turn's own pause is not undone twice: resuming again would fight
        // the user's decision, which is the same class of defect as resuming a
        // player that was never playing.
        assertEquals(listOf("pause"), deps.playback.calls)
    }

    private class RecordingRenderer : CloudSearchResultsRenderer {
        val rendered = mutableListOf<CloudSearchResults>()
        val empties = mutableListOf<String>()
        var unavailableCount = 0

        override fun renderResults(results: CloudSearchResults) {
            rendered += results
        }

        override fun renderEmpty(scope: String) {
            empties += scope
        }

        override fun renderUnavailable() {
            unavailableCount += 1
        }
    }

    private companion object {
        /** The mapping's offset: reference[ms] + this = sink[ms], as ad insertion produces. */
        const val REFERENCE_TO_SINK_OFFSET_MS = 45_000
    }

    private data class CloudRouteAnalyticsCall(
        val outcome: String,
        val inputTokens: Int?,
        val outputTokens: Int?,
    )

    @Test
    fun `a stated zero delta stays put instead of applying the interval`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_relative",
                    params = mapOf("delta_seconds" to 0),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        deps.sink().routeToCloud("stay put", VoiceIntent.CloudTier.Premium, playbackContext)

        // A named zero is a real delta, not a missing one. Applying the interval here would move
        // a user who named no movement — the defaulted-seek failure this dispatch exists to avoid.
        // The local executor maps 0 -> Silent the same way.
        assertEquals(
            "a stated zero must not move the player",
            emptyList<String>(),
            deps.playback.calls.filter { it.startsWith("skip") },
        )
    }

    @Test
    fun `an unstated amount with an unknown direction is refused instead of defaulted`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_relative",
                    params = mapOf("direction" to "sideways"),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        val response = deps.sink().routeToCloud("go sideways", VoiceIntent.CloudTier.Premium, playbackContext)

        // The refusal must be OBSERVABLE, not merely an absence of movement: a refused action
        // means the turn delivered nothing, so the turn must not read as delivered (Silent).
        // Asserting only "no skip call" would pass on the buggy code too — the defect was that
        // the `when` value was discarded, so the caller saw `true` while nothing happened.
        assertEquals(
            "a refused action must not count as a delivered turn",
            VoiceResponse.Spoken("Sorry, I couldn't complete that request."),
            response,
        )
        assertEquals(
            emptyList<String>(),
            deps.playback.calls.filter { it.startsWith("skip") },
        )
    }

    @Test
    fun `an unstated amount applies the interval in the stated direction`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_relative",
                    params = mapOf("direction" to "backward"),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        deps.sink().routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext)

        // A null delta is the only case that uses the sink's own interval.
        assertEquals(
            listOf("skipBackward:null"),
            deps.playback.calls.filter { it.startsWith("skip") },
        )
    }

    /**
     * `events` with a hold frame addressed to THIS turn prepended.
     *
     * The owning policy pauses ONLY when the server sends its delivery commitment for a long answer, so
     * the turn-start block records metadata and does not touch playback. A test that expects the user's
     * place to be taken must therefore deliver this frame: without it the turn correctly pauses nothing,
     * which is what a short answer is entitled to.
     *
     * The id comes from the turn the sink BUILT (`routeInvoker` receives it) rather than being written
     * literally, so a case cannot pass by addressing an id the sink never issued.
     */
    private fun withHold(
        events: kotlinx.coroutines.flow.Flow<CloudRouteEvent>,
    ): (CloudRouteTurn) -> kotlinx.coroutines.flow.Flow<CloudRouteEvent> = { turn ->
        kotlinx.coroutines.flow.flow {
            emit(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "pause",
                    params = emptyMap(),
                    turnId = turn.requestId,
                ),
            )
            emitAll(events)
        }
    }

    private class TestDeps(
        private val baseUrl: String = "https://cloud.example.com",
        private val userId: String = "user_test",
        clientPositionMs: Long = 50_000L,
        // Mockito defaults Int? to 0; unmapped seeks must see null so we fall back to referenceMs.
        // A NONZERO-OFFSET mapping by default: reference seconds map to sink seconds plus an offset,
        // as dynamic ad insertion produces. An identity mapping cannot distinguish the two timelines
        // -- a seek computed from reference ms and one computed through the mapping would agree, so a
        // test could pass while the code used the wrong one. The offset makes the two differ.
        private val fingerprintTimingManager: FingerprintTimingManager = mock<FingerprintTimingManager>().also {
            whenever(it.playbackTimeMs(any())).thenAnswer { invocation ->
                val seconds = invocation.getArgument<Double>(0)
                (seconds * 1000).toInt() + REFERENCE_TO_SINK_OFFSET_MS
            }
        },
        val cloudPlaybackContextState: CloudPlaybackContextState = CloudPlaybackContextState(),
        private val events: kotlinx.coroutines.flow.Flow<CloudRouteEvent> = flowOf(CloudRouteEvent.Done(0, 0)),
        val renderer: CloudSearchResultsRenderer? = null,
        /** Whether the host app is playing while this turn runs. */
        var hostPlaying: Boolean = true,

        /** Play/pause/stop commands issued from outside the voice path. */
        var commandRevision: Long = 0,
        val conversationMemory: CloudConversationMemory = CloudConversationMemory(),
        private val locale: java.util.Locale = java.util.Locale.ENGLISH,
        private val audioPlayer: au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer? = null,
        /** Whether a privacy event has closed the window during this turn. */
        var privacyClosed: Boolean = false,

        /** Monotonic, so a closure during a turn is visible after a wake clears [privacyClosed]. */
        var privacyClosures: Long = 0,
        var playbackStopClosures: Long = 0,
        private val episodeDurationSeconds: Int = 0,
        // Keys built from the same constant the sink uses, so renaming the
        // wire code breaks these tests rather than silently detaching the
        // template from it.
        //
        // This map is a hand copy of strings_voice_templates.xml. When a code gains a line
        // there, it needs one here too, or the suite stays green while the shipped app speaks
        // something else — which is how the quota lines nearly landed untested.
        private val templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(
            mapOf(
                "general.cloud_coming_soon" to "Cloud processing is coming soon",
                "cloud_error_" + CloudRouteErrorCodes.CONNECTION_LOST to "Connection lost. Please try again.",
                "general.cloud_incomplete_turn" to "Sorry, I couldn't complete that request.",
                "cloud_error_" + CloudRouteErrorCodes.NO_ANSWER to "Sorry, I couldn't complete that request.",
                "cloud_error_generic" to "Sorry, I couldn't complete that request.",
                "cloud_error_" + CloudRouteErrorCodes.UNAUTHORIZED to "Service is unavailable at the moment.",
                "cloud_error_" + CloudRouteErrorCodes.PROVIDER_ERROR to "The assistant couldn't finish that request.",
                "cloud_error_limit_exceeded" to "You've reached today's free limit.",
                "cloud_error_budget_exceeded" to "The assistant has reached its limit right now.",
                "cloud_error_" + CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE to "I can't reach the podcast evidence right now.",
            ),
        ),
        routeInvoker: ((CloudRouteTurn) -> kotlinx.coroutines.flow.Flow<CloudRouteEvent>)? = null,
    ) {
        /** The host's playback state, moved by the fake sink the way a player would. */
        val host = HostPlaybackState(hostPlaying)
        val playback = FakePlaybackSink(host)
        val analytics = FakeCloudRouteAnalytics()
        val routeCalls = mutableListOf<String>()
        val routeContexts = mutableListOf<CloudRouteContext>()
        val routeTurns = mutableListOf<CloudRouteTurn>()
        private val explicitInvoker = routeInvoker

        private val playbackContextProvider = object : PlaybackContextProvider {
            override fun current(): PlaybackContext = PlaybackContext(clientPositionMs = clientPositionMs)
        }

        fun sink(): CloudRouteSink = CloudRouteSink(
            resolveBaseUrl = { baseUrl },
            tokenProvider = object : CloudTokenProviding {
                // Mirrors what the legacy identity path returned, so the suite keeps asserting the
                // same requests while the sink takes its credential from the injected provider.
                override suspend fun currentToken(): String? = userId.takeIf { it.isNotBlank() }
            },
            playbackSink = playback,
            fingerprintTimingManager = fingerprintTimingManager,
            playbackContextProvider = playbackContextProvider,
            cloudPlaybackContextState = cloudPlaybackContextState,
            analytics = analytics,
            routeInvoker = { turn: CloudRouteTurn ->
                routeCalls += turn.request
                routeContexts += turn.context
                routeTurns += turn
                explicitInvoker?.invoke(turn) ?: events
            },
            searchResultsRenderer = renderer,
            conversationMemory = conversationMemory,
            templateResolver = templateResolver,
            currentLocale = { locale },
            isHostPlaying = { host.playing },
            playbackCommandRevision = { commandRevision },
            isPrivacyClosed = { privacyClosed },
            privacyClosureCount = { playbackStopClosures },
            episodeDurationSeconds = { episodeDurationSeconds },
            audioPlayer = audioPlayer,
        )
    }

    /** Models the host player's state so pause/resume can be observed, and moved. */
    private class HostPlaybackState(var playing: Boolean)

    private class FakePlaybackSink(private val host: HostPlaybackState? = null) : VoicePlaybackSink {
        val calls = mutableListOf<String>()

        /** When set, resume() suspends until released (models play-queue loads). */
        var resumeGate: CompletableDeferred<Unit>? = null
        val resumeStarted = CompletableDeferred<Unit>()

        /** When set, pause() takes effect then suspends until released. */
        var pauseGate: CompletableDeferred<Unit>? = null
        val pauseStarted = CompletableDeferred<Unit>()

        /** Throw on this pause call number (1-based); null = never. */
        var throwOnPauseCall: Int? = null
        private var pauseCalls = 0

        override suspend fun duck(): VoiceResponse {
            calls += "duck"
            return VoiceResponse.Silent
        }

        override suspend fun restore(): VoiceResponse {
            calls += "restore"
            return VoiceResponse.Silent
        }

        override suspend fun pauseAsHold(): VoiceResponse = pause()
        override suspend fun pause(): VoiceResponse {
            calls += "pause"
            host?.playing = false
            pauseCalls += 1
            if (throwOnPauseCall == pauseCalls) error("pause failed")
            pauseStarted.complete(Unit)
            pauseGate?.await()
            return VoiceResponse.Silent
        }

        override suspend fun resume(): VoiceResponse {
            resumeStarted.complete(Unit)
            resumeGate?.await()
            calls += "resume"
            host?.playing = true
            return VoiceResponse.Silent
        }

        override suspend fun skipForward(seconds: Int?): VoiceResponse {
            calls += "skipForward:$seconds"
            return VoiceResponse.Silent
        }
        override suspend fun skipBackward(seconds: Int?): VoiceResponse {
            calls += "skipBackward:$seconds"
            return VoiceResponse.Silent
        }

        override suspend fun seekTo(positionSeconds: Int, episodeDurationSeconds: Int): VoiceResponse {
            // Resolve the schema's signed semantics the same way production does, so a fake cannot
            // make a negative position pass by clamping it where the real sink resolves an offset.
            val resolved = if (positionSeconds < 0) {
                (episodeDurationSeconds + positionSeconds).coerceIn(0, episodeDurationSeconds)
            } else {
                positionSeconds
            }
            calls += "seekTo:$resolved"
            return VoiceResponse.Silent
        }

        override fun nextEpisode(): VoiceResponse = VoiceResponse.Silent
    }

    private class FakeCloudRouteAnalytics : CloudRouteAnalytics {
        val calls = mutableListOf<CloudRouteAnalyticsCall>()

        override fun recordTurn(outcome: String, inputTokens: Int?, outputTokens: Int?) {
            calls += CloudRouteAnalyticsCall(outcome, inputTokens, outputTokens)
        }
    }

    @Test
    fun `a refused answer traces through the sink to error feedback with the server outcome preserved`() = runTest {
        // The whole acceptance: the injected writer refuses, the real player reports it, the sink
        // turns it into user-visible feedback, AND the server's terminal outcome is still handled.
        // A player callback alone would be narrower than this.
        val player = au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16_000,
        ).apply {
            var accepted = 0
            writeToSink = { _, _, _, count -> if (accepted++ < 1) count else -1 }
        }
        val deps = TestDeps(
            routeInvoker = withHold(
                flow {
                    emit(CloudRouteEvent.Connected("pcm_s16le"))
                    emit(CloudRouteEvent.AudioFrame("pcm_s16le", ByteArray(4096) { 1 }))
                    emit(CloudRouteEvent.AudioFrame("pcm_s16le", ByteArray(4096) { 1 }))
                    emit(CloudRouteEvent.Done(0, 0))
                },
            ),
            audioPlayer = player,
        )
        try {
            val response = deps.sink().routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext)
            assertEquals(
                "a refused answer must reach the user as an error, not a delivered reply",
                VoiceResponse.Earcon(EarconId.ERROR),
                response,
            )
            // The server outcome still arrived and was processed: the turn is recorded as done even
            // though local playback failed, because the two are different facts.
            assertTrue("the server terminal outcome must still be handled", deps.routeCalls.isNotEmpty())
            // Output cleanup: the host player was ducked while the answer played, so it must be
            // restored even though the answer was cut short — otherwise the user is left with a
            // ducked episode and no answer.
            assertTrue(
                "output cleanup must restore the host player (calls=${deps.playback.calls})",
                deps.playback.calls.contains("restore"),
            )
            assertTrue(
                "the host must not be left ducked or paused (calls=${deps.playback.calls})",
                deps.playback.calls.last() == "resume",
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `a privacy closure blocks the restore, which the command revision cannot see`() = runTest(UnconfinedTestDispatcher()) {
        // A privacy event (route loss, backgrounding) closes the voice window and does NOT pass
        // through the playback layer, so it cannot advance the command revision. The turn must still
        // decline to resume: restoring would restart playback the user's privacy action ended.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()

        val route = launch { deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.pauseStarted.await()

        // Privacy closes WITH an observed playback stop while the turn's own pause is in flight; the
        // revision is untouched. A closure with a playback-stop fact is what blocks the restore.
        deps.privacyClosed = true
        deps.playbackStopClosures += 1
        deps.playback.pauseGate?.complete(Unit)
        route.join()

        assertEquals(
            "a privacy closure must not be resumed over (calls=${deps.playback.calls})",
            listOf("pause"),
            deps.playback.calls,
        )
    }

    @Test
    fun `a later turn is not blocked by an old closure flag once privacy reopens`() = runTest(UnconfinedTestDispatcher()) {
        // The guard is scoped to whether the window is closed NOW, not to whether it ever was: a
        // stale flag must not become a blanket ban on an unrelated later turn's cleanup.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        // The closure was earlier and privacy has since reopened (a wake clears it).
        deps.privacyClosed = false

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        // The property is that an open window lets the turn restore its own pause: the host ends
        // playing again. A stale flag would leave it paused.
        assertTrue(
            "a turn with an open window must resume what it paused (calls=${deps.playback.calls})",
            deps.playback.calls.contains("resume") && deps.host.playing,
        )
    }

    // (Removed) `the guard does not block capture when no turn paused anything`. The case could not
    // fail: `hostPlaying = false` means the turn never takes its pause, so `playerAutoPaused` stays false
    // and `restoreTransientAudioState` returns at its first line -- before the guard is consulted at all.
    // It therefore held with the guard, without it, and even if the guard aborted the turn. The
    // direction it claimed (a privacy closure is not a blanket ban) is observable only where a
    // turn-owned pause actually exists, which `a turn does not override a user pause that changed
    // nothing` covers.

    @Test
    fun `a privacy closure invalidates the turn before any successor registers`() = runTest(UnconfinedTestDispatcher()) {
        // The interval @spec named: A owns the turn and has paused the host; privacy closes A; a new
        // wake clears the closure flag; A's delayed cleanup then lands BEFORE any successor cloud turn
        // registers. `activeTurnId` is still A's, so ownership alone would let A through, and the live
        // flag has been cleared. Only a latch on A itself can hold. The closure carried an observed
        // playback stop, which is the fact that survives the wake.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()

        val turnA = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        deps.playback.pauseStarted.await()

        // Privacy ends A, then a wake reopens the window before A's cleanup arrives. No successor
        // registers in between — this is the gap the newer-cloud-turn test cannot see.
        deps.privacyClosed = true
        deps.privacyClosures += 1
        deps.playbackStopClosures += 1
        deps.privacyClosed = false

        deps.playback.pauseGate?.complete(Unit)
        turnA.join()

        assertEquals(
            "a privacy-invalidated turn must not resume, even before a successor registers (calls=${deps.playback.calls})",
            listOf("pause"),
            deps.playback.calls.filter { it == "pause" || it == "resume" || it == "restore" },
        )
    }

    /**
     * The delivery-commitment frame CONFIRMS this turn's reservation -- @cloud's arrangement.
     *
     * WHAT DISTINGUISHES CONFIRMED FROM UNCONFIRMED, and my first two attempts missed it: the playback
     * calls are the SAME either way, because the player is already paused from the RESERVATION, and a
     * second turn in the chain inherits the paused player rather than taking a second hold. So the
     * difference is not in the calls at all -- it is in WHEN the claim is released.
     *
     * The observable form is the claim's lifetime through the production state: after the frame, the
     * reservation is spent, so the turn's own end has nothing left to release; without a frame it is
     * held until the end. Both leave the host playing, which is why the assertion has to be about the
     * claim rather than the outcome.
     *
     * The turn id is READ FROM THE TURN THE SINK BUILT (`routeInvoker` receives it), never written into
     * the fixture: a hand-written id could pass by matching something the sink never issued.
     */
    @Test
    fun `the delivery-commitment frame confirms this turn's reservation`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(
            hostPlaying = true,
            routeInvoker = { turn ->
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "pause",
                        params = emptyMap(),
                        turnId = turn.requestId,
                    ),
                    CloudRouteEvent.Done(1, 0),
                )
            },
        )
        val sink = deps.sink()

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        // One hold, taken and released, and the host is where it started. The frame confirmed the
        // reservation, so the hold that remains is the ACTUAL one.
        assertEquals(
            "a confirmed reservation leaves exactly one hold, released by the turn ending: " +
                "calls=${deps.playback.calls}",
            listOf("pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
        assertTrue("the host is left as it was found", deps.host.playing)
    }

    /**
     * The CONTROL: with no frame, the reservation is released unsent.
     *
     * Without this, "the hold stands" is satisfiable by never having had the choice -- so this is the
     * half that makes the pair discriminating, and it drives the same fixture with the frame omitted.
     */
    @Test
    fun `without the frame the reservation is released unsent`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        val sink = deps.sink()

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        // The reservation was taken and released: the pause happened and was undone, and no
        // confirmation ever arrived to make it stand.
        assertEquals(
            "the unconfirmed reservation must still be released",
            listOf("pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
        assertTrue("the host is left as it was found", deps.host.playing)
    }

    /**
     * A frame for a DIFFERENT turn must not confirm this one -- the attribution rule.
     */
    @Test
    fun `a frame for another turn does not confirm this one`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(
            hostPlaying = true,
            routeInvoker = { turn ->
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "pause",
                        params = emptyMap(),
                        // Deliberately NOT this turn's id.
                        turnId = "${turn.requestId}-someone-else",
                    ),
                    CloudRouteEvent.Done(1, 0),
                )
            },
        )
        val sink = deps.sink()

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)

        // A foreign frame neither takes a hold nor confirms one; the turn's own reservation is still
        // released by the end of the turn.
        // A foreign frame takes no hold AND the turn takes none of its own (it received no frame
        // addressed to it), so playback is untouched -- the host is never paused and never resumed.
        // The earlier expectation was two calls because the turn paused unconditionally at start.
        assertEquals(
            "a foreign frame must not act on this turn, and an unconfirmed turn must not pause",
            emptyList<String>(),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
        assertTrue("the host is left as it was found", deps.host.playing)
    }

    /**
     * The CONFIRMED half, built so the ONLY difference from the unconfirmed half is the confirmation.
     *
     * Both variants deliver the SAME user pause; `withConfirmation` decides whether the server's
     * delivery-commitment frame arrives first. Anything else that differed would be the asserted
     * property's confound rather than its cause.
     */
    private suspend fun runUserPauseDuringHold(
        scope: kotlinx.coroutines.CoroutineScope,
        withConfirmation: Boolean,
        fails: Boolean,
    ): List<String> {
        lateinit var deps: TestDeps
        deps = TestDeps(
            hostPlaying = true,
            routeInvoker = { turn ->
                kotlinx.coroutines.flow.flow {
                    // The user's action and the server's frame, in the order this variant means. Both
                    // orders are meaningful: the frame first ACTIVATES the hold and the user then
                    // supersedes it, or the user acts on a reservation the frame never activates.
                    if (withConfirmation) {
                        emit(
                            CloudRouteEvent.Action(
                                tool = "playback",
                                action = "pause",
                                params = emptyMap(),
                                turnId = turn.requestId,
                            ),
                        )
                    }
                    // The user's pause, recorded as the command it is. It is recorded as a revision
                    // bump rather than delivered as an action so the case isolates whether a user
                    // action SUPERSEDED the hold from whether one EXECUTED.
                    deps.commandRevision += 1
                    // The spec asks for BOTH terminal paths, so both are driven here.
                    if (fails) {
                        throw RuntimeException("provider error")
                    } else {
                        emit(CloudRouteEvent.Done(1, 0))
                    }
                }
            },
        )
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()
        val turn = scope.launch {
            runCatching { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext) }
        }
        // The pause only happens when the frame activates the hold, so the UNCONFIRMED variant never
        // reaches it. Waiting unconditionally would hang rather than report that -- which is exactly
        // how this case failed once the pause moved off turn start.
        if (withConfirmation) {
            deps.playback.pauseStarted.await()
        }
        deps.playback.pauseGate?.complete(Unit)
        turn.join()
        return deps.playback.calls.filter { it == "pause" || it == "resume" }
    }

    @Test
    fun `a user pause is handled the same with and without the confirmation`() = runTest {
        // The pair is asserted because the confirmation has NO observable consequence, here measured
        // across every ordering and both terminal paths. It is NOT asserted against a divergence: an
        // earlier probe of @reviewer's reported `confirmed = [pause, resume]` vs `unconfirmed =
        // [pause]`, and they traced it to a deadlock in the probe that reproduced identically on
        // re-run -- a deadlock is perfectly stable, which is why three identical runs looked like a
        // measurement. The attribution to the confirmation was wrong and is not cited here.
        //
        // What the pair does: it pins the behaviour that HOLDS, so it fails the day a confirmation
        // starts changing the outcome, whether or not that is provable today.
        val confirmed = runUserPauseDuringHold(this, withConfirmation = true, fails = true)
        val unconfirmed = runUserPauseDuringHold(this, withConfirmation = false, fails = true)
        val confirmedOk = runUserPauseDuringHold(this, withConfirmation = true, fails = false)
        val unconfirmedOk = runUserPauseDuringHold(this, withConfirmation = false, fails = false)

        // The pair now SEPARATES, and the difference is the policy: only the frame activates the
        // hold, so the confirmed turn takes the user's place and the unconfirmed one never touches
        // playback. An earlier version of this case asserted they were identical, which was true while
        // the pause happened unconditionally at turn start -- the behaviour the owning policy forbids.
        assertEquals(
            "the confirmed hold is taken and then superseded, and never resumed over " +
                "(confirmed=$confirmed)",
            listOf("pause"),
            confirmed,
        )
        assertEquals(
            "the unconfirmed reservation never pauses -- a short answer keeps its ducking " +
                "(unconfirmed=$unconfirmed)",
            emptyList<String>(),
            unconfirmed,
        )
        // The spec's OTHER terminal path, and the same policy applies: the frame is what activates the
        // hold, so the confirmed turn takes the user's place and the unconfirmed one does not.
        assertEquals(
            "on a SUCCESSFUL termination the confirmed hold is likewise taken and superseded " +
                "(confirmed=$confirmedOk)",
            listOf("pause"),
            confirmedOk,
        )
        assertEquals(
            "and the unconfirmed reservation again leaves playback untouched (unconfirmed=$unconfirmedOk)",
            emptyList<String>(),
            unconfirmedOk,
        )
    }
}
