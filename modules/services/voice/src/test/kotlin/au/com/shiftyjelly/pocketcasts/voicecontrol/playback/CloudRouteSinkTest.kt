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
        val successor = sink.prepareTurn("new question", VoiceIntent.CloudTier.Premium, playbackContext, capturedOrigin = 2, logicalRequestId = "b2f5e9a1-7c34-4d80-9e11-6a4b8c0d2f35")!!
        val newOwner = async { sink.dispatchPrepared(successor) }
        entered.receive()
        val duplicate = async { sink.dispatchPrepared(successor) }
        try {
            withTimeout(1_000) { oldOwner.join() }
            assertTrue(oldOwner.isCancelled)
            org.junit.Assert.assertSame(successor, sink.retainedTurnFor("b2f5e9a1-7c34-4d80-9e11-6a4b8c0d2f35"))
            assertNotEquals(first.requestId, successor.requestId)
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
        val retired = deps.conversationAdmission.beginOrigin(2)
        assertTrue(
            "advancing the window retires the superseded request's turn (retired=$retired)",
            retired.contains("ordinary-1"),
        )
        assertNull(
            "and it is no longer retained",
            deps.conversationAdmission.retained("ordinary-1"),
        )
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
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
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
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        sink.routeToCloud(
            "first window",
            VoiceIntent.CloudTier.Premium,
            playbackContext,
            admitted = testWireId,
            capturedOrigin = 1,
            isWakeOriginated = true,
        ).feedback
        assertEquals(1, deps.routeTurns.size)
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
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
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
            deps.playback.currentEpisodeIdForTest = "B"
            val sink = deps.sink()
            sink.routeToCloud(
                "about episode A",
                VoiceIntent.CloudTier.Premium,
                PlaybackContext(episodeId = "A", clientPositionMs = 0L),
                admitted = testWireId,
            ).feedback
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
        deps.selectEpisodeForTest("B")
        deps.playback.currentEpisodeIdForTest = "B"
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
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)
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
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        deps.cloudPlaybackContextState.record(
            referencePositionMs = 100L,
            previousReferencePositionMs = 300L,
        )
        val sink = deps.sink()
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
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
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
    fun `a superseding turn restores the chain's duck and stops the buffered audio even when silent`() = runTest {
        val player = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
        whenever(player.audioWritten).thenReturn(true)
        val firstTurnReachedFrame = CompletableDeferred<Unit>()
        var routeCall = 0
        val deps = TestDeps(
            audioPlayer = player,
            routeInvoker = { _ ->
                if (routeCall++ == 0) {
                    kotlinx.coroutines.flow.flow {
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        emit(CloudRouteEvent.AudioFrame("pcm_s16le@24k", byteArrayOf(1, 2)))
                        firstTurnReachedFrame.complete(Unit)
                        awaitCancellation()
                    }
                } else {
                    kotlinx.coroutines.flow.flowOf(
                        CloudRouteEvent.Connected("pcm_s16le@24k"),
                        CloudRouteEvent.Done(1, 1),
                    )
                }
            },
        )
        val sink = deps.sink()
        val turnA = launch {
            runCatching {
                sink.routeToCloud("first question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
            }
        }
        withTimeout(5_000) {
            firstTurnReachedFrame.await()
            kotlinx.coroutines.yield()
        }
        assertTrue("the predecessor ducked the host on its first frame", deps.playback.calls.contains("duck"))
        sink.routeToCloud("second question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
        withTimeout(5_000) { turnA.join() }
        turnA.cancel()
        assertTrue(
            "the successor's finally must restore the chain's duck (calls=${deps.playback.calls})",
            deps.playback.calls.contains("restore"),
        )
        org.mockito.kotlin.verify(player, org.mockito.kotlin.atLeastOnce()).stop()
    }
    @Test
    fun `an in-flight frame mutation completes before the successor's transfer stop, never after`() = runTest {
        val player = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
        whenever(player.audioWritten).thenReturn(true)
        val order = java.util.concurrent.ConcurrentLinkedQueue<String>()
        whenever(player.play()).thenAnswer {
            order.add("play")
            Unit
        }
        whenever(player.stop()).thenAnswer {
            order.add("stop")
            Unit
        }
        val firstTurnConnected = CompletableDeferred<Unit>()
        val duckPaused = java.util.concurrent.CountDownLatch(1)
        val frame = CloudRouteEvent.AudioFrame("pcm_s16le@24k", byteArrayOf(1, 2))
        val events = { list: List<CloudRouteEvent> -> kotlinx.coroutines.flow.flow { list.forEach { emit(it) } } }
        var routeCall = 0
        val deps = TestDeps(
            audioPlayer = player,
            routeInvoker = { _ ->
                if (routeCall++ == 0) {
                    kotlinx.coroutines.flow.flow {
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        firstTurnConnected.complete(Unit)
                        kotlinx.coroutines.yield()
                        emit(frame)
                        awaitCancellation()
                    }
                } else {
                    events(
                        listOf(
                            CloudRouteEvent.Connected("pcm_s16le@24k"),
                            CloudRouteEvent.Done(1, 1),
                        ),
                    )
                }
            },
        )
        deps.playback.duckBlocker = duckPaused
        val sink = deps.sink()
        val aError = CompletableDeferred<Throwable?>()
        val turnA = launch(kotlinx.coroutines.Dispatchers.Default) {
            aError.complete(
                runCatching {
                    sink.routeToCloud("first question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
                }.exceptionOrNull(),
            )
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) { firstTurnConnected.await() }
                kotlinx.coroutines.withTimeout(5_000) { deps.playback.duckStarted.await() }
            }
        }
        val turnB = launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                sink.routeToCloud("second question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
            }
        }
        val deadline = System.nanoTime() + 1_000_000_000L
        while (!order.contains("stop") && System.nanoTime() < deadline) Thread.sleep(1)
        duckPaused.countDown()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    turnB.join()
                    turnA.cancel()
                    turnA.join()
                }
            }
        }
        aError.await()?.let { if (it !is kotlinx.coroutines.CancellationException) throw it }
        val mutationOrder = order.toList()
        assertTrue(
            "expected submit/play to complete BEFORE the successor's stop, got: " + mutationOrder,
            mutationOrder.indexOf("play") != -1 && mutationOrder.indexOf("play") < mutationOrder.lastIndexOf("stop"),
        )
    }
    @Test
    fun `a superseding turn stops the predecessor's buffered audio AT TRANSFER, not at its own exit`() = runTest {
        val player = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
        whenever(player.audioWritten).thenReturn(true)
        val firstTurnReachedFrame = CompletableDeferred<Unit>()
        val successorMidFlight = CompletableDeferred<Unit>()
        var routeCall = 0
        val deps = TestDeps(
            audioPlayer = player,
            routeInvoker = { _ ->
                if (routeCall++ == 0) {
                    kotlinx.coroutines.flow.flow {
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        emit(CloudRouteEvent.AudioFrame("pcm_s16le@24k", byteArrayOf(1, 2)))
                        firstTurnReachedFrame.complete(Unit)
                        awaitCancellation()
                    }
                } else {
                    kotlinx.coroutines.flow.flow {
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        successorMidFlight.complete(Unit)
                        awaitCancellation()
                    }
                }
            },
        )
        val sink = deps.sink()
        val turnA = launch {
            runCatching {
                sink.routeToCloud("first question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
            }
        }
        withTimeout(5_000) { firstTurnReachedFrame.await() }
        val turnB = launch {
            runCatching {
                sink.routeToCloud("second question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId)
            }
        }
        withTimeout(5_000) { successorMidFlight.await() }
        org.mockito.kotlin.verify(
            player,
            org.mockito.kotlin.times(1)
                .description(
                    "the predecessor's buffered answer must be stopped at transfer, before the successor's own turn progresses (calls=${'$'}{deps.playback.calls})",
                ),
        ).stop()
        turnB.cancel()
        turnA.cancel()
        withTimeout(5_000) {
            turnB.join()
            turnA.join()
        }
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
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.referenceTime(60_000)).thenReturn(45.0)
        whenever(fingerprint.playbackTimeMs(45_000.0)).thenReturn(45_000)
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            cloudPlaybackContextState = state,
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
        deps.playback.preActionPositionMsForTest = 60_000L
        val sink = deps.sink()
        sink.routeToCloud("play the quote", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        val recorded = state.snapshot("episode-id")
        assertTrue(
            "the recorded previous reference must come from the OWNER'S snapshot (60_000 playback), not " +
                "the provider's current position (999_000): recorded=${recorded.previousReferencePositionMs}",
            recorded.previousReferencePositionMs == 45_000L,
        )
    }
    @Test
    fun `composite steps stay unknown when one step was not established`() {
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
        assertEquals(
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            CloudRouteSink.mergeCompositeSteps(
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
            ),
        )
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
        assertEquals(
            "episode-id",
            playbackContext.episodeId,
        )
        assertEquals(
            "the recorded value is read back FOR the episode it was recorded from",
            45_000L,
            state.snapshot(playbackContext.episodeId).previousReferencePositionMs,
        )
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
        assertEquals(null, state.snapshot("episode-id").previousReferencePositionMs)
    }
    @Test
    fun `a no-answer error from the service is spoken rather than left silent`() = runTest {
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
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0)),
        )
        val sink = deps.sink()
        val response = sink.routeToCloud("what?", VoiceIntent.CloudTier.Free, playbackContext, admitted = testWireId).feedback
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }
    @Test
    fun `an empty turn speaks the default line whatever the device locale`() = runTest {
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
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }
    @Test
    fun `a budget refusal speaks its own line rather than the generic one`() = runTest {
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
        assertTrue("the action was executed (calls=${deps.playback.calls})", deps.playback.calls.contains("seekTo:545"))
        assertTrue(!deps.playback.calls.contains("seekTo:50"))
    }
    @Test
    fun `builds route context enriched from sink state`() = runTest {
        val state = CloudPlaybackContextState(
            recentReferencePositions = listOf(100L, 200L),
            previousReferencePositionMs = 300L,
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
        assertTrue(
            "the executed seek is the MAPPED sink position (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:545"),
        )
        assertEquals(VoiceResponse.Spoken("The assistant couldn't finish that request."), response)
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
        assertEquals(VoiceResponse.Silent, response)
        assertTrue(deps.playback.calls.none { it.startsWith("seekTo") })
        assertTrue(renderer.rendered.isEmpty())
        assertEquals(1, renderer.unavailableCount)
    }
    @Test
    fun `a token that expires between turns fails the next turn closed`() = runTest {
        var token: String? = "user_test"
        val deps = TestDeps(
            routeInvoker = { _ ->
                if (token == null) {
                    flowOf(CloudRouteEvent.Error("unauthorized", ""))
                } else {
                    flowOf(CloudRouteEvent.Connected("pcm_s16le@24k"), CloudRouteEvent.Done(1, 1))
                }
            },
        )
        val sink = deps.sink()
        assertEquals(
            VoiceResponse.Spoken("Sorry, I couldn't complete that request."),
            sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback,
        )
        token = null
        val second = sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(VoiceResponse.Spoken("Service is unavailable at the moment."), second)
        assertTrue(deps.playback.calls.none { it.startsWith("seekTo") })
    }
    @Test
    fun `cancellation during the initial pause still restores audio`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val deps = TestDeps(routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 1))))
        deps.playback.pauseGate = gate
        val sink = deps.sink()
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
        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.resumeStarted.await()
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
        val first = launch { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.resumeStarted.await()
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
        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }
    @Test
    fun `a refusal with no detected language speaks the default line whatever the device locale`() = runTest {
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
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }
    @Test
    fun `a whitespace-only server message is treated as absent`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "   ")),
        )
        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }
    @Test
    fun `a client code without a template falls back to the client generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "invalid_response", message = "")),
        )
        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }
    @Test
    fun `a server-supplied message is never spoken, only a template or the generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "limit_exceeded", message = "You've used 10/10 free requests today.")),
        )
        val response = deps.sink().routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
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
        deps.playback.throwOnPauseCall = 2
        val sink = deps.sink()
        val outcome = runCatching { sink.routeToCloud("x", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        assertEquals("pause failed", outcome.exceptionOrNull()?.message)
        assertEquals(listOf("pause", "pause"), deps.playback.calls)
    }
    @Test
    fun `a turn while the host is paused neither pauses nor resumes`() = runTest {
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
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()
        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.pauseStarted.await()
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
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()
        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.pauseStarted.await()
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
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()
        val turn = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.pauseStarted.await()
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
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(listOf("pause", "resume"), deps.playback.calls)
        assertTrue("playback is where it started", deps.host.playing)
    }
    @Test
    fun `a user command during the pause suspension blocks the restore`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val route = launch { deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.pauseStarted.await()
        deps.commandRevision += 1
        deps.playback.pauseGate?.complete(Unit)
        route.join()
        assertEquals("the user's command stands", listOf("pause"), deps.playback.calls)
    }
    @Test
    fun `a user pause during an answer hold survives a FAILED termination`() = runTest {
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
                    deps.commandRevision += 1
                    throw RuntimeException("provider error")
                }
            },
        )
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()
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
    }
    @Test
    fun `a turn does not override a user pause that changed nothing`() = runTest {
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
                    deps.commandRevision += 1
                    deps.host.playing = true
                    emit(CloudRouteEvent.Done(1, 0))
                }
            },
        )
        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
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
        assertEquals(
            "a stated zero must not move the player",
            emptyList<String>(),
            deps.playback.calls.filter { it.startsWith("skip") },
        )
    }
    @Test
    fun `the terminal result carries the outcome the turn ACCUMULATED`() = runTest {
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
        assertEquals(
            listOf("skipBackward:null"),
            deps.playback.calls.filter { it.startsWith("skip") },
        )
    }
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
        private val episodeId: String? = "episode-id",
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
        var hostPlaying: Boolean = true,
        var commandRevision: Long = 0,
        private val locale: java.util.Locale = java.util.Locale.ENGLISH,
        private val audioPlayer: au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer? = null,
        var privacyClosed: Boolean = false,
        var privacyClosures: Long = 0,
        var playbackStopClosures: Long = 0,
        private val episodeDurationSeconds: Int = 0,
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
        val host = HostPlaybackState(hostPlaying)
        val playback = FakePlaybackSink(host)
        val analytics = FakeCloudRouteAnalytics()
        val routeCalls = mutableListOf<String>()
        val routeContexts = mutableListOf<CloudRouteContext>()
        val routeTurns = mutableListOf<CloudRouteTurn>()
        private val explicitInvoker = routeInvoker
        private val explicitTransportFactory = transportFactory
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
        val conversationAdmission = CloudConversationAdmission()
        fun sink(): CloudRouteSink = CloudRouteSink(
            resolveBaseUrl = { baseUrl },
            tokenProvider = object : CloudTokenProviding {
                override suspend fun currentToken(): String? = userId.takeIf { it.isNotBlank() }
            },
            playbackSink = playback,
            fingerprintTimingManager = fingerprintTimingManager,
            playbackContextProvider = playbackContextProvider,
            conversationAdmission = conversationAdmission,
            cloudPlaybackContextState = cloudPlaybackContextState,
            analytics = analytics,
            routeInvoker = if (explicitTransportFactory != null && explicitInvoker == null) {
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
    private class HostPlaybackState(var playing: Boolean)
    private class FakePlaybackSink(private val host: HostPlaybackState? = null) : VoicePlaybackSink {
        val calls = mutableListOf<String>()
        var resumeGate: CompletableDeferred<Unit>? = null
        val resumeStarted = CompletableDeferred<Unit>()
        var pauseGate: CompletableDeferred<Unit>? = null
        val pauseStarted = CompletableDeferred<Unit>()
        var throwOnPauseCall: Int? = null
        private var pauseCalls = 0
        override suspend fun duck(): VoiceResponse {
            calls += "duck"
            duckStarted.complete(Unit)
            duckBlocker?.await()
            return VoiceResponse.Silent
        }
        var duckBlocker: java.util.concurrent.CountDownLatch? = null
        val duckStarted = CompletableDeferred<Unit>()
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
            seekOutcomeForTest?.let { return it }
            if (!accepts(expectedEpisodeId)) {
                calls += "seekToRefused:$positionSeconds"
                return EpisodeOperationResult.Refused
            }
            val resolved = if (positionSeconds < 0) {
                (episodeDurationSeconds + positionSeconds).coerceIn(0, episodeDurationSeconds)
            } else {
                positionSeconds
            }
            calls += "seekTo:$resolved"
            return EpisodeOperationResult.Accepted(expectedEpisodeId, preActionPositionMsForTest)
        }
        var currentEpisodeIdForTest: String? = null
        var seekOutcomeForTest: EpisodeOperationResult? = null
        var skipOutcomeForTest: EpisodeOperationResult? = null
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
            assertTrue("the server terminal outcome must still be handled", deps.routeCalls.isNotEmpty())
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
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val route = launch { deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.pauseStarted.await()
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
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playbackStopClosures = 1
        deps.privacyClosed = false
        deps.sink().routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertTrue(
            "a turn with an open window must resume what it paused (calls=${deps.playback.calls})",
            deps.playback.calls.contains("resume") && deps.host.playing,
        )
    }
    @Test
    fun `a privacy closure invalidates the turn before any successor registers`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()
        val turnA = launch { sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback }
        deps.playback.pauseStarted.await()
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
        assertEquals(
            "a confirmed reservation leaves exactly one hold, released by the turn ending: " +
                "calls=${deps.playback.calls}",
            listOf("pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
        assertTrue("the host is left as it was found", deps.host.playing)
    }
    @Test
    fun `without the frame the reservation is released unsent`() = runTest(UnconfinedTestDispatcher()) {
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        val sink = deps.sink()
        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(
            "the unconfirmed reservation must still be released",
            listOf("pause", "resume"),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
        assertTrue("the host is left as it was found", deps.host.playing)
    }
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
                        turnId = "${turn.requestId}-someone-else",
                    ),
                    CloudRouteEvent.Done(1, 0),
                )
            },
        )
        val sink = deps.sink()
        sink.routeToCloud("a question", VoiceIntent.CloudTier.Premium, playbackContext, admitted = testWireId).feedback
        assertEquals(
            "a foreign frame must not act on this turn, and an unconfirmed turn must not pause",
            emptyList<String>(),
            deps.playback.calls.filter { it == "pause" || it == "resume" },
        )
        assertTrue("the host is left as it was found", deps.host.playing)
    }
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
                    deps.commandRevision += 1
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
        if (withConfirmation) {
            deps.playback.pauseStarted.await()
        }
        deps.playback.pauseGate?.complete(Unit)
        turn.join()
        return deps.playback.calls.filter { it == "pause" || it == "resume" }
    }
    @Test
    fun `a user pause is handled the same with and without the confirmation`() = runTest {
        val confirmed = runUserPauseDuringHold(this, withConfirmation = true, fails = true)
        val unconfirmed = runUserPauseDuringHold(this, withConfirmation = false, fails = true)
        val confirmedOk = runUserPauseDuringHold(this, withConfirmation = true, fails = false)
        val unconfirmedOk = runUserPauseDuringHold(this, withConfirmation = false, fails = false)
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
        val admittedA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val admittedB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
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
        val stamped = completedIntent!!.withLogicalRequest(admittedB)
        val stampedCloud = stamped as VoiceIntent.CloudRoute
        assertEquals("engine stamped B onto the dialog-completed intent", admittedB, stampedCloud.logicalRequestId)
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
        sink.dispatchPrepared(prepared)
        assertEquals("exactly one turn dispatched", 1, deps.routeTurns.size)
        assertEquals(
            "dispatched turn's request_id is B",
            admittedB,
            deps.routeTurns.single().requestId,
        )
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
        assertEquals("zh", result.feedbackLanguage)
    }
    @Test
    fun `a refusal for an unsupported language speaks English text with an English voice`() = kotlinx.coroutines.runBlocking {
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
            assertNotNull("service dispatch through the REAL executor must complete", done)
            turn.join()
            turnScope.cancel()
            driver.join()
            awaitCaptureLine(capture, "reason=TurnSocketOpened", testWireId)
            awaitCaptureLine(capture, "SEND_ATTEMPTED", testWireId)
            awaitCaptureLine(capture, "reason=TurnTerminalReceived", testWireId, alsoContains = "limit_exceeded")
            awaitCaptureLine(capture, "reason=TurnFeedbackSelected", testWireId, alsoContains = "entry_spoken")
            assertTrue(
                "the hold must still be pending while output is in flight (survived sink return)",
                graceSignal.hasOutputHold(testWireId),
            )
            Thread.sleep(400)
            assertTrue("in-flight refusal output must hold the grace window open", graceSignal.isActive.value)
            tts.speakGate?.complete(Unit)
            kotlin.runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(5_000) { tts.speakReturned?.await() }
                }
            }
            awaitCaptureLine(capture, "reason=TurnFeedbackRendered", testWireId, alsoContains = "spoken_submitted")
            assertEquals("你已达到 Auris 的每日免费额度，请明天再试。", tts.lastSpokenText)
            assertEquals("zh", tts.lastSpokenLanguage)
            val submissions = capture.lines().count {
                it.contains("reason=TurnFeedbackRendered") && it.contains("spoken_submitted") && it.contains("request_id=$testWireId")
            }
            assertEquals("the localized refusal must be submitted exactly once", 1, submissions)
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
            assertTrue("hold must be pending while render is in flight", graceSignal.hasOutputHold(testWireId))
            tts.speakGate?.complete(Unit)
            val releaseDeadline = System.currentTimeMillis() + 8_000
            while (graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < releaseDeadline) Thread.sleep(50)
            assertTrue("the failure terminal must have released the hold", !graceSignal.hasOutputHold(testWireId))
            val lines = capture.lines()
            val failureIdx = lines.indexOfFirst { it.contains("submission_failed") && it.contains("render_failed") }
            val holdReleaseIdx = lines.indexOfFirst { it.contains("event=Hold") && (it.contains("HoldReleased") || it.contains("HoldCancelled")) }
            assertTrue("failure drop must be recorded before the hold release", failureIdx in 0 until holdReleaseIdx)
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
    fun `cancellation holds the grace window until output clear completes, then releases exactly once`() = kotlinx.coroutines.runBlocking {
        val capture = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture()
        try {
            val wireClient = CapturingWebSocketClient()
            val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
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
            val tts = au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine().apply {
                clearCompletedGate = kotlinx.coroutines.CompletableDeferred()
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
                    dispatched.complete(Unit)
                } catch (t: Throwable) {
                    dispatched.completeExceptionally(t)
                }
            }
            driver.start()
            val cleanupDeadline = System.currentTimeMillis() + 5_000
            while (!tts.clearRequested && System.currentTimeMillis() < cleanupDeadline) Thread.sleep(20)
            assertTrue("cleanup must be requested (stop/clear in flight)", tts.clearRequested)
            assertTrue("the hold must survive while output clear is blocked", graceSignal.hasOutputHold(testWireId))
            renderer.release()
            Thread.sleep(200)
            assertTrue("the hold must survive cancellation while clear is still blocked", graceSignal.hasOutputHold(testWireId))
            tts.clearCompletedGate?.complete(Unit)
            val joinDeadline = System.currentTimeMillis() + 8_000
            while (graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < joinDeadline) {
                Thread.sleep(50)
            }
            turn.join()
            turnScope.cancel()
            driver.join()
            val loweredId = testWireId.lowercase()
            val releaseEvents = capture.lines().count { line ->
                line.contains("event=Hold") &&
                    (line.contains("reason=HoldReleased") || line.contains("reason=HoldCancelled")) &&
                    line.contains("request_id=$loweredId")
            }
            assertEquals("exactly one hold release event for this request", 1, releaseEvents)
            assertFalse("no output hold remains after clear completes", graceSignal.hasOutputHold(testWireId))
            val shadowLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            var expiryTicks = 0
            while (graceSignal.isActive.value && expiryTicks < 50) {
                shadowLooper.runToEndOfTasks()
                expiryTicks++
            }
            assertFalse("the window must expire after the terminal release", graceSignal.isActive.value)
            renderer.release()
        } finally {
            capture.close()
        }
    }
    @Test
    fun `supersession releases the predecessor hold only after its output terminal is recorded`() = kotlinx.coroutines.runBlocking {
        val capture = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture()
        try {
            val wireClient = CapturingWebSocketClient()
            val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
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
                } catch (t: Throwable) {
                    dispatched.completeExceptionally(t)
                }
            }
            driver.start()
            val startedDeadline = System.currentTimeMillis() + 5_000
            while (!graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < startedDeadline) Thread.sleep(20)
            assertTrue("hold must transfer and stay pending while output is in flight", graceSignal.hasOutputHold(testWireId))
            renderer.render(VoiceResponse.Silent, requestId = "successor-supersede")
            val releasedDeadline = System.currentTimeMillis() + 8_000
            while (graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < releasedDeadline) Thread.sleep(20)
            assertFalse("supersession must release the predecessor's hold", graceSignal.hasOutputHold(testWireId))
            val lines = capture.lines()
            val supersededIdx = lines.indexOfFirst {
                it.contains("superseded") || it.contains("render_cancelled")
            }
            val holdReleaseIdx = lines.indexOfFirst {
                it.contains("event=Hold") && (it.contains("HoldReleased") || it.contains("HoldCancelled"))
            }
            assertTrue("supersession drop must be recorded before the hold release", supersededIdx in 0 until holdReleaseIdx)
            tts.speakGate?.complete(Unit)
            renderer.release()
            turn.join()
            turnScope.cancel()
            driver.join()
        } finally {
            capture.close()
        }
    }
    @Test
    fun `cancellation at the execute-to-handoff boundary releases the transferred hold`() = kotlinx.coroutines.runBlocking {
        val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 5_000L)
        graceSignal.onWakeWordDetected()
        var answerRan = false
        val executor = au.com.shiftyjelly.pocketcasts.voicecontrol.service.StubVoiceExecutor { intent, _, _ ->
            answerRan = true
            val hold = graceSignal.holdCloudTurn(owner = 3L)!!
            graceSignal.transferHoldToOutput(testWireId, hold)
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
        var threwOther: String? = null
        try {
            service.renderWithFailureHandling(
                VoiceIntent.CloudRoute(request = "q", tier = VoiceIntent.CloudTier.Premium, logicalRequestId = testWireId),
            )
        } catch (expected: kotlinx.coroutines.CancellationException) {
            releasedThenRethrown = !graceSignal.hasOutputHold(testWireId)
        } catch (other: Throwable) {
            threwOther = other::class.simpleName
        }
        assertTrue(
            "answerRan=" + answerRan + " rethrown=" + releasedThenRethrown +
                " holdPresent=" + graceSignal.hasOutputHold(testWireId) + " other=" + threwOther,
            releasedThenRethrown,
        )
    }
    @Test
    fun `local playback failure derives succeeded false even with Done Completed`() = runTest {
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
        assertEquals(listOf("auth:RESOLVED", "connection_failed"), recording.events)
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
        assertFalse("exchange should not be called when token is missing", exchangeCalled)
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
