package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode
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
import org.junit.Assert.assertNotNull
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
    fun `an ordinary request is retryable while its window lives and retired once it is superseded`() = runTest {
        // @spec's ordinary-request lifecycle: a pending dialog does not own an ordinary ASR request, so
        // the WINDOW is what ends its retryability -- retain while it lives, retire when it is
        // superseded, and refuse an explicit retry once retired.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "an ordinary question",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = true,
            logicalRequestId = "ordinary-1",
        )!!
        assertNotNull(
            "the turn is retained while its window lives",
            deps.conversationAdmission.retained("ordinary-1"),
        )

        // A SUCCESSOR WINDOW: the ordinary request's window is superseded.
        val retired = deps.conversationAdmission.beginOrigin(2)

        assertTrue(
            "advancing the window retires the superseded request's turn (retired=$retired)",
            retired.contains("ordinary-1"),
        )
        assertNull(
            "and it is no longer retained",
            deps.conversationAdmission.retained("ordinary-1"),
        )

        // An explicit RETRY of the retired identity must not resurrect it.
        val retry = sink.prepareTurn(
            request = "an ordinary question",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = false,
            logicalRequestId = "ordinary-1",
        )
        assertTrue(
            "a retry under a retired identity does not reuse the retired turn " +
                "(reused=${retry?.requestId == prepared.requestId})",
            retry?.requestId != prepared.requestId,
        )
    }

    @Test
    fun `a window ending by TIMEOUT or PRIVACY retires what it was holding, not only replacement`() = runTest {
        // @spec's requirement: the owning window's replacement is ONE way a window ends. A privacy close
        // and the timeout are the others, and a request whose window ended that way must stop being
        // retryable too -- otherwise it kept its prepared turn for the life of the process, reached by
        // the most ordinary ending there is.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(5)

        sink.prepareTurn(
            request = "a question in window five",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            capturedOrigin = 5,
            isWakeOriginated = true,
            logicalRequestId = "in-window-5",
        )
        assertNotNull(
            "the turn is retained while its window lives",
            deps.conversationAdmission.retained("in-window-5"),
        )

        // THE WINDOW ENDS without being replaced by a newer one.
        val retired = deps.conversationAdmission.endWindow(5)

        assertTrue(
            "ending the window retires what it held (retired=$retired)",
            retired.contains("in-window-5"),
        )
        assertNull(
            "and it is no longer retained",
            deps.conversationAdmission.retained("in-window-5"),
        )
    }

    @Test
    fun `a retry of an earlier logical request reuses its turn on the REAL sink path`() = runTest {
        // @reviewer's bounded case, on the production sink rather than a fake: the fake returns numbered
        // IDs and always a turn, so a case driving it cannot distinguish a real retry from a fresh
        // preparation. Here the turns come from the real admission and their identities are the ones
        // the sink actually resolved.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        suspend fun prepare(request: String, logical: String) = sink.prepareTurn(
            request = request,
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = true,
            // The LOGICAL identity is what a retry is recognised by -- without it every prepare is a
            // new user act, which is the behaviour the same-text cases exist to pin separately.
            logicalRequestId = logical,
        )

        val first = prepare("first", "logical-A")!!
        sink.dispatchPrepared(first)
        val second = prepare("second", "logical-B")!!
        sink.dispatchPrepared(second)
        val retryOfFirst = prepare("retry first", "logical-A")!!

        assertEquals(
            "the retry reuses the FIRST turn's request ID (first=${first.requestId} " +
                "retry=${retryOfFirst.requestId})",
            first.requestId,
            retryOfFirst.requestId,
        )
        assertEquals(
            "and its conversation handle",
            first.conversationId,
            retryOfFirst.conversationId,
        )
        assertTrue(
            "while the second logical request kept its own",
            second.requestId != retryOfFirst.requestId &&
                second.conversationId != retryOfFirst.conversationId,
        )
    }

    @Test
    fun `a retry whose window was superseded is refused, not dispatched`() = runTest {
        // @reviewer's invalidation case: a prepared turn retried AFTER its window was superseded still
        // reached transport. A retry must re-validate its own captured origin, so a superseded turn is
        // refused exactly as a first dispatch of it would have been.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "prepared under window 1",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = true,
        )!!
        // A successor window opens BEFORE the retry.
        deps.conversationAdmission.beginOrigin(2)

        sink.dispatchPrepared(prepared)

        assertEquals(
            "a retry under a superseded window must not reach transport " +
                "(turns=${deps.routeTurns.map { it.request }})",
            0,
            deps.routeTurns.size,
        )
    }

    @Test
    fun `a retry under a still-live window is dispatched with its identity intact`() = runTest {
        // The positive control: the same retry with NO supersession must go through, and carry the
        // identity it was prepared with -- otherwise the refusal above would be indistinguishable from
        // a retry path that never dispatches at all.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "prepared under window 1",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = true,
        )!!
        sink.dispatchPrepared(prepared)

        assertEquals(1, deps.routeTurns.size)
        assertEquals("the same request ID", prepared.requestId, deps.routeTurns.single().requestId)
        assertEquals(
            "and the same conversation handle",
            prepared.conversationId,
            deps.routeTurns.single().conversationId,
        )
    }

    @Test
    fun `every dispatched turn carries the conversation identity the Worker requires`() = runTest {
        // The staged Worker makes identity MANDATORY: a turn missing both fields is `invalid_request`.
        // Every path that dispatches must therefore carry the pair -- a turn without it is refused by
        // the server, so the client must not be able to produce one at all.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        // Both entry points, including one with no captured window at all.
        sink.routeToCloud("with a captured window", VoiceIntent.CloudTier.Premium, playbackContext, capturedOrigin = 1)
        sink.routeToCloud("and with none", VoiceIntent.CloudTier.Premium, playbackContext)
        sink.routeToCloudWithHint(
            request = "and via the typed-flow entry point",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            hint = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint("playback"),
        )

        assertEquals("all three dispatched", 3, deps.routeTurns.size)
        deps.routeTurns.forEach { turn ->
            assertTrue(
                "a turn must carry a conversation handle or the Worker rejects it " +
                    "(turn=${turn.request})",
                !turn.conversationId.isNullOrBlank(),
            )
            assertNotNull(
                "and its mode (turn=${turn.request})",
                turn.conversationMode,
            )
        }
    }

    @Test
    fun `retrying a prepared turn reuses its identity without re-preparing`() = runTest {
        // @spec's controller retry contract: the prepared turn is IMMUTABLE. Retrying it must reuse its
        // request ID, handle and mode -- re-preparing would mint a SECOND conversation and consume the
        // wake selection again, so a retried request would look like a new user act.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "a question worth retrying",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = true,
        )
        assertNotNull("the turn is prepared", prepared)

        // Dispatch it twice, as a controller retry would. A retry goes through the SAME lifecycle as a
        // first dispatch -- it is not a shortcut to the transport.
        sink.dispatchPrepared(prepared!!)
        sink.dispatchPrepared(prepared)

        val turns = deps.routeTurns
        assertEquals("both attempts dispatched", 2, turns.size)
        assertEquals(
            "the retry reuses the request ID",
            turns[0].requestId,
            turns[1].requestId,
        )
        assertEquals(
            "and the conversation handle",
            turns[0].conversationId,
            turns[1].conversationId,
        )
        assertEquals(
            "and the mode",
            turns[0].conversationMode,
            turns[1].conversationMode,
        )
        assertEquals(
            "so no SECOND conversation was created by the retry: exactly one handle is in play",
            1,
            setOf(turns[0].conversationId, turns[1].conversationId).size,
        )
    }

    @Test
    fun `a valid request under a LATER window is adopted, not refused as stale`() = runTest {
        // @reviewer's measured defect: adoption happened only for the very first origin ever seen, so a
        // successor wake's first request was dropped as stale. A HIGHER origin is genuinely newer; the
        // caller has already established that its window is live, and only a lower (superseded) origin
        // is the stale case.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        // Window 1's first request.
        sink.routeToCloud(
            "first window",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = true,
        )
        assertEquals(1, deps.routeTurns.size)

        // Window 2, a successor wake: its first request must ALSO be admitted.
        sink.routeToCloud(
            "successor window",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            capturedOrigin = 2,
            isWakeOriginated = true,
        )
        assertEquals(
            "a request under a LATER live window must be adopted, not dropped " +
                "(turns=${deps.routeTurns.size})",
            2,
            deps.routeTurns.size,
        )
        assertEquals(CloudConversationMode.Create, deps.routeTurns.last().conversationMode)

        // And an origin BELOW the current one is still refused: that is the stale case.
        sink.routeToCloud(
            "an old window",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = false,
        )
        assertEquals(
            "an explicitly older origin is still refused",
            2,
            deps.routeTurns.size,
        )
    }

    @Test
    fun `saved episode A positions cannot follow episode B`() = runTest {
        // @reviewer's measured case: reference positions recorded while episode A played were sent when
        // episode B was asked about. A position is a position IN an episode, so it describes that
        // episode -- and a saved value must not outlive its subject.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        // Episode A records its reference history.
        deps.cloudPlaybackContextState.record(
            referencePositionMs = 100L,
            previousReferencePositionMs = 300L,
            episodeId = "A",
        )

        sink.routeToCloud(
            "about A",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
        )
        val forA = deps.routeTurns.last().context
        assertEquals(
            "episode A sees its own saved history",
            listOf(100L),
            forA.recentReferencePositions,
        )
        assertEquals(300L, forA.previousReferencePositionMs)

        // Episode B must NOT inherit it.
        sink.routeToCloud(
            "about B",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "B", clientPositionMs = 0L),
        )
        val forB = deps.routeTurns.last().context
        assertTrue(
            "saved A positions cannot be sent as B (recent=${forB.recentReferencePositions} " +
                "previous=${forB.previousReferencePositionMs})",
            forB.recentReferencePositions.isEmpty() && forB.previousReferencePositionMs == null,
        )
    }

    @Test
    fun `a REFUSED seek does not record its pre-action position against the other episode`() = runTest {
        // @reviewer's explicit-refusal history case. The sink is independently on episode B and refuses
        // A's seek, yet B's history became [700,100] -- because `capturePreActionPosition` ran BEFORE the
        // outcome was known, and `seekToReference` returned true after ignoring the sink's refusal.
        //
        // History is a record of what HAPPENED. An action that never reached the player did not happen,
        // and recording its position against the episode now playing is the history-corruption the
        // episode binding exists to prevent.
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            episodeId = "A",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("position_seconds" to 100),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // The player has moved to B, so the seek bound to A is REFUSED.
        deps.playback.currentEpisodeIdForTest = "B"
        val sink = deps.sink()

        sink.routeToCloud(
            "about episode A",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
        )

        assertTrue(
            "a refused seek must not be recorded as a performed action " +
                "(calls=${deps.playback.calls} recorded=${state.snapshot("B").recentReferencePositions})",
            !deps.playback.calls.any { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a REFUSED reference seek does not record history, and a refused quote does not either`() = runTest {
        // The REFERENCE branch is where @reviewer measured this, and it is a DIFFERENT branch from
        // `position_seconds`: that one routes to the plain seek, this one through the fingerprint
        // mapping. A case for one cannot see a defect in the other -- which is how my first version of
        // this control passed while the reference branch still recorded history on a refusal.
        //
        // Both reference verbs are covered because both recorded first: `seek_to` with
        // `reference_position_ms`, and `play_quote`.
        listOf("seek_to" to "reference_position_ms", "play_quote" to "reference_position_ms").forEach { (action, field) ->
            val state = CloudPlaybackContextState()
            val deps = TestDeps(
                cloudPlaybackContextState = state,
                episodeId = "A",
                events = flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = action,
                        params = mapOf(field to 45_000_000L),
                    ),
                    CloudRouteEvent.Done(1, 0),
                ),
            )
            // The player has moved to B, so the reference seek bound to A is REFUSED.
            deps.playback.currentEpisodeIdForTest = "B"
            val sink = deps.sink()

            sink.routeToCloud(
                "about episode A",
                VoiceIntent.CloudTier.Premium,
                PlaybackContext(episodeId = "A", clientPositionMs = 0L),
            )

            // THE HISTORY IS THE OBSERVATION, not the call list. A refused operation records nothing,
            // so the episode's saved positions must be exactly what they were -- an assertion about
            // `calls` alone passes whether or not history was corrupted, which is how my first version
            // of this control survived its mutation.
            assertTrue(
                "$action: a refused reference seek must not reach the player " +
                    "(calls=${deps.playback.calls})",
                !deps.playback.calls.any { it.startsWith("seekTo:") },
            )
            assertTrue(
                "$action: and it must not record history against any episode " +
                    "(A=${state.snapshot("A").recentReferencePositions} " +
                    "B=${state.snapshot("B").recentReferencePositions})",
                state.snapshot("A").recentReferencePositions.isEmpty() &&
                    state.snapshot("B").recentReferencePositions.isEmpty(),
            )
        }
    }

    @Test
    fun `a bound skip keeps the CALLER'S expectation rather than adopting the current episode`() = runTest {
        // @reviewer's finding: `skipForwardSuspend` re-read the queue, so a bound skip for A that found B
        // selected would adopt B as its subject and proceed -- under A's intent. The caller's expectation
        // must travel with the call.
        val deps = TestDeps(
            episodeId = "A",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "skip_forward",
                    params = mapOf("seconds" to 30),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // The player has moved to B, so a skip planned for A must be refused.
        deps.playback.currentEpisodeIdForTest = "B"
        val sink = deps.sink()

        sink.routeToCloud(
            "about episode A",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
        )

        assertTrue(
            "a skip planned for A must not proceed against B (calls=${deps.playback.calls})",
            !deps.playback.calls.any { it.startsWith("skipForward:") },
        )
    }

    @Test
    fun `a quote resume does not resume a replacement episode`() = runTest {
        // The quote's own resume is bound to the recording the quote came from.
        val deps = TestDeps(
            episodeId = "A",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        deps.playback.currentEpisodeIdForTest = "B"
        val sink = deps.sink()

        sink.routeToCloud(
            "play the quote",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
        )

        assertTrue(
            "a quote whose episode was replaced must not seek or resume (calls=${deps.playback.calls})",
            !deps.playback.calls.any { it.startsWith("seekTo:") || it == "resume" },
        )
    }

    @Test
    fun `a REFUSED bound skip is not reported as a performed action`() = runTest {
        // @spec's requirement: the scoped mutation's own payload decides, and statistics/history follow
        // ACCEPTANCE. A refused skip moved nothing, so reporting it as performed would publish a skip
        // that never happened -- and against the replacement episode.
        val deps = TestDeps(
            episodeId = "A",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "skip_forward",
                    params = mapOf("seconds" to 30),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // The player is on B, so the skip bound to A is refused.
        deps.playback.currentEpisodeIdForTest = "B"
        val sink = deps.sink()

        sink.routeToCloud(
            "about episode A",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
        )

        assertTrue(
            "a refused bound skip must not reach the player (calls=${deps.playback.calls})",
            !deps.playback.calls.any { it.startsWith("skip") && !it.contains("Refused") },
        )
    }

    @Test
    fun `an UNBOUND reference position is refused rather than applied to whatever is playing`() = runTest {
        // @spec's unbound case: a reference position is a position IN a recording, so with no episode to
        // be a position in there is nothing it can be a position in. A null expectation must not turn an
        // episode-specific operation into an unrestricted seek.
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
        val sink = deps.sink()

        // A turn with NO episode: an episode-specific reference action has nothing to act on.
        sink.routeToCloud(
            "play the quote",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = null, clientPositionMs = 0L),
        )

        assertTrue(
            "an unbound reference action must not seek whatever is playing " +
                "(calls=${deps.playback.calls})",
            !deps.playback.calls.any { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `an action still executes while the player is on the episode the turn is about`() = runTest {
        // The guard must not refuse everything. A turn about episode A, with the player still on A, is
        // exactly the case the action exists for -- and a guard that disabled actions universally would
        // pass the case above for the wrong reason.
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            // THE PROVIDER AND THE TURN NAME THE SAME EPISODE, which is what "still on it" means.
            episodeId = "A",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("position_seconds" to 100),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud(
            "about episode A",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
        )

        assertTrue(
            "an action must still execute while the player is on the turn's own episode " +
                "(calls=${deps.playback.calls})",
            deps.playback.calls.any { it.startsWith("seekTo") },
        )
    }

    @Test
    fun `an action planned for episode A does not seek episode B after the selection changes`() = runTest {
        // @spec's delayed episode-action rule: an action originating in episode A must not seek or
        // append history to episode B after selection changes.
        //
        // The turn is planned against episode A and the USER then selects B while the turn is in
        // flight -- an ordinary act that opens no new cloud turn, so `activeTurnId` still names this
        // turn and the ownership guard does not fire. Every playback verb is then addressed to
        // "whatever is playing", which is now B, so a seek computed for A lands in B at a position that
        // means nothing there.
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            episodeId = "A",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("position_seconds" to 100),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // The user changes episode MID-TURN: the provider now reports B while the turn is about A.
        deps.selectEpisodeForTest("B")
        deps.playback.currentEpisodeIdForTest = "B"
        // (the provider started on A, matching the turn, so the ONLY difference is the selection change)
        val sink = deps.sink()

        sink.routeToCloud(
            "about episode A",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
        )

        assertTrue(
            "an action planned for episode A must not seek the player now holding episode B " +
                "(calls=${deps.playback.calls})",
            deps.playback.calls.none { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a fresh wake begins a new conversation even though an earlier handle exists`() = runTest {
        // @reviewer's executed finding: a fresh wake RESUMEd the previous conversation, which makes a
        // new user act look like a continuation of whatever was being discussed before it.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        // A turn captured under the FIRST window's wake: it creates.
        sink.routeToCloud(
            "what is this about",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            capturedOrigin = 1,
            isWakeOriginated = true,
        )
        val first = deps.routeTurns.last()
        assertEquals(CloudConversationMode.Create, first.conversationMode)

        // A second window, also opened by a wake, and INSIDE the live grace window -- so the only
        // thing that can distinguish it from a continuation is the wake origin. Without that flag this
        // input is exactly a resume, which is what makes the case able to fail on the flag.
        deps.conversationAdmission.beginOrigin(2)
        sink.routeToCloud(
            "a completely new question",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            isLiveGraceTurn = true,
            capturedOrigin = 2,
            isWakeOriginated = true,
        )
        val second = deps.routeTurns.last()
        assertEquals(
            "a fresh wake begins a new conversation",
            CloudConversationMode.Create,
            second.conversationMode,
        )
        assertTrue(
            "with its own handle (first=${first.conversationId} second=${second.conversationId})",
            first.conversationId != second.conversationId,
        )
    }

    @Test
    fun `a wake-free request in the same live window resumes, and a stale origin is refused`() = runTest {
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(7)

        sink.routeToCloud(
            "first",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            capturedOrigin = 7,
            isWakeOriginated = true,
        )
        val created = deps.routeTurns.last()

        // A wake-FREE follow-up in the SAME window continues the conversation.
        sink.routeToCloud(
            "and another thing",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            isLiveGraceTurn = true,
            capturedOrigin = 7,
            isWakeOriginated = false,
        )
        val resumed = deps.routeTurns.last()
        assertEquals(CloudConversationMode.Resume, resumed.conversationMode)
        assertEquals(
            "the follow-up resumes the conversation the wake created",
            created.conversationId,
            resumed.conversationId,
        )

        // A STALE captured origin is refused outright, even though a newer window is live.
        deps.conversationAdmission.beginOrigin(8)
        val turnsBefore = deps.routeTurns.size
        sink.routeToCloud(
            "a request captured before the successor wake",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            capturedOrigin = 7,
            isWakeOriginated = false,
        )
        assertEquals(
            "a superseded origin must not reach transport at all",
            turnsBefore,
            deps.routeTurns.size,
        )
    }

    @Test
    fun `cloud frames carry NO local conversation history, asserted on the serialized frame`() = runTest {
        // @spec's requirement: the omission is asserted on the ACTUAL serialized cloud frame, with
        // local conversation memory POPULATED -- a case that left memory empty could not tell an
        // intentional omission from an empty one.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        // Populate LOCAL memory by recording a completed exchange, then ask a question.
        deps.conversationMemory.record("what is this podcast about", "it is about birds")
        sink.routeToCloud("and this one", VoiceIntent.CloudTier.Premium, playbackContext)

        val turn = deps.routeTurns.last()
        assertTrue(
            "the turn must not carry local history as cloud context " +
                "(recent=${turn.context.recentConversation})",
            turn.context.recentConversation.isNullOrEmpty(),
        )

        // The WIRE assertion lives in the repositories module, where the encoder is visible
        // (`CloudTurnRouteTest`): it serializes a frame whose context DOES hold history and asserts the
        // key is absent, so the omission is pinned at the encoder rather than assumed from the model.
    }

    @Test
    fun `the first turn CREATES a client-minted conversation and a later grace turn RESUMES it`() = runTest {
        // @spec's admission contract: the CLIENT generates the handle, CREATE carries it, and a later
        // turn inside the live grace window resumes the SAME conversation.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        sink.routeToCloud("first question", VoiceIntent.CloudTier.Premium, playbackContext)
        val first = deps.routeTurns.last()

        assertEquals(
            "the first turn creates a conversation",
            CloudConversationMode.Create,
            first.conversationMode,
        )
        assertTrue(
            "and carries the client-minted handle",
            !first.conversationId.isNullOrBlank(),
        )

        // A later turn continuing the live grace window RESUMES the same conversation.
        sink.routeToCloud(
            "and another thing",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            isLiveGraceTurn = true,
        )
        val second = deps.routeTurns.last()

        assertEquals(
            "a live grace turn resumes rather than creating",
            CloudConversationMode.Resume,
            second.conversationMode,
        )
        assertEquals(
            "and the two turns SHARE one handle, which is what makes them one conversation",
            first.conversationId,
            second.conversationId,
        )
    }

    @Test
    fun `a later turn outside the grace window begins a new conversation`() = runTest {
        // The control: RESUME is about continuing a live conversation, not about being the second
        // turn. Without the grace window a new question starts a new conversation.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        sink.routeToCloud("first question", VoiceIntent.CloudTier.Premium, playbackContext)
        val first = deps.routeTurns.last()
        sink.routeToCloud("an unrelated question", VoiceIntent.CloudTier.Premium, playbackContext)
        val second = deps.routeTurns.last()

        assertEquals(CloudConversationMode.Create, second.conversationMode)
        assertTrue(
            "a new conversation carries a DIFFERENT handle (first=${first.conversationId} " +
                "second=${second.conversationId})",
            first.conversationId != second.conversationId,
        )
    }

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
        // could not see the merge it is about. No episode is named, which is the state this case is
        // about: positions with no subject at all.
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
        // Recorded BEFORE the turn, AGAINST the episode this turn is about -- a saved position belongs
        // to the episode it was recorded from, so the control must name it to assert retention.
        deps.cloudPlaybackContextState.record(
            referencePositionMs = 100L,
            previousReferencePositionMs = 300L,
            episodeId = "episode-1",
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
        assertEquals(
            "episode-id",
            playbackContext.episodeId,
        )
        assertEquals(
            "the recorded value is read back FOR the episode it was recorded from",
            45_000L,
            state.snapshot(playbackContext.episodeId).previousReferencePositionMs,
        )
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
            // The episode the saved value belongs to: the assertion below is that THIS episode's
            // previous reference is CLEARED when the mapping is unavailable, not that some other
            // episode's value is invisible.
            episodeId = "episode-id",
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
        assertEquals(null, state.snapshot("episode-id").previousReferencePositionMs)
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
            // The episode these positions belong to: the context below is about `episode-id`, so this
            // is the only episode for which the saved history may be offered.
            episodeId = "episode-id",
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
    fun `a completed turn records local memory, which cloud frames still must not carry`() = runTest {
        // REVERSED from its earlier form, which asserted that the SECOND turn carried the recorded
        // exchange as cloud context. The owning spec forbids `recent_conversation` on cloud frames --
        // the Worker owns cloud history -- so that expectation encoded the defect.
        //
        // Local memory still RECORDS the exchange (it serves the local intent path), and this case
        // keeps that half while asserting the cloud frame does not carry it.
        val memory = CloudConversationMemory()
        val deps = TestDeps(
            conversationMemory = memory,
            events = flowOf(CloudRouteEvent.Token("an answer"), CloudRouteEvent.Done(1, 1)),
        )
        val sink = deps.sink()

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext)
        sink.routeToCloud("a follow-up", VoiceIntent.CloudTier.Premium, playbackContext)

        // The local record IS kept -- both exchanges, in order, which is what local memory is for.
        val remembered = memory.recent().map { it.role to it.text }
        assertTrue(
            "the completed exchange is remembered locally (remembered=$remembered)",
            remembered.containsAll(
                listOf(
                    CloudRouteConversationEntry.ROLE_USER to "a question",
                    CloudRouteConversationEntry.ROLE_ASSISTANT to "an answer",
                ),
            ),
        )
        // ...and NEITHER turn carries it as cloud context, whatever memory holds.
        assertTrue(
            "no cloud turn carries local history (first=${deps.routeTurns.first().context.recentConversation})",
            deps.routeTurns.first().context.recentConversation.isNullOrEmpty(),
        )
        assertTrue(
            "not even after memory is populated (last=${deps.routeTurns.last().context.recentConversation})",
            deps.routeTurns.last().context.recentConversation.isNullOrEmpty(),
        )
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
        /** The episode the sink's own provider reports, so recorded positions have a subject. */
        private val episodeId: String? = "episode-id",
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

        /**
         * The provider the SINK reads, which must name the SAME episode the fixture's context does:
         * saved reference positions are recorded against the episode they describe, so a provider
         * without one records them against nothing and a later read cannot find them.
         */
        // The episode the provider reports. MUTABLE, because a user can change episode while a turn is
        // in flight -- the ordinary way an action planned for one episode would otherwise be addressed
        // to another.
        private var liveEpisodeId: String? = episodeId

        fun selectEpisodeForTest(id: String) {
            liveEpisodeId = id
        }

        private val playbackContextProvider = object : PlaybackContextProvider {
            override fun current(): PlaybackContext = PlaybackContext(
                episodeId = liveEpisodeId,
                clientPositionMs = clientPositionMs,
            )
        }

        /** The admission component the sink uses, so a case can declare its live window. */
        val conversationAdmission = CloudConversationAdmission()

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
            conversationAdmission = conversationAdmission,
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

        override suspend fun resume(expectedEpisodeId: String?): VoiceResponse {
            resumeStarted.complete(Unit)
            resumeGate?.await()
            calls += "resume"
            host?.playing = true
            return VoiceResponse.Silent
        }

        override suspend fun skipForward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult {
            calls += "skipForward:$seconds"
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)
        }
        override suspend fun skipBackward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult {
            calls += "skipBackward:$seconds"
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)
        }

        override suspend fun seekTo(
            positionSeconds: Int,
            episodeDurationSeconds: Int,
            expectedEpisodeId: String?,
        ): EpisodeOperationResult {
            // MIRRORS THE PRODUCTION REFUSAL. The fake used to record every call unconditionally, so a
            // mutation removing the episode binding left it unchanged and the case could not fail for
            // the defect. The binding is part of the contract now, so the fake honours it.
            if (!accepts(expectedEpisodeId)) {
                calls += "seekToRefused:$positionSeconds"
                // REFUSED, not Accepted. Reporting acceptance here made the fake claim a refusal had
                // been performed, so a caller recording history from the outcome saw success and the
                // defect was invisible: the fake told the caller what the production sink would not.
                return EpisodeOperationResult.Refused
            }
            // Resolve the schema's signed semantics the same way production does, so a fake cannot
            // make a negative position pass by clamping it where the real sink resolves an offset.
            val resolved = if (positionSeconds < 0) {
                (episodeDurationSeconds + positionSeconds).coerceIn(0, episodeDurationSeconds)
            } else {
                positionSeconds
            }
            calls += "seekTo:$resolved"
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)
        }

        // Whether the sink is on the episode the operation was planned for. ABSENT MEANS "AGREE": the
        // ordinary case is that nothing changed, so a case has to say so explicitly when the player
        // moved to another episode. A default of "refuse" would make every unrelated case fail for a
        // reason that has nothing to do with what it is about.
        var currentEpisodeIdForTest: String? = null

        private fun accepts(expectedEpisodeId: String?): Boolean = expectedEpisodeId == null || currentEpisodeIdForTest == null || expectedEpisodeId == currentEpisodeIdForTest

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
