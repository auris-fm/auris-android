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
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@kotlinx.coroutines.ExperimentalCoroutinesApi
class CloudRouteSinkTest {

    private val playbackContext = PlaybackContext(
        episodeId = "episode-id",
        podcastId = "podcast-id",
        referencePositionMs = 1_230_000L,
        clientPositionMs = 50_000L,
    )

    @Test
    fun `upstream throw during collect still restores auto pause`() = runTest {
        val deps = TestDeps(
            events = flow {
                emit(CloudRouteEvent.Token("partial "))
                throw IllegalStateException("upstream blew up")
            },
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
            events = flow {
                emit(CloudRouteEvent.Token("partial "))
                awaitCancellation()
            },
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
            events = flowOf(
                CloudRouteEvent.Token("She "),
                CloudRouteEvent.Token("is arguing."),
                CloudRouteEvent.Done(inputTokens = 10, outputTokens = 5),
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
        assertTrue(deps.playback.calls.contains("seekTo:45000"))
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

        assertTrue(deps.playback.calls.contains("seekTo:45000"))
    }

    @Test
    fun `seek_to falls back to reference ms as playback when unmapped`() = runTest {
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

        assertTrue(deps.playback.calls.contains("seekTo:1130000"))
    }

    @Test
    fun `play_quote captures position seeks and resumes`() = runTest {
        val deps = TestDeps(
            clientPositionMs = 60_000L,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 900_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play quote", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(
            listOf("pause", "seekTo:900000", "resume"),
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

        assertTrue(deps.playback.calls.contains("seekTo:60000"))
    }

    @Test
    fun `unknown tool and action are ignored`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action("effects", "set_speed", mapOf("speed" to 2.0)),
                CloudRouteEvent.Action("playback", "next_episode", emptyMap()),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `error clears token buffer restores auto pause and speaks message`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Token("partial"),
                CloudRouteEvent.Error(code = "connection_lost", message = "Connection lost"),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("Connection lost"), response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
        assertEquals(listOf(CloudRouteAnalyticsCall("error", null, null)), deps.analytics.calls)
    }

    @Test
    fun `error with empty message returns earcon`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Error(code = "invalid_request", message = ""),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), response)
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

        assertTrue(deps.playback.calls.contains("seekTo:500000"))
        assertTrue(!deps.playback.calls.contains("seekTo:50000"))
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
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 500_000L),
                ),
                CloudRouteEvent.Token("She was about to"),
                CloudRouteEvent.Error(code = "provider_error", message = "The assistant hit an error."),
            ),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // The executed seek is not rolled back; the partial speech is dropped.
        assertTrue(deps.playback.calls.contains("seekTo:500000"))
        assertEquals(VoiceResponse.Spoken("The assistant hit an error."), response)
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
    fun `retrieval_unavailable renders as speech without actions`() = runTest {
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

        assertEquals(VoiceResponse.Spoken("I can't reach the podcast evidence right now."), response)
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

        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), second)
        assertTrue(deps.playback.calls.none { it.startsWith("seekTo") })
    }

    @Test
    fun `cancellation during the initial pause still restores audio`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val deps = TestDeps(events = flowOf(CloudRouteEvent.Done(1, 1)))
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
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
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
                    // Predecessor: pauses, then stays in flight until superseded.
                    "first" -> flow { awaitCancellation() }

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
            routeInvoker = { _ ->
                if (fail) error("route invoker failed during setup")
                flowOf(CloudRouteEvent.Token("answer"), CloudRouteEvent.Done(1, 1))
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
        val deps = TestDeps(events = flowOf(CloudRouteEvent.Token("a"), CloudRouteEvent.Done(1, 1)))
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

        // Turn one paused; the successor paused and resumed once. The superseded
        // turn must NOT resume — otherwise it resumes audio the newer turn
        // paused, and the answer streams over playing audio.
        assertEquals(listOf("pause", "pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
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
    fun `a client code without a template falls back to the error earcon`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "invalid_response", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Internal diagnostics are a sound, never English prose to a
        // non-English user.
        assertEquals(VoiceResponse.Earcon(EarconId.ERROR), response)
    }

    @Test
    fun `a server-supplied message still passes through untouched`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "limit_exceeded", message = "You've used 10/10 free requests today.")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        assertEquals(VoiceResponse.Spoken("You've used 10/10 free requests today."), response)
    }

    @Test
    fun `a whitespace-only answer is silent rather than spoken`() = runTest {
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Token("   "),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        val sink = deps.sink()
        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext)

        // Same rule as the error path: blank is not speech. Speaking "   " would
        // send a whitespace utterance to the TTS engine instead of a no-op.
        assertEquals(VoiceResponse.Silent, response)

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
            events = flowOf(
                CloudRouteEvent.Action(tool = "playback", action = "pause", params = emptyMap()),
                CloudRouteEvent.Done(1, 0),
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

    private data class CloudRouteAnalyticsCall(
        val outcome: String,
        val inputTokens: Int?,
        val outputTokens: Int?,
    )

    private class TestDeps(
        private val baseUrl: String = "https://cloud.example.com",
        private val userId: String = "user_test",
        clientPositionMs: Long = 50_000L,
        // Mockito defaults Int? to 0; unmapped seeks must see null so we fall back to referenceMs.
        private val fingerprintTimingManager: FingerprintTimingManager = mock<FingerprintTimingManager>().also {
            whenever(it.playbackTimeMs(any())).thenReturn(null)
        },
        private val cloudPlaybackContextState: CloudPlaybackContextState = CloudPlaybackContextState(),
        private val events: kotlinx.coroutines.flow.Flow<CloudRouteEvent> = flowOf(CloudRouteEvent.Done(0, 0)),
        val renderer: CloudSearchResultsRenderer? = null,
        val conversationMemory: CloudConversationMemory = CloudConversationMemory(),
        private val locale: java.util.Locale = java.util.Locale.ENGLISH,
        // Keys built from the same constant the sink uses, so renaming the
        // wire code breaks these tests rather than silently detaching the
        // template from it.
        private val templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(
            mapOf(
                "general.cloud_coming_soon" to "Cloud processing is coming soon",
                "cloud_error_" + CloudRouteErrorCodes.CONNECTION_LOST to "Connection lost. Please try again.",
            ),
        ),
        routeInvoker: ((CloudRouteTurn) -> kotlinx.coroutines.flow.Flow<CloudRouteEvent>)? = null,
    ) {
        val playback = FakePlaybackSink()
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
            resolveUserId = { userId },
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
        )
    }

    private class FakePlaybackSink : VoicePlaybackSink {
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

        override suspend fun pause(): VoiceResponse {
            calls += "pause"
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
            return VoiceResponse.Silent
        }

        override suspend fun skipForward(seconds: Int): VoiceResponse = VoiceResponse.Silent
        override suspend fun skipBackward(seconds: Int): VoiceResponse = VoiceResponse.Silent

        override suspend fun seekTo(positionMs: Int): VoiceResponse {
            calls += "seekTo:$positionMs"
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
}
