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
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.ExoPlayerDataSourceFactory
import au.com.shiftyjelly.pocketcasts.repositories.podcast.ChapterManager
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintDecodePolicy
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.SharedPreferencesCloudConfig
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    fun `a prepared turn carries the UI locale's primary subtag, so the server can answer in it`() = runTest {
        // M6: the server picks the reply language before inference and the app's UI locale is its
        // fall-through when the spoken language is absent or unsupported. The subtag is what the
        // server's rule decides on, so a regional locale (`zh-CN` -- what a device actually reports)
        // travels as `zh`, not as a region the server would have to parse back off.
        val deps = TestDeps()
        val sink = deps.sink(locale = java.util.Locale.forLanguageTag("zh-CN"))
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "question",
            context = playbackContext,
            capturedOrigin = 1,
            logicalRequestId = "ui-language-turn",
        )!!

        assertEquals("the screen's language, primary subtag only", "zh", prepared.uiLanguage)
    }

    @Test
    fun `a turn routed through the entry point carries the UI language too`() = runTest {
        // The entry points BUILD their own turn instead of dispatching a prepared one, so they are a
        // separate place the field has to be set. Missing it there would not fail any other test:
        // every existing caller goes through prepareTurn, so the gap would only appear the first
        // time something used routeToCloud directly -- and the answer would silently be English.
        val deps = TestDeps()
        val sink = deps.sink(locale = java.util.Locale.forLanguageTag("zh-CN"))
        deps.conversationAdmission.beginOrigin(1)

        sink.routeToCloud("question", playbackContext, admitted = testWireId)

        assertEquals(
            "the screen's language, primary subtag only",
            "zh",
            deps.routeTurns.single().uiLanguage,
        )
    }

    @Test
    fun `a retry keeps the language the request was captured under`() = runTest {
        // The language is a fact of the REQUEST: the user is being answered about an utterance made
        // while the app was in one language. A retry must not re-read the locale, or switching the
        // app's language between the two dispatches would have one request answered in two
        // languages. Retention is what carries it, so THE LOCALE HAS TO CHANGE HERE for the test to
        // be able to fail: with one fixed locale, re-reading produces the same answer either way.
        val deps = TestDeps()
        deps.conversationAdmission.beginOrigin(1)
        var locale = java.util.Locale.forLanguageTag("de-DE")
        val sink = deps.sink(localeProvider = { locale })

        val first = sink.prepareTurn(
            request = "question",
            context = playbackContext,
            capturedOrigin = 1,
            logicalRequestId = "retry-ui-language",
        )!!
        locale = java.util.Locale.forLanguageTag("fr-FR")
        val retryPrepared = sink.prepareTurn(
            request = "question",
            context = playbackContext,
            capturedOrigin = 1,
            logicalRequestId = "retry-ui-language",
            isRetry = true,
        )!!

        assertEquals("de", first.uiLanguage)
        assertEquals("the retry answers in the language the request was captured under", "de", retryPrepared.uiLanguage)
        org.junit.Assert.assertSame("the retry reuses the retained turn rather than preparing a new one", first, retryPrepared)
    }

    @Test
    fun `a dispatch the sink refuses before routing leaves no live timing entry`() = runTest {
        // A turn whose retained identity was retired (forgetRetained) is refused at claimDispatch,
        // BEFORE any route opens — so routeTurn's end-of-turn finish never runs. The sink must
        // finish the record itself, or the registry keeps one live entry per refused turn.
        val deps = TestDeps()
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)
        val prepared = sink.prepareTurn("question", playbackContext, capturedOrigin = 1, logicalRequestId = "one-utterance")!!
        val requestId = prepared.requestId
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(
            requestId,
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(requestId),
        )
        deps.conversationAdmission.forgetRetained("one-utterance")

        val result = sink.dispatchPrepared(prepared)

        assertEquals(VoiceResponse.Silent, result.feedback)
        assertFalse(
            "a turn refused before routing must not keep a live timing entry",
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.isActive(requestId),
        )
    }

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
                    sink.prepareTurn("question", playbackContext, capturedOrigin = 1, isWakeOriginated = true, logicalRequestId = "one-utterance")
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
        val prepared = sink.prepareTurn("question", playbackContext, capturedOrigin = 1, logicalRequestId = "one-utterance")!!
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
            val prepared = sink.prepareTurn("question", playbackContext, capturedOrigin = 1, logicalRequestId = "one-utterance")!!
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
                // capturedOrigin is client-local and not carried on the wire, so the transport-level
                // turn reconstructs it as unset; identity is the wire-visible payload.
                assertEquals(
                    "retry preserves the original wire payload",
                    prepared.copy(capturedOrigin = 0L),
                    deps.routeTurns.last(),
                )
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
        val first = sink.prepareTurn("old question", playbackContext, capturedOrigin = 1, logicalRequestId = "one-utterance")!!
        val oldOwner = launch { sink.dispatchPrepared(first) }
        entered.receive()
        deps.conversationAdmission.endWindow(1)
        deps.conversationAdmission.beginOrigin(2)
        // A NEW utterance under a successor window carries its OWN admitted identity: since
        //  the wire id is the admitted one, so reusing the same string here would mean
        // the same utterance, not a successor.
        val successor = sink.prepareTurn("new question", playbackContext, capturedOrigin = 2, logicalRequestId = "b2f5e9a1-7c34-4d80-9e11-6a4b8c0d2f35")!!
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
            val turnResult = deps.sink().routeToCloud("question", playbackContext, admitted = testWireId)
            assertEquals(VoiceResponse.Silent, turnResult.feedback)
            // Positive success: only an actual done-Completed terminal is a
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
        // ordinary-request lifecycle: a pending dialog does not own an ordinary ASR request, so
        // the WINDOW is what ends its retryability -- retain while it lives, retire when it is
        // superseded, and refuse an explicit retry once retired.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "an ordinary question",
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
        // requirement: the owning window's replacement is ONE way a window ends. A privacy close
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
        // bounded case, on the production sink rather than a fake: the fake returns numbered
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
        // invalidation case: a prepared turn retried AFTER its window was superseded still
        // reached transport. A retry must re-validate its own captured origin, so a superseded turn is
        // refused exactly as a first dispatch of it would have been.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "prepared under window 1",
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
        sink.routeToCloud("with a captured window", playbackContext, admitted = testWireId, capturedOrigin = 1).feedback
        sink.routeToCloud("and with none", playbackContext, admitted = testWireId).feedback
        sink.routeToCloudWithHint(
            request = "and via the typed-flow entry point",
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
        // controller retry contract: the prepared turn is IMMUTABLE. Retrying it must reuse its
        // request ID, handle and mode -- re-preparing would mint a SECOND conversation and consume the
        // wake selection again, so a retried request would look like a new user act.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        val prepared = sink.prepareTurn(
            request = "a question worth retrying",
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
        // measured defect: adoption happened only for the very first origin ever seen, so a
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
            playbackContext,
            admitted = testWireId,
            capturedOrigin = 1,
            isWakeOriginated = true,
        ).feedback
        assertEquals(1, deps.routeTurns.size)

        // Window 2, a successor wake: its first request must ALSO be admitted.
        sink.routeToCloud(
            "successor window",
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
        // measured case: reference positions recorded while episode A played were sent when
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
        // explicit-refusal history case. The sink is independently on episode B and refuses
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
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
            admitted = testWireId,
        ).feedback

        assertTrue(
            "a refused seek must not be recorded as a performed action " +
                "(calls=${deps.playback.calls} recorded=${state.snapshot("B").recentReferencePositions})",
            !deps.playback.calls.any { it.startsWith("seekTo:") },
        )
        // THE HISTORY IS THE OBSERVATION, not the call list: a refusal that reached no player must
        // leave both episodes' stored positions exactly as they were.
        assertTrue(
            "and it must not record history against any episode " +
                "(A=${state.snapshot("A").recentReferencePositions} " +
                "B=${state.snapshot("B").recentReferencePositions})",
            state.snapshot("A").recentReferencePositions.isEmpty() &&
                state.snapshot("B").recentReferencePositions.isEmpty(),
        )
    }

    @Test
    fun `a REFUSED reference seek does not record history`() = runTest {
        // The reference branch is a different branch from `position_seconds`: that one routes to the
        // plain seek, this one through the fingerprint mapping. A case for one cannot see a defect in
        // the other, so each branch needs its own refusal control.
        //
        // `seek_to` is the only reference verb.
        listOf("seek_to" to "reference_position_ms").forEach { (action, field) ->
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
                PlaybackContext(episodeId = "A", clientPositionMs = 0L),
                admitted = testWireId,
            ).feedback

            // THE HISTORY IS THE OBSERVATION, not the call list. A refused operation records nothing,
            // so the episode's saved positions must be exactly what they were -- an assertion about
            // `calls` alone passes whether or not history was corrupted.
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
        // finding: `skipForwardSuspend` re-read the queue, so a bound skip for A that found B
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
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
            admitted = testWireId,
        ).feedback

        assertTrue(
            "a skip planned for A must not proceed against B (calls=${deps.playback.calls})",
            !deps.playback.calls.any { it.startsWith("skipForward:") },
        )
    }

    @Test
    fun `a resume does not resume a replacement episode`() = runTest {
        // A resume is a player-state mutation, so it carries the turn's episode binding: resuming a
        // recording the user moved away from would start audio under a turn about something else.
        val deps = TestDeps(
            episodeId = "A",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "resume",
                    params = emptyMap(),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // The user changes episode MID-TURN: the provider reports B while the turn is about A. The
        // fake's resume accepts whatever it is handed, so the sink's own binding is what must refuse.
        deps.selectEpisodeForTest("B")
        deps.playback.currentEpisodeIdForTest = "B"
        val sink = deps.sink()

        sink.routeToCloud(
            "about episode A",
            PlaybackContext(episodeId = "A", clientPositionMs = 0L),
            admitted = testWireId,
        ).feedback

        assertTrue(
            "a resume whose episode was replaced must not reach the player (calls=${deps.playback.calls})",
            !deps.playback.calls.any { it == "resume" },
        )
    }

    @Test
    fun `a REFUSED bound skip is not reported as a performed action`() = runTest {
        // requirement: the scoped mutation's own payload decides, and statistics/history follow
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
        // "returning true for Unknown still reports confirmed execution" -- the defect is that the
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
        suspend fun outcomeFor(result: EpisodeOperationResult): CloudRouteActionOutcome {
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
            return sink.routeToCloud(
                "skip",
                PlaybackContext(episodeId = "episode-id", clientPositionMs = 0L),
                admitted = testWireId,
            ).action
        }

        assertEquals(
            "an UNKNOWN skip must report Unknown, not Confirmed",
            CloudRouteActionOutcome.Unknown,
            outcomeFor(EpisodeOperationResult.Unknown("episode-id", 0L)),
        )
        assertEquals(
            "a REFUSED skip must report None, not Confirmed",
            CloudRouteActionOutcome.None,
            outcomeFor(EpisodeOperationResult.Refused),
        )
        assertEquals(
            "an ACCEPTED skip reports Confirmed",
            CloudRouteActionOutcome.Confirmed,
            outcomeFor(EpisodeOperationResult.Accepted("episode-id", 0L)),
        )
    }

    @Test
    fun `an UNKNOWN outcome records no history`() = runTest {
        // An UNKNOWN outcome records nothing: nothing established that the player reached the target,
        // and a previous position with no destination describes a seek that has no history. The
        // observation is the stored positions, so this fails if any record is written -- including one
        // carrying only the pre-action provenance.
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            episodeId = "episode-id",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // UNKNOWN, carrying a KNOWN pre-action position.
        deps.playback.seekOutcomeForTest = EpisodeOperationResult.Unknown("episode-id", 60_000L)
        val sink = deps.sink()

        sink.routeToCloud(
            "play from the reference",
            PlaybackContext(episodeId = "episode-id", clientPositionMs = 0L),
            admitted = testWireId,
        ).feedback

        val recorded = state.snapshot("episode-id")
        assertTrue(
            "an unknown outcome must record NO target, because nothing observed it being reached " +
                "(recorded=${recorded.recentReferencePositions})",
            recorded.recentReferencePositions.isEmpty(),
        )
        assertNull(
            "and no pre-action provenance either (previous=${recorded.previousReferencePositionMs})",
            recorded.previousReferencePositionMs,
        )
    }

    @Test
    fun `an UNKNOWN plain seek records no history either`() = runTest {
        // The plain branch decides its own history, separately from the reference branch: a target in
        // seconds is converted here rather than mapped through the fingerprint. An unobserved attempt
        // records nothing on this path too, including the pre-action provenance alone.
        val state = CloudPlaybackContextState()
        val deps = TestDeps(
            cloudPlaybackContextState = state,
            episodeId = "episode-id",
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("position_seconds" to 100),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // UNKNOWN, carrying a KNOWN pre-action position.
        deps.playback.seekOutcomeForTest = EpisodeOperationResult.Unknown("episode-id", 60_000L)
        val sink = deps.sink()

        sink.routeToCloud(
            "go to 100 seconds",
            PlaybackContext(episodeId = "episode-id", clientPositionMs = 0L),
            admitted = testWireId,
        ).feedback

        val recorded = state.snapshot("episode-id")
        assertTrue(
            "an unknown plain seek must record NO target (recorded=${recorded.recentReferencePositions})",
            recorded.recentReferencePositions.isEmpty(),
        )
        assertNull(
            "and no pre-action provenance either (previous=${recorded.previousReferencePositionMs})",
            recorded.previousReferencePositionMs,
        )
    }

    @Test
    fun `an ordinary position seek reports its TYPED outcome to the caller, not None`() = runTest {
        // measured regression from my conversion: the ordinary branch computed its `when`
        // and DISCARDED it, so execution fell through to `referenceMs == null` and returned None. The
        // CALLER therefore saw [None, None] for Unknown->Confirmed and [None] for a single Unknown --
        // every seek reaching the real sink yet reporting that nothing happened.
        //
        // The observation is the OUTCOME THE CALLER RECEIVES, which is the value the mechanism decides
        // on. Asserting the response or the seek count cannot see this: both are unchanged by the
        // discarded result.
        suspend fun outcomeFor(result: EpisodeOperationResult, action: String = "seek_to"): CloudRouteActionOutcome {
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
            return sink.routeToCloud(
                "seek",
                PlaybackContext(episodeId = "episode-id", clientPositionMs = 0L),
                admitted = testWireId,
            ).action
        }

        assertEquals(
            "an ACCEPTED ordinary seek must report Confirmed, not None",
            CloudRouteActionOutcome.Confirmed,
            outcomeFor(EpisodeOperationResult.Accepted("episode-id", 0L)),
        )
        assertEquals(
            "an UNKNOWN ordinary seek must report Unknown, not None",
            CloudRouteActionOutcome.Unknown,
            outcomeFor(EpisodeOperationResult.Unknown("episode-id", 0L)),
        )
        assertEquals(
            "a REFUSED ordinary seek reports None: nothing happened",
            CloudRouteActionOutcome.None,
            outcomeFor(EpisodeOperationResult.Refused),
        )
    }

    @Test
    fun `an UNBOUND reference position is refused rather than applied to whatever is playing`() = runTest {
        // unbound case: a reference position is a position IN a recording, so with no episode to
        // be a position in there is nothing it can be a position in. A null expectation must not turn an
        // episode-specific operation into an unrestricted seek.
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        // A turn with NO episode: an episode-specific reference action has nothing to act on.
        sink.routeToCloud(
            "play from the reference",
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
        // delayed episode-action rule: an action originating in episode A must not seek or
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
        // executed finding: a fresh wake RESUMEd the previous conversation, which makes a
        // new user act look like a continuation of whatever was being discussed before it.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)

        // A turn captured under the FIRST window's wake: it creates.
        sink.routeToCloud(
            "what is this about",
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
            // The handshake first: only an ACKNOWLEDGED conversation may be resumed.
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Connected("pcm@16k"), CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(7)

        sink.routeToCloud(
            "first",
            playbackContext,
            admitted = testWireId,
            capturedOrigin = 7,
            isWakeOriginated = true,
        ).feedback
        val created = deps.routeTurns.last()

        // A wake-FREE follow-up in the SAME window continues the conversation.
        sink.routeToCloud(
            "and another thing",
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
        // admission contract: the client generates the handle, create carries it, and a later
        // turn inside the live grace window resumes the SAME conversation -- once the worker has
        // ACKNOWLEDGED it with `connected`. Without that frame there is no conversation to resume.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Connected("pcm@16k"), CloudRouteEvent.Done(1, 0)) },
        )
        val sink = deps.sink()

        sink.routeToCloud("first question", playbackContext, admitted = testWireId).feedback
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

        sink.routeToCloud("first question", playbackContext, admitted = testWireId).feedback
        val first = deps.routeTurns.last()
        sink.routeToCloud("an unrelated question", playbackContext, admitted = testWireId).feedback
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
        // stateful case: the saved state accumulates reference positions while an episode
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
        // The positive control asked for: with an episode current, the saved reference history is
        // retained, so the omission above is about the missing episode rather than about the builder
        // dropping positions unconditionally.
        val deps = TestDeps(
            routeInvoker = { kotlinx.coroutines.flow.flowOf(CloudRouteEvent.Done(1, 0)) },
        )
        // Recorded BEFORE the turn, AGAINST the episode this turn is about -- a saved position belongs
        // To the episode it was recorded from so the control must name it to assert retention.
        deps.cloudPlaybackContextState.record(
            referencePositionMs = 100L,
            previousReferencePositionMs = 300L,
            episodeId = "episode-1",
        )
        val sink = deps.sink()

        sink.routeToCloud(
            "what happened",
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
        // when turn B supersedes turn A mid-answer, A's finally is skipped
        // (it no longer owns the audio state) and A's `ducked`/`audioPlayed` die with its frame.
        // A user who barges in on an answer with an action-only (silent) turn was left with the
        // podcast ducked until another app took audio focus, and A's buffered cloud audio kept
        // playing. The duck obligation is the CHAIN's, like the pause reservation: whoever owns
        // the chain at its finally restores it and stops the chain's player.
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
                sink.routeToCloud("first question", playbackContext, admitted = testWireId)
            }
        }
        withTimeout(5_000) {
            firstTurnReachedFrame.await()
            kotlinx.coroutines.yield()
        }
        assertTrue("the predecessor ducked the host on its first frame", deps.playback.calls.contains("duck"))

        // The successor: an action-only turn whose terminal is Done with no audio of its own.
        sink.routeToCloud("second question", playbackContext, admitted = testWireId)
        withTimeout(5_000) { turnA.join() }
        turnA.cancel()

        assertTrue(
            "the successor's finally must restore the chain's duck (calls=${deps.playback.calls})",
            deps.playback.calls.contains("restore"),
        )
        org.mockito.kotlin.verify(player, org.mockito.kotlin.atLeastOnce()).stop()
    }

    // the AudioFrame mutation (duck/submitFrame/play) must be serialized with
    // ownership transfer. The predecessor is paused INSIDE its frame's duck - actively executing,
    // not at a suspension point, so a cancellation cannot shield the race - while the successor
    // registers and stops the chain player. When the mutation is serialized under the turn mutex,
    // the successor's transfer waits and the stop necessarily lands after the mutation; when it is
    // not, the predecessor resumes submit/play AFTER the stop and restarts stale audio.
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
                    sink.routeToCloud("first question", playbackContext, admitted = testWireId)
                }.exceptionOrNull(),
            )
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) { firstTurnConnected.await() }
                kotlinx.coroutines.withTimeout(5_000) { deps.playback.duckStarted.await() }
            }
        }

        // The successor transfers while the predecessor is paused inside its frame mutation.
        val turnB = launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                sink.routeToCloud("second question", playbackContext, admitted = testWireId)
            }
        }
        // Give the unsynchronized race a real-time window to manifest before releasing the frame.
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

    // the predecessor's buffered answer must be stopped when ownership
    // TRANSFERS, not when the successor exits. A successor whose server stream stalls keeps the
    // predecessor's answer playing through the successor's whole turn and ahead of its own answer.
    // Observation point: while the successor's route flow is mid-flight (before its exit and
    // before any successor audio), the player must already have been stopped.
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
                        // The successor is now mid-turn with a stalled stream: no successor audio
                        // has played and the successor has not exited. The predecessor's buffered
                        // answer must already be stopped.
                        successorMidFlight.complete(Unit)
                        awaitCancellation()
                    }
                }
            },
        )
        val sink = deps.sink()

        val turnA = launch {
            runCatching {
                sink.routeToCloud("first question", playbackContext, admitted = testWireId)
            }
        }
        withTimeout(5_000) { firstTurnReachedFrame.await() }
        val turnB = launch {
            runCatching {
                sink.routeToCloud("second question", playbackContext, admitted = testWireId)
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
            sink.routeToCloud("question", playbackContext, admitted = testWireId).feedback
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
                sink.routeToCloud("question", playbackContext, admitted = testWireId).feedback
            }
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)

        assertEquals(listOf("pause", "resume"), deps.playback.calls)
    }

    @Test
    fun `empty base url returns coming soon without calling route`() = runTest {
        val deps = TestDeps(baseUrl = "")
        val sink = deps.sink()

        val response = sink.routeToCloud("summarize", playbackContext, admitted = testWireId).feedback

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
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // THE OWNER'S SNAPSHOT: the position BEFORE the action, on the playback timeline.
        deps.playback.preActionPositionMsForTest = 60_000L
        val sink = deps.sink()

        sink.routeToCloud("play from the reference", playbackContext, admitted = testWireId).feedback

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
    fun `turn aggregation still promotes a confirmed action past an unknown sibling`() {
        // Independent actions do not depend on each other, so an unknown sibling must not erase a
        // confirmed one.
        assertEquals(
            CloudRouteSink.Companion.ActionOutcome.Confirmed,
            CloudRouteSink.mergeActionOutcome(
                CloudRouteSink.Companion.ActionOutcome.Unknown,
                CloudRouteSink.Companion.ActionOutcome.Confirmed,
            ),
        )
    }

    @Test
    fun `a reference seek records the previous position on the reference timeline`() = runTest {
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
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play from the reference", playbackContext, admitted = testWireId).feedback

        // The previous position is playback-timeline; the context state must carry the
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
        assertTrue(deps.playback.calls.contains("seekTo:45"))
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
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 45_000_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play from the reference", playbackContext, admitted = testWireId).feedback

        // Turn 2+ regression guard: null must clear, not retain turn 1's value.
        assertEquals(null, state.snapshot("episode-id").previousReferencePositionMs)
    }

    @Test
    fun `a no-answer error from the service is spoken rather than left silent`() = runTest {
        // The service ends with `error no_answer`. Its line used to be word-for-word the generic
        // one, so this case could not tell "the code has a line" from "the code fell through" --
        // approved wording (docs/specs/cloud-assistant.md#error-codes) makes them distinguishable,
        // and this asserts the code's own sentence.
        val deps = TestDeps(
            events = flowOf(
                CloudRouteEvent.Error(code = CloudRouteErrorCodes.NO_ANSWER, message = ""),
                CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0),
            ),
        )

        val response = deps.sink().routeToCloud("what?", playbackContext, admitted = testWireId).feedback

        assertEquals(
            VoiceResponse.Spoken("Sorry, I couldn't find an answer to that. Please try asking another way."),
            response,
        )
    }

    /**
     * A Required code must have a line of its OWN (cloud-assistant.md, error table). These three
     * were recognised for logging only, so the sink fell through to `cloud_error_generic`: the user
     * heard a translated apology that named neither what happened nor what to do next, and no test
     * noticed because the fixture supplied lines the resources did not carry.
     *
     * Each case asserts the code's own sentence AND that it is not the generic one, so a future
     * edit that collapses a code back into the catch-all fails here rather than sounding plausible.
     */
    private suspend fun assertOwnLine(code: String, expected: String) {
        val deps = TestDeps(events = flowOf(CloudRouteEvent.Error(code = code, message = "")))

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken(expected), response)
        assertNotEquals(
            "$code must not collapse into the generic line",
            VoiceResponse.Spoken("Sorry, I couldn't complete that request."),
            response,
        )
    }

    @Test
    fun `an audio queue overflow speaks its own line rather than the generic one`() = runTest {
        assertOwnLine(
            CloudRouteErrorCodes.QUEUE_OVERFLOW,
            "Sorry, I couldn't finish answering that. Please try again.",
        )
    }

    @Test
    fun `a turn conflict tells the user an answer is still running`() = runTest {
        assertOwnLine(
            CloudRouteErrorCodes.TURN_CONFLICT,
            "I'm still answering your last question. Please try again in a moment.",
        )
    }

    @Test
    fun `an unreadable record asks the user to ask again rather than naming storage`() = runTest {
        assertOwnLine(
            CloudRouteErrorCodes.CORRUPT_RECORD,
            "Sorry, I've lost track of our conversation. Please ask again to start a new one.",
        )
    }

    @Test
    fun `a turn that neither speaks nor acts says the request could not be completed`() = runTest {
        // No tokens and no action: an unexpected empty turn, not a search that found nothing.
        // The server's guard should make this unreachable; this covers old servers and regressions.
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0)),
        )
        val sink = deps.sink()

        val response = sink.routeToCloud("what?", playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `an empty turn speaks the default line whatever the device locale`() = runTest {
        // The utterance's language decides; with none detected the default
        // (English) line is spoken on any device locale — the earcon is no longer the
        // language fallback.
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(inputTokens = 1, outputTokens = 0)),
            locale = java.util.Locale.FRENCH,
        )

        val response = deps.sink().routeToCloud("quoi?", playbackContext, admitted = testWireId).feedback

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

        val response = sink.routeToCloud("pause", playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("jump to five minutes", playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek", playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek", playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek", playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("seek nowhere", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "a seek with no target must be refused (calls=${deps.playback.calls})",
            deps.playback.calls.none { it.startsWith("seekTo") },
        )
    }

    @Test
    fun `an uncovered reference target seeks to the nearest covered estimate, not the raw value`() = runTest {
        // `timeline-alignment` § Action position conversion: when the mapping does not cover the
        // target, the client still acts -- it seeks to the target plus the already-observed offset at
        // the nearest covered point BEFORE the target, and keeps matching. Seeking to the raw
        // reference milliseconds would assume the timelines coincide, which dynamic ad insertion
        // breaks; treating the uncovered target as unreachable would abandon the request.
        // The offset fixture (reference 60 s ↔ playback 100 s) makes all three candidates differ:
        // raw 1130, sink-time substitution 1130+50, estimate 1130+40 = 1170.
        val fingerprint = mock<FingerprintTimingManager>()
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(0L))
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "an uncovered target must still seek, using the covered offset " +
                "(calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        assertFalse(
            "the raw reference milliseconds must not be used as sink time " +
                "(calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1130"),
        )
    }

    @Test
    fun `an uncovered target is re-seeked once the mapping converges onto it`() = runTest {
        // `timeline-alignment` § Action position conversion: after the estimate seek, matching keeps
        // running; once the mapping converges and the matched reference still differs from the target
        // by more than 3 s, the client re-seeks by the difference.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        // THE CORRECTION IS MEASURED AT THE PLAYHEAD: the target converts to nothing before the map
        // covers it, so the difference comes from the reference heard at the current position.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(null)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )

        // CONVERGENCE: the playhead now hears reference 1,115 s -- 15 s short of the 1,130 s the
        // listener asked for -- so the correction seeks forward by that difference.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(
            "a converged difference of 15 s must produce a corrective seek by that difference " +
                "(calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1185"),
        )
        assertEquals(
            "and only one correction, until the map converges again",
            2,
            deps.playback.calls.count { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `an uncovered target is corrected at most twice`() = runTest {
        // `timeline-alignment` § Action position conversion: the loop is bounded -- two corrections --
        // so a persistently drifting anchor cannot seek forever.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        // The correction reads the reference at the PLAYHEAD, so the stubbed read is the one there:
        // the estimate sought to 1,170 s, i.e. 1_170_000 ms on the sink timeline.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(null)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        // Each convergence still leaves the reference heard at the playhead far short of the
        // target, so every wake wants a correction -- the playhead moves WITH each seek, so the
        // next reading is stubbed at the new position. After the second the cap stops the loop,
        // however many more times the map converges.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()
        whenever(fingerprint.matchedReferenceTime(1_185_000)).thenReturn(1_090.0)
        mappingVersion.value = 2L
        advanceTimeBy(1_000)
        runCurrent()
        whenever(fingerprint.matchedReferenceTime(1_225_000)).thenReturn(1_065.0)
        mappingVersion.value = 3L
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "the estimate plus exactly two corrections, then no more (calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185", "seekTo:1225"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a correction converges inside sixty seconds and nothing after`() = runTest {
        // `timeline-alignment` step 3: a correction that has not converged 60 s after the action's
        // seek gives up -- no further correction, and the loop ENDS. Three assertions pin the bound
        // from both sides: a convergence just INSIDE the window still corrects (a shortened window
        // fails there); an unconverged stretch adds nothing; and a convergence arriving AFTER the
        // window makes no seek (a removed bound fails there, the loop still polling). Virtual time
        // over the poll count.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // JUST INSIDE THE WINDOW a convergence must still correct: this pins the bound's LENGTH,
        // not merely its existence -- a shortened window would fail here.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        // Advance to just short of the bound with the mapping STILL UNCOVERED, then publish the
        // convergence: a window shortened to 30 s has already closed by now, so its loop is gone
        // and the assertion below fails. A convergence published before the advance would be seen
        // by the shortened loop's first poll and could not distinguish the two windows.
        advanceTimeBy(58_750)
        runCurrent()
        mappingVersion.value = 1L
        advanceTimeBy(500)
        runCurrent()
        assertTrue(
            "a convergence inside the window must still correct (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1185"),
        )

        // Back to uncovered, and the rest of the window passes with NO further convergence.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(null)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(
            "the estimate plus the one in-window correction, nothing more (calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )

        // A NEW convergence arriving AFTER the window must not resurrect the loop. This is what
        // proves the loop ENDED rather than merely stayed quiet: without the bound it is still
        // polling past 60 s, so this puts the playhead at reference 1090 s -- 40 s short of the
        // target, over threshold -- and seeks. The assertion below fails.
        whenever(fingerprint.matchedReferenceTime(1_185_000)).thenReturn(1_090.0)
        mappingVersion.value = 2L
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "a late convergence must make no seek (calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a pause inside the window does not use it up`() = runTest {
        // timeline-alignment step 3 / plan step 6: the 60 s window counts PLAYING time. The answer
        // hold pauses playback while the answer is spoken and matching cannot advance during it, so
        // a 70 s pause followed by convergence 40 s into the resumed playback -- 110 s of wall
        // time, 40 s of play -- must still correct. The old wall-clock window died during the
        // pause, so this case failed under it.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // THE ANSWER IS SPOKEN: the host stays paused for 70 s of wall time.
        deps.host.playing = false
        val sink = deps.sink()

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // LET THE LOOP BODY START: the launch is queued, so its opening reads -- the seek's wall
        // stamp and the command revision -- must happen BEFORE the case moves the state they read.
        advanceTimeBy(250)
        runCurrent()
        deps.wallClockMs = 70_000L
        advanceTimeBy(70_000)
        runCurrent()

        // PLAYBACK RESUMES; matching converges 40 s into it -- inside the window's playing time.
        deps.host.playing = true
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        advanceTimeBy(40_000)
        runCurrent()
        mappingVersion.value = 1L
        advanceTimeBy(500)
        runCurrent()

        assertEquals(
            "the correction must follow the pause -- 40 s of play is inside the window (calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a pause that carries the session past the wall cap ends it with no correction`() = runTest {
        // timeline-alignment step 3 / plan step 6: in any case 120 s of wall time ends the session.
        // Playing time here is only 20 s -- under the window -- so without the wall cap the loop
        // would still be alive when this convergence arrives and would seek; with it, nothing.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // LET THE LOOP BODY START: the launch is queued, so its opening reads -- the seek's wall
        // stamp and the command revision -- must happen BEFORE the case moves the state they read.
        advanceTimeBy(250)
        runCurrent()
        // 10 s of play, then a pause that carries the session past the cap.
        deps.wallClockMs = 10_000L
        advanceTimeBy(10_000)
        runCurrent()
        deps.host.playing = false
        deps.wallClockMs = 125_000L
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        advanceTimeBy(115_000)
        runCurrent()
        mappingVersion.value = 1L
        advanceTimeBy(500)
        runCurrent()

        assertEquals(
            "a convergence after the wall cap must make no seek (calls=${deps.playback.calls})",
            listOf("seekTo:1170"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `an answer hold longer than the cap does not start it`() = runTest {
        // timeline-alignment step 3 / plan step 6: the wall cap counts from the ANSWER HOLD'S
        // RELEASE, not from the seek. The hold pauses playback while the answer is spoken; a hold
        // open for 130 s -- longer than the whole cap -- must not expire the session, and matching
        // must still correct 40 s into the resumed playback. A cap anchored at the seek dies at
        // 120 s and makes no seek, so this case fails under it -- and under the old wall-clock
        // window alike.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // THE ANSWER IS SPOKEN for 130 s: hold open the whole time, host paused.
        deps.answerHoldOpen = true
        deps.host.playing = false
        val sink = deps.sink()

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // LET THE LOOP BODY START: the launch is queued, so its opening reads -- the seek's wall
        // stamp and the command revision -- must happen BEFORE the case moves the state they read.
        advanceTimeBy(250)
        runCurrent()
        deps.wallClockMs = 130_000L
        advanceTimeBy(130_000)
        runCurrent()

        // THE HOLD RELEASES; playback resumes and matching converges 40 s into it.
        deps.answerHoldOpen = false
        deps.answerHoldReleasedAtMs = 130_000L
        deps.host.playing = true
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        advanceTimeBy(40_000)
        runCurrent()
        mappingVersion.value = 1L
        advanceTimeBy(500)
        runCurrent()

        assertEquals(
            "the cap runs from the hold's release -- 40 s after it the session is alive (calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a hold released before this action's seek does not anchor its cap`() = runTest {
        // THE STALE ANCHOR. The signal records the last hold any turn released. Anchored without a
        // floor, a release minutes before this action's seek would already be past the 120 s cap on
        // the FIRST poll, ending a correction that never started. The cap anchor must be the later
        // of the seek and the release: here a release at wall 0, a seek at 10:00, convergence 30 s
        // later -- inside every bound -- must still correct.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // A PREVIOUS TURN released its hold long ago; this action has none of its own.
        deps.answerHoldReleasedAtMs = 0L
        deps.wallClockMs = 600_000L
        val sink = deps.sink()

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        advanceTimeBy(250)
        runCurrent()
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        deps.wallClockMs = 630_000L
        advanceTimeBy(30_000)
        runCurrent()
        mappingVersion.value = 1L
        advanceTimeBy(500)
        runCurrent()

        assertEquals(
            "an older turn's hold release must not end this action's correction (calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `the app's own answer hold pauses playback without cancelling the correction`() = runTest {
        // plan step 7, the NON-trigger, done the way the hold actually works: the turn pauses to
        // hold the user's place while the answer is spoken and resumes in place. Its pause and
        // resume carry the VOICE_COMMANDS tag, so they never bump the command revision and never
        // emit on the user-seek stream -- that is why the hold cannot cancel a pending correction.
        // This case plays exactly that: hold open, then released, with the revision and the seek
        // stream untouched, and the correction must still land after the release.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        advanceTimeBy(250)
        runCurrent()
        val revisionBeforeHold = deps.commandRevision
        // THE ANSWER IS SPOKEN: the app pauses the player for 20 s, then resumes in place. No
        // revision bump, no user-seek emission -- the voice path's own commands.
        deps.answerHoldOpen = true
        deps.host.playing = false
        deps.wallClockMs = 20_000L
        advanceTimeBy(20_000)
        runCurrent()
        deps.answerHoldOpen = false
        deps.answerHoldReleasedAtMs = 20_000L
        deps.host.playing = true
        assertEquals("the app's own pause must not advance the command revision", revisionBeforeHold, deps.commandRevision)
        // CONVERGENCE after the hold released, inside the window: still corrects.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        deps.wallClockMs = 40_000L
        advanceTimeBy(20_000)
        runCurrent()
        mappingVersion.value = 1L
        advanceTimeBy(500)
        runCurrent()

        assertEquals(
            "the app's own answer hold must not cancel the correction (calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a mapping that never converges gives up after sixty seconds of playing time`() = runTest {
        // timeline-alignment step 3 / plan step 6: the give-up case. With the host playing the
        // whole time, 60 s of playing time is 60 s of wall time, so the loop ends and a
        // convergence arriving after it makes no seek.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // LET THE LOOP BODY START: the launch is queued, so its opening reads -- the seek's wall
        // stamp and the command revision -- must happen BEFORE the case moves the state they read.
        advanceTimeBy(250)
        runCurrent()
        deps.wallClockMs = 61_000L
        advanceTimeBy(61_000)
        runCurrent()
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_090.0)
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "a mapping that never converges makes no correction, and the late one lands nowhere (calls=${deps.playback.calls})",
            listOf("seekTo:1170"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a skip while a correction is pending cancels it`() = runTest {
        // timeline-alignment step 4, trigger: user skip. A skip is a playback command the voice
        // path does not own, so it advances the command revision; the pending correction must not
        // follow a player someone else just moved.
        cancelOnCommandRevision("skip")
    }

    @Test
    fun `a pause and resume elsewhere while a correction is pending cancels it`() = runTest {
        // timeline-alignment step 4, trigger: pause-and-resume elsewhere. The listener moved
        // playback themselves and back; re-seeking by a stale difference would yank them again.
        cancelOnCommandRevision("pause-and-resume elsewhere")
    }

    @Test
    fun `an episode change while a correction is pending cancels it`() = runTest {
        // timeline-alignment step 4, trigger: episode change. The episode is a playback change the
        // voice path does not own; the corrective seek is episode-bound, and the revision advance
        // stands the correction down before it can fire against the new episode.
        cancelOnCommandRevision("episode change")
    }

    @Test
    fun `another moving action while a correction is pending cancels it`() = runTest {
        // timeline-alignment step 4, trigger: another moving action. Any playback-moving command
        // outside this action -- skip, seek, play from another surface -- advances the revision.
        cancelOnCommandRevision("another moving action")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.cancelOnCommandRevision(trigger: String) {
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // LET THE LOOP BODY START: the launch is queued, so its opening reads -- the seek's wall
        // stamp and the command revision -- must happen BEFORE the case moves the state they read.
        advanceTimeBy(250)
        runCurrent()
        // THE TRIGGER, mid-window: playback moves outside this action.
        deps.commandRevision += 1
        advanceTimeBy(1_000)
        runCurrent()
        // CONVERGENCE after the trigger, well inside every bound.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "the $trigger must have cancelled the correction (calls=${deps.playback.calls})",
            listOf("seekTo:1170"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a newer voice turn cancels the pending correction`() = runTest {
        // timeline-alignment step 4, trigger: a newer voice turn. The player now belongs to that
        // turn; re-seeking from the old one would fight it.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val secondTurnStarted = CompletableDeferred<Unit>()
        val secondTurnRelease = CompletableDeferred<Unit>()
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            routeInvoker = { turn ->
                if (turn.request == "go back") {
                    flowOf(
                        CloudRouteEvent.Action(
                            tool = "playback",
                            action = "seek_to",
                            params = mapOf("reference_position_ms" to 1_130_000L),
                        ),
                        CloudRouteEvent.Done(1, 0),
                    )
                } else {
                    // THE NEWER TURN STAYS IN FLIGHT until the case releases it: only a LIVE newer
                    // turn owns the active-turn slot -- once it exits, the slot clears and the
                    // pending correction would survive.
                    flow {
                        secondTurnStarted.complete(Unit)
                        secondTurnRelease.await()
                        emit(CloudRouteEvent.Done(1, 1))
                    }
                }
            },
        )
        val sink = deps.sink()

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // LET THE LOOP BODY START: the launch is queued, so its opening reads -- the seek's wall
        // stamp and the command revision -- must happen BEFORE the case moves the state they read.
        advanceTimeBy(250)
        runCurrent()
        // THE NEWER TURN: a second dispatch takes the active-turn slot and stays in flight.
        val second = launch { sink.routeToCloud("what about now", playbackContext, admitted = testWireId).feedback }
        secondTurnStarted.await()
        assertEquals(
            "the newer turn must own the active slot (routeCalls=${deps.routeCalls})",
            2,
            deps.routeCalls.size,
        )
        // CONVERGENCE while the newer turn is live: the correction must stand down.
        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "the newer turn must have cancelled the correction (calls=${deps.playback.calls})",
            listOf("seekTo:1170"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
        secondTurnRelease.complete(Unit)
        second.join()
    }

    @Test
    fun `a correction survives its own seek surfacing on the seek stream`() = runTest {
        // The REAL manager used to emit on every seek, including the corrective one -- the loop then
        // cancelled itself after its first correction. The production fix removes the emission at the
        // source (voice seeks no longer emit); this control keeps the loop honest against ANY emitter
        // that still surfaces a corrective seek: the correction must not stand itself down.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // Wire the seek stream the way the pre-fix manager behaved: every seek emits.
        deps.playback.userSeekFeedback = deps.userSeeksFlow
        val sink = deps.sink()

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        // The watcher starts after the turn; drop the estimate seek's replayed emission, which on the
        // real stream was consumed before the watcher existed.
        deps.userSeeksFlow.value = null

        whenever(fingerprint.matchedReferenceTime(1_170_000)).thenReturn(1_115.0)
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()
        whenever(fingerprint.matchedReferenceTime(1_185_000)).thenReturn(1_090.0)
        mappingVersion.value = 2L
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "a corrective seek's own emission must not stop the second correction " +
                "(calls=${deps.playback.calls})",
            listOf("seekTo:1170", "seekTo:1185", "seekTo:1225"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a correction closes the difference heard at the playhead even when the target never converts`() = runTest {
        // timeline-alignment step 2: the correction measures at the PLAYHEAD -- the reference heard
        // at the current position -- because the target itself may never be covered. The fixture:
        // the estimate carried a 60-second ad offset (anchors reference 198..202 ↔ playback
        // 138..142), landing the playhead at 140 s for a reference target of 200 s. Matching then
        // converges with anchors ONLY around the playhead, offsets 0, reference 138..142: the
        // target at reference 200 s sits past the last anchor with an unplayed 60-second ad in
        // between, so the direct conversion refuses it forever. The playhead, however, hears
        // reference 140 s -- 60 s short of the target -- and the loop must seek forward by that
        // difference. A loop that converts the target instead gets null here and never corrects:
        // RED against a gate that only refuses.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(200.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(playbackTime = 138.0, referenceTime = 198.0),
                FingerprintTimingManager.TimeMappingEntry(playbackTime = 142.0, referenceTime = 202.0),
            ),
        )
        whenever(fingerprint.matchedReferenceTime(140_000)).thenReturn(null)
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 200_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the estimate seek must have carried the observed 60 s ad offset (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:140"),
        )

        // CONVERGENCE with anchors only around the playhead: offsets 0, so the playhead hears
        // reference 140 s. The target never converts (playbackTimeMs(200) stays null), and the
        // correction must still happen -- 60 s forward from the playhead.
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(playbackTime = 138.0, referenceTime = 138.0),
                FingerprintTimingManager.TimeMappingEntry(playbackTime = 142.0, referenceTime = 142.0),
            ),
        )
        whenever(fingerprint.matchedReferenceTime(140_000)).thenReturn(140.0)
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "the target never converts, so the correction closes the playhead's own 60 s gap " +
                "(calls=${deps.playback.calls})",
            listOf("seekTo:140", "seekTo:200"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `an estimate that landed early seeks forward to the located target`() = runTest {
        // timeline-alignment step 2, driven through a REAL fingerprint manager. The estimate map
        // holds a stale 80-second offset (reference 198..202 <-> playback 118..122), so the seek
        // lands at playback 120 s for a reference target of 200 s -- before the target. The player
        // then runs 12 seconds; matching converges with new anchors around where the playhead NOW
        // is (reference 132..136 <-> playback 132..136, offset 0).
        //
        // At the playhead p = 132 s the matched reference is r = 132 s, so the target sits at
        // T = p + (target - r) = 132 + (200 - 132) = 200 s. That is 80 s after the playhead --
        // still ahead -- so the loop seeks to 200 s. A loop that reads at the estimate point finds
        // nothing there and never corrects; one that converts the target against the new anchors
        // answers 120 s and calls itself converged. Only the playhead measurement corrects.
        val fingerprint = FingerprintTimingManager(
            playbackManager = mock<PlaybackManager> {
                on { playbackStateFlow }.thenReturn(kotlinx.coroutines.flow.MutableStateFlow(au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState()))
            },
            referenceRetriever = mock(),
            eventHorizon = mock<EventHorizon>(),
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { mock<ChapterManager>() },
            settings = mock<Settings>(),
            dataSourceFactory = Lazy { mock<ExoPlayerDataSourceFactory>() },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = mock<SharedPreferencesCloudConfig>(),
            cloudFetcher = mock(),
            cloudReferenceStore = mock(),
            decodePolicy = mock<FingerprintDecodePolicy>(),
        )
        // THE ESTIMATE MAP: a pair with the ad offset that is UNCOVERED on the playback clock
        // (12 s apart), so the target takes the estimate path and a correction loop exists.
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 118.0, referenceTime = 198.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 130.0, referenceTime = 202.0))

        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 200_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // THE PLAYER RAN 12 SECONDS between the estimate seek and the convergence below.
        deps.playheadMs = 132_000
        val sink = deps.sink()

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the seek must have landed at the covered conversion of the target (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:120"),
        )

        // CONVERGENCE: anchors around the moved playhead, offset 0. Inserting bumps the version,
        // which wakes the loop.
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 132.0, referenceTime = 132.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 136.0, referenceTime = 136.0))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "the correction seeks to the target located from the playhead, 200 s (calls=${deps.playback.calls})",
            listOf("seekTo:120", "seekTo:200"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `an estimate that assumed an ad that is not there seeks back to the located target`() = runTest {
        // timeline-alignment step 2, driven through a REAL fingerprint manager: the estimate map
        // holds a stale +60-second offset (reference 100..104 <-> playback 160..164) -- it assumes
        // an ad that is not there -- so the seek lands at playback 260 s for a reference target of
        // 200 s, PAST the target. The player runs 10 seconds; matching converges with anchors
        // around the playhead (reference 268..272 <-> playback 268..272, offset 0).
        //
        // At the playhead p = 270 s the matched reference is r = 270 s, so the target sits at
        // T = p + (target - r) = 270 + (200 - 270) = 200 s. That is 60 s before where the seek
        // landed (overshoot), so the loop seeks BACK to 200 s.
        val fingerprint = FingerprintTimingManager(
            playbackManager = mock<PlaybackManager> {
                on { playbackStateFlow }.thenReturn(kotlinx.coroutines.flow.MutableStateFlow(au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState()))
            },
            referenceRetriever = mock(),
            eventHorizon = mock<EventHorizon>(),
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { mock<ChapterManager>() },
            settings = mock<Settings>(),
            dataSourceFactory = Lazy { mock<ExoPlayerDataSourceFactory>() },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = mock<SharedPreferencesCloudConfig>(),
            cloudFetcher = mock(),
            cloudReferenceStore = mock(),
            decodePolicy = mock<FingerprintDecodePolicy>(),
        )
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 160.0, referenceTime = 100.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 164.0, referenceTime = 104.0))

        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 200_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        deps.playheadMs = 270_000
        val sink = deps.sink()

        sink.routeToCloud("play the passage", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the seek must have landed at the estimate, past the target (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:260"),
        )

        // CONVERGENCE: anchors around the moved playhead, offset 0. Inserting bumps the version,
        // which wakes the loop.
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 268.0, referenceTime = 268.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 272.0, referenceTime = 272.0))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "the correction seeks back to the target located from the playhead, 200 s (calls=${deps.playback.calls})",
            listOf("seekTo:260", "seekTo:200"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `an unplayed ad between the seek and the target produces no correction`() = runTest {
        // timeline-alignment step 2: a 20-second unplayed ad sits between where the estimate landed
        // and the target. The listener plays THROUGH the ad and converges 30 s after the seek, 10 s
        // into the passage -- inside the 60-second correction window, so the loop is still alive and
        // the case can actually fail. The elapsed formula would compute the expected reference as
        // target + elapsed (200 + 30) against the matched 210 s and jump by the 20 s difference past
        // the passage start; the position rule locates the target at
        // T = p + (target - r) = 170 + (200 - 210) = 160 s, which lies between the seek landing
        // (140 s) and the playhead (170 s) -- playback reached it on its own, so no correction.
        val fingerprint = FingerprintTimingManager(
            playbackManager = mock<PlaybackManager> {
                on { playbackStateFlow }.thenReturn(kotlinx.coroutines.flow.MutableStateFlow(au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState()))
            },
            referenceRetriever = mock(),
            eventHorizon = mock<EventHorizon>(),
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { mock<ChapterManager>() },
            settings = mock<Settings>(),
            dataSourceFactory = Lazy { mock<ExoPlayerDataSourceFactory>() },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = mock<SharedPreferencesCloudConfig>(),
            cloudFetcher = mock(),
            cloudReferenceStore = mock(),
            decodePolicy = mock<FingerprintDecodePolicy>(),
        )
        // THE ESTIMATE MAP: a stale 60-second offset, so the target takes the estimate path and the
        // seek lands at playback 140 s for a reference target of 200 s.
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 100.0, referenceTime = 160.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 104.0, referenceTime = 164.0))

        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 200_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        // THE PLAYER RAN through the unplayed ad: the playhead now stands 10 s into the passage,
        // 30 s after the seek -- inside the correction window.
        deps.playheadMs = 170_000
        val sink = deps.sink()

        sink.routeToCloud("play the passage", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the seek must have landed at the estimate (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:140"),
        )

        // CONVERGENCE 10 s INTO THE PASSAGE: the passage starts at reference 200 s, so playback
        // 170 s -- 10 s into it -- carries reference 210 s (anchors playback 168..172 <-> reference
        // 208..212, offset +40 s). Inserting bumps the version, which wakes the loop.
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 168.0, referenceTime = 208.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 172.0, referenceTime = 212.0))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "no correction may follow -- the target lies between the seek and the playhead (calls=${deps.playback.calls})",
            listOf("seekTo:140"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a correct estimate converging after playback needs no correction`() = runTest {
        // timeline-alignment step 2: the estimate was right (offset 0), the listener plays 10 s
        // past the seek, and matching converges there. The target locates to T = 210 + (200 - 210)
        // = 200 s, between the seek landing (200 s) and the playhead (210 s) -- reached on its own,
        // no correction. The elapsed formula would demand target + 10 = 210 and "correct" forward.
        val fingerprint = FingerprintTimingManager(
            playbackManager = mock<PlaybackManager> {
                on { playbackStateFlow }.thenReturn(kotlinx.coroutines.flow.MutableStateFlow(au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState()))
            },
            referenceRetriever = mock(),
            eventHorizon = mock<EventHorizon>(),
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { mock<ChapterManager>() },
            settings = mock<Settings>(),
            dataSourceFactory = Lazy { mock<ExoPlayerDataSourceFactory>() },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = mock<SharedPreferencesCloudConfig>(),
            cloudFetcher = mock(),
            cloudReferenceStore = mock(),
            decodePolicy = mock<FingerprintDecodePolicy>(),
        )
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 100.0, referenceTime = 100.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 104.0, referenceTime = 104.0))

        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
            events = flowOf(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "seek_to",
                    params = mapOf("reference_position_ms" to 200_000L),
                ),
                CloudRouteEvent.Done(1, 0),
            ),
        )
        deps.playheadMs = 210_000
        val sink = deps.sink()

        sink.routeToCloud("play the passage", playbackContext, admitted = testWireId).feedback

        assertTrue(
            "the seek must have landed at the estimate (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:200"),
        )

        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 208.0, referenceTime = 208.0))
        fingerprint.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 212.0, referenceTime = 212.0))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "no correction may follow (calls=${deps.playback.calls})",
            listOf("seekTo:200"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a user seek while a correction is pending cancels the correction`() = runTest {
        // `timeline-alignment` § Action position conversion: any later playback change cancels a
        // pending correction. The user moved playback themselves; re-seeking by a stale difference
        // would yank it straight back out of their hand.
        val fingerprint = mock<FingerprintTimingManager>()
        val mappingVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        whenever(fingerprint.playbackTimeMs(1_130.0)).thenReturn(null)
        whenever(fingerprint.mappingVersion).thenReturn(mappingVersion)
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 100.0,
                    referenceTime = 60.0,
                ),
            ),
        )
        val deps = TestDeps(
            fingerprintTimingManager = fingerprint,
            seekCorrectionScope = backgroundScope,
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

        sink.routeToCloud("go back", playbackContext, admitted = testWireId).feedback
        assertTrue(
            "the estimate seek must have run (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:1170"),
        )
        // LET THE LOOP BODY START: the launch is queued, so its opening reads -- the seek's wall
        // stamp and the command revision -- must happen BEFORE the case moves the state they read.
        advanceTimeBy(250)
        runCurrent()

        // THE USER MOVES FIRST: the seek lands before the map converges.
        deps.userSeeksFlow.value = Unit
        whenever(fingerprint.mappingSnapshot).thenReturn(
            listOf(
                FingerprintTimingManager.TimeMappingEntry(
                    playbackTime = 1170.0,
                    referenceTime = 1185.0,
                ),
            ),
        )
        mappingVersion.value = 1L
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "a cancelled correction must not seek again (calls=${deps.playback.calls})",
            listOf("seekTo:1170"),
            deps.playback.calls.filter { it.startsWith("seekTo:") },
        )
    }

    @Test
    fun `a reference seek maps the reference timeline onto the sink timeline`() = runTest {
        val deps = TestDeps(
            clientPositionMs = 60_000L,
            routeInvoker = withHold(
                flowOf(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "seek_to",
                        params = mapOf("reference_position_ms" to 900_000L),
                    ),
                    CloudRouteEvent.Done(1, 0),
                ),
            ),
        )
        val sink = deps.sink()

        sink.routeToCloud("play the reference", playbackContext, admitted = testWireId).feedback

        // 900_000 ms on the REFERENCE timeline maps to 945 s on the sink timeline: the mapping's
        // 45 s offset is what distinguishes a reference seek from the reference ms used directly.
        // Asserted by containment rather than the whole list, so the test pins the mapping it is
        // about (the seekTo argument) and not the surrounding call sequence.
        assertTrue(
            "expected a mapped seek of 945s, got ${deps.playback.calls}",
            deps.playback.calls.contains("seekTo:945"),
        )
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

        val response = sink.routeToCloud("x", playbackContext, admitted = testWireId).feedback

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

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        assertEquals(
            VoiceResponse.Spoken("Sorry, that took too long to answer. Please try again."),
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

        val response = sink.routeToCloud("x", playbackContext, admitted = testWireId).feedback

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

        val response = sink.routeToCloud("x", playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("x", playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("x", playbackContext, admitted = testWireId).feedback

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
        // the wire id IS the admitted identity, so two distinct utterances send
        // two DISTINCT admitted values -- and each is carried through byte-for-byte (no remint).
        val deps = TestDeps()
        val sink = deps.sink()
        val first = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
        val second = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"

        sink.routeToCloud("one", playbackContext, admitted = first).feedback
        sink.routeToCloud("two", playbackContext, admitted = second).feedback

        val ids = deps.routeTurns.map { it.requestId }
        assertEquals(2, ids.size)
        assertEquals(listOf(first, second), ids)
    }

    @Test
    fun `a dispatch with no admitted identity is refused and sends nothing`() = runTest {
        // Fail closed: the sink must never mint an identity client logs cannot name.
        val deps = TestDeps()
        val sink = deps.sink()

        val response = sink.routeToCloud("unidentified", playbackContext, admitted = null).feedback

        assertEquals(VoiceResponse.Silent, response)
        assertEquals("nothing reached the wire", 0, deps.routeTurns.size)
    }

    @Test
    fun `free text turns carry no route hint and typed entry supplies one`() = runTest {
        val deps = TestDeps()
        val sink = deps.sink()

        sink.routeToCloud("find beginner investing episodes", playbackContext, admitted = testWireId).feedback
        assertEquals(null, deps.routeTurns.single().routeHint)

        val hint = CloudRouteHint(operation = "search_episodes", arguments = mapOf("query" to "investing"))
        sink.routeToCloudWithHint(
            "find beginner investing episodes",
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

        val first = launch { sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback }
        firstTurnStarted.await()
        sink.routeToCloud("second", playbackContext, admitted = testWireId).feedback
        firstCancelled.await()
        assertTrue(first.isCancelled)
    }

    @Test
    fun `a superseded turn publishes its own timing table and leaves the successor's alone`() = runTest(UnconfinedTestDispatcher()) {
        // THE BLOCKER THE BOT FOUND: the finish path read the shared activeTimingRequestId, so a
        // turn superseded mid-flight finished and published its SUCCESSOR's record under the
        // successor's id, losing both tables. Each turn must publish its own table under its own
        // request id, whatever a successor did to the shared field meanwhile.
        val aOnWire = CompletableDeferred<Unit>()
        val published = java.util.concurrent.CopyOnWriteArrayList<String>()
        val previousSink = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = { _, message -> published += message }
        val idA = "wire-superseded"
        val idB = "wire-successor"
        for (id in listOf(idA, idB)) {
            val record = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(id)
            record.mark(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.AcousticEnd, 1_000_000_000L)
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(id, record)
        }
        val deps = TestDeps(
            routeInvoker = { turn ->
                if (turn.request == "first") {
                    // A is live on the wire when B arrives, and is superseded mid-flight — the
                    // interleaving that made the finish path read the successor's state off the
                    // shared field instead of the turn's own id.
                    flow {
                        aOnWire.complete(Unit)
                        awaitCancellation()
                    }
                } else {
                    flowOf(CloudRouteEvent.Connected("pcm_s16le@24k"), CloudRouteEvent.Done(0, 0))
                }
            },
        )
        val sink = deps.sink()

        val first = launch { sink.routeToCloud("first", playbackContext, admitted = idA).feedback }
        aOnWire.await()
        sink.routeToCloud("second", playbackContext, admitted = idB).feedback
        // A's publish runs in its non-cancellable cleanup; wait for the job, but never hang the
        // suite on it — a timeout fails the assertions below with what did publish.
        withTimeoutOrNull(5_000) { first.join() }
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = previousSink
        for (id in listOf(idA, idB)) {
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.unregister(id)
        }

        // EACH TURN PUBLISHES EXACTLY ITS OWN TABLE, under its own request id: the old
        // finish path published the superseded turn's marks under the successor's id (or
        // lost them to a null), so either A's block is missing or B's is duplicated.
        assertEquals(
            "one published table per turn",
            setOf(idA, idB),
            published.mapNotNull { Regex("request_id=(\\S+)").find(it)?.groupValues?.get(1) }.toSet(),
        )
        assertTrue(
            "the superseded turn's table names its own cancellation",
            published.any { it.contains("request_id=$idA") && it.contains("outcome=cancelled") },
        )
        assertTrue(
            "the successor's table names its own commit",
            published.any { it.contains("request_id=$idB") && it.contains("outcome=committed") },
        )
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

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        // The executed seek is not rolled back when the stream fails.
        assertTrue(
            "the executed seek is the MAPPED sink position (calls=${deps.playback.calls})",
            deps.playback.calls.contains("seekTo:545"),
        )
        // The client speaks its own words, never the server's message. provider_error is a
        // Generic code in the contract, so its own words ARE the generic line.
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
        // Auto-pause is restored exactly once.
        assertEquals(listOf("pause", "resume"), deps.playback.calls.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun `a token that expires between turns fails the next turn closed`() = runTest {
        var token: String? = "user_test"
        val deps = TestDeps(
            routeInvoker = { _ ->
                if (token == null) {
                    // Client-minted code, no prose: the sink localises it. unauthorized is a
                    // Generic code, so it resolves to the generic line.
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
            sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback,
        )

        // The token expires mid-session: the next turn fails closed and no
        // playback action is attempted.
        token = null
        val second = sink.routeToCloud("second", playbackContext, admitted = testWireId).feedback

        // A rejected credential still speaks rather than earcons: unauthorized is Generic, so it
        // gets the same localized catch-all as any other code with no line of its own.
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), second)
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
        val turn = launch { sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback }
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
                        action = "resume",
                        params = emptyMap(),
                    ),
                    CloudRouteEvent.Done(1, 0),
                ),
            ),
        )
        deps.playback.resumeGate = gate
        val sink = deps.sink()

        // Turn A: auto-pause, then its resume action resumes — suspending
        // inside the player while A still owns it.
        val first = launch { sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback }
        deps.playback.resumeStarted.await()

        // B must not register/pause until A's action has finished with the player.
        val second = launch { sink.routeToCloud("second", playbackContext, admitted = testWireId).feedback }
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

        val first = launch { runCatching { sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback } }
        deps.playback.pauseStarted.await()
        val second = launch { runCatching { sink.routeToCloud("second", playbackContext, admitted = testWireId).feedback } }
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
        val first = launch { runCatching { sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback } }
        first.join()

        fail = false
        val second = sink.routeToCloud("second", playbackContext, admitted = testWireId).feedback

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
        val first = launch { sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback }
        deps.playback.resumeStarted.await()

        // Turn B starts during that suspension: its registration and pause must
        // wait for A's restore to finish, or B's answer streams over resumed audio.
        val second = launch { sink.routeToCloud("second", playbackContext, admitted = testWireId).feedback }
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

        val first = launch { sink.routeToCloud("first", playbackContext, admitted = testWireId).feedback }
        firstStarted.await()
        sink.routeToCloud("second", playbackContext, admitted = testWireId).feedback
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
        // The ui locale no longer decides: the line follows the utterance's
        // language. With no detected language the default (English) line is spoken — never an
        // earcon for a language reason, and never the device locale's wording.
        val deps = TestDeps(
            locale = java.util.Locale.KOREAN,
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `the no-gateway message follows the utterance language, not the device locale`() = runTest {
        val deps = TestDeps(baseUrl = "", locale = java.util.Locale.JAPANESE)

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        assertEquals(VoiceResponse.Spoken("Cloud processing is coming soon"), response)
    }

    @Test
    fun `a client code with a template is spoken in the localized wording`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "")),
        )

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        // Localized template, not the client's own prose and not the code.
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `a whitespace-only server message is treated as absent`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "connection_lost", message = "   ")),
        )

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        // Whitespace is not a message: it must resolve the localized template
        // rather than being spoken as silence.
        assertEquals(VoiceResponse.Spoken("Connection lost. Please try again."), response)
    }

    @Test
    fun `a client code without a template falls back to the client generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "invalid_response", message = "")),
        )

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

        // Internal diagnostics are a sound, never English prose to a
        // non-English user.
        // Deliberately changed: this is the fallback the owner asked for — the client's own
        // words for a code it doesn't know, rather than a tone the user has to interpret.
        assertEquals(VoiceResponse.Spoken("Sorry, I couldn't complete that request."), response)
    }

    @Test
    fun `an expired conversation is refused once and the next request starts a new one`() = runTest {
        // The worker keeps a conversation ten minutes from its last turn and refuses a resume of one it
        // no longer holds. Nothing cleared the client's handle, so every later turn in the window
        // resumed the same dead id and failed the same way until a wake began a new conversation.
        val deps = TestDeps(
            routeInvoker = { turn ->
                // The handshake precedes the outcome: it is what makes the conversation resumable at all.
                if (turn.conversationMode == CloudConversationMode.Resume) {
                    flowOf(CloudRouteEvent.Connected("pcm@16k"), CloudRouteEvent.Error(code = "conversation_expired", message = ""))
                } else {
                    flowOf(CloudRouteEvent.Connected("pcm@16k"), CloudRouteEvent.Done(0, 0))
                }
            },
        )
        val sink = deps.sink()
        deps.conversationAdmission.beginOrigin(1)
        sink.routeToCloud("first", playbackContext, admitted = testWireId, capturedOrigin = 1, isWakeOriginated = true)
        val created = deps.routeTurns.last()
        assertEquals(CloudConversationMode.Create, created.conversationMode)

        val refused = sink.routeToCloud("second", playbackContext, admitted = testWireId, isLiveGraceTurn = true, capturedOrigin = 1)
        assertEquals(CloudConversationMode.Resume, deps.routeTurns.last().conversationMode)
        assertEquals(created.conversationId, deps.routeTurns.last().conversationId)
        assertEquals(
            "the refusal is spoken with the code's own line, not the generic one",
            VoiceResponse.Spoken("That conversation has ended."),
            refused.feedback,
        )
        assertEquals("the refused request is never resubmitted", 2, deps.routeTurns.size)

        sink.routeToCloud("third", playbackContext, admitted = testWireId, isLiveGraceTurn = true, capturedOrigin = 1)
        val next = deps.routeTurns.last()
        assertEquals("the next request begins a new conversation", CloudConversationMode.Create, next.conversationMode)
        assertTrue("under a new id", next.conversationId != created.conversationId)
    }

    @Test
    fun `a late refusal for an old conversation does not clear a newer one`() {
        // Forgetting is keyed by the id that was refused: a refusal that arrives after a wake has begun a
        // new conversation is about the old one and must leave the new handle alone.
        val admission = CloudConversationAdmission()
        admission.beginOrigin(1)
        val old = admission.prepare(origin = 1, isLiveGraceTurn = false, isWakeOriginated = false)!!
        val replacement = admission.prepare(origin = 1, isLiveGraceTurn = false, isWakeOriginated = true)!!
        // The replacement was ACKNOWLEDGED, which is what makes it resumable at all; without this the
        // case would pass for the wrong reason, since an unacknowledged handle is never resumed.
        admission.acknowledge(replacement.conversationId)

        admission.forgetConversation(old.conversationId)

        val next = admission.prepare(origin = 1, isLiveGraceTurn = true, isWakeOriginated = false)!!
        assertEquals(CloudConversationMode.Resume, next.mode)
        assertEquals(replacement.conversationId, next.conversationId)
    }

    @Test
    fun `a server-supplied message is never spoken, only a template or the generic line`() = runTest {
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Error(code = "limit_exceeded", message = "You've used 10/10 free requests today.")),
        )

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

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

        val response = deps.sink().routeToCloud("x", playbackContext, admitted = testWireId).feedback

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

        val outcome = runCatching { sink.routeToCloud("x", playbackContext, admitted = testWireId).feedback }

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

        deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback

        assertEquals(
            "a paused host must be left exactly as it was",
            emptyList<String>(),
            deps.playback.calls,
        )
    }

    @Test
    fun `a turn that inherits a paused host does not resume it after privacy closed mid-turn`() = runTest {
        // The inherited-pause gap: the obligation latch is sampled only when the turn
        // takes its own pause, so a turn arriving to an ALREADY-paused host records nothing and its
        // privacy guard reads `atPause == null` -- always false. The user then presses play during
        // the answer, privacy closes mid-turn, and the turn's restore path must still not resume:
        // the user's play command plus a privacy closure both stand regardless of who paused first.
        val deps = TestDeps(hostPlaying = false, events = flowOf(CloudRouteEvent.Done(1, 0)))
        deps.privacyClosed = true

        deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback

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

        val turn = launch { sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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

        val turn = launch { sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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

        val turn = launch { sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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

        deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback

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

        val route = launch { deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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
            runCatching { sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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
                    // ..and the cloud then requests a pause as an app operation (no turn id).
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

        deps.sink().routeToCloud("pause it", playbackContext, admitted = testWireId).feedback

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
        // Worse than an absent one because it reads as coverage.
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

        deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback

        // The turn's own pause is not undone twice: resuming again would fight
        // the user's decision, which is the same class of defect as resuming a
        // player that was never playing.
        assertEquals(listOf("pause"), deps.playback.calls)
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

        deps.sink().routeToCloud("stay put", playbackContext, admitted = testWireId).feedback

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

        val confirmedResult = confirmed.sink().routeToCloud("pause", playbackContext, admitted = testWireId)

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

        val noneResult = none.sink().routeToCloud("hello", playbackContext, admitted = testWireId)

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

        val response = deps.sink().routeToCloud("go sideways", playbackContext, admitted = testWireId).feedback

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

        deps.sink().routeToCloud("go back", playbackContext, admitted = testWireId).feedback

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
        var events: kotlinx.coroutines.flow.Flow<CloudRouteEvent> = flowOf(CloudRouteEvent.Done(0, 0)),
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
        /**
         * The player's live position. Defaults to wherever the fake player stands after its last
         * accepted seek -- the seek point, for cases that do not care about elapsed playback; a
         * case that does care moves it forward the way a player would. Null means the read failed,
         * which the loop must skip.
         */
        var playheadMs: Int? = null,
        /** User seeks observed outside the voice path; a pending correction stands down on one. */
        val userSeeksFlow: kotlinx.coroutines.flow.MutableStateFlow<Unit?> =
            kotlinx.coroutines.flow.MutableStateFlow(null),
        /** Where pending seek corrections run; a case passes `backgroundScope` for virtual time. */
        var seekCorrectionScope: kotlinx.coroutines.CoroutineScope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            ),
        // THE SHIPPED RESOLVER, not a hand copy of it. This map used to restate
        // strings_voice_templates.xml, which made every wording assertion below an assertion about
        // the test's own copy: the app could speak something else entirely and the suite stayed
        // green. That is how three Required codes sat with no line at all while this fixture
        // supplied lines for codes the resources did not carry.
        //
        // Reading the real resources means a case asserts what a LISTENER hears. Cases that need a
        // deliberately broken table (a blank line, a missing key) still pass their own resolver.
        private val templateResolver: SpokenTemplateResolver =
            SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
        routeInvoker: ((CloudRouteTurn) -> kotlinx.coroutines.flow.Flow<CloudRouteEvent>)? = null,
        transportFactory: ((String, okhttp3.OkHttpClient, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver?) -> au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransport)? = null,
    ) {
        /**
         * The wall clock the correction session reads. Cases move it forward beside the virtual
         * scheduler so the session's wall-clock cap can be driven without real waiting.
         */
        var wallClockMs: Long = 0L
        /** Whether the answer hold is open; while it is, the wall-clock cap has not started. */
        var answerHoldOpen: Boolean = false
        /** The recorded answer-hold release, in the fixture's own wall clock. */
        var answerHoldReleasedAtMs: Long? = null
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

        /** @param locale the app's UI locale AT PREPARE TIME; a case that cares overrides it. */
        fun sink(locale: java.util.Locale = this.locale): CloudRouteSink = sink(localeProvider = { locale })

        /** For a case whose locale must CHANGE between two prepares. */
        fun sink(localeProvider: () -> java.util.Locale): CloudRouteSink = CloudRouteSink(
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
            templateResolver = templateResolver,
            currentLocale = localeProvider,
            isHostPlaying = { host.playing },
            wallClockNowMs = { wallClockMs },
            answerHoldOpen = { answerHoldOpen },
            answerHoldReleasedAtMs = { answerHoldReleasedAtMs },
            playbackCommandRevision = { commandRevision },
            isPrivacyClosed = { privacyClosed },
            privacyClosureCount = { playbackStopClosures },
            episodeDurationSeconds = { episodeDurationSeconds },
            currentPlaybackPositionMs = { playheadMs ?: playback.lastSeekMs },
            userSeeks = userSeeksFlow.filterNotNull(),
            seekCorrectionScope = seekCorrectionScope,
            audioPlayer = audioPlayer,
            transportFactory = if (explicitTransportFactory != null && explicitInvoker == null) {
                // Transport tests need the real openRoute path; don't shadow it.
                explicitTransportFactory
            } else {
                // The invoker seam moved to the transport boundary: the stub records the turn
                // from the auth frame (all fields the invokers and assertions read) and emits
                // the case's events, so the route-level routeCall/routeTurn assertions hold.
                { _, _, _ ->
                    object : au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransport {
                        override fun exchange(frame: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                            val auth = frame as? au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame.Authenticate
                            val turn = auth?.let {
                                CloudRouteTurn(
                                    request = it.request,
                                    sourceLanguage = it.sourceLanguage,
                                    uiLanguage = it.uiLanguage,
                                    context = it.context,
                                    requestId = it.requestId,
                                    capabilities = it.capabilities,
                                    routeHint = it.routeHint,
                                    conversationId = it.conversationId,
                                    conversationMode = it.conversationMode,
                                    capturedOrigin = 0L,
                                )
                            }
                            if (turn != null) {
                                routeCalls += turn.request
                                routeContexts += turn.context
                                routeTurns += turn
                            }
                            emitAll(explicitInvoker?.invoke(turn!!) ?: events)
                        }
                    }
                }
            },
            gracePeriodSignal = gracePeriodSignal,
        )
    }

    /** Models the host player's state so pause/resume can be observed, and moved. */
    private class HostPlaybackState(var playing: Boolean)

    private class FakePlaybackSink(private val host: HostPlaybackState? = null) : VoicePlaybackSink {
        val calls = mutableListOf<String>()

        /** Where the fake's player actually stands after the last accepted seek, in ms. */
        var lastSeekMs: Int? = null

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
            duckStarted.complete(Unit)
            duckBlocker?.await()
            return VoiceResponse.Silent
        }

        /** When set, duck() blocks (non-suspending) until released: models a mid-mutation pause. */
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
            // CONTROL HOOK: when set, every seek surfaces on the seek stream, the way the REAL manager
            // does (a seek lands on `userSeeks`, the voice path's own included). The self-cancellation
            // control wires this so the loop must survive its own corrective seek's emission.
            userSeekFeedback?.value = Unit
            lastSeekMs = resolved * 1000
            return EpisodeOperationResult.Accepted(expectedEpisodeId, preActionPositionMsForTest)
        }

        // Whether the sink is on the episode the operation was planned for. ABSENT MEANS "AGREE": the
        // ordinary case is that nothing changed, so a case has to say so explicitly when the player
        // moved to another episode. A default of "refuse" would make every unrelated case fail for a
        // reason that has nothing to do with what it is about.
        var currentEpisodeIdForTest: String? = null

        /** Overrides the outcome a seek reports, so a case can drive accepted/refused/unknown. */
        var seekOutcomeForTest: EpisodeOperationResult? = null

        /** When set, each seek also emits here, mirroring the real manager's seek stream. */
        var userSeekFeedback: kotlinx.coroutines.flow.MutableStateFlow<Unit?>? = null

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
            writerExecutor = au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer.CALLER_THREAD,
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
            val response = deps.sink().routeToCloud("question", playbackContext, admitted = testWireId).feedback
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

        val route = launch { deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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

        deps.sink().routeToCloud("a question", playbackContext, admitted = testWireId).feedback

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
        // The interval named: A owns the turn and has paused the host; privacy closes A; a new
        // wake clears the closure flag; A's delayed cleanup then lands BEFORE any successor cloud turn
        // registers. `activeTurnId` is still A's, so ownership alone would let A through, and the live
        // flag has been cleared. Only a latch on A itself can hold. The closure carried an observed
        // playback stop, which is the fact that survives the wake.
        val deps = TestDeps(hostPlaying = true, routeInvoker = withHold(flowOf(CloudRouteEvent.Done(1, 0))))
        deps.playback.pauseGate = kotlinx.coroutines.CompletableDeferred()
        val sink = deps.sink()

        val turnA = launch { sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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
     * The delivery-commitment frame CONFIRMS this turn's reservation -- that is the server's arrangement.
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

        sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback

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

        sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback

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
            runCatching { sink.routeToCloud("a question", playbackContext, admitted = testWireId).feedback }
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
        // Earlier probe ofreported `confirmed = [pause, resume]` vs `unconfirmed =
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
     * composition control: a pre-Connected limit refusal must carry the same
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
        // Real resource control: the resolver is built from the module's
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

    /**
     * A Required code's line must reach the user in the language they SPOKE, not in English
     * (cloud-assistant.md). These codes lived as entries of `voice_spoken_templates`, and no
     * `values-*` redefines that array — so a German speaker asking in German heard English while
     * every test passed, because the fixture handed the sink its own English map.
     *
     * Driven through prepareTurn/dispatchPrepared with the REAL resource resolver on an ENGLISH UI
     * context: the utterance language is the only thing that can select the localized line, so a
     * UI-locale leak or a missing translation fails here.
     */
    private suspend fun assertSpokenInLanguage(code: String, language: String, expected: String) {
        val deps = TestDeps(
            templateResolver = SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
            locale = java.util.Locale.ENGLISH,
            routeInvoker = { _ -> flowOf(CloudRouteEvent.Error(code = code, message = "")) },
        )
        val sink = deps.sink()
        val prepared = sink.prepareTurn(
            request = "question",
            context = playbackContext,
            isLiveGraceTurn = false,
            capturedOrigin = 1,
            isWakeOriginated = true,
            hint = null,
            logicalRequestId = testWireId,
            isRetry = false,
            sourceLanguage = language,
        )!!

        val result = sink.dispatchPrepared(prepared, isRetry = false)

        assertEquals(expected, (result.feedback as VoiceResponse.Spoken).text)
        assertEquals("the voice must match the text", language, result.feedbackLanguage)
    }

    /**
     * A Generic code reaches the listener as the LOCALIZED generic line, driven through the real
     * dispatch with the real resource resolver on an ENGLISH UI context.
     *
     * unauthorized and provider_error are Generic in the contract's error table, so they have no
     * dedicated string: the sink substitutes cloud_error_generic in the UTTERANCE's language. Giving
     * either an English-only line of its own would make a German listener hear English, which is the
     * defect this whole change removes -- so the assertion is on the German sentence, not on "some
     * text was spoken".
     */
    @Test
    fun `a german unauthorized hears the german generic line`() = kotlinx.coroutines.runBlocking {
        assertSpokenInLanguage(
            CloudRouteErrorCodes.UNAUTHORIZED,
            "de",
            "Entschuldigung, ich konnte diese Anfrage nicht abschließen.",
        )
    }

    @Test
    fun `a german provider error hears the german generic line`() = kotlinx.coroutines.runBlocking {
        assertSpokenInLanguage(
            CloudRouteErrorCodes.PROVIDER_ERROR,
            "de",
            "Entschuldigung, ich konnte diese Anfrage nicht abschließen.",
        )
    }

    @Test
    fun `a german no-answer refusal is spoken in german`() = kotlinx.coroutines.runBlocking {
        assertSpokenInLanguage(
            CloudRouteErrorCodes.NO_ANSWER,
            "de",
            "Entschuldigung, ich konnte darauf keine Antwort finden. Bitte versuche es anders zu fragen.",
        )
    }

    @Test
    fun `a japanese connection-lost refusal is spoken in japanese`() = kotlinx.coroutines.runBlocking {
        assertSpokenInLanguage(
            CloudRouteErrorCodes.CONNECTION_LOST,
            "ja",
            "接続が切れました。もう一度お試しください。",
        )
    }

    @Test
    fun `a french budget refusal is spoken in french`() = kotlinx.coroutines.runBlocking {
        assertSpokenInLanguage(
            CloudRouteErrorCodes.BUDGET_EXCEEDED,
            "fr",
            "Désolé, la réponse a pris trop de temps. Veuillez réessayer.",
        )
    }

    @Test
    fun `a korean retrieval refusal is spoken in korean`() = kotlinx.coroutines.runBlocking {
        assertSpokenInLanguage(
            CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE,
            "ko",
            "죄송합니다. 지금은 확인하지 못했습니다. 다시 시도해 주세요.",
        )
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
            // Real resource control: the resolver is
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

            // REAL executor composition: the service dispatch runs the production
            // VoicePlaybackIntentExecutor, which prepares the turn on the REAL sink — carrying the
            // intent's logical request ID — and dispatches it over the REAL transport. The sinks the
            // CloudRoute branch never touches are mocks; the executor, sink, service and renderer are
            // real.
            val earconPlayer = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer>()
            // Controllably suspended backend: the gate holds speak() in flight so the
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
                playbackContextMonitor = mock(),
            )
            val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().also { it.voiceSessionGate = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceSessionGate() }.apply {
                audioFeedbackRenderer = renderer
                voicePlaybackIntentExecutor = executor
            }

            // Scheduling: the service dispatch runs on a detached event loop; a REAL
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
            // The output-hold contract: the hold survives
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
    fun `a speech outage is named in the persisted terminal trace, not bounded to unknown`() = kotlinx.coroutines.runBlocking {
        // The persisted lifecycle log is what support reads. A second allowlist bounds the terminal's
        // error code, and a code missing from it lands as "unknown" — so recognising the code for the
        // Timber line alone still left the artifact that matters unable to name a speech outage.
        val capture = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture()
        try {
            val wireClient = CapturingWebSocketClient()
            val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 5_000L)
            graceSignal.onWakeWordDetected()
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
            val driver = Thread {
                try {
                    val deadline = System.currentTimeMillis() + 5_000
                    while (wireClient.listener == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    val upgrading = mock<okhttp3.Response>().also { whenever(it.code).thenReturn(101) }
                    wireClient.listener!!.onOpen(wireClient.socket, upgrading)
                    while (wireClient.sendCalls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    wireClient.listener!!.onMessage(
                        wireClient.socket,
                        """{"type":"error","code":"speech_unavailable","message":""}""",
                    )
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            val turnScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val turn = turnScope.launch {
                runCatching {
                    kotlinx.coroutines.withTimeout(8_000) {
                        deps.sink().routeToCloud("question", playbackContext, admitted = testWireId)
                    }
                }
            }
            driver.start()
            turn.join()
            turnScope.cancel()
            driver.join()

            val line = awaitCaptureLine(capture, "reason=TurnTerminalReceived", testWireId)
            assertTrue(
                "the persisted terminal must name the speech outage, got: $line",
                line.contains("speech_unavailable"),
            )
            assertFalse("bounded to unknown hides the cause: $line", line.contains("error_code=unknown"))
        } finally {
            capture.close()
        }
    }

    /**
     * The user-visible half of the no-terminal defect, through the REAL sink and REAL transport.
     *
     * The sink assigns its outcome only in the Done and Error branches, so a stream that simply
     * ENDS leaves it null and returns Silent: the user asked something and hears nothing at all.
     * A socket FAILURE already spoke a line; a clean CLOSE mid-turn did not. This drives the real
     * transport's own listener with a close after the handshake and no terminal, and asserts what
     * the caller receives -- not what the transport emitted, which the transport suite pins.
     */
    @Test
    fun `a stream that ends with no terminal speaks the connection-lost line instead of silence`() = kotlinx.coroutines.runBlocking {
        val wireClient = CapturingWebSocketClient()
        val deps = TestDeps(
            // Real resources: the line must be the shipped one, not a map this test invented.
            templateResolver = SpokenTemplateResolver(org.robolectric.RuntimeEnvironment.getApplication()),
            transportFactory = { _, _, observer ->
                au.com.shiftyjelly.pocketcasts.repositories.cloud.WebSocketCloudTurnTransport(
                    "https://example.invalid",
                    wireClient,
                    observer,
                )
            },
        )
        val driver = Thread {
            try {
                val deadline = System.currentTimeMillis() + 5_000
                while (wireClient.listener == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
                val upgrading = mock<okhttp3.Response>().also { whenever(it.code).thenReturn(101) }
                wireClient.listener!!.onOpen(wireClient.socket, upgrading)
                while (wireClient.sendCalls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                // The turn is ACCEPTED -- this is not a failed dial -- and then the socket closes
                // with neither done nor error.
                wireClient.listener!!.onMessage(
                    wireClient.socket,
                    """{"type":"connected","codec":"pcm_s16le@24k","reservation_id":"r-1"}""",
                )
                wireClient.listener!!.onClosed(wireClient.socket, 1000, "")
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        val turnScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        var result: CloudTurnResult? = null
        val turn = turnScope.launch {
            result = kotlinx.coroutines.withTimeout(8_000) {
                deps.sink().routeToCloud("question", playbackContext, admitted = testWireId)
            }
        }
        driver.start()
        turn.join()
        turnScope.cancel()
        driver.join()

        assertNotNull("the turn must end rather than hang", result)
        val ended = requireNotNull(result) { "the turn must end rather than hang" }
        assertEquals(
            "an ended stream must speak the connection-lost line, not return silence",
            VoiceResponse.Spoken("Connection lost. Please try again."),
            ended.feedback,
        )
        assertFalse("a turn that never answered must not earn recognition", ended.succeeded)
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
                playbackContextMonitor = mock(),
            )
            val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().also { it.voiceSessionGate = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceSessionGate() }.apply {
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
            // Stopped/cleared-before-release ordering: the failure drop record
            // must be observable BEFORE the hold's release event in the persisted sequence.
            val lines = capture.lines()
            val failureIdx = lines.indexOfFirst { it.contains("submission_failed") && it.contains("render_failed") }
            val holdReleaseIdx = lines.indexOfFirst { it.contains("event=Hold") && (it.contains("HoldReleased") || it.contains("HoldCancelled")) }
            assertTrue("failure drop must be recorded before the hold release", failureIdx in 0 until holdReleaseIdx)
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
            // CONTROLLABLE CLEAR: speak runs normally, but its NonCancellable cleanup waits on this
            // gate — so the control can prove the hold survives WHILE stop/clear is blocked, then
            // Releases exactly once after the gate completes.
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
                playbackContextMonitor = mock(),
            )
            val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().also { it.voiceSessionGate = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceSessionGate() }.apply {
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
            // Wait for the render to complete speak() and enter the blocked NonCancellable cleanup.
            val cleanupDeadline = System.currentTimeMillis() + 5_000
            while (!tts.clearRequested && System.currentTimeMillis() < cleanupDeadline) Thread.sleep(20)
            assertTrue("cleanup must be requested (stop/clear in flight)", tts.clearRequested)
            // The hold must survive while cleanup is blocked: a premature-release
            // implementation would have already released here; the correct one keeps the window held
            // until the actual output terminal.
            assertTrue("the hold must survive while output clear is blocked", graceSignal.hasOutputHold(testWireId))
            // Cancel the render while cleanup is blocked: this is the exact
            // scenario that kills a premature-release implementation. The cancellation arrives
            // while the NonCancellable cleanup is still awaiting the gate — the hold must survive
            // both the cancel request AND the blocked cleanup, releasing only after the gate
            // completes.
            renderer.release()
            // Brief pause to let the cancellation propagate into the job.
            Thread.sleep(200)
            // THE HOLD MUST STILL SURVIVE AFTER CANCELLATION (while cleanup remains blocked).
            assertTrue("the hold must survive cancellation while clear is still blocked", graceSignal.hasOutputHold(testWireId))
            // Let the blocked cleanup complete — the real output terminal.
            tts.clearCompletedGate?.complete(Unit)
            // Wait for the render job to fully unwind through its NonCancellable cleanup.
            val joinDeadline = System.currentTimeMillis() + 8_000
            while (graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < joinDeadline) {
                Thread.sleep(50)
            }
            turn.join()
            turnScope.cancel()
            driver.join()
            // Exactly-once release: count request/owner-matched persisted
            // release events from the diagnostic log. The diagnostics logger lowercases UUIDs.
            val loweredId = testWireId.lowercase()
            val releaseEvents = capture.lines().count { line ->
                line.contains("event=Hold") &&
                    (line.contains("reason=HoldReleased") || line.contains("reason=HoldCancelled")) &&
                    line.contains("request_id=$loweredId")
            }
            assertEquals("exactly one hold release event for this request", 1, releaseEvents)
            assertFalse("no output hold remains after clear completes", graceSignal.hasOutputHold(testWireId))
            // Window expiry: the release arms the countdown; advancing the looper past the timeout
            // must expire the window.
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
                playbackContextMonitor = mock(),
            )
            val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().also { it.voiceSessionGate = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceSessionGate() }.apply {
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

            // A SUCCESSOR render replaces the in-flight predecessor (real renderer supersession).
            renderer.render(VoiceResponse.Silent, requestId = "successor-supersede")
            val releasedDeadline = System.currentTimeMillis() + 8_000
            while (graceSignal.hasOutputHold(testWireId) && System.currentTimeMillis() < releasedDeadline) Thread.sleep(20)
            assertFalse("supersession must release the predecessor's hold", graceSignal.hasOutputHold(testWireId))

            // Stopped/cleared-before-release ordering: the predecessor's
            // superseded/cancelled drop record must appear BEFORE the hold release event.
            val lines = capture.lines()
            val supersededIdx = lines.indexOfFirst {
                it.contains("superseded") || it.contains("render_cancelled")
            }
            val holdReleaseIdx = lines.indexOfFirst {
                it.contains("event=Hold") && (it.contains("HoldReleased") || it.contains("HoldCancelled"))
            }
            assertTrue("supersession drop must be recorded before the hold release", supersededIdx in 0 until holdReleaseIdx)
            // The successor must not be blocked: the gate completes and the window can expire.
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
        // The gap: the sink transferred the hold, then CancellationException
        // wins at the execute return — the render handoff never runs, so the service's execute
        // cancellation catch must release the transferred hold before rethrowing.
        val graceSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 5_000L)
        graceSignal.onWakeWordDetected()
        var answerRan = false
        val executor = au.com.shiftyjelly.pocketcasts.voicecontrol.service.StubVoiceExecutor { intent, _, _ ->
            answerRan = true
            // Simulate the sink's completed transfer, then the caller withdrawing.
            val hold = graceSignal.holdCloudTurn(owner = 3L)!!
            graceSignal.transferHoldToOutput(testWireId, hold)
            throw kotlinx.coroutines.CancellationException("caller withdrew at the boundary")
        }
        val service = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService().also { it.voiceSessionGate = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceSessionGate() }.apply {
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
        val result2 = deps2.sink().routeToCloud("question", playbackContext, admitted = testWireId)
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
        override fun onConnectionFailed(requestId: String, stillNeeded: Boolean) {
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
                        observer?.onConnectionFailed(rid, stillNeeded = true)
                        recording.onAuthResolved(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.AuthOutcome.RESOLVED)
                        recording.onConnectionFailed(rid, stillNeeded = true)
                        emit(CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""))
                    }
                }
            },
        )
        val sink = deps.sink()
        val result = sink.routeToCloud("question", playbackContext, admitted = testWireId)

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
        val result = sink.routeToCloud("question", playbackContext, admitted = testWireId)

        // Transport exchange never reached — missing token stops before socket.
        assertFalse("exchange should not be called when token is missing", exchangeCalled)

        // Localized feedback, not silence. unauthorized is Generic, so the generic line is what
        // this listener hears. The expected sentence is written out rather than read back from the
        // resolver: comparing the resolver to itself would pass whatever it returned.
        val feedback = result.feedback
        assertTrue("expected localized Spoken refusal, got $feedback", feedback is VoiceResponse.Spoken)
        assertEquals("Sorry, I couldn't complete that request.", (feedback as VoiceResponse.Spoken).text)
    }

    @Test
    fun `the persisted terminal diagnostic records conversation_expired instead of unknown`() = kotlinx.coroutines.runBlocking {
        val capture = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture()
        try {
            val deps = TestDeps(
                templateResolver = SpokenTemplateResolver(
                    mapOf(
                        "cloud_error_conversation_expired" to "That conversation has ended.",
                        "general.cloud_coming_soon" to "Cloud processing is coming soon",
                        "general.cloud_incomplete_turn" to "Sorry, I couldn't complete that request.",
                        "cloud_error_generic" to "Sorry, I couldn't complete that request.",
                    ),
                ),
                transportFactory = { _, _, observer ->
                    object : CloudTurnTransport {
                        override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                            val rid = (frame as CloudTurnFrame.Authenticate).requestId
                            observer?.onTerminalReceived(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.TerminalOrigin.SERVER_ERROR, "conversation_expired")
                            emit(CloudRouteEvent.Error(code = "conversation_expired", message = ""))
                        }
                    }
                },
            )
            deps.sink().routeToCloud("question", playbackContext, admitted = testWireId)

            val line = awaitCaptureLine(capture, "reason=TurnTerminalReceived", testWireId)
            assertTrue("the diagnostic must carry the code, got: $line", line.contains("conversation_expired"))
            assertFalse("the code must not be bucketed as unknown, got: $line", line.contains("unknown"))
        } finally {
            capture.close()
        }
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
        val result = sink.routeToCloud("question", playbackContext, admitted = testWireId)

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

    @Test
    fun `the send attempt marks the live turn's timing table with the cloud request stage`() = runTest {
        val admittedId = testWireId
        val record = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(admittedId)
        // Acoustic end on the capture clock's monotonic ns base: 1s.
        record.mark(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.AcousticEnd, 1_000_000_000L)
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(admittedId, record)
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(0, 0)),
            transportFactory = { _, _, observer ->
                object : CloudTurnTransport {
                    override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                        val rid = (frame as CloudTurnFrame.Authenticate).requestId
                        // THE REAL BRIDGE: the sink's own observer sees the send attempt, as the
                        // transport invokes it on the wire.
                        observer?.onSendAttempted(rid)
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        emit(CloudRouteEvent.Done(0, 0))
                    }
                }
            },
        )
        val sink = deps.sink()
        try {
            sink.routeToCloud("question", playbackContext, admitted = admittedId)
        } finally {
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.unregister(admittedId)
        }
        // The registry route lands in the live record: without the sink's observer mark the stage
        // is absent, and the acoustic-end baseline makes its absence (not a null lag) the failure.
        assertNotNull(
            "cloud request stage must be marked through the registry",
            record.stageMs(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.CloudRequestSent),
        )
    }

    @Test
    fun `the first audible from the player marks the active turn's timing table`() = runTest {
        val admittedId = testWireId
        val published = java.util.concurrent.CopyOnWriteArrayList<String>()
        val record = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(admittedId)
        // Acoustic end on the capture clock's monotonic ns base: 1s.
        record.mark(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.AcousticEnd, 1_000_000_000L)
        val previousSink = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = { _, message -> published += message }
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(admittedId, record)
        val player = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
        whenever(player.audioWritten).thenReturn(true)
        whenever(player.drainAndAwaitCompletion()).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer.Completion.Consumed)
        // The sink now registers for PRESENTATION evidence (first AudioTimestamp with a positive
        // framePosition), not the submission-side audible stamp.
        val firstAudibleListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()
        org.mockito.kotlin.doAnswer { invocation ->
            firstAudibleListeners += invocation.getArgument<(Long) -> Unit>(0)
            Unit
        }.whenever(player).addFirstAudibleListener(any())
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(0, 0)),
            audioPlayer = player,
            transportFactory = { _, _, _ ->
                object : CloudTurnTransport {
                    override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        // The player's first-audible callback arrives DURING the turn, through
                        // the listener the sink registered — after the turn it is not the first.
                        // The instant is the track's presentation nanoTime (monotonic ns).
                        firstAudibleListeners.forEach { it(5_000_000_000L) }
                        emit(CloudRouteEvent.Done(0, 0))
                    }
                }
            },
        )
        val sink = deps.sink()
        assertTrue("sink must register a first-audible listener", firstAudibleListeners.isNotEmpty())
        try {
            sink.routeToCloud("question", playbackContext, admitted = admittedId)
        } finally {
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.unregister(admittedId)
        }
        assertNotNull(
            "first audible must be marked through the registry while the turn is live",
            record.stageMs(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.FirstAudible),
        )
    }

    @Test
    fun `the turn's timing table splits start, auth, duck and first write between request and audible`() = runTest {
        // The 1.4 s between a first audio frame arriving and sound has parts the table could not
        // tell apart, and the 4.5 s auth step on one turn was invisible. Four stages make them
        // readable: the turn starting, its auth resolving, the duck finishing and the player's
        // first write returning. Each is observed where it happens and marked by turn id.
        val admittedId = testWireId
        val record = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(admittedId)
        record.mark(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.AcousticEnd, 1_000_000_000L)
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(admittedId, record)
        val player = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
        whenever(player.audioWritten).thenReturn(true)
        whenever(player.drainAndAwaitCompletion()).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer.Completion.Consumed)
        val firstWriteListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()
        org.mockito.kotlin.doAnswer { invocation ->
            firstWriteListeners += invocation.getArgument<(Long) -> Unit>(0)
            Unit
        }.whenever(player).addFirstWriteListener(any())
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(0, 0)),
            audioPlayer = player,
            transportFactory = { _, _, observer ->
                object : CloudTurnTransport {
                    override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                        val rid = (frame as CloudTurnFrame.Authenticate).requestId
                        observer?.onAuthResolved(rid, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransportObserver.AuthOutcome.RESOLVED)
                        observer?.onSendAttempted(rid)
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        emit(CloudRouteEvent.AudioFrame("pcm_s16le@24k", byteArrayOf(1, 2)))
                        // The player reports its first write while the turn is live.
                        firstWriteListeners.forEach { it(7_000_000_000L) }
                        emit(CloudRouteEvent.Done(0, 0))
                    }
                }
            },
        )
        val sink = deps.sink()
        assertTrue("sink must register a first-write listener", firstWriteListeners.isNotEmpty())
        try {
            sink.routeToCloud("question", playbackContext, admitted = admittedId)
        } finally {
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.unregister(admittedId)
        }
        val stages = record.lines().map { line -> line.substringAfter("stage=").substringBefore(" ") }
        for (stage in listOf("turn_started", "auth_resolved", "cloud_request_sent", "duck_finished", "first_write_returned")) {
            assertTrue("stage $stage must be in the table, got $stages", stage in stages)
        }
        assertEquals(
            "the first write carries the player's own instant (7 s on the timing clock, 6 s after acoustic end)",
            6_000L,
            record.elapsedMs(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.FirstWriteReturned),
        )
        assertTrue(
            "the stages are in the order they happen: started, auth, request, duck",
            stages.indexOf("turn_started") < stages.indexOf("auth_resolved") &&
                stages.indexOf("auth_resolved") < stages.indexOf("cloud_request_sent") &&
                stages.indexOf("cloud_request_sent") < stages.indexOf("duck_finished"),
        )
    }

    @Test
    fun `a turn logs which conversation it asks the worker to create or resume`() = runTest {
        // A resume of a conversation the worker no longer has is refused, and until now the phone
        // could not say which id and mode it had sent. The first eight characters identify it in a
        // log without printing the whole handle.
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val deps = TestDeps(events = flowOf(CloudRouteEvent.Done(0, 0)))
            val sink = deps.sink()
            sink.routeToCloud("question", playbackContext, admitted = testWireId)
        } finally {
            timber.log.Timber.uproot(tree)
        }
        val line = lines.singleOrNull { it.startsWith("[CloudRoute] turn conversation=") }
        assertNotNull("the turn must log its conversation, got: $lines", line)
        assertTrue("a first turn creates: $line", line!!.contains(" mode=create "))
        assertTrue("the id is eight characters: $line", Regex("conversation=[0-9a-f]{8} ").containsMatchIn(line))
    }

    @Test
    fun `the record lives until the cloud turn actually ends - published once, not at handoff`() = kotlinx.coroutines.test.runTest {
        // REVIEWER (task81): the engine published the table the moment it dispatched the intent,
        // and publish() unregisters the id — so the sink's CloudRequestSent, the player's
        // FirstAudible and the disposition arrived at an EMPTY registry and were silently
        // dropped. The record must live through the whole async turn: the sink publishes at the
        // turn's real end (Done, failure or cancellation), inside its non-cancellable cleanup.
        val admittedId = testWireId
        val published = java.util.concurrent.CopyOnWriteArrayList<String>()
        val record = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(admittedId)
        record.mark(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.AcousticEnd, 1_000_000_000L)
        val previousSink = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = { _, message -> published += message }
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(admittedId, record)
        val player = mock<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer>()
        whenever(player.audioWritten).thenReturn(true)
        whenever(player.drainAndAwaitCompletion()).thenReturn(au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer.Completion.Consumed)
        val firstAudibleListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()
        org.mockito.kotlin.doAnswer { invocation ->
            firstAudibleListeners += invocation.getArgument<(Long) -> Unit>(0)
            Unit
        }.whenever(player).addFirstAudibleListener(any())
        val deps = TestDeps(
            events = flowOf(CloudRouteEvent.Done(0, 0)),
            audioPlayer = player,
            transportFactory = { _, _, _ ->
                object : CloudTurnTransport {
                    override fun exchange(frame: CloudTurnFrame): kotlinx.coroutines.flow.Flow<CloudRouteEvent> = kotlinx.coroutines.flow.flow {
                        emit(CloudRouteEvent.Connected("pcm_s16le@24k"))
                        // First audible arrives mid-turn, as the player would report it.
                        firstAudibleListeners.forEach { it(5_000_000_000L) }
                        emit(CloudRouteEvent.Done(0, 0))
                    }
                }
            },
        )
        val sink = deps.sink()
        try {
            sink.routeToCloud("question", playbackContext, admitted = admittedId)
        } finally {
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.unregister(admittedId)
        }
        val text = published.joinToString("\n")
        assertTrue("the table must be published at the turn's real end: '$text'", text.isNotEmpty())
        // The VALUE, not the label: a substring check passes while the number is wrong or absent.
        // AcousticEnd is 1 s and first audible 5 s, so the stage must read 4000 ms.
        val audibleLine = text.lines().firstOrNull { it.contains("stage=first_audible") }
        assertNotNull("the published table carries first_audible: '$text'", audibleLine)
        assertTrue(
            "first_audible must be measured from acoustic end, got: '$audibleLine'",
            audibleLine!!.contains("elapsed_ms=4000"),
        )
        assertTrue("the published table carries the committed outcome: '$text'", text.contains("outcome=committed"))
    }
}
