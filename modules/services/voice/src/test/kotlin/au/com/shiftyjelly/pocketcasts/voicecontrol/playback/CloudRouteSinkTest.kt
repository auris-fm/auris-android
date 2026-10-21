package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteCapabilities
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteErrorCodes
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudSearchEvidenceItem
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudSearchResults
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransport
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.withLogicalRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
@kotlinx.coroutines.ExperimentalCoroutinesApi
class CloudRouteSinkTest {

    private val testWireId = "6f9619ff-8b86-d011-b42d-00c04fc964ff"

    private val playbackContext = PlaybackContext(
        episodeId = "episode-id",
        podcastId = "podcast-id",
        referencePositionMs = 1_230_000L,
        clientPositionMs = 50_000L,
    )

    @Test
    fun `concurrent callbacks prepare exactly one retained wire identity`() = runTest {
        val deps = TestDeps()
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)
        val stateLock = checkNotNull(CloudPlaybackContextState::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(deps.cloudPlaybackContextState))
        val turns = java.util.concurrent.CopyOnWriteArrayList<CloudRouteTurn>()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        fun prepare() = Thread {
            try {
                val turn = kotlinx.coroutines.runBlocking {
                    sink.prepareTurn("question", VoiceIntent.CloudTier.Premium, playbackContext, capturedOrigin = 1, isWakeOriginated = true, logicalRequestId = "one-utterance")
                }
                turns += checkNotNull(turn)
            } catch (t: Throwable) {
                failures += t
            }
        }
        val first = prepare()
        val second = prepare()
        try {
            synchronized(stateLock) {
                first.start()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (first.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(2)
                assertTrue("first preparation must wait at the real context snapshot", first.stackTrace.any { it.className == CloudPlaybackContextState::class.java.name && it.methodName == "snapshot" })
                second.start()
                while (second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(2)
                assertEquals(Thread.State.BLOCKED, second.state)
            }
        } finally {
            first.join(3_000L)
            if (second.state != Thread.State.NEW) second.join(3_000L)
        }
        assertTrue("preparations terminate", !first.isAlive && !second.isAlive)
        assertTrue("no preparation failure: $failures", failures.isEmpty())
        assertEquals(2, turns.size)
        org.junit.Assert.assertSame("both callbacks reuse one prepared request", turns[0], turns[1])
        assertEquals(turns[0], deps.conversationAdmission.retained("one-utterance"))
    }

    @Test
    fun `duplicate callbacks join one dispatch without repeating feedback`() = runTest(UnconfinedTestDispatcher()) {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val deps = TestDeps(routeInvoker = {
            flow {
                entered.complete(Unit)
                finish.await()
                emit(CloudRouteEvent.Done(1, 0))
            }
        })
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)
        val prepared = sink.prepareTurn("question", VoiceIntent.CloudTier.Premium, playbackContext, capturedOrigin = 1, logicalRequestId = "one-utterance")!!
        val first = async { sink.dispatchPrepared(prepared) }
        entered.await()
        val duplicate = async { sink.dispatchPrepared(prepared) }
        try {
            assertTrue("duplicate callback must not cancel its own request", first.isActive)
            assertEquals("one request owns one running dispatch", 1, deps.routeTurns.size)
            assertTrue(duplicate.isActive)
            finish.complete(Unit)
            first.await()
            assertEquals(VoiceResponse.Silent, duplicate.await().feedback)
            assertEquals(VoiceResponse.Silent, sink.dispatchPrepared(prepared).feedback)
            assertEquals("a completed callback does not re-execute", 1, deps.routeTurns.size)
        } finally {
            finish.complete(Unit)
            first.cancel()
            duplicate.cancel()
            first.join()
            duplicate.join()
        }
    }

    @Test
    fun `failure and cancellation release duplicate waiters and explicit retry keeps identity`() = runTest(UnconfinedTestDispatcher()) {
        for (cancelOwner in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val fail = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
            val expected = IllegalStateException("controlled route failure")
            var attempts = 0
            val deps = TestDeps(routeInvoker = {
                flow {
                    if (++attempts == 1) {
                        entered.complete(Unit)
                        fail.await()
                        throw expected
                    }
                    emit(CloudRouteEvent.Done(1, 0))
                }
            })
            val sink = deps.sink()
            deps.conversationAdmission.beginOrigin(1)
            val prepared = sink.prepareTurn("question", VoiceIntent.CloudTier.Premium, playbackContext, capturedOrigin = 1, logicalRequestId = "one-utterance")!!
            val owner = launch {
                try {
                    sink.dispatchPrepared(prepared)
                } catch (failure: Throwable) {
                    observed.complete(failure)
                }
            }
            entered.await()
            val duplicate = async { sink.dispatchPrepared(prepared) }
            try {
                assertTrue(duplicate.isActive)
                if (cancelOwner) owner.cancel() else fail.complete(Unit)
                withTimeout(1_000) {
                    owner.join()
                    assertEquals(VoiceResponse.Silent, duplicate.await().feedback)
                }
                if (cancelOwner) assertTrue(observed.await() is CancellationException) else org.junit.Assert.assertSame(expected, observed.await())
                assertEquals(1, deps.routeTurns.size)
                sink.dispatchPrepared(prepared, isRetry = true)
                assertEquals("one explicit retry, no duplicate execution", 2, deps.routeTurns.size)
                assertEquals("retry preserves the original wire payload", prepared, deps.routeTurns.last())
            } finally {
                fail.complete(Unit)
                owner.cancel()
                duplicate.cancel()
                owner.join()
                duplicate.join()
            }
        }
    }

    @Test
    fun `old completion cannot retire a successor request with the same logical identity`() = runTest(UnconfinedTestDispatcher()) {
        val entered = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val finish = CompletableDeferred<Unit>()
        val deps = TestDeps(routeInvoker = { turn ->
            flow {
                entered.send(Unit)
                if (turn.capturedOrigin == 1L) awaitCancellation() else finish.await()
                emit(CloudRouteEvent.Done(1, 0))
            }
        })
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)
        val first = sink.prepareTurn("old question", VoiceIntent.CloudTier.Premium, playbackContext, capturedOrigin = 1, logicalRequestId = "one-utterance")!!
        val oldOwner = launch { sink.dispatchPrepared(first) }
        entered.receive()
        deps.conversationAdmission.endWindow(1)
        deps.conversationAdmission.beginOrigin(2)
        // A NEW utterance under a successor window carries its OWN admitted identity: since
        // spec 3b1def9 the wire id is the admitted one, so reusing the same string here would mean
        // the same utterance, not a successor.
        val successor = sink.prepareTurn("new question", VoiceIntent.CloudTier.Premium, playbackContext, capturedOrigin = 2, logicalRequestId = "b2f5e9a1-7c34-4d80-9e11-6a4b8c0d2f35")!!
        val newOwner = async { sink.dispatchPrepared(successor) }
        entered.receive()
        val duplicate = async { sink.dispatchPrepared(successor) }
        try {
            withTimeout(1_000) { oldOwner.join() }
            assertTrue(oldOwner.isCancelled)
            org.junit.Assert.assertSame(successor, sink.retainedTurnFor("b2f5e9a1-7c34-4d80-9e11-6a4b8c0d2f35"))
            assertNotEquals(first.requestId, successor.requestId)
            // THE STALE PATH, kept as a negative control rather than dropped when the successor's
            // identity changed: the retired OLD request is no longer retained, and neither its
            // identity nor the superseded window can resurrect it. A delayed callback naming the old
            // logical id must be refused outright -- not adopted into the successor's window.
            assertNull(
                "the superseded request is not retained any more",
                sink.retainedTurnFor("one-utterance"),
            )
            assertNull(
                "and a late first attempt naming it is refused, not prepared",
                sink.prepareTurn(
                    request = "late old question",
                    tier = VoiceIntent.CloudTier.Premium,
                    context = playbackContext,
                    capturedOrigin = 1,
                    isWakeOriginated = true,
                    logicalRequestId = "one-utterance",
                ),
            )
            assertTrue(newOwner.isActive)
            assertTrue(duplicate.isActive)
            assertEquals(2, deps.routeTurns.size)
            finish.complete(Unit)
            newOwner.await()
            assertEquals(VoiceResponse.Silent, duplicate.await().feedback)
            assertEquals(2, deps.routeTurns.size)
        } finally {
            finish.complete(Unit)
            oldOwner.cancel()
            newOwner.cancel()
            duplicate.cancel()
            oldOwner.join()
            newOwner.join()
            duplicate.join()
        }
    }

    @Test
    fun `interrupted terminal discards output while completed terminal drains it`() = runTest {
        for (terminal in CloudRouteEvent.Outcome.entries) {
            val player = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
            whenever(player.audioWritten).thenReturn(true)
            whenever(player.drainAndAwaitCompletion()).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer.Completion.Consumed)
            val deps = TestDeps(
                audioPlayer = player,
                events = flowOf(
                    CloudRouteEvent.Connected("pcm_s16le@24k"),
                    CloudRouteEvent.AudioFrame("pcm_s16le@24k", byteArrayOf(1, 2)),
                    CloudRouteEvent.Done(3, 4, terminal),
                ),
            )
            val turnResult = deps.sink().routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
            assertEquals(VoiceResponse.Silent, turnResult.feedback)
            // POSITIVE success (reviewer 73ea2ad5): only an actual DONE-Completed terminal is a
            // success; Interrupted must be derived false, never by absence-of-failure.
            assertEquals(terminal == CloudRouteEvent.Outcome.Completed, turnResult.succeeded)
            org.mockito.kotlin.verify(player).submitFrame(byteArrayOf(1, 2))
            if (terminal == CloudRouteEvent.Outcome.Interrupted) {
                org.mockito.kotlin.verify(player, org.mockito.kotlin.never()).drainAndAwaitCompletion()
                org.mockito.kotlin.verify(player, org.mockito.kotlin.atLeastOnce()).stop()
            } else {
                org.mockito.kotlin.verify(player).drainAndAwaitCompletion()
            }
            assertEquals(listOf(CloudRouteAnalyticsCall(if (terminal == CloudRouteEvent.Outcome.Completed) "done" else "interrupted", 3, 4)), deps.analytics.calls)
        }
    }

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
            logicalRequestId = "prepared-window-1",
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
            logicalRequestId = "prepared-window-1",
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
        sink.routeToCloud("with a captured window", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId, capturedOrigin = 1).feedback
        sink.routeToCloud("and with none", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        sink.routeToCloudWithHint(
            request = "and via the typed-flow entry point",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            admitted = testWireId,
            hint = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint("playback"),
        ).feedback

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
            logicalRequestId = "retryable-1",
        )
        assertNotNull("the turn is prepared", prepared)

        // Dispatch it twice, as a controller retry would. A retry goes through the SAME lifecycle as a
        // first dispatch -- it is not a shortcut to the transport.
        sink.dispatchPrepared(prepared!!)
        sink.dispatchPrepared(prepared, isRetry = true)

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
            admitted = testWireId,
            capturedOrigin = 1,
            isWakeOriginated = true,
        ).feedback
        assertEquals(1, deps.routeTurns.size)

        // Window 2, a successor wake: its first request must ALSO be admitted.
        sink.routeToCloud(
            "successor window",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            admitted = testWireId,
            capturedOrigin = 2,
            isWakeOriginated = true,
        ).feedback
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
            admitted = testWireId,
            capturedOrigin = 1,
            isWakeOriginated = false,
        ).feedback
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
            admitted = testWireId,
        ).feedback
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
            admitted = testWireId,
        ).feedback
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
            admitted = testWireId,
        ).feedback

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
                admitted = testWireId,
            ).feedback

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
            admitted = testWireId,
        ).feedback

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
            admitted = testWireId,
        ).feedback

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
            admitted = testWireId,
        ).feedback

        assertTrue(
            "a refused bound skip must not reach the player (calls=${deps.playback.calls})",
            !deps.playback.calls.any { it.startsWith("skip") && !it.contains("Refused") },
        )
    }

    @Test
    fun `an UNKNOWN action is carried as Unknown, not as nothing and not as confirmed`() {
        // @spec: "returning true for Unknown still reports confirmed execution" -- the defect is that the
        // Boolean could not distinguish the three. The RESPONSE cannot show the difference: an `Action`
        // event makes the turn handled regardless, so Unknown and Refused both yield Silent. What the
        // mechanism carries is the ACTION OUTCOME, and it must keep all three apart.
        val unknown = CloudRouteSink.mergeActionOutcome(
            CloudRouteSink.Companion.ActionOutcome.None,
            CloudRouteSink.Companion.ActionOutcome.Unknown,
        )
        assertNotEquals(
            "an unknown action must not be recorded as nothing having happened",
            CloudRouteSink.Companion.ActionOutcome.None,
            unknown,
        )
        assertNotEquals(
            "and it must not be recorded as confirmed execution, which nobody observed",
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            unknown,
        )
        assertEquals(
            "it is carried as UNKNOWN, distinctly",
            CloudRouteSink.Companion.ActionOutcome.Unknown,
            unknown,
        )
        // AND A CONFIRMED OUTCOME IS NOT DOWNGRADED by a later unknown one.
        assertEquals(
            "confirmation is not replaced by a later unknown",
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            CloudRouteSink.mergeActionOutcome(
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
                CloudRouteSink.Companion.ActionOutcome.Unknown,
            ),
        )
    }

    @Test
    fun `a skip that is UNKNOWN reports Unknown, not a confirmed or empty action`() = runTest {
        // The skip path ended its arm with a bare `ActionOutcome.Confirmed`, DISCARDING whatever the sink
        // reported -- so a refused or unknown skip read as a confirmed one. And every branch collapsed
        // Unknown into None, reporting an attempted skip as "nothing happened".
        suspend fun outcomeFor(result: EpisodeOperationResult): CloudRouteSink.Companion.ActionOutcome {
            val deps = TestDeps(
                cloudPlaybackContextState = CloudPlaybackContextState(),
                episodeId = "episode-id",
                events = flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "seek_relative",
                        params = mapOf("delta_seconds" to 30),
                    ),
                ),
            )
            deps.playback.skipOutcomeForTest = result
            val sink = deps.sink()
            sink.routeToCloud(
                "skip",
                VoiceIntent.CloudTier.Premium,
                PlaybackContext(episodeId = "episode-id", clientPositionMs = 0L),
                admitted = testWireId,
            ).feedback
            return sink.lastActionOutcome
        }

        assertEquals(
            "an UNKNOWN skip must report Unknown, not Confirmed",
            CloudRouteSink.Companion.ActionOutcome.Unknown,
            outcomeFor(EpisodeOperationResult.Unknown("episode-id", 0L)),
        )
        assertEquals(
            "a REFUSED skip must report None, not Confirmed",
            CloudRouteSink.Companion.ActionOutcome.None,
            outcomeFor(EpisodeOperationResult.Refused),
        )
        assertEquals(
            "an ACCEPTED skip reports Confirmed",
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            outcomeFor(EpisodeOperationResult.Accepted("episode-id", 0L)),
        )
    }

    @Test
    fun `an UNKNOWN outcome keeps the pre-action provenance while recording NO target`() = runTest {
        // @spec: "Preserve the owner's known value; Unknown concerns execution, not whether its
        // pre-action snapshot existed." The TARGET is suppressed because nothing established the player
        // reached it; the PRE-ACTION position is kept because the owner captured it before the attempt.
        // An early return on a null target would discard BOTH, which is the defect this pins.
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            episodeId = "episode-id",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // UNKNOWN, carrying a KNOWN pre-action position.
        deps.playback.seekOutcomeForTest = EpisodeOperationResult.Unknown("episode-id", 60_000L)
        val sink = deps.sink()

        sink.routeToCloud(
            "play the quote",
            VoiceIntent.CloudTier.Premium,
            PlaybackContext(episodeId = "episode-id", clientPositionMs = 0L),
            admitted = testWireId,
        ).feedback

        val recorded = state.snapshot("episode-id")
        assertTrue(
            "an unknown outcome must record NO target, because nothing observed it being reached " +
                "(recorded=${recorded.recentReferencePositions})",
            recorded.recentReferencePositions.isEmpty(),
        )
    }

    @Test
    fun `an ordinary position seek reports its TYPED outcome to the caller, not None`() = runTest {
        // @reviewer's measured regression from my conversion: the ordinary branch computed its `when`
        // and DISCARDED it, so execution fell through to `referenceMs == null` and returned None. The
        // CALLER therefore saw [None, None] for Unknown->Confirmed and [None] for a single Unknown --
        // every seek reaching the real sink yet reporting that nothing happened.
        //
        // The observation is the OUTCOME THE CALLER RECEIVES, which is the value the mechanism decides
        // on. Asserting the response or the seek count cannot see this: both are unchanged by the
        // discarded result.
        suspend fun outcomeFor(result: EpisodeOperationResult, action: String = "seek_to"): CloudRouteSink.Companion.ActionOutcome {
            val deps = TestDeps(
                cloudPlaybackContextState = CloudPlaybackContextState(),
                episodeId = "episode-id",
                events = flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = action,
                        params = mapOf("position_seconds" to 100),
                    ),
                ),
            )
            deps.playback.seekOutcomeForTest = result
            val sink = deps.sink()
            sink.routeToCloud(
                "seek",
                VoiceIntent.CloudTier.Premium,
                PlaybackContext(episodeId = "episode-id", clientPositionMs = 0L),
                admitted = testWireId,
            ).feedback
            return sink.lastActionOutcome
        }

        assertEquals(
            "an ACCEPTED ordinary seek must report Confirmed, not None",
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            outcomeFor(EpisodeOperationResult.Accepted("episode-id", 0L)),
        )
        assertEquals(
            "an UNKNOWN ordinary seek must report Unknown, not None",
            CloudRouteSink.Companion.ActionOutcome.Unknown,
            outcomeFor(EpisodeOperationResult.Unknown("episode-id", 0L)),
        )
        assertEquals(
            "a REFUSED ordinary seek reports None: nothing happened",
            CloudRouteSink.Companion.ActionOutcome.None,
            outcomeFor(EpisodeOperationResult.Refused),
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
            admitted = testWireId,
        ).feedback

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
            admitted = testWireId,
        ).feedback

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
            admitted = testWireId,
        ).feedback

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
            admitted = testWireId,
            capturedOrigin = 1,
            isWakeOriginated = true,
        ).feedback
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
            admitted = testWireId,
            isLiveGraceTurn = true,
            capturedOrigin = 2,
            isWakeOriginated = true,
        ).feedback
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
            admitted = testWireId,
            capturedOrigin = 7,
            isWakeOriginated = true,
        ).feedback
        val created = deps.routeTurns.last()

        // A wake-FREE follow-up in the SAME window continues the conversation.
        sink.routeToCloud(
            "and another thing",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            admitted = testWireId,
            isLiveGraceTurn = true,
            capturedOrigin = 7,
            isWakeOriginated = false,
        ).feedback
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
            admitted = testWireId,
            capturedOrigin = 7,
            isWakeOriginated = false,
        ).feedback
        assertEquals(
            "a superseded origin must not reach transport at all",
            turnsBefore,
            deps.routeTurns.size,
        )
    }

    @Test
    fun `the first turn CREATES a client-minted conversation and a later grace turn RESUMES it`() = runTest {
        // @spec's admission contract: the CLIENT generates the handle, CREATE carries it, and a later
        // turn inside the live grace window resumes the SAME conversation.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        sink.routeToCloud("first question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
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
            admitted = testWireId,
            isLiveGraceTurn = true,
        ).feedback
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

        sink.routeToCloud("first question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        val first = deps.routeTurns.last()
        sink.routeToCloud("an unrelated question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
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
            admitted = testWireId,
        ).feedback

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
            admitted = testWireId,
        ).feedback

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
                    emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                    throw IllegalStateException("upstream blew up")
                },
            ),
        )
        val sink = deps.sink()

        val failure = runCatching {
            sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `cancellation during collect still restores auto pause`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flow {
                    emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                    awaitCancellation()
                },
            ),
        )
        val sink = deps.sink()

        val cancelled = runCatching {
            withTimeout(1_000) {
                sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
            }
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)

        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `empty base url returns coming soon without calling route`() = runTest {
        val deps = TestDeps(baseUrl = "")
        val sink = deps.sink()

        val response = sink.routeToCloud("summarize", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Cloud processing is coming soon"), response)
        assertTrue(deps.routeCalls.isEmpty())
        assertTrue(deps.playback.calls.isEmpty())
    }

    @Test
    fun `history uses the OWNER'S pre-action snapshot, not a later provider read`() = runTest {
        // THE OBSERVATION THAT DISTINGUISHES THE TWO. The provider reports the position the CLIENT has
        // NOW, which after an accepted seek is the DESTINATION; the owner reports the position BEFORE the
        // mutation. A case where those differ is the only one that can tell which value history used --
        // with them equal, a live read and the owner's snapshot are indistinguishable, which is why the
        // earlier cases could not see the difference.
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.referenceTime(60_000)).thenReturn(45.0)
        whenever(fingerprint.playbackTimeMs(45_000.0)).thenReturn(45_000)
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            cloudPlaybackContextState = state,
            // WHERE THE CLIENT IS NOW -- deliberately different from the pre-action snapshot below.
            clientPositionMs = 999_000L,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // THE OWNER'S SNAPSHOT: the position BEFORE the action, on the playback timeline.
        deps.playback.preActionPositionMsForTest = 60_000L
        val sink = deps.sink()

        sink.routeToCloud("play the quote", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        val recorded = state.snapshot("episode-id")
        // 60_000 playback maps to 45_000 reference. The provider's 999_000 would map far away, so a live
        // read cannot produce this value.
        assertTrue(
            "the recorded previous reference must come from the OWNER'S snapshot (60_000 playback), not " +
                "the provider's current position (999_000): recorded=${recorded.previousReferencePositionMs}",
            recorded.previousReferencePositionMs == 45_000L,
        )
    }

    @Test
    fun `composite steps stay unknown when one step was not established`() {
        // THE THREE INPUTS THAT DECIDE IT are enumerated, so the case cannot pass on a rule that only
        // happens to agree for one of them. A confirmed-later-step rule would report the first two as
        // Confirmed.
        assertEquals(
            "an unknown step leaves the composite unknown however well the later step went",
            CloudRouteSink.Companion.ActionOutcome.Unknown,
            CloudRouteSink.mergeCompositeSteps(
                CloudRouteSink.Companion.ActionOutcome.Unknown,
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
            ),
        )
        assertEquals(
            CloudRouteSink.Companion.ActionOutcome.Unknown,
            CloudRouteSink.mergeCompositeSteps(
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
                CloudRouteSink.Companion.ActionOutcome.Unknown,
            ),
        )
        // The composite is confirmed only when EVERY required step was.
        assertEquals(
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            CloudRouteSink.mergeCompositeSteps(
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
            ),
        )
        // A step that did nothing cannot make the composite confirmed.
        assertEquals(
            CloudRouteSink.Companion.ActionOutcome.None,
            CloudRouteSink.mergeCompositeSteps(
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
                CloudRouteSink.Companion.ActionOutcome.None,
            ),
        )
    }

    @Test
    fun `turn aggregation still promotes a confirmed action past an unknown sibling`() {
        // THE OTHER CONTRACT, asserted here so the two rules cannot silently become one. Independent
        // actions do not depend on each other, so an unknown sibling must not erase a confirmed one.
        assertEquals(
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            CloudRouteSink.mergeActionOutcome(
                CloudRouteSink.Companion.ActionOutcome.Unknown,
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
            ),
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

        sink.routeToCloud("play the quote", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("play the quote", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val response = deps.sink().routeToCloud("what?", VoiceIntent.CloudTier.Free, playbackContext, admitted = testWireId).feedback

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

        val response = sink.routeToCloud("what?", VoiceIntent.CloudTier.Free, playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `an empty turn speaks the default line whatever the device locale`() = runTest {
        // The utterance's language decides (spec 54287f13); with none detected the default
        // (English) line is spoken on any device locale — the earcon is no longer the
        // language fallback.
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0)),
            locale = java.util.Locale.FRENCH,
        )

        val response = deps.sink().routeToCloud("quoi?", VoiceIntent.CloudTier.Free, playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
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

        val response = sink.routeToCloud("pause", VoiceIntent.CloudTier.Free, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("jump to five minutes", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek nowhere", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("play quote", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("quote", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        assertEquals(
            VoiceResponse.Spoken("The assistant has reached its limit right now."),
            response,
        )
        assertNotEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `error restores auto pause and speaks the client line`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Connected("pcm_s16le@24k"),
                    CloudRouteEvent.Error(code = "connection_lost", message = "Connection lost"),
                ),
            ),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val response = sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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
    fun `distinct utterances send their own admitted identities unchanged`() = runTest {
        // Spec 3b1def9/f47e972: the wire id IS the admitted identity, so two distinct utterances send
        // two DISTINCT admitted values -- and each is carried through byte-for-byte (no remint).
        val deps = TestDeps()
        val sink = deps.sink()
        val first = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
        val second = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"

        sink.routeToCloud("one", VoiceIntent.CloudTier.Premium, playbackContext, admitted = first).feedback
        sink.routeToCloud("two", VoiceIntent.CloudTier.Premium, playbackContext, admitted = second).feedback

        val ids = deps.routeTurns.map { it.requestId }
        assertEquals(2, ids.size)
        assertEquals(listOf(first, second), ids)
    }

    @Test
    fun `a dispatch with no admitted identity is refused and sends nothing`() = runTest {
        // Fail closed: the sink must never mint an identity client logs cannot name.
        val deps = TestDeps()
        val sink = deps.sink()

        val response = sink.routeToCloud("unidentified", VoiceIntent.CloudTier.Premium, playbackContext, admitted = null).feedback

        assertEquals(VoiceResponse.Silent, response)
        assertEquals("nothing reached the wire", 0, deps.routeTurns.size)
    }

    @Test
    fun `capabilities are advertised only when a renderer is present`() = runTest {
        val withoutRenderer = TestDeps()
        withoutRenderer.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertTrue(withoutRenderer.routeTurns.single().capabilities.isEmpty())

        val withRenderer = TestDeps(renderer = RecordingRenderer())
        withRenderer.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(
            listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1),
            withRenderer.routeTurns.single().capabilities,
        )
    }

    @Test
    fun `free text turns carry no route hint and typed entry supplies one`() = runTest {
        val deps = TestDeps()
        val sink = deps.sink()

        sink.routeToCloud("find beginner investing episodes", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(null, deps.routeTurns.single().routeHint)

        val hint = CloudRouteHint(operation = "search_episodes", arguments = mapOf("query" to "investing"))
        sink.routeToCloudWithHint(
            "find beginner investing episodes",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            hint,
            admitted = testWireId,
        ).feedback
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

        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        firstTurnStarted.await()
        sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
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

        deps.sink().routeToCloud("find investing", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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
                        items = emptyList(),
                    ),
                ),
                CloudRouteEvent.Done(0, 0),
            ),
        )

        deps.sink().routeToCloud("nothing matches", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        assertTrue(renderer.rendered.isEmpty())
        assertEquals(listOf(CloudSearchResults.SCOPE_LIBRARY), renderer.empties)
        assertTrue(renderer.unavailableCount == 0)
    }

    @Test
    fun `failed mixed stream keeps executed actions and reports the client error`() = runTest {
        val deps = TestDeps(
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "seek_to",
                        params = mapOf("reference_position_ms" to 500_000L),
                    ),
                    CloudRouteEvent.Connected("pcm_s16le@24k"),
                    CloudRouteEvent.Error(code = "provider_error", message = "The assistant hit an error."),
                ),
            ),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        // The executed seek is not rolled back when the stream fails.
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

        deps.sink().routeToCloud("find something", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val response = deps.sink().routeToCloud("what did she mean?", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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
                    flowOf(CloudRouteEvent.Connected("pcm_s16le@24k"), CloudRouteEvent.Done(1, 1))
                }
            },
        )
        val sink = deps.sink()

        // Turn 1 reaches its terminal while the token is valid (no answer output).
        assertEquals(
            VoiceResponse.Spoken("Sorry, I couldn't complete that request."),
            sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback,
        )

        // The token expires mid-session: the next turn fails closed and no
        // playback action is attempted.
        token = null
        val second = sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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
        val turn = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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
        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.resumeStarted.await()

        // B must not register/pause until A's action has finished with the player.
        val second = launch { sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

        val first = launch { runCatching { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback } }
        deps.playback.pauseStarted.await()
        val second = launch { runCatching { sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback } }
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
                    CloudRouteEvent.Connected("pcm_s16le@24k"),
                    CloudRouteEvent.Done(1, 1),
                )
            },
        )
        val sink = deps.sink()

        // Nothing was paused before the failure, so the only claim this test
        // makes is about ownership: a later turn registers cleanly.
        val first = launch { runCatching { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback } }
        first.join()

        fail = false
        val second = sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), second)
        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun `a newer turn cannot pause while an older turn's restore is suspended`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(routeInvoker = withHold(flowOf(CloudRouteEvent.Connected("pcm_s16le@24k"), CloudRouteEvent.Done(1, 1))))
        val gate = CompletableDeferred<Unit>()
        deps.playback.resumeGate = gate
        val sink = deps.sink()

        // Turn A runs to completion, entering its restore — which suspends
        // inside resume() (play-queue load) while still owning the player.
        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.resumeStarted.await()

        // Turn B starts during that suspension: its registration and pause must
        // wait for A's restore to finish, or B's answer streams over resumed audio.
        val second = launch { sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        firstStarted.await()
        sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
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
    fun `a refusal with no detected language speaks the default line whatever the device locale`() = runTest {
        // The UI locale no longer decides (spec 54287f13): the line follows the UTTERANCE's
        // language. With no detected language the default (English) line is spoken — never an
        // earcon for a language reason, and never the device locale's wording.
        val deps = TestDeps(
            locale = java.util.Locale.KOREAN,
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `the no-gateway message follows the utterance language, not the device locale`() = runTest {
        val deps = TestDeps(baseUrl = "", locale = java.util.Locale.JAPANESE)

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Cloud processing is coming soon"), response)
    }

    @Test
    fun `a client code with a template is spoken in the localized wording`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        // Localized template, not the client's own prose and not the code.
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `a whitespace-only server message is treated as absent`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "   ")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        // Whitespace is not a message: it must resolve the localized template
        // rather than being spoken as silence.
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `a client code without a template falls back to the client generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "invalid_response", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

        // The whole point: the server's message is not spoken at all — it can carry upstream
        // detail, as the captured turn did — even when it is a sentence written for the user.
        // The code's own local line is spoken instead.
        assertEquals(VoiceResponse.Spoken("You have reached your Auris free daily limit. Please try again tomorrow."), response)
    }

    @Test
    fun `a whitespace-only template is not spoken`() = runTest {
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(mapOf("cloud_error_connection_lost" to "   ")),
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val outcome = runCatching { sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }

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

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val route = launch { deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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
            runCatching { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

        deps.sink().routeToCloud("pause it", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("stay put", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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
    fun `the terminal result carries the outcome the turn ACCUMULATED`() = runTest {
        // THE SINK'S OWN RETURN, which no case observed before: the accumulated outcome must LEAVE the
        // sink. The terminal return used to hardcode `None`, so every action a turn performed was
        // reported as "no action" to the caller -- the value was computed, merged, and then discarded at
        // the exit. Asserted at the RETURN, because that is where it was lost.
        val confirmed = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "pause",
                    params = emptyMap(),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )

        val confirmedResult = confirmed.sink().routeToCloud("pause", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)

        assertEquals(
            "a confirmed action must be reported as confirmed at the sink's own return",
            CloudRouteActionOutcome.Confirmed,
            confirmedResult.action,
        )

        // THE CONTROL: a turn with no action reports None, so the case is decided by the accumulated
        // value rather than by the return always claiming something.
        val none = TestDeps(
            events = flowOf(CloudRouteEvent.Done(1, 0)),
        )

        val noneResult = none.sink().routeToCloud("hello", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)

        assertEquals(
            CloudRouteActionOutcome.None,
            noneResult.action,
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

        val response = deps.sink().routeToCloud("go sideways", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("go back", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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
        val gracePeriodSignal: au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal? = null,
        private val events: kotlinx.coroutines.flow.Flow<CloudRouteEvent> = flowOf(CloudRouteEvent.Done(0, 0)),
        val renderer: CloudSearchResultsRenderer? = null,
        /** Whether the host app is playing while this turn runs. */
        var hostPlaying: Boolean = true,

        /** Play/pause/stop commands issued from outside the voice path. */
        var commandRevision: Long = 0,
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
                "cloud_error_limit_exceeded" to "You have reached your Auris free daily limit. Please try again tomorrow.",
                "cloud_error_budget_exceeded" to "The assistant has reached its limit right now.",
                "cloud_error_" + CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE to "I can't reach the podcast evidence right now.",
            ),
        ),
        routeInvoker: ((CloudRouteTurn) -> kotlinx.coroutines.flow.Flow<CloudRouteEvent>)? = null,
        transportFactory: ((String, okhttp3.OkHttpClient, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver?) -> au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransport)? = null,
    ) {
        /** The host's playback state, moved by the fake sink the way a player would. */
        val host = HostPlaybackState(hostPlaying)
        val playback = FakePlaybackSink(host)
        val analytics = FakeCloudRouteAnalytics()
        val routeCalls = mutableListOf<String>()
        val routeContexts = mutableListOf<CloudRouteContext>()
        val routeTurns = mutableListOf<CloudRouteTurn>()
        private val explicitInvoker = routeInvoker
        private val explicitTransportFactory = transportFactory

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
            routeInvoker = if (explicitTransportFactory != null && explicitInvoker == null) {
                // Transport tests need the real openRoute path; don't shadow with the default invoker.
                null
            } else {
                { turn: CloudRouteTurn ->
                    routeCalls += turn.request
                    routeContexts += turn.context
                    routeTurns += turn
                    explicitInvoker?.invoke(turn) ?: events
                }
            },
            searchResultsRenderer = renderer,
            templateResolver = templateResolver,
            currentLocale = { locale },
            isHostPlaying = { host.playing },
            playbackCommandRevision = { commandRevision },
            isPrivacyClosed = { privacyClosed },
            privacyClosureCount = { playbackStopClosures },
            episodeDurationSeconds = { episodeDurationSeconds },
            audioPlayer = audioPlayer,
            transportFactory = explicitTransportFactory,
            gracePeriodSignal = gracePeriodSignal,
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

        override suspend fun resume(expectedEpisodeId: String?): EpisodeOperationResult {
            resumeStarted.complete(Unit)
            resumeGate?.await()
            calls += "resume"
            host?.playing = true
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = preActionPositionMsForTest)
        }

        override suspend fun skipForward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult {
            skipOutcomeForTest?.let { return it }
            calls += "skipForward:$seconds"
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = preActionPositionMsForTest)
        }
        override suspend fun skipBackward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult {
            skipOutcomeForTest?.let { return it }
            calls += "skipBackward:$seconds"
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = preActionPositionMsForTest)
        }

        override suspend fun seekTo(
            positionSeconds: Int,
            episodeDurationSeconds: Int,
            expectedEpisodeId: String?,
        ): EpisodeOperationResult {
            // MIRRORS THE PRODUCTION REFUSAL. The fake used to record every call unconditionally, so a
            // mutation removing the episode binding left it unchanged and the case could not fail for
            // the defect. The binding is part of the contract now, so the fake honours it.
            seekOutcomeForTest?.let { return it }
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
            return EpisodeOperationResult.Accepted(expectedEpisodeId, preActionPositionMsForTest)
        }

        // Whether the sink is on the episode the operation was planned for. ABSENT MEANS "AGREE": the
        // ordinary case is that nothing changed, so a case has to say so explicitly when the player
        // moved to another episode. A default of "refuse" would make every unrelated case fail for a
        // reason that has nothing to do with what it is about.
        var currentEpisodeIdForTest: String? = null

        /** Overrides the outcome a seek reports, so a case can drive accepted/refused/unknown. */
        var seekOutcomeForTest: EpisodeOperationResult? = null

        /** Overrides the outcome a SKIP reports, on the same three-way contract. */
        var skipOutcomeForTest: EpisodeOperationResult? = null

        // The PRE-ACTION POSITION the owner would report. The production manager snapshots this at its
        // mutation, so the fake supplies it here: a case that asserts history must not rely on the
        // provider being read afterwards, because production no longer does that.
        var preActionPositionMsForTest: Long? = 60_000L

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
            val response = deps.sink().routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
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

        val route = launch { deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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
        // The closure was EARLIER -- a real closure history the count records -- and privacy has
        // since reopened (a wake clears the flag). Setting only the flag false here would make the
        // case indistinguishable from never-closed; the count is what gives "reopened" a past.
        deps.playbackStopClosures = 1
        deps.privacyClosed = false

        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        val turnA = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback

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
            runCatching { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
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

    @Test
    fun `dialog-completed cloud route dispatches the completing utterance identity through CloudTurnRoute`() = runTest {
        // DIALOG-TO-DISPATCH CONTROL: a dialog begins with utterance A, completes with
        // utterance B. The completed intent (no logicalRequestId) is stamped by the engine
        // with B, dispatched through the sink, and routed through a REAL CloudTurnRoute.
        // The dispatched CloudRouteTurn.requestId must be B.
        // NOTE: Full Moshi serialization verification requires CloudRouteJson which is
        // internal to repositories. See CloudTurnRouteTest for the encoded JSON control.
        val admittedA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val admittedB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

        // Step 1: Real dialog manager — begin with A, complete with B.
        val dialogManager = au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager(
            au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper(),
        )
        assertNull(
            dialogManager.resolve(
                "Ask about weather.",
                "<start_function_call>call:dialog_control{action:<escape>begin<escape>}<end_function_call>",
                au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall(
                    name = "dialog_control",
                    action = "begin",
                    params = mapOf("target_tool" to "cloud_route", "target_action" to "route"),
                ),
                admittedId = admittedA,
            ),
        )
        assertEquals(admittedA, dialogManager.pendingDialog?.logicalRequestId)

        // Complete the dialog with utterance B.
        val completedIntent = dialogManager.resolve(
            "What is the weather tomorrow?",
            "<start_function_call>call:dialog_control{action:<escape>provide_slot<escape>}<end_function_call>",
            au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall(
                name = "dialog_control",
                action = "provide_slot",
                params = mapOf("slot" to "request", "value" to "What is the weather tomorrow?"),
            ),
            admittedId = admittedB,
        )
        assertNotNull("dialog completed", completedIntent)

        // Step 2: Engine stamps the completing utterance's admitted identity.
        val stamped = completedIntent!!.withLogicalRequest(admittedB)
        val stampedCloud = stamped as VoiceIntent.CloudRoute
        assertEquals("engine stamped B onto the dialog-completed intent", admittedB, stampedCloud.logicalRequestId)

        // Step 3: Wire the sink's routeInvoker to a REAL CloudTurnRoute with a capturing
        // transport, so the dispatched turn goes through the actual route creation path.
        val capturingTransport = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransport {
            val frames = mutableListOf<au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame>()
            override fun exchange(frame: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                frames += frame
                emit(CloudRouteEvent.Done(1, 0))
            }
        }
        val realRoute = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnRoute(
            baseUrl = "https://cloud.example.com",
            tokenProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding {
                override suspend fun currentToken(): String = "test-token"
                override suspend fun refreshToken() = Unit
            },
            transport = capturingTransport,
        )
        val deps = TestDeps(
            routeInvoker = { turn -> realRoute.route(turn) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(stampedCloud.windowGeneration ?: 0L)
        val prepared = sink.prepareTurn(
            request = stampedCloud.request,
            tier = stampedCloud.tier,
            context = playbackContext,
            isLiveGraceTurn = false,
            capturedOrigin = stampedCloud.windowGeneration ?: 0L,
            isWakeOriginated = false,
            hint = null,
            logicalRequestId = stampedCloud.logicalRequestId,
            isRetry = false,
            sourceLanguage = null,
        )!!

        // Step 4: Dispatch through the sink → CloudTurnRoute → transport frame.
        sink.dispatchPrepared(prepared)
        assertEquals("exactly one turn dispatched", 1, deps.routeTurns.size)
        assertEquals(
            "dispatched turn's request_id is B",
            admittedB,
            deps.routeTurns.single().requestId,
        )

        // Step 5: Verify the CloudTurnFrame.Authenticate created by CloudTurnRoute carries B.
        assertEquals("one frame exchanged", 1, capturingTransport.frames.size)
        val authFrame = capturingTransport.frames.single() as au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame.Authenticate
        assertEquals(
            "Authenticate frame's requestId is B (the completing utterance)",
            admittedB,
            authFrame.requestId,
        )
        assertNotEquals(
            "Authenticate frame must NOT carry the begin identity",
            admittedA,
            authFrame.requestId,
        )
    }

    /**
     * COMPOSITION CONTROL (spec e452d3b7): a pre-Connected limit refusal must carry the SAME
     * request ID through the real transport's open/send/decode wiring, the persisted diagnostics
     * flush, the service's feedback dispatch, and the renderer's own observation. Fails if the
     * service drops the ID or never invokes the renderer.
     */
    @Test
    fun `a refusal speaks the utterance language, not the device locale`() = kotlinx.coroutines.runBlocking {
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(
                templates = mapOf(
                    "cloud_error_limit_exceeded" to "You have reached your Auris free daily limit. Please try again tomorrow.",
                    "cloud_error_generic" to "Sorry, I couldn't complete that request.",
                ),
                languageTemplates = mapOf(
                    "zh" to mapOf("cloud_error_limit_exceeded" to "你已达到 Auris 的每日免费额度，请明天再试。"),
                ),
            ),
            routeInvoker = { _ ->
                kotlinx.coroutines.flow.flowOf(
                    CloudRouteEvent.Error(code = CloudRouteErrorCodes.LIMIT_EXCEEDED, message = ""),
                )
            },
        )
        val sink = deps.sink()
        // Production shape: ASR detected Chinese; the ROUTING transcript is English; the turn
        // carries the detected language. The refusal must be spoken in Chinese.
        val prepared = sink.prepareTurn(
            request = "播放一些东西",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            isLiveGraceTurn = false,
            capturedOrigin = 1,
            isWakeOriginated = true,
            hint = null,
            logicalRequestId = testWireId,
            isRetry = false,
            sourceLanguage = "zh",
        )!!
        val result = sink.dispatchPrepared(prepared, isRetry = false)
        assertEquals("你已达到 Auris 的每日免费额度，请明天再试。", (result.feedback as VoiceResponse.Spoken).text)
        // The reported feedback language must MATCH the text that was spoken.
        assertEquals("zh", result.feedbackLanguage)
    }

    @Test
    fun `a refusal for an unsupported language speaks English text with an English voice`() = kotlinx.coroutines.runBlocking {
        // REAL RESOURCE CONTROL (reviewer 8004f444): the resolver is built from the module's
        // actual locale resources, on a KOREAN UI context — so a UI-locale leak or an
        // unsupported-language voice mismatch fails here, not behind custom maps.
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
            locale = java.util.Locale.KOREAN,
            routeInvoker = { _ ->
                kotlinx.coroutines.flow.flowOf(
                    CloudRouteEvent.Error(code = CloudRouteErrorCodes.LIMIT_EXCEEDED, message = ""),
                )
            },
        )
        val sink = deps.sink()
        val prepared = sink.prepareTurn(
            request = "something unclassifiable",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            isLiveGraceTurn = false,
            capturedOrigin = 1,
            isWakeOriginated = true,
            hint = null,
            logicalRequestId = testWireId,
            isRetry = false,
            sourceLanguage = "xx",
        )!!
        val result = sink.dispatchPrepared(prepared, isRetry = false)
        assertEquals(
            "You have reached your Auris free daily limit. Please try again tomorrow.",
            (result.feedback as VoiceResponse.Spoken).text,
        )
        assertEquals("en", result.feedbackLanguage)
    }

    @Test
    fun `an explicit English utterance on a Chinese UI speaks English with an English voice`() = kotlinx.coroutines.runBlocking {
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
            locale = java.util.Locale.CHINESE,
            routeInvoker = { _ ->
                kotlinx.coroutines.flow.flowOf(
                    CloudRouteEvent.Error(code = CloudRouteErrorCodes.LIMIT_EXCEEDED, message = ""),
                )
            },
        )
        val sink = deps.sink()
        val prepared = sink.prepareTurn(
            request = "play something",
            tier = VoiceIntent.CloudTier.Premium,
            context = playbackContext,
            isLiveGraceTurn = false,
            capturedOrigin = 1,
            isWakeOriginated = true,
            hint = null,
            logicalRequestId = testWireId,
            isRetry = false,
            sourceLanguage = "en",
        )!!
        val result = sink.dispatchPrepared(prepared, isRetry = false)
        assertEquals(
            "You have reached your Auris free daily limit. Please try again tomorrow.",
            (result.feedback as VoiceResponse.Spoken).text,
        )
        assertEquals("en", result.feedbackLanguage)
    }

    @Test
    fun `pre-Connected refusal joins request ID across transport, persisted log, service and renderer`() = kotlinx.coroutines.runBlocking {
        val capture = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture()
        try {
            val wireClient = CapturingWebSocketClient()
            // A LIVE grace window: the refusal path acquires its hold here, and the assertions below
            // track it across sink return -> renderer output -> release.
            val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 5_000L)
            graceSignal.onWakeWordDetected()
            val graceWindow = graceSignal.capturedGeneration()
            // REAL RESOURCE CONTROL (reviewer 8004f444 / spec ruling 7c7575f6): the resolver is
            // built from the module's actual locale resources — no custom language maps — so the
            // zh line below must come from values-zh through the real lookup.
            val deps = TestDeps(
                templateResolver = SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
                gracePeriodSignal = graceSignal,
                transportFactory = { _, _, observer ->
                    au.com.shiftyjelly.pocketcasts.repositories.cloud.WebSocketCloudTurnTransport(
                        "https://example.invalid",
                        wireClient,
                        observer,
                    )
                },
            )
            val sink = deps.sink()

            // REAL executor composition (spec f980336e): the service dispatch runs the production
            // VoicePlaybackIntentExecutor, which prepares the turn on the REAL sink — carrying the
            // intent's logical request ID — and dispatches it over the REAL transport. The sinks the
            // CloudRoute branch never touches are mocks; the executor, sink, service and renderer are
            // real.
            val earconPlayer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer>()
            // CONTROLLABLY SUSPENDED BACKEND (spec c9c37325): the gate holds speak() in flight so the
            // control proves the hold survives BOTH sink return and in-flight output, releasing only
            // when the awaited TTS call actually returns (true output completion).
            val tts = au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine().apply {
                speakGate = kotlinx.coroutines.CompletableDeferred()
                speakReturned = kotlinx.coroutines.CompletableDeferred()
            }
            val renderer = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer(
                earconPlayer = earconPlayer,
                ttsEngine = tts,
                gracePeriodSignal = graceSignal,
            )
            renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
            val executor = VoicePlaybackIntentExecutor(
                playbackSink = mock(),
                effectsSink = mock(),
                volumeSink = mock(),
                sleepSink = mock(),
                chapterSink = mock(),
                bookmarkSink = mock(),
                cloudRouteSink = sink,
                playbackContextProvider = object : PlaybackContextProvider {
                    override fun current() = playbackContext
                },
                queueSink = mock(),
                playbackQuerySink = mock(),
                statsQuerySink = mock(),
                gracePeriodSignal = graceSignal,
                playbackManager = mock(),
            )
            val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().apply {
                audioFeedbackRenderer = renderer
                voicePlaybackIntentExecutor = executor
            }

            // Scheduling (spec 7b9f3228): the service dispatch runs on a detached event loop; a REAL
            // driver THREAD fires the transport's own listener callbacks, so main never blocks
            // waiting for main-bound work.
            val dispatched = CompletableDeferred<Unit>()
            val driver = Thread {
                try {
                    val deadline = System.currentTimeMillis() + 5_000
                    while (wireClient.listener == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    val upgrading = mock<okhttp3.Response>().also { whenever(it.code).thenReturn(101) }
                    wireClient.listener!!.onOpen(wireClient.socket, upgrading)
                    while (wireClient.sendCalls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    wireClient.listener!!.onMessage(
                        wireClient.socket,
                        """{"type":"error","code":"limit_exceeded","message":""}""",
                    )
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            // Detached scope: the turn's job topology (the sink treats its caller's Job as the
            // turn's) is not the fact under test — the ID join is.
            val turnScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val turn = turnScope.launch {
                try {
                    kotlinx.coroutines.withTimeout(8_000) {
                        service.renderWithFailureHandling(
                            VoiceIntent.CloudRoute(
                                request = "question",
                                tier = VoiceIntent.CloudTier.Premium,
                                logicalRequestId = testWireId,
                                windowGeneration = graceWindow,
                                // NON-ENGLISH ASR: the detected utterance language rides the intent
                                // while the routing transcript stays English — the production shape
                                // for "user spoke Chinese, pipeline routed in English".
                                sourceLanguage = "zh",
                            ),
                        )
                    }
                    dispatched.complete(Unit)
                } catch (t: Throwable) {
                    dispatched.completeExceptionally(t)
                }
            }
            driver.start()
            val done = runCatching { kotlinx.coroutines.withTimeout(15_000) { dispatched.await() } }.getOrNull()
            assertNotNull("service dispatch through the REAL executor must complete", done)
            turn.join()
            turnScope.cancel()
            driver.join()

            // PERSISTED transport events, joined to the request ID, observed through the real flush.
            awaitCaptureLine(capture, "reason=TurnSocketOpened", testWireId)
            awaitCaptureLine(capture, "SEND_ATTEMPTED", testWireId)
            awaitCaptureLine(capture, "reason=TurnTerminalReceived", testWireId, alsoContains = "limit_exceeded")

            // RENDERER observation: entry AND accepted submission, SAME request ID. If the executor
            // drops the ID or the service never invokes the renderer, neither line appears.
            // Submission lands only after the gated speak() finishes, so the hold assertions below
            // run FIRST, with output genuinely in flight.
            awaitCaptureLine(capture, "reason=TurnFeedbackSelected", testWireId, alsoContains = "entry_spoken")
            // THE OUTPUT-HOLD CONTRACT (spec ruling 7a6a62e3, reviewer73ea2ad5): the hold survives
            // SINK RETURN (dispatch already completed) and IN-FLIGHT OUTPUT (speak() still gated),
            // and releases EXACTLY ONCE when the awaited TTS call truly returns.
            assertTrue(
                "the hold must still be pending while output is in flight (survived sink return)",
                graceSignal.hasOutputHold(testWireId),
            )
            // A live hold keeps the window open past its own timeout while the refusal is sounding.
            Thread.sleep(400)
            assertTrue("in-flight refusal output must hold the grace window open", graceSignal.isActive.value)

            tts.speakGate?.complete(Unit)
            kotlin.runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(5_000) { tts.speakReturned?.await() }
                }
            }
            awaitCaptureLine(capture, "reason=TurnFeedbackRendered", testWireId, alsoContains = "spoken_submitted")
            // The SUBSTANCE must be the UTTERANCE-LANGUAGE Auris daily-limit explanation — not any
            // generic spoken error, not the English default — delivered EXACTLY ONCE, in the
            // language the user spoke, through the real TTS.
            assertEquals("你已达到 Auris 的每日免费额度，请明天再试。", tts.lastSpokenText)
            assertEquals("zh", tts.lastSpokenLanguage)
            val submissions = capture.lines().count {
                it.contains("reason=TurnFeedbackRendered") && it.contains("spoken_submitted") && it.contains("request_id=$testWireId")
            }
            assertEquals("the localized refusal must be submitted exactly once", 1, submissions)
            // Wait out the window timeout AFTER the true output terminal: the release must have
            // happened, so the window now expires instead of being held forever (no leak). The
            // signal arms its countdown on the Robolectric main looper, so the clock is advanced
            // there rather than slept.
            val shadowLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            var expiryTicks = 0
            while (graceSignal.isActive.value && expiryTicks < 50) {
                shadowLooper.runToEndOfTasks()
                expiryTicks++
            }
            assertFalse("the hold must release at the true output terminal", graceSignal.isActive.value)
            assertFalse("exactly-once: no output hold remains", graceSignal.hasOutputHold(testWireId))
            renderer.release()
        } finally {
            capture.close()
        }
    }

    @Test
    fun `a TTS failure mid-render releases the transferred hold exactly once`() = kotlinx.coroutines.runBlocking {
        val capture = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture()
        try {
            val wireClient = CapturingWebSocketClient()
            val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 5_000L)
            graceSignal.onWakeWordDetected()
            val graceWindow = graceSignal.capturedGeneration()
            val deps = TestDeps(
                templateResolver = SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
                gracePeriodSignal = graceSignal,
                transportFactory = { _, _, observer ->
                    au.com.shiftyjelly.pocketcasts.repositories.cloud.WebSocketCloudTurnTransport(
                        "https://example.invalid",
                        wireClient,
                        observer,
                    )
                },
            )
            val sink = deps.sink()
            val earconPlayer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer>()
            // GATED + FAILING backend: speak suspends until the gate completes, then throws — so the
            // control proves the hold survives in-flight render AND releases at the failure terminal.
            val tts = au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine().apply {
                speakGate = kotlinx.coroutines.CompletableDeferred()
                speakFailure = java.lang.RuntimeException("tts engine fault")
            }
            val renderer = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer(
                earconPlayer = earconPlayer,
                ttsEngine = tts,
                gracePeriodSignal = graceSignal,
            )
            renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
            val executor = VoicePlaybackIntentExecutor(
                playbackSink = mock(), effectsSink = mock(), volumeSink = mock(), sleepSink = mock(),
                chapterSink = mock(), bookmarkSink = mock(), cloudRouteSink = sink,
                playbackContextProvider = object : PlaybackContextProvider {
                    override fun current() = playbackContext
                },
                queueSink = mock(), playbackQuerySink = mock(), statsQuerySink = mock(),
                gracePeriodSignal = graceSignal, playbackManager = mock(),
            )
            val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().apply {
                audioFeedbackRenderer = renderer
                voicePlaybackIntentExecutor = executor
            }
            val dispatched = CompletableDeferred<Unit>()
            val driver = Thread {
                try {
                    val deadline = System.currentTimeMillis() + 5_000
                    while (wireClient.listener == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    val upgrading = mock<okhttp3.Response>().also { whenever(it.code).thenReturn(101) }
                    wireClient.listener!!.onOpen(wireClient.socket, upgrading)
                    while (wireClient.sendCalls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    wireClient.listener!!.onMessage(
                        wireClient.socket,
                        """{"type":"error","code":"limit_exceeded","message":""}""",
                    )
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            val turnScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val turn = turnScope.launch {
                try {
                    kotlinx.coroutines.withTimeout(8_000) {
                        service.renderWithFailureHandling(
                            VoiceIntent.CloudRoute(
                                request = "question",
                                tier = VoiceIntent.CloudTier.Premium,
                                logicalRequestId = testWireId,
                                windowGeneration = graceWindow,
                                sourceLanguage = "zh",
                            ),
                        )
                    }
                    dispatched.complete(Unit)
                } catch (t: Throwable) {
                    dispatched.completeExceptionally(t)
                }
            }
            driver.start()
            val done = runCatching { kotlinx.coroutines.withTimeout(15_000) { dispatched.await() } }.getOrNull()
            assertNotNull("service dispatch must complete even when TTS fails", done)
            turn.join()
            turnScope.cancel()
            driver.join()

            // The hold is pending while TTS is suspended (in flight).
            assertTrue("hold must be pending while render is in flight", graceSignal.hasOutputHold(testWireId))
            // Let the gated speak proceed into its configured failure.
            tts.speakGate?.complete(Unit)
            val releaseDeadline = System.currentTimeMillis() + 8_000
            while (graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < releaseDeadline) Thread.sleep(50)
            assertTrue("the failure terminal must have released the hold", !graceSignal.hasOutputHold(testWireId))
            // Wait out the window timeout AFTER the failure terminal: the release must have happened.
            val shadowLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            var expiryTicks = 0
            while (graceSignal.isActive.value && expiryTicks < 50) {
                shadowLooper.runToEndOfTasks()
                expiryTicks++
            }
            assertFalse("the hold must release at the TTS failure terminal", graceSignal.isActive.value)
            assertFalse("exactly-once: no output hold remains after failure", graceSignal.hasOutputHold(testWireId))
            renderer.release()
        } finally {
            capture.close()
        }
    }

    @Test
    fun `a cancelled render before acceptance releases the transferred hold via the service`() = kotlinx.coroutines.runBlocking {
        val capture = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture()
        try {
            val wireClient = CapturingWebSocketClient()
            val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 5_000L)
            graceSignal.onWakeWordDetected()
            val graceWindow = graceSignal.capturedGeneration()
            val deps = TestDeps(
                templateResolver = SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
                gracePeriodSignal = graceSignal,
                transportFactory = { _, _, observer ->
                    au.com.shiftyjelly.pocketcasts.repositories.cloud.WebSocketCloudTurnTransport(
                        "https://example.invalid",
                        wireClient,
                        observer,
                    )
                },
            )
            val sink = deps.sink()
            val earconPlayer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer>()
            // PERMANENTLY GATED backend: speak never returns, so cancelling the render scope models
            // a session-stop or supersession that arrives BEFORE the renderer accepts ownership.
            val tts = au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine().apply {
                speakGate = kotlinx.coroutines.CompletableDeferred()
            }
            val renderer = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer(
                earconPlayer = earconPlayer,
                ttsEngine = tts,
                gracePeriodSignal = graceSignal,
            )
            renderer.scopeDispatcherForTest = kotlinx.coroutines.Dispatchers.Default
            val executor = VoicePlaybackIntentExecutor(
                playbackSink = mock(), effectsSink = mock(), volumeSink = mock(), sleepSink = mock(),
                chapterSink = mock(), bookmarkSink = mock(), cloudRouteSink = sink,
                playbackContextProvider = object : PlaybackContextProvider {
                    override fun current() = playbackContext
                },
                queueSink = mock(), playbackQuerySink = mock(), statsQuerySink = mock(),
                gracePeriodSignal = graceSignal, playbackManager = mock(),
            )
            val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().apply {
                audioFeedbackRenderer = renderer
                voicePlaybackIntentExecutor = executor
            }
            val dispatched = CompletableDeferred<Unit>()
            val driver = Thread {
                try {
                    val deadline = System.currentTimeMillis() + 5_000
                    while (wireClient.listener == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    val upgrading = mock<okhttp3.Response>().also { whenever(it.code).thenReturn(101) }
                    wireClient.listener!!.onOpen(wireClient.socket, upgrading)
                    while (wireClient.sendCalls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    wireClient.listener!!.onMessage(
                        wireClient.socket,
                        """{"type":"error","code":"limit_exceeded","message":""}""",
                    )
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            val turnScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val turn = turnScope.launch {
                try {
                    kotlinx.coroutines.withTimeout(8_000) {
                        service.renderWithFailureHandling(
                            VoiceIntent.CloudRoute(
                                request = "question",
                                tier = VoiceIntent.CloudTier.Premium,
                                logicalRequestId = testWireId,
                                windowGeneration = graceWindow,
                                sourceLanguage = "zh",
                            ),
                        )
                    }
                    dispatched.complete(Unit)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    // Expected: we cancel below.
                    dispatched.complete(Unit)
                } catch (t: Throwable) {
                    dispatched.completeExceptionally(t)
                }
            }
            driver.start()
            // Wait for the render to start (hold transferred, TTS suspended).
            val startedDeadline = System.currentTimeMillis() + 5_000
            while (!graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < startedDeadline) Thread.sleep(20)
            assertTrue("hold must transfer before cancellation", graceSignal.hasOutputHold(testWireId))
            // SESSION TEARDOWN mid-render (reviewer 664f82d0's cancel case): release() cancels the
            // in-flight invocation, whose catch releases the hold at its actual terminal.
            renderer.release()
            turn.join()
            driver.join()
            val releaseDeadline = System.currentTimeMillis() + 8_000
            while (graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < releaseDeadline) Thread.sleep(50)
            assertFalse("the cancellation terminal must have released the hold", graceSignal.hasOutputHold(testWireId))
            // After the cancellation release, the window should expire on the looper.
            val shadowLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            var expiryTicks = 0
            while (graceSignal.isActive.value && expiryTicks < 50) {
                shadowLooper.runToEndOfTasks()
                expiryTicks++
            }
            assertFalse("the hold must release when render is cancelled before acceptance", graceSignal.isActive.value)
            assertFalse("no output hold remains after cancel-before-accept", graceSignal.hasOutputHold(testWireId))
            renderer.release()
        } finally {
            capture.close()
        }
    }

    @Test
    fun `cancellation at the execute-to-handoff boundary releases the transferred hold`() = kotlinx.coroutines.runBlocking {
        // The gap (reviewer b25287c6): the sink transferred the hold, then CancellationException
        // wins at the execute return — the render handoff never runs, so the service's execute
        // cancellation catch must release the transferred hold before rethrowing.
        val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 5_000L)
        graceSignal.onWakeWordDetected()
        val executor = org.mockito.Mockito.mock(VoicePlaybackIntentExecutor::class.java)
        org.mockito.kotlin.whenever(executor.execute(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenAnswer {
                // Simulate the sink's completed transfer, then the caller withdrawing.
                val intent = it.getArgument<VoiceIntent.CloudRoute>(0)
                val hold = graceSignal.holdCloudTurn(owner = 3L)!!
                graceSignal.transferHoldToOutput(intent.logicalRequestId ?: testWireId, hold)
                throw kotlinx.coroutines.CancellationException("caller withdrew at the boundary")
            }
        val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().apply {
            voicePlaybackIntentExecutor = executor
            audioFeedbackRenderer = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer(
                mock(),
                au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine(),
                graceSignal,
            )
            gracePeriodSignal = graceSignal
        }
        var releasedThenRethrown = false
        try {
            service.renderWithFailureHandling(
                VoiceIntent.CloudRoute(request = "q", tier = VoiceIntent.CloudTier.Premium, logicalRequestId = testWireId),
            )
        } catch (expected: kotlinx.coroutines.CancellationException) {
            releasedThenRethrown = !graceSignal.hasOutputHold(testWireId)
        }
        assertTrue("cancellation must rethrow after releasing the transferred hold", releasedThenRethrown)
    }

    @Test
    fun `local playback failure derives succeeded false even with Done Completed`() = runTest {
        // Drain reporting Unconsumed is the sink's PRODUCTION local-failure path (the head never
        // reached the submitted frames), so localPlaybackFailed is set through real wiring — not a
        // test hook.
        val failPlayer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
        whenever(failPlayer.audioWritten).thenReturn(true)
        whenever(failPlayer.drainAndAwaitCompletion()).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer.Completion.Unconsumed)
        val deps2 = TestDeps(
            audioPlayer = failPlayer,
            events = flowOf(
                CloudRouteEvent.Connected("pcm_s16le@24k"),
                CloudRouteEvent.AudioFrame("pcm_s16le@24k", byteArrayOf(1, 2)),
                CloudRouteEvent.Done(3, 4, CloudRouteEvent.Outcome.Completed),
            ),
        )
        val result2 = deps2.sink().routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
        assertEquals("local playback failure must be reported as an error earcon", VoiceResponse.Earcon(EarconId.ERROR), result2.feedback)
        assertFalse("local playback failure must derive succeeded=false", result2.succeeded)
    }

    /** Deadline-bounded persisted-line lookup by reason and request ID, with an optional extra token. */
    private suspend fun awaitCaptureLine(
        capture: au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture,
        reasonToken: String,
        requestId: String,
        alsoContains: String? = null,
    ): String = kotlinx.coroutines.withTimeout(5_000) {
        var found: String? = null
        while (found == null) {
            found = capture.lines().firstOrNull {
                it.contains(reasonToken) && it.contains("request_id=$requestId") && (alsoContains == null || it.contains(alsoContains))
            }
            if (found == null) delay(10)
        }
        found
    }

    /** okhttp client whose newWebSocket captures the listener the REAL transport installs. */
    private class CapturingWebSocketClient : okhttp3.OkHttpClient() {
        var listener: okhttp3.WebSocketListener? = null
            private set
        val socket: okhttp3.WebSocket = mock<okhttp3.WebSocket>().also { s ->
            whenever(s.send(org.mockito.kotlin.any<String>())).thenAnswer { inv ->
                sendCalls += inv.getArgument<String>(0)
                true
            }
        }
        val sendCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun newWebSocket(request: okhttp3.Request, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
            this.listener = listener
            return socket
        }
    }

    /** Records transport observer invocations for test assertions. */
    private class RecordingObserver : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver {
        val events = mutableListOf<String>()
        override fun onAuthResolved(requestId: String, outcome: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.AuthOutcome) {
            events += "auth:${outcome.name}"
        }
        override fun onSocketOpened(requestId: String) {
            events += "socket_opened"
        }
        override fun onSendAttempted(requestId: String) {
            events += "send_attempted"
        }
        override fun onSendResult(requestId: String, outcome: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.SendOutcome) {
            events += "send:${outcome.name}"
        }
        override fun onTerminalReceived(requestId: String, origin: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.TerminalOrigin, errorCode: String?) {
            events += "terminal:${origin.name}:$errorCode"
        }
        override fun onConnectionFailed(requestId: String) {
            events += "connection_failed"
        }
    }

    @Test
    fun `refusal before Connected fires connection failure, no server terminal, and localized feedback`() = runTest {
        val recording = RecordingObserver()
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(
                mapOf(
                    "cloud_error_" + CloudRouteErrorCodes.CONNECTION_LOST to "Connection lost. Please try again.",
                    "general.cloud_coming_soon" to "Cloud processing is coming soon",
                    "general.cloud_incomplete_turn" to "Sorry, I couldn't complete that request.",
                    "cloud_error_generic" to "Sorry, I couldn't complete that request.",
                ),
            ),
            transportFactory = { _, _, observer ->
                object : CloudTurnTransport {
                    override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                        val rid = (frame as CloudTurnFrame.Authenticate).requestId
                        assertEquals(testWireId, rid)
                        // Auth resolved, then the handshake fails — never Connected.
                        observer?.onAuthResolved(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.AuthOutcome.RESOLVED)
                        observer?.onConnectionFailed(rid)
                        recording.onAuthResolved(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.AuthOutcome.RESOLVED)
                        recording.onConnectionFailed(rid)
                        emit(CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""))
                    }
                }
            },
        )
        val sink = deps.sink()
        val result = sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)

        // Transport facts: auth resolved, connection failed, NO server terminal, NO socket opened.
        assertEquals(listOf("auth:RESOLVED", "connection_failed"), recording.events)

        // Localized refusal feedback was selected (Spoken, not Silent).
        val feedback = result.feedback
        assertTrue("expected localized Spoken refusal, got $feedback", feedback is VoiceResponse.Spoken)
        assertEquals("Connection lost. Please try again.", (feedback as VoiceResponse.Spoken).text)
    }

    @Test
    fun `local missing-token produces localized unauthorized feedback without transport exchange`() = runTest {
        var exchangeCalled = false
        val deps = TestDeps(
            userId = "", // No token.
            templateResolver = SpokenTemplateResolver(
                mapOf(
                    "cloud_error_" + CloudRouteErrorCodes.UNAUTHORIZED to "Service is unavailable at the moment.",
                    "general.cloud_coming_soon" to "Cloud processing is coming soon",
                    "general.cloud_incomplete_turn" to "Sorry, I couldn't complete that request.",
                    "cloud_error_generic" to "Sorry, I couldn't complete that request.",
                ),
            ),
            transportFactory = { _, _, _ ->
                object : CloudTurnTransport {
                    override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                        exchangeCalled = true
                    }
                }
            },
        )
        val sink = deps.sink()
        val result = sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)

        // Transport exchange never reached — missing token stops before socket.
        assertFalse("exchange should not be called when token is missing", exchangeCalled)

        // Localized unauthorized feedback, not silence.
        val feedback = result.feedback
        assertTrue("expected localized Spoken refusal, got $feedback", feedback is VoiceResponse.Spoken)
        assertEquals("Service is unavailable at the moment.", (feedback as VoiceResponse.Spoken).text)
    }

    @Test
    fun `server refusal before render carries limit_exceeded terminal and localized feedback`() = runTest {
        val recording = RecordingObserver()
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(
                mapOf(
                    "cloud_error_limit_exceeded" to "You have reached your Auris free daily limit. Please try again tomorrow.",
                    "general.cloud_coming_soon" to "Cloud processing is coming soon",
                    "general.cloud_incomplete_turn" to "Sorry, I couldn't complete that request.",
                    "cloud_error_generic" to "Sorry, I couldn't complete that request.",
                ),
            ),
            transportFactory = { _, _, observer ->
                object : CloudTurnTransport {
                    override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                        val rid = (frame as CloudTurnFrame.Authenticate).requestId
                        observer?.onAuthResolved(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.AuthOutcome.RESOLVED)
                        observer?.onSocketOpened(rid)
                        observer?.onSendAttempted(rid)
                        observer?.onSendResult(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.SendOutcome.ACCEPTED)
                        recording.onAuthResolved(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.AuthOutcome.RESOLVED)
                        recording.onSocketOpened(rid)
                        recording.onSendAttempted(rid)
                        recording.onSendResult(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.SendOutcome.ACCEPTED)
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        // Server refusal terminal AT DECODE.
                        observer?.onTerminalReceived(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.TerminalOrigin.SERVER_ERROR, "limit_exceeded")
                        recording.onTerminalReceived(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.TerminalOrigin.SERVER_ERROR, "limit_exceeded")
                        emit(CloudRouteEvent.Error(code = "limit_exceeded", message = ""))
                    }
                }
            },
        )
        val sink = deps.sink()
        val result = sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)

        assertEquals(
            listOf("auth:RESOLVED", "socket_opened", "send_attempted", "send:ACCEPTED", "terminal:SERVER_ERROR:limit_exceeded"),
            recording.events,
        )
        val feedback = result.feedback
        assertTrue("expected localized Spoken limit refusal, got $feedback", feedback is VoiceResponse.Spoken)
        assertEquals(
            "You have reached your Auris free daily limit. Please try again tomorrow.",
            (feedback as VoiceResponse.Spoken).text,
        )
    }
}
