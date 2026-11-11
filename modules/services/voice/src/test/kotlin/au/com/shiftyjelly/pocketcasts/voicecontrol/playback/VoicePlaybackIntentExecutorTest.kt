package au.com.shiftyjelly.pocketcasts.voicecontrol.playback
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
class VoicePlaybackIntentExecutorTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()
    @Test
    fun `pause pauses sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Playback.Pause)
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf("pause"), sinks.fakePlayback.calls)
    }
    @Test
    fun `resume resumes sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Playback.Resume)
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("resume"), sinks.fakePlayback.calls)
    }
    @Test
    fun `relative positive seek skips forward`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Playback.SeekRelative(30))
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipForward:30"), sinks.fakePlayback.calls)
    }
    @Test
    fun `relative negative seek skips backward`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Playback.SeekRelative(-10))
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipBackward:10"), sinks.fakePlayback.calls)
    }
    @Test
    fun `relative null seek with direction uses sink interval`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Playback.SeekRelative(null, "backward"))
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipBackward:null"), sinks.fakePlayback.calls)
    }
    @Test
    fun `relative null seek without direction uses forward interval`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Playback.SeekRelative(null, null))
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipForward:null"), sinks.fakePlayback.calls)
    }
    @Test
    fun `negative absolute seek resolves as an offset back from the episode end`() = runTest {
        val sinks = FakeSinks()
        org.mockito.kotlin.whenever(sinks.playbackManager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-id",
                publishedDate = java.util.Date(),
                podcastUuid = "podcast-id",
                duration = 1800.0,
            ),
        )
        val response = sinks.executor().execute(VoiceIntent.Playback.SeekAbsolute(-5))
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("seekTo:1795"), sinks.fakePlayback.calls)
    }
    @Test
    fun `next episode calls sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Playback.NextEpisode)
        assertEquals(VoiceResponse.Earcon(EarconId.NEXT_EPISODE), response)
        assertEquals(listOf("nextEpisode"), sinks.fakePlayback.calls)
    }
    @Test
    fun `next chapter calls chapter sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Chapter.NextChapter)
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("next"), sinks.chapter.calls)
    }
    @Test
    fun `previous chapter calls chapter sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Chapter.PreviousChapter)
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("previous"), sinks.chapter.calls)
    }
    @Test
    fun `chapter by index calls chapter sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Chapter.ByIndex(2))
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("byIndex:2"), sinks.chapter.calls)
    }
    @Test
    fun `chapter by title does nothing`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Chapter.ByTitle("intro"))
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(emptyList<String>(), sinks.chapter.calls)
    }
    @Test
    fun `set speed calls effects sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Effects.SetSpeed(1.25))
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf("setSpeed:1.25"), sinks.effects.calls)
    }
    @Test
    fun `set volume calls volume sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Volume.SetVolume(50))
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf("setVolume:50"), sinks.volume.calls)
    }
    @Test
    fun `sleep set calls sleep sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Sleep.Set(30))
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf("set:30"), sinks.sleep.calls)
    }
    @Test
    fun `bookmark add calls bookmark sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Bookmark.Add("my bookmark"))
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf("add:my bookmark"), sinks.bookmark.calls)
    }
    @Test
    fun `a refused cloud turn does not renew the grace window`() = runTest {
        val sinks = FakeSinks()
        val signal = GracePeriodSignal(timeoutMs = 5_000L)
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        sinks.fakeCloudRoute.firstDispatchResult = CloudTurnResult(
            feedback = VoiceResponse.Spoken("You have reached your Auris free daily limit."),
            action = CloudRouteActionOutcome.None,
            succeeded = false,
        )
        kotlinx.coroutines.delay(3_000L)
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "question",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
            ),
        )
        kotlinx.coroutines.delay(3_000L)
        assertFalse("a refusal must not extend the grace window", signal.isActive.value)
        assertEquals(window, signal.capturedGeneration())
    }
    @Test
    fun `a successful no-action answer still renews the grace window`() = runTest {
        val sinks = FakeSinks()
        val signal = GracePeriodSignal(timeoutMs = 5_000L)
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        sinks.fakeCloudRoute.firstDispatchResult = CloudTurnResult(
            feedback = VoiceResponse.Spoken("Here is your answer."),
            action = CloudRouteActionOutcome.None,
            succeeded = true,
        )
        kotlinx.coroutines.delay(3_000L)
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "question",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
            ),
        )
        kotlinx.coroutines.delay(3_000L)
        assertTrue("a successful answer renews the window", signal.isActive.value)
    }
    @Test
    fun `a dispatched turn carries its own window stamp, so a fresh wake creates`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val firstWindow = signal.capturedGeneration()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "first",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = firstWindow,
            ),
        )
        assertEquals(
            "a wake-stamped dispatch creates",
            true,
            sinks.fakeCloudRoute.wakeOrigins.last(),
        )
        assertEquals(firstWindow, sinks.fakeCloudRoute.capturedOrigins.last())
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "follow-up",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = firstWindow,
            ),
        )
        assertEquals(
            "and a follow-up carries the same captured window",
            firstWindow,
            sinks.fakeCloudRoute.capturedOrigins.last(),
        )
    }
    @Test
    fun `a turn stamped with a superseded window never reaches transport`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val staleStamp = signal.capturedGeneration()
        signal.onWakeWordDetected() // a successor wake opens a NEW window
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "successor",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.capturedGeneration(),
            ),
        )
        val callsAfterSuccessor = sinks.fakePlayback.calls.toList()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "late old",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = staleStamp,
            ),
        )
        assertEquals(
            "only the successor reached transport -- the stale dispatch was refused BEFORE preparing " +
                "(prepared=${sinks.fakeCloudRoute.preparedTurns.map { it.request }} " +
                "dispatched=${sinks.fakeCloudRoute.preparedDispatches.map { it.request }})",
            listOf("successor"),
            sinks.fakeCloudRoute.preparedDispatches.map { it.request },
        )
        assertEquals(
            "and the stale dispatch did not mutate the successor's playback",
            callsAfterSuccessor,
            sinks.fakePlayback.calls,
        )
    }
    @Test
    fun `a wake-free follow-up in the same window does not inherit the wake's flag`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "the wake's own question",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
            ),
        )
        assertTrue(
            "the wake's OWN request begins the conversation",
            sinks.fakeCloudRoute.wakeOrigins.last(),
        )
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "a follow-up in the same window",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
            ),
        )
        assertFalse(
            "and a follow-up in that window must NOT be marked as a wake request " +
                "(wakeOrigins=${sinks.fakeCloudRoute.wakeOrigins})",
            sinks.fakeCloudRoute.wakeOrigins.last(),
        )
    }
    @Test
    fun `a privacy-closed window is not current even at a matching generation`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        signal.onAppBackgrounded() // the privacy close invalidates the window
        assertFalse(
            "a closed window must not read as current",
            signal.isCurrent(window),
        )
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "after backgrounding",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
            ),
        )
        assertTrue(
            "so the dispatch does not reach transport (calls=${sinks.fakeCloudRoute.calls})",
            sinks.fakeCloudRoute.calls.isEmpty(),
        )
    }
    @Test
    fun `a retry of the same utterance reuses the turn the first dispatch prepared`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        val intent = VoiceIntent.CloudRoute(
            request = "a question worth retrying",
            tier = VoiceIntent.CloudTier.Premium,
            windowGeneration = window,
            logicalRequestId = "logical-1",
        )
        executor.execute(intent)
        assertEquals("one turn prepared", 1, sinks.fakeCloudRoute.preparedTurns.size)
        executor.execute(intent)
        assertEquals(
            "the retry did NOT prepare a second turn (prepared=" +
                "${sinks.fakeCloudRoute.preparedTurns.map { it.requestId }})",
            1,
            sinks.fakeCloudRoute.preparedTurns.size,
        )
        assertEquals("two attempts dispatched", 2, sinks.fakeCloudRoute.preparedDispatches.size)
        assertEquals(
            "and both carried the SAME request ID",
            sinks.fakeCloudRoute.preparedDispatches[0].requestId,
            sinks.fakeCloudRoute.preparedDispatches[1].requestId,
        )
        assertEquals(
            "and the same conversation handle",
            sinks.fakeCloudRoute.preparedDispatches[0].conversationId,
            sinks.fakeCloudRoute.preparedDispatches[1].conversationId,
        )
    }
    @Test
    fun `two same-text user acts a fresh wake apart both dispatch and both create`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "what time is it",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.capturedGeneration(),
            ),
        )
        signal.onWakeWordDetected()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "what time is it",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.capturedGeneration(),
            ),
        )
        assertEquals(
            "both user acts dispatched, not one (dispatched=" +
                "${sinks.fakeCloudRoute.preparedDispatches.map { it.requestId }})",
            2,
            sinks.fakeCloudRoute.preparedDispatches.size,
        )
        assertNotEquals(
            "distinct user acts need distinct request IDs",
            sinks.fakeCloudRoute.preparedDispatches[0].requestId,
            sinks.fakeCloudRoute.preparedDispatches[1].requestId,
        )
        assertNotEquals(
            "and distinct conversations, since each is its own CREATE",
            sinks.fakeCloudRoute.preparedDispatches[0].conversationId,
            sinks.fakeCloudRoute.preparedDispatches[1].conversationId,
        )
    }
    @Test
    fun `a retry of a turn prepared under an older window is refused after a successor opens`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val first = signal.capturedGeneration()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "prepared under window 1",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = first,
                logicalRequestId = "logical-1",
            ),
        )
        val dispatchesAfterFirst = sinks.fakeCloudRoute.preparedDispatches.size
        signal.onWakeWordDetected()
        val successor = signal.capturedGeneration()
        assertNotEquals("the window moved", first, successor)
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "prepared under window 1",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = successor,
                logicalRequestId = "logical-1",
                isRetry = true,
            ),
        )
        assertEquals(
            "a retry of a turn from a superseded window must not dispatch, whatever stamp it carries " +
                "(dispatches=${sinks.fakeCloudRoute.preparedDispatches.size})",
            dispatchesAfterFirst,
            sinks.fakeCloudRoute.preparedDispatches.size,
        )
    }
    @Test
    fun `a retry retired BETWEEN the executor's guards and preparation is refused, not recreated`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "prepare me",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                logicalRequestId = "logical-seam",
                isRetry = false,
            ),
        )
        assertEquals("the first attempt prepared", 1, sinks.fakeCloudRoute.preparedTurns.size)
        sinks.fakeCloudRoute.onPrepareSeam = { sinks.fakeCloudRoute.forgetRetained("logical-seam") }
        sinks.fakeCloudRoute.forgetAfterFirstPrepare = "logical-seam"
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "prepare me",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                logicalRequestId = "logical-seam",
                isRetry = true,
            ),
        )
        assertEquals(
            "a retry retired at the seam must NOT be prepared afresh " +
                "(prepared=${sinks.fakeCloudRoute.preparedTurns.size})",
            1,
            sinks.fakeCloudRoute.preparedTurns.size,
        )
        assertEquals(
            "and nothing may be dispatched for it " +
                "(dispatched=${sinks.fakeCloudRoute.preparedDispatches.size})",
            1,
            sinks.fakeCloudRoute.preparedDispatches.size,
        )
    }
    @Test
    fun `a retry naming an UNKNOWN logical request is refused rather than treated as new input`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "never prepared",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.capturedGeneration(),
                logicalRequestId = "never-prepared",
                isRetry = true,
            ),
        )
        assertTrue(
            "an unknown retry reaches neither preparation nor transport " +
                "(prepared=${sinks.fakeCloudRoute.preparedTurns.size} " +
                "dispatched=${sinks.fakeCloudRoute.preparedDispatches.size})",
            sinks.fakeCloudRoute.preparedTurns.isEmpty() && sinks.fakeCloudRoute.preparedDispatches.isEmpty(),
        )
    }
    @Test
    fun `a FIRST attempt naming a logical request is not refused`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "first attempt",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.capturedGeneration(),
                logicalRequestId = "fresh-request",
            ),
        )
        assertEquals("a first attempt prepares normally", 1, sinks.fakeCloudRoute.preparedTurns.size)
        assertEquals("and dispatches", 1, sinks.fakeCloudRoute.preparedDispatches.size)
    }
    @Test
    fun `a retry of an EARLIER logical request still finds its own turn after a later one`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        suspend fun dispatch(logical: String) = executor.execute(
            VoiceIntent.CloudRoute(
                request = "question $logical",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                logicalRequestId = logical,
            ),
        )
        dispatch("A")
        dispatch("B")
        val preparedAfterAB = sinks.fakeCloudRoute.preparedTurns.size
        dispatch("A") // the retry of the EARLIER one
        assertEquals(
            "the retry of A did not prepare a third turn " +
                "(prepared=${sinks.fakeCloudRoute.preparedTurns.map { it.requestId }})",
            preparedAfterAB,
            sinks.fakeCloudRoute.preparedTurns.size,
        )
        val dispatchedA = sinks.fakeCloudRoute.preparedDispatches.filter { it.request.startsWith("question A") }
        assertEquals("A was dispatched twice, once initially and once as a retry", 2, dispatchedA.size)
        assertEquals(
            "and both carried A's OWN request ID",
            dispatchedA[0].requestId,
            dispatchedA[1].requestId,
        )
    }
    @Test
    fun `the logical identity is what matches, not the words`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                logicalRequestId = "logical-1",
            ),
        )
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "could you summarise it",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                logicalRequestId = "logical-1",
            ),
        )
        assertEquals(
            "the same logical request does not prepare twice, whatever words it is retried with " +
                "(ids=${sinks.fakeCloudRoute.preparedTurns.map { it.requestId }})",
            1,
            sinks.fakeCloudRoute.preparedTurns.size,
        )
        assertEquals(
            "and the retry reuses the request ID the first attempt fixed",
            sinks.fakeCloudRoute.preparedTurns.single().requestId,
            sinks.fakeCloudRoute.preparedDispatches.last().requestId,
        )
    }
    @Test
    fun `two distinct user acts saying the same words prepare separate requests`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "play the latest episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.capturedGeneration(),
                logicalRequestId = "act-1",
            ),
        )
        signal.onWakeWordDetected()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "play the latest episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.capturedGeneration(),
                logicalRequestId = "act-2",
            ),
        )
        assertEquals(
            "each user act prepared its OWN turn (ids=${sinks.fakeCloudRoute.preparedTurns.map { it.requestId }})",
            2,
            sinks.fakeCloudRoute.preparedTurns.size,
        )
        assertNotEquals(
            "with a distinct request ID",
            sinks.fakeCloudRoute.preparedTurns[0].requestId,
            sinks.fakeCloudRoute.preparedTurns[1].requestId,
        )
    }
    @Test
    fun `an utterance naming no logical request is a new act, not a retry`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val intent = VoiceIntent.CloudRoute(
            request = "the same words twice",
            tier = VoiceIntent.CloudTier.Premium,
            windowGeneration = signal.capturedGeneration(),
        )
        executor.execute(intent)
        executor.execute(intent)
        assertEquals(
            "with no logical identity each dispatch prepares its own turn " +
                "(ids=${sinks.fakeCloudRoute.preparedTurns.map { it.requestId }})",
            2,
            sinks.fakeCloudRoute.preparedTurns.size,
        )
    }
    @Test
    fun `a dispatch carrying a prepared turn goes through the retry path, not fresh preparation`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        val prepared = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn(
            request = "prepared request",
            context = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext(),
            requestId = "request-id-1",
            conversationId = "handle-1",
            conversationMode = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode.Create,
            capturedOrigin = window,
        )
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "prepared request",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                preparedTurn = prepared,
            ),
        )
        assertEquals(
            "the prepared turn was dispatched through the retry path (calls=${sinks.fakeCloudRoute.calls})",
            listOf(prepared),
            sinks.fakeCloudRoute.preparedDispatches,
        )
        assertTrue(
            "and NOT re-prepared as a fresh request",
            sinks.fakeCloudRoute.calls.none { it.startsWith("routeToCloud:") },
        )
    }
    @Test
    fun `a prepared retry whose window was closed by privacy is refused before dispatch`() = runTest {
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        val prepared = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn(
            request = "prepared request",
            context = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext(),
            requestId = "request-id-1",
            conversationId = "handle-1",
            conversationMode = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode.Create,
            capturedOrigin = window,
        )
        signal.onAppBackgrounded() // the privacy close invalidates the window
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "prepared request",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                preparedTurn = prepared,
            ),
        )
        assertTrue(
            "a retry under a closed window must not reach the sink " +
                "(dispatches=${sinks.fakeCloudRoute.preparedDispatches})",
            sinks.fakeCloudRoute.preparedDispatches.isEmpty(),
        )
    }
    @Test
    fun `the abandonment binding releases a retained turn and clears on teardown`() = runTest {
        val sinks = FakeSinks()
        val manager = au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager(
            au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper(),
        )
        val executor = sinks.executor()
        val released = mutableListOf<String>()
        sinks.fakeCloudRoute.onForget = { released += it }
        executor.bindDialogAbandonment(manager)
        assertNotNull(
            "the binding connects the manager to the release",
            manager.onRequestAbandoned,
        )
        manager.onRequestAbandoned?.invoke("logical-1")
        assertEquals(
            "an abandoned request reaches the sink's release (released=$released)",
            listOf("logical-1"),
            released,
        )
        executor.unbindDialogAbandonment()
        assertNull(
            "teardown clears the binding, so the manager does not report into a finished session",
            manager.onRequestAbandoned,
        )
        val releasedBeforeTeardown = released.toList()
        assertEquals(
            "clearing the binding does not undo a release it already delivered",
            releasedBeforeTeardown,
            released,
        )
    }
    @Test
    fun `cloud route delegates to cloud route sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
            ),
        )
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(
            "the turn is prepared once and dispatched through the retry path, so a first attempt and a " +
                "retry take the same route",
            listOf("prepareTurn:summarize this episode", "dispatchPrepared:summarize this episode"),
            sinks.fakeCloudRoute.calls,
        )
    }
    @Test
    fun `the executor records the action outcome the dispatch reported`() = runTest {
        val sinks = FakeSinks()
        sinks.fakeCloudRoute.actionOutcome = CloudRouteActionOutcome.Unknown
        val executor = sinks.executor()
        val outcome = VoicePlaybackIntentExecutor.TurnOutcome()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
            ),
            outcome,
        )
        assertEquals(
            "an unknown action outcome must survive the dispatch, not be collapsed into a settled one",
            CloudRouteActionOutcome.Unknown,
            outcome.action,
        )
    }
    @Test
    fun `a confirmed action is recorded as confirmed`() = runTest {
        val sinks = FakeSinks()
        sinks.fakeCloudRoute.actionOutcome = CloudRouteActionOutcome.Confirmed
        val executor = sinks.executor()
        val outcome = VoicePlaybackIntentExecutor.TurnOutcome()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
            ),
            outcome,
        )
        assertEquals(CloudRouteActionOutcome.Confirmed, outcome.action)
    }
    @Test
    fun `a spoken pause is reported as a user command`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        executor.execute(VoiceIntent.Playback.Pause)
        org.mockito.kotlin.verify(sinks.playbackManager).noteUserPlaybackCommand()
    }
    @Test
    fun `the user's command is recorded before the action runs`() = runTest {
        val order = mutableListOf<String>()
        val sinks = FakeSinks()
        sinks.fakePlayback.onCall = { order += "playback:$it" }
        Mockito.doAnswer { order += "noteUserPlaybackCommand" }
            .`when`(sinks.playbackManager).noteUserPlaybackCommand()
        val executor = sinks.executor()
        executor.execute(VoiceIntent.Playback.Pause)
        assertEquals(listOf("noteUserPlaybackCommand", "playback:pause"), order)
    }
    @Test
    fun `a spoken seek is not a play-pause command`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        executor.execute(VoiceIntent.Playback.SeekRelative(30))
        org.mockito.kotlin.verify(sinks.playbackManager, org.mockito.kotlin.never()).noteUserPlaybackCommand()
    }
    @Test
    fun `a fallback completing after a privacy close leaves the window closed`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val generation = signal.currentGeneration
        sinks.fakeCloudRoute.whileInFlight = { signal.onAppBackgrounded() }
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "what did the guests say about sleep and memory",
                tier = VoiceIntent.CloudTier.Unknown,
                windowGeneration = generation,
                origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
            ),
        )
        assertFalse("a completion reopened a closed window", signal.isActive.value)
        assertFalse(signal.isCurrent(generation))
    }
    @Test
    fun `a model-chosen route completing after a privacy close leaves the window closed`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val generation = signal.currentGeneration
        sinks.fakeCloudRoute.whileInFlight = { signal.onAppBackgrounded() }
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = generation,
            ),
        )
        assertFalse("a completion reopened a privacy-closed window", signal.isActive.value)
    }
    @Test
    fun `a model-chosen route completing inside an open window extends it`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 100L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = signal.currentGeneration,
            ),
        )
        assertTrue(signal.isActive.value)
    }
    @Test
    fun `playback query whats playing returns spoken`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.PlaybackQuery.WhatsPlaying)
        assertEquals(VoiceResponse.Spoken("whats playing"), response)
    }
    @Test
    fun `queue clear calls queue sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.Queue.Clear)
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf("clear"), sinks.queue.calls)
    }
    @Test
    fun `stats query returns spoken`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val response = executor.execute(VoiceIntent.StatsQuery.ListeningStreak)
        assertEquals(VoiceResponse.Spoken("streak"), response)
    }
    @Test
    fun `clear reports dispatch while rendering the same CONFIRM_REQUIRED earcon`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val facts = mutableListOf<VoiceActionOutcome>()
        val response = executor.execute(VoiceIntent.Queue.Clear) { facts += it }
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf<VoiceActionOutcome>(VoiceActionOutcome.Dispatched, VoiceActionOutcome.NotObserved), facts)
    }
    @Test
    fun `pause reports dispatch even though its earcon says success`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val facts = mutableListOf<VoiceActionOutcome>()
        val response = executor.execute(VoiceIntent.Playback.Pause) { facts += it }
        assertEquals(VoiceResponse.Earcon(EarconId.SUCCESS), response)
        assertEquals(listOf<VoiceActionOutcome>(VoiceActionOutcome.Dispatched, VoiceActionOutcome.NotObserved), facts)
    }
    @Test
    fun `resume reports dispatch although it renders Silent`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val facts = mutableListOf<VoiceActionOutcome>()
        val response = executor.execute(VoiceIntent.Playback.Resume) { facts += it }
        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf<VoiceActionOutcome>(VoiceActionOutcome.Dispatched, VoiceActionOutcome.NotObserved), facts)
    }
    @Test
    fun `overlapping executions each deliver facts to their OWN reporter and never cross`() {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val aFacts = mutableListOf<VoiceActionOutcome>()
        val bFacts = mutableListOf<VoiceActionOutcome>()
        val latch = java.util.concurrent.CountDownLatch(2)
        val a = Thread {
            kotlinx.coroutines.runBlocking {
                executor.execute(VoiceIntent.Playback.Pause) { aFacts += it }
            }
            latch.countDown()
        }
        val b = Thread {
            kotlinx.coroutines.runBlocking {
                executor.execute(VoiceIntent.Playback.Resume) { bFacts += it }
            }
            latch.countDown()
        }
        a.start()
        b.start()
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf(VoiceActionOutcome.Dispatched, VoiceActionOutcome.NotObserved), aFacts)
        assertEquals(listOf(VoiceActionOutcome.Dispatched, VoiceActionOutcome.NotObserved), bFacts)
    }
    @Test
    fun `a held cloud stream does not block a pause from entering`() {
        val sinks = FakeSinks()
        val executor = sinks.executor()
        val cloudHeld = java.util.concurrent.CountDownLatch(1)
        val releaseCloud = java.util.concurrent.CountDownLatch(1)
        sinks.fakeCloudRoute.whileInFlight = {
            cloudHeld.countDown()
            releaseCloud.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        val cloudThread = Thread {
            kotlinx.coroutines.runBlocking { executor.execute(VoiceIntent.CloudRoute(request = "q", tier = VoiceIntent.CloudTier.Unknown, context = PlaybackContext())) }
        }
        cloudThread.start()
        assertTrue(cloudHeld.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val pauseFacts = mutableListOf<VoiceActionOutcome>()
        val pauseDone = java.util.concurrent.CountDownLatch(1)
        var pauseCompleted = false
        val pauseThread = Thread {
            kotlinx.coroutines.runBlocking {
                executor.execute(VoiceIntent.Playback.Pause) { pauseFacts += it }
                pauseCompleted = true
                pauseDone.countDown()
            }
        }
        pauseThread.start()
        val enteredWhileHeld = pauseDone.await(2, java.util.concurrent.TimeUnit.SECONDS)
        releaseCloud.countDown()
        cloudThread.join(10_000)
        pauseThread.join(10_000)
        assertTrue("pause must enter while the cloud stream is held", enteredWhileHeld)
        assertTrue(pauseCompleted)
        assertEquals(listOf(VoiceActionOutcome.Dispatched, VoiceActionOutcome.NotObserved), pauseFacts)
    }
    @Suppress("DEPRECATION")
    @Test
    fun `identical Silent presentations from granted and denied focus report different facts`() {
        val context = Mockito.mock(android.content.Context::class.java)
        val audioManager = Mockito.mock(android.media.AudioManager::class.java)
        Mockito.`when`(context.getSystemService(android.media.AudioManager::class.java)).thenReturn(audioManager)
        Mockito.`when`(audioManager.mode).thenReturn(android.media.AudioManager.MODE_NORMAL)
        val playbackManager = Mockito.mock(au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager::class.java)
        val sink = PlaybackManagerPlaybackSink(playbackManager, context)
        val facts = mutableListOf<VoiceActionOutcome>()
        Mockito.`when`(
            audioManager.requestAudioFocus(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenReturn(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val granted = kotlinx.coroutines.runBlocking { sink.duckReporting { facts += it } }
        Mockito.`when`(
            audioManager.requestAudioFocus(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenReturn(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val denied = kotlinx.coroutines.runBlocking { sink.duckReporting { facts += it } }
        assertEquals("the granted path renders Silent", VoiceResponse.Silent, granted)
        assertEquals("the denied path renders Silent too", VoiceResponse.Silent, denied)
        assertEquals(
            listOf<VoiceActionOutcome>(
                VoiceActionOutcome.AuxiliaryPerformed("audio_focus"),
                VoiceActionOutcome.AuxiliaryPreconditionFailed("audio_focus", "audio_focus_denied"),
            ),
            facts,
        )
    }
    @Test
    @Suppress("DEPRECATION")
    fun `a REAL cloud turn whose duck is granted still records its own action as unobserved`() = kotlinx.coroutines.runBlocking {
        val context = Mockito.mock(android.content.Context::class.java)
        val audioManager = Mockito.mock(android.media.AudioManager::class.java)
        Mockito.`when`(context.getSystemService(android.media.AudioManager::class.java)).thenReturn(audioManager)
        Mockito.`when`(audioManager.mode).thenReturn(android.media.AudioManager.MODE_NORMAL)
        Mockito.`when`(audioManager.requestAudioFocus(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenReturn(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val playback = PlaybackManagerPlaybackSink(
            org.mockito.kotlin.mock<au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager>(),
            context,
        )
        val sinks = FakeSinks(playbackOverride = playback).withRealCloudRoute(playback)
        val facts = mutableListOf<VoiceActionOutcome>()
        sinks.executor().execute(VoiceIntent.CloudRoute(request = "play something", tier = VoiceIntent.CloudTier.Premium)) { facts += it }
        assertTrue(
            "the auxiliary grant must be reported",
            facts.any { it is VoiceActionOutcome.AuxiliaryPerformed },
        )
        assertTrue(
            "an auxiliary success must NOT suppress the primary unknown; facts were $facts",
            facts.contains(VoiceActionOutcome.NotObserved),
        )
    }
    private class FakeSinks(
        private val playbackOverride: au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink? = null,
        cloudRouteOverride: au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceCloudRouteSink? = null,
    ) {
        val fakePlayback = FakePlaybackSink()
        val playback: au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink = playbackOverride ?: fakePlayback
        val effects = FakeEffectsSink()
        val volume = FakeVolumeSink()
        val sleep = FakeSleepSink()
        val chapter = FakeChapterSink()
        val bookmark = FakeBookmarkSink()
        val fakeCloudRoute = FakeCloudRouteSink()
        val cloudRoute: au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceCloudRouteSink = cloudRouteOverride ?: fakeCloudRoute
        val playbackContext = FakePlaybackContextProvider()
        val queue = FakeQueueSink()
        val playbackQuery = FakePlaybackQuerySink()
        val statsQuery = FakeStatsQuerySink()
        val playbackManager: au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager = org.mockito.kotlin.mock()
        fun withRealCloudRoute(playbackSink: au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerPlaybackSink): FakeSinks = FakeSinks(playbackOverride = playbackSink, cloudRouteOverride = RealCloudRouteSink(playbackSink))
        fun executor(signal: au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()) = VoicePlaybackIntentExecutor(
            playbackSink = playback,
            effectsSink = effects,
            volumeSink = volume,
            sleepSink = sleep,
            chapterSink = chapter,
            bookmarkSink = bookmark,
            cloudRouteSink = cloudRoute,
            playbackContextProvider = playbackContext,
            queueSink = queue,
            playbackQuerySink = playbackQuery,
            statsQuerySink = statsQuery,
            gracePeriodSignal = signal,
            playbackManager = playbackManager,
        )
    }
    private class FakePlaybackSink : VoicePlaybackSink {
        var onCall: ((String) -> Unit)? = null
        val calls = mutableListOf<String>()
        override suspend fun duck(): VoiceResponse {
            calls += "duck"
            onCall?.invoke("duck")
            return VoiceResponse.Silent
        }
        override suspend fun restore(): VoiceResponse {
            calls += "restore"
            onCall?.invoke("restore")
            return VoiceResponse.Silent
        }
        override suspend fun pauseAsHold(): VoiceResponse = pause()
        override suspend fun pause(): VoiceResponse {
            calls += "pause"
            onCall?.invoke("pause")
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override suspend fun resume(expectedEpisodeId: String?): EpisodeOperationResult {
            calls += "resume"
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)
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
            val resolved = if (positionSeconds < 0) {
                (episodeDurationSeconds + positionSeconds).coerceIn(0, episodeDurationSeconds)
            } else {
                positionSeconds
            }
            calls += "seekTo:$resolved"
            return EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)
        }
        override fun nextEpisode(): VoiceResponse {
            calls += "nextEpisode"
            return VoiceResponse.Earcon(EarconId.NEXT_EPISODE)
        }
    }
    private class FakeEffectsSink : VoiceEffectsSink {
        val calls = mutableListOf<String>()
        override fun setSpeed(speed: Double): VoiceResponse {
            calls += "setSpeed:$speed"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun adjustSpeed(delta: Double): VoiceResponse {
            calls += "adjustSpeed:$delta"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun setTrimMode(mode: String): VoiceResponse {
            calls += "setTrimMode:$mode"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun setVolumeBoost(enabled: Boolean): VoiceResponse {
            calls += "setVolumeBoost:$enabled"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun queryEffects(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }
    private class FakeVolumeSink : VoiceVolumeSink {
        val calls = mutableListOf<String>()
        override fun setVolume(volume: Int): VoiceResponse {
            calls += "setVolume:$volume"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun adjustVolume(delta: Int): VoiceResponse {
            calls += "adjustVolume:$delta"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun query(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }
    private class FakeSleepSink : VoiceSleepSink {
        val calls = mutableListOf<String>()
        override fun set(minutes: Int): VoiceResponse {
            calls += "set:$minutes"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun endOfEpisode(): VoiceResponse {
            calls += "endOfEpisode"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun endOfChapter(): VoiceResponse {
            calls += "endOfChapter"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun addTime(minutes: Int): VoiceResponse {
            calls += "addTime:$minutes"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun cancel(): VoiceResponse {
            calls += "cancel"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun query(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }
    private class FakeChapterSink : VoiceChapterSink {
        val calls = mutableListOf<String>()
        override fun next(): VoiceResponse {
            calls += "next"
            return VoiceResponse.Silent
        }
        override fun previous(): VoiceResponse {
            calls += "previous"
            return VoiceResponse.Silent
        }
        override fun byIndex(index: Int): VoiceResponse {
            calls += "byIndex:$index"
            return VoiceResponse.Silent
        }
        override fun openLink(index: Int): VoiceResponse {
            calls += "openLink:$index"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun queryList(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
        override fun queryCurrent(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
        override fun queryCount(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
        override fun queryNext(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }
    private class FakeBookmarkSink : VoiceBookmarkSink {
        val calls = mutableListOf<String>()
        override suspend fun add(title: String?): VoiceResponse {
            calls += "add:$title"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun rename(ref: String, title: String): VoiceResponse {
            calls += "rename:$ref:$title"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun play(ref: String): VoiceResponse {
            calls += "play:$ref"
            return VoiceResponse.Silent
        }
        override fun delete(ref: String): VoiceResponse {
            calls += "delete:$ref"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun deleteAll(): VoiceResponse {
            calls += "deleteAll"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun queryList(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
        override fun queryCount(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
        override fun queryNearby(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }
    private class FakeCloudRouteSink : VoiceCloudRouteSink {
        val calls = mutableListOf<String>()
        val graceFlags = mutableListOf<Boolean>()
        var whileInFlight: (() -> Unit)? = null
        val capturedOrigins = mutableListOf<Long?>()
        val wakeOrigins = mutableListOf<Boolean>()
        var actionOutcome: CloudRouteActionOutcome = CloudRouteActionOutcome.Confirmed
        var firstDispatchResult: CloudTurnResult? = null
        val preparedDispatches = mutableListOf<au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()
        val preparedTurns = mutableListOf<au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()
        private val retained =
            mutableMapOf<String, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()
        var onForget: ((String) -> Unit)? = null
        override fun onRetentionReleased(consumer: (logicalRequestId: String) -> Unit) {
        }
        override fun forgetRetained(logicalRequestId: String) {
            retained.remove(logicalRequestId)
            onForget?.invoke(logicalRequestId)
        }
        var onPrepareSeam: (() -> Unit)? = null
        var forgetAfterFirstPrepare: String? = null
        val endedWindows = mutableListOf<Long>()
        override fun endWindow(endedGeneration: Long): List<String> {
            endedWindows += endedGeneration
            return retained.keys.filter { it.isNotEmpty() }.also { retained.clear() }
        }
        override fun retainedTurnFor(
            logicalRequestId: String,
        ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? = retained[logicalRequestId]
        override suspend fun prepareTurn(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            isLiveGraceTurn: Boolean,
            capturedOrigin: Long?,
            isWakeOriginated: Boolean,
            hint: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint?,
            logicalRequestId: String?,
            isRetry: Boolean,
            sourceLanguage: String?,
        ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? {
            onPrepareSeam?.invoke()
            forgetAfterFirstPrepare?.let { id ->
                forgetAfterFirstPrepare = null
                forgetRetained(id)
            }
            logicalRequestId?.let { id ->
                retained[id]?.let { return it }
                if (isRetry) return null
            }
            val prepared = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn(
                request = if (logicalRequestId != null) "$request|$logicalRequestId" else request,
                context = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext(),
                requestId = "prepared-${preparedTurns.size + 1}",
                conversationId = "handle-${preparedTurns.size + 1}",
                conversationMode = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode.Create,
                capturedOrigin = capturedOrigin,
                sourceLanguage = sourceLanguage,
            )
            preparedTurns += prepared
            logicalRequestId?.let { retained[it] = prepared }
            graceFlags += isLiveGraceTurn
            capturedOrigins += capturedOrigin
            wakeOrigins += isWakeOriginated
            calls += "prepareTurn:$request"
            return prepared
        }
        override suspend fun dispatchPrepared(
            prepared: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn,
            isRetry: Boolean,
        ): CloudTurnResult {
            preparedDispatches += prepared
            calls += "dispatchPrepared:${prepared.request}"
            whileInFlight?.invoke()
            firstDispatchResult?.let { return it }
            return CloudTurnResult(
                feedback = VoiceResponse.Silent,
                action = actionOutcome,
            )
        }
        override suspend fun routeToCloud(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            isLiveGraceTurn: Boolean,
            capturedOrigin: Long?,
            isWakeOriginated: Boolean,
            admitted: String?,
        ): CloudTurnResult {
            calls += "routeToCloud:$request:${tier.name}"
            graceFlags += isLiveGraceTurn
            capturedOrigins += capturedOrigin
            wakeOrigins += isWakeOriginated
            return CloudTurnResult(
                feedback = VoiceResponse.Silent,
                action = actionOutcome,
            )
        }
    }
    private class RealCloudRouteSink(private val playback: au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerPlaybackSink) : ObservingCloudRouteTestAdapter() {
        val calls = mutableListOf<String>()
        override suspend fun reportRoute(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            onDuck: ((VoiceActionOutcome) -> Unit)?,
        ): VoiceResponse {
            calls += "routeToCloud"
            playback.duckReporting(onDuck)
            return VoiceResponse.Silent
        }
    }
    private class FakePlaybackContextProvider : PlaybackContextProvider {
        override fun current(): PlaybackContext = PlaybackContext()
    }
    private class FakeQueueSink : VoiceQueueSink {
        val calls = mutableListOf<String>()
        override suspend fun addTop(episode: String?): VoiceResponse {
            calls += "addTop:$episode"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override suspend fun addBottom(episode: String?): VoiceResponse {
            calls += "addBottom:$episode"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override suspend fun remove(episode: String): VoiceResponse {
            calls += "remove:$episode"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override suspend fun moveToTop(episode: String): VoiceResponse {
            calls += "moveToTop:$episode"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override suspend fun moveToBottom(episode: String): VoiceResponse {
            calls += "moveToBottom:$episode"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun clear(): VoiceResponse {
            calls += "clear"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override suspend fun removeByPodcast(podcast: String): VoiceResponse {
            calls += "removeByPodcast:$podcast"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun sort(sortOrder: String): VoiceResponse {
            calls += "sort:$sortOrder"
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override fun queryContents(): VoiceResponse.Spoken {
            calls += "queryContents"
            return VoiceResponse.Spoken("queue query")
        }
        override fun queryNext(): VoiceResponse.Spoken {
            calls += "queryNext"
            return VoiceResponse.Spoken("queue next")
        }
        override fun queryLength(): VoiceResponse.Spoken {
            calls += "queryLength"
            return VoiceResponse.Spoken("queue length")
        }
        override suspend fun queryIsQueued(episode: String): VoiceResponse.Spoken {
            calls += "queryIsQueued:$episode"
            return VoiceResponse.Spoken("queue check")
        }
    }
    private class FakePlaybackQuerySink : VoicePlaybackQuerySink {
        override fun whatsPlaying(): VoiceResponse.Spoken = VoiceResponse.Spoken("whats playing")
        override fun position(): VoiceResponse.Spoken = VoiceResponse.Spoken("position")
        override fun timeRemaining(): VoiceResponse.Spoken = VoiceResponse.Spoken("time remaining")
        override fun currentPodcast(): VoiceResponse.Spoken = VoiceResponse.Spoken("podcast")
        override fun episodeDuration(): VoiceResponse.Spoken = VoiceResponse.Spoken("duration")
        override fun publishDate(): VoiceResponse.Spoken = VoiceResponse.Spoken("date")
        override fun episodeDescription(): VoiceResponse.Spoken = VoiceResponse.Spoken("description")
        override fun downloadStatus(): VoiceResponse.Spoken = VoiceResponse.Spoken("status")
        override fun episodeTitle(): VoiceResponse.Spoken = VoiceResponse.Spoken("title")
    }
    private class FakeStatsQuerySink : VoiceStatsQuerySink {
        override fun listeningTime(period: String?): VoiceResponse.Spoken = VoiceResponse.Spoken("time")
        override fun topPodcasts(period: String?): VoiceResponse.Spoken = VoiceResponse.Spoken("top")
        override fun episodesFinished(period: String?): VoiceResponse.Spoken = VoiceResponse.Spoken("finished")
        override fun listeningStreak(): VoiceResponse.Spoken = VoiceResponse.Spoken("streak")
        override fun subscriptionCount(): VoiceResponse.Spoken = VoiceResponse.Spoken("subs")
        override fun unplayedTotal(): VoiceResponse.Spoken = VoiceResponse.Spoken("unplayed")
        override fun downloadStats(): VoiceResponse.Spoken = VoiceResponse.Spoken("downloads")
        override fun queueTotal(): VoiceResponse.Spoken = VoiceResponse.Spoken("queue")
        override fun newEpisodes(timeframe: String?): VoiceResponse.Spoken = VoiceResponse.Spoken("new")
        override fun timeSinceLastListen(): VoiceResponse.Spoken = VoiceResponse.Spoken("last")
    }
    @Test
    fun `an Unknown cloud outcome does not reset inactivity at the recognizing site`() = runTest {
        val sinks = FakeSinks()
        sinks.fakeCloudRoute.actionOutcome = CloudRouteActionOutcome.Unknown
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 60_000L)
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        val timerBefore = signal.timerJobForTest()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
            ),
        )
        org.junit.Assert.assertSame("an unknown action cannot reset inactivity", timerBefore, signal.timerJobForTest())
    }
    @Test
    fun `a Confirmed cloud outcome resets inactivity`() = runTest {
        val sinks = FakeSinks()
        sinks.fakeCloudRoute.actionOutcome = CloudRouteActionOutcome.Confirmed
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 60_000L)
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()
        val timerBefore = signal.timerJobForTest()
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
            ),
        )
        org.junit.Assert.assertNotSame("a confirmed action resets inactivity", timerBefore, signal.timerJobForTest())
    }
}
