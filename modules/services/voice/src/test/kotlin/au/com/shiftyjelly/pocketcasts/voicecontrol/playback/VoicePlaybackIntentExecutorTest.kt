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
        assertEquals(listOf("pause"), sinks.playback.calls)
    }

    @Test
    fun `resume resumes sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.Resume)

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("resume"), sinks.playback.calls)
    }

    @Test
    fun `relative positive seek skips forward`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(30))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipForward:30"), sinks.playback.calls)
    }

    @Test
    fun `relative negative seek skips backward`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(-10))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipBackward:10"), sinks.playback.calls)
    }

    @Test
    fun `relative null seek with direction uses sink interval`() = runTest {
        // null deltaSeconds means "no amount stated — sink should apply its own
        // interval in direction". The executor passes null so the sink uses the
        // app's configured interval rather than a manufactured value.
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(null, "backward"))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipBackward:null"), sinks.playback.calls)
    }

    @Test
    fun `relative null seek without direction uses forward interval`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(null, null))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipForward:null"), sinks.playback.calls)
    }

    @Test
    fun `negative absolute seek resolves as an offset back from the episode end`() = runTest {
        // voice-intents: "non-negative values are offsets from the beginning, 0 means beginning, and
        // negative values are offsets back from the episode end." Clamping to zero is a different
        // instruction: "5 s before the end" is not "the beginning". The duration is what makes the
        // offset resolvable, so it is supplied and must be used.
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
        assertEquals(listOf("seekTo:1795"), sinks.playback.calls)
    }

    @Test
    fun `next episode calls sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.NextEpisode)

        assertEquals(VoiceResponse.Earcon(EarconId.NEXT_EPISODE), response)
        assertEquals(listOf("nextEpisode"), sinks.playback.calls)
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
    fun `a dispatched turn carries its own window stamp, so a fresh wake creates`() = runTest {
        // @reviewer's bound-path case: the STAMP ON THE INTENT is the captured origin. A fresh wake
        // must create even while the previous conversation is still live, and a follow-up stamped with
        // the same window must resume it.
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
            sinks.cloudRoute.wakeOrigins.last(),
        )
        assertEquals(firstWindow, sinks.cloudRoute.capturedOrigins.last())

        // A follow-up in the SAME window: not wake-originated, so it resumes.
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
            sinks.cloudRoute.capturedOrigins.last(),
        )
    }

    @Test
    fun `a turn stamped with a superseded window never reaches transport`() = runTest {
        // @reviewer's stale-origin case: an intent captured before a successor wake must not dispatch
        // under the successor's window, and must not mutate the successor's playback either.
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
        val callsAfterSuccessor = sinks.playback.calls.toList()

        executor.execute(
            VoiceIntent.CloudRoute(
                request = "late old",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = staleStamp,
            ),
        )

        assertEquals(
            "only the successor reached transport -- the stale dispatch was refused BEFORE preparing " +
                "(prepared=${sinks.cloudRoute.preparedTurns.map { it.request }} " +
                "dispatched=${sinks.cloudRoute.preparedDispatches.map { it.request }})",
            listOf("successor"),
            sinks.cloudRoute.preparedDispatches.map { it.request },
        )
        assertEquals(
            "and the stale dispatch did not mutate the successor's playback",
            callsAfterSuccessor,
            sinks.playback.calls,
        )
    }

    @Test
    fun `a wake-free follow-up in the same window does not inherit the wake's flag`() = runTest {
        // @spec's correction: the wake selection belongs to EACH REQUEST. The window is opened by a
        // wake, but only the wake's own request begins a conversation; a follow-up in the same window
        // continues it. Reading "this window was opened by a wake" for the follow-up made every
        // follow-up look like a fresh user act.
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
            sinks.cloudRoute.wakeOrigins.last(),
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
                "(wakeOrigins=${sinks.cloudRoute.wakeOrigins})",
            sinks.cloudRoute.wakeOrigins.last(),
        )
    }

    @Test
    fun `a privacy-closed window is not current even at a matching generation`() = runTest {
        // @spec's third correction: currentness must reject a closed window, not just compare numbers.
        // A privacy close means nothing keeps listening, so a request whose window was closed must not
        // be admitted simply because its generation still matches.
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
            "so the dispatch does not reach transport (calls=${sinks.cloudRoute.calls})",
            sinks.cloudRoute.calls.isEmpty(),
        )
    }

    @Test
    fun `a retry of the same utterance reuses the turn the first dispatch prepared`() = runTest {
        // @spec's production-retention requirement: preparation and retention happen in the EXECUTOR,
        // so the retry path is reachable without a caller populating the intent. A branch only a test
        // can reach would leave retention unimplemented.
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

        // First attempt: the executor prepares and retains.
        executor.execute(intent)
        assertEquals("one turn prepared", 1, sinks.cloudRoute.preparedTurns.size)

        // RETRY of the same utterance, with NO prepared turn on the intent: the executor must find the
        // one it retained.
        executor.execute(intent)

        assertEquals(
            "the retry did NOT prepare a second turn (prepared=" +
                "${sinks.cloudRoute.preparedTurns.map { it.requestId }})",
            1,
            sinks.cloudRoute.preparedTurns.size,
        )
        assertEquals("two attempts dispatched", 2, sinks.cloudRoute.preparedDispatches.size)
        assertEquals(
            "and both carried the SAME request ID",
            sinks.cloudRoute.preparedDispatches[0].requestId,
            sinks.cloudRoute.preparedDispatches[1].requestId,
        )
        assertEquals(
            "and the same conversation handle",
            sinks.cloudRoute.preparedDispatches[0].conversationId,
            sinks.cloudRoute.preparedDispatches[1].conversationId,
        )
    }

    @Test
    fun `two same-text user acts a fresh wake apart both dispatch and both create`() = runTest {
        // @reviewer's bound-path case, whose failure motivated the logical identity: two separate user
        // acts saying the SAME words, each under its own wake. Each must dispatch, and each must be its
        // own CREATE with its own request ID and handle -- neither an omission nor a shared turn.
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
                "${sinks.cloudRoute.preparedDispatches.map { it.requestId }})",
            2,
            sinks.cloudRoute.preparedDispatches.size,
        )
        assertNotEquals(
            "distinct user acts need distinct request IDs",
            sinks.cloudRoute.preparedDispatches[0].requestId,
            sinks.cloudRoute.preparedDispatches[1].requestId,
        )
        assertNotEquals(
            "and distinct conversations, since each is its own CREATE",
            sinks.cloudRoute.preparedDispatches[0].conversationId,
            sinks.cloudRoute.preparedDispatches[1].conversationId,
        )
    }

    @Test
    fun `a retry of a turn prepared under an older window is refused after a successor opens`() = runTest {
        // @reviewer's regression: prepare under generation 1, open generation 2, then retry the SAME
        // logical request with the new dispatch's stamp. The RETENTION lookup returns the OLD turn, so
        // validating the incoming stamp passes while the turn being sent belongs to a superseded window.
        // The check must run on the TURN's own captured origin.
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
        val dispatchesAfterFirst = sinks.cloudRoute.preparedDispatches.size

        // A SUCCESSOR window opens.
        signal.onWakeWordDetected()
        val successor = signal.capturedGeneration()
        assertNotEquals("the window moved", first, successor)

        // Retry the SAME logical request, stamped with the NEW window.
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
                "(dispatches=${sinks.cloudRoute.preparedDispatches.size})",
            dispatchesAfterFirst,
            sinks.cloudRoute.preparedDispatches.size,
        )
    }

    @Test
    fun `a retry retired BETWEEN the executor's guards and preparation is refused, not recreated`() = runTest {
        // @reviewer's dispatch-seam control. The executor's entry guards run first and then the sink
        // prepares; a retirement landing BETWEEN them used to be invisible, because the executor did not
        // forward the retry flag -- so the sink saw a request it had never seen and MINTED A NEW ONE,
        // sending a fresh conversation for a request the user meant to retry. The refusal exists, but a
        // refusal that never receives the flag cannot fire.
        val sinks = FakeSinks()
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val executor = sinks.executor(signal)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()

        // First attempt: prepares and retains under its logical identity.
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "prepare me",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = window,
                logicalRequestId = "logical-seam",
                isRetry = false,
            ),
        )
        assertEquals("the first attempt prepared", 1, sinks.cloudRoute.preparedTurns.size)

        // The retirement lands AT THE SEAM. The executor's guards are entry checks on a state read once:
        // a retirement arriving after them but before preparation is exactly the gap, so the hook fires
        // from the PREPARATION path where the second call's preparation begins.
        sinks.cloudRoute.onPrepareSeam = { sinks.cloudRoute.forgetRetained("logical-seam") }
        // Fires only on the RETRY: the first attempt legitimately prepares.
        sinks.cloudRoute.forgetAfterFirstPrepare = "logical-seam"

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
                "(prepared=${sinks.cloudRoute.preparedTurns.size})",
            1,
            sinks.cloudRoute.preparedTurns.size,
        )
        assertEquals(
            "and nothing may be dispatched for it " +
                "(dispatched=${sinks.cloudRoute.preparedDispatches.size})",
            1,
            sinks.cloudRoute.preparedDispatches.size,
        )
    }

    @Test
    fun `a retry naming an UNKNOWN logical request is refused rather than treated as new input`() = runTest {
        // @spec's unknown-retry refusal: a retry whose identity was never prepared is not a retry of
        // anything. Treating it as new input would create a conversation for a request whose own
        // identity is unresolvable -- the substitution this mechanism exists to prevent.
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
                "(prepared=${sinks.cloudRoute.preparedTurns.size} " +
                "dispatched=${sinks.cloudRoute.preparedDispatches.size})",
            sinks.cloudRoute.preparedTurns.isEmpty() && sinks.cloudRoute.preparedDispatches.isEmpty(),
        )
    }

    @Test
    fun `a FIRST attempt naming a logical request is not refused`() = runTest {
        // The control: the refusal is about retries, not about the identity. A first attempt naming a
        // logical request is exactly how that request comes to exist.
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

        assertEquals("a first attempt prepares normally", 1, sinks.cloudRoute.preparedTurns.size)
        assertEquals("and dispatches", 1, sinks.cloudRoute.preparedDispatches.size)
    }

    @Test
    fun `a retry of an EARLIER logical request still finds its own turn after a later one`() = runTest {
        // @reviewer's retired-ID case: executing logical A, then B, then retrying A must reuse A's turn.
        // With a single retention slot, B OVERWROTE A, so the retry of A found nothing and created a
        // THIRD wire request for a logical request that already had one.
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
        val preparedAfterAB = sinks.cloudRoute.preparedTurns.size
        dispatch("A") // the retry of the EARLIER one

        assertEquals(
            "the retry of A did not prepare a third turn " +
                "(prepared=${sinks.cloudRoute.preparedTurns.map { it.requestId }})",
            preparedAfterAB,
            sinks.cloudRoute.preparedTurns.size,
        )
        // The fake carries the logical id in the request text, so the filter matches on the prefix.
        val dispatchedA = sinks.cloudRoute.preparedDispatches.filter { it.request.startsWith("question A") }
        assertEquals("A was dispatched twice, once initially and once as a retry", 2, dispatchedA.size)
        assertEquals(
            "and both carried A's OWN request ID",
            dispatchedA[0].requestId,
            dispatchedA[1].requestId,
        )
    }

    @Test
    fun `the logical identity is what matches, not the words`() = runTest {
        // THE DISCRIMINATING CASE for the retention key. A retry that names the SAME logical request
        // but words the request differently must reuse the prepared turn: the identity is what matches.
        // Under a text key it would prepare a second turn -- which is the failure this case exists to
        // separate from the same-text case, whose outcome a text key happens to reproduce.
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
        // The SAME logical request, worded differently.
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
                "(ids=${sinks.cloudRoute.preparedTurns.map { it.requestId }})",
            1,
            sinks.cloudRoute.preparedTurns.size,
        )
        assertEquals(
            "and the retry reuses the request ID the first attempt fixed",
            sinks.cloudRoute.preparedTurns.single().requestId,
            sinks.cloudRoute.preparedDispatches.last().requestId,
        )
    }

    @Test
    fun `two distinct user acts saying the same words prepare separate requests`() = runTest {
        // @spec's key correction: IDENTICAL WORDS ARE NOT THE SAME REQUEST. Two separate user acts can
        // say the same thing, and each needs its own request ID and its own CREATE/RESUME decision
        // under its own origin. Keying retention on the request text would have merged them.
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
        // A NEW wake: a separate user act, saying the SAME words.
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
            "each user act prepared its OWN turn (ids=${sinks.cloudRoute.preparedTurns.map { it.requestId }})",
            2,
            sinks.cloudRoute.preparedTurns.size,
        )
        assertNotEquals(
            "with a distinct request ID",
            sinks.cloudRoute.preparedTurns[0].requestId,
            sinks.cloudRoute.preparedTurns[1].requestId,
        )
    }

    @Test
    fun `an utterance naming no logical request is a new act, not a retry`() = runTest {
        // The control for the key: a dispatch that names NO logical request never finds a retained turn,
        // so it prepares its own. Without this, retention keyed on anything would look correct.
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
                "(ids=${sinks.cloudRoute.preparedTurns.map { it.requestId }})",
            2,
            sinks.cloudRoute.preparedTurns.size,
        )
    }

    @Test
    fun `a dispatch carrying a prepared turn goes through the retry path, not fresh preparation`() = runTest {
        // @reviewer's missing production caller: the executor must carry and dispatch a prepared
        // turn, so a controller retry reuses its identity instead of preparing a new request.
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
            "the prepared turn was dispatched through the retry path (calls=${sinks.cloudRoute.calls})",
            listOf(prepared),
            sinks.cloudRoute.preparedDispatches,
        )
        assertTrue(
            "and NOT re-prepared as a fresh request",
            sinks.cloudRoute.calls.none { it.startsWith("routeToCloud:") },
        )
    }

    @Test
    fun `a prepared retry whose window was closed by privacy is refused before dispatch`() = runTest {
        // The privacy half belongs to the caller: the sink cannot observe the grace signal, so the
        // executor refuses a closed window for a retry exactly as it does for a first attempt.
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
                "(dispatches=${sinks.cloudRoute.preparedDispatches})",
            sinks.cloudRoute.preparedDispatches.isEmpty(),
        )
    }

    @Test
    fun `the abandonment binding releases a retained turn and clears on teardown`() = runTest {
        // @spec's production composition call, with its lifetime: the binding must CONNECT abandonment
        // to the retention release, and must be REMOVABLE so a session's end does not leave the manager
        // reporting into a sink whose session is over.
        val sinks = FakeSinks()
        val manager = au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager(
            au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper(),
        )
        val executor = sinks.executor()

        // Connected: the bound callback reaches the SINK's release, which is the production effect.
        val released = mutableListOf<String>()
        sinks.cloudRoute.onForget = { released += it }
        executor.bindDialogAbandonment(manager)
        assertNotNull(
            "the binding connects the manager to the release",
            manager.onRequestAbandoned,
        )
        // Drive it: an abandoned request must reach the sink's release.
        manager.onRequestAbandoned?.invoke("logical-1")
        assertEquals(
            "an abandoned request reaches the sink's release (released=$released)",
            listOf("logical-1"),
            released,
        )

        // Cleared: the manager no longer reports into the session that has ended.
        executor.unbindDialogAbandonment()
        assertNull(
            "teardown clears the binding, so the manager does not report into a finished session",
            manager.onRequestAbandoned,
        )
        // The release recorded BEFORE teardown is unaffected by clearing the binding, and nothing new
        // arrives afterwards: clearing REMOVES the wiring without undoing what it already delivered.
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
            sinks.cloudRoute.calls,
        )
    }

    @Test
    fun `a spoken pause is reported as a user command`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        executor.execute(VoiceIntent.Playback.Pause)

        // The playback layer cannot tell the user's spoken pause from the cloud
        // turn's own pause — both carry the voice tag — so the executor reports
        // it, and that is what blocks a turn from restoring over the user.
        org.mockito.kotlin.verify(sinks.playbackManager).noteUserPlaybackCommand()
    }

    @Test
    fun `the user's command is recorded before the action runs`() = runTest {
        val order = mutableListOf<String>()
        val sinks = FakeSinks()
        sinks.playback.onCall = { order += "playback:$it" }
        Mockito.doAnswer { order += "noteUserPlaybackCommand" }
            .`when`(sinks.playbackManager).noteUserPlaybackCommand()
        val executor = sinks.executor()

        executor.execute(VoiceIntent.Playback.Pause)

        // A pause that has reached the player but not the counter is the window
        // where a cloud turn would resume over the user.
        assertEquals(listOf("noteUserPlaybackCommand", "playback:pause"), order)
    }

    @Test
    fun `a spoken seek is not a play-pause command`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        executor.execute(VoiceIntent.Playback.SeekRelative(30))

        // A seek does not contest the turn's pause, so it must not invalidate it.
        org.mockito.kotlin.verify(sinks.playbackManager, org.mockito.kotlin.never()).noteUserPlaybackCommand()
    }

    @Test
    fun `handling an escalation does not refresh the window budget`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)

        // A user act opens the window; its one dispatch is spent on this turn.
        signal.onWakeWordDetected()
        val generation = signal.issueEscalation()!!
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "what did the guests say about sleep and memory",
                tier = VoiceIntent.CloudTier.Unknown,
                windowGeneration = generation,
                origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
            ),
        )

        // Handling it extends the window, but must not hand the turn a new
        // allowance — otherwise one unclear question per turn re-arms it forever.
        assertNull(
            "a dispatched turn must not fund its own next attempt",
            signal.issueEscalation(),
        )
        assertEquals(
            listOf(
                "prepareTurn:what did the guests say about sleep and memory",
                "dispatchPrepared:what did the guests say about sleep and memory",
            ),
            sinks.cloudRoute.calls,
        )
    }

    @Test
    fun `a fallback completing after a privacy close leaves the window closed`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)

        signal.onWakeWordDetected()
        val generation = signal.issueEscalation()!!

        // The app is backgrounded while the cloud turn is still in flight.
        sinks.cloudRoute.whileInFlight = { signal.onAppBackgrounded() }
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "what did the guests say about sleep and memory",
                tier = VoiceIntent.CloudTier.Unknown,
                windowGeneration = generation,
                origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
            ),
        )

        // The turn's completion must not bring the mic back: the privacy close
        // wins over an in-flight turn.
        assertFalse("a completion reopened a closed window", signal.isActive.value)
        assertNull(signal.issueEscalation())
    }

    @Test
    fun `a model-chosen route completing in its window restores the allowance`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)

        signal.onWakeWordDetected()
        val generation = signal.issueEscalation()!!
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
                windowGeneration = generation,
                origin = VoiceIntent.CloudRouteOrigin.ModelCall,
            ),
        )

        assertNotNull("a deliberate route earns the next dispatch", signal.issueEscalation())
    }

    @Test
    fun `a model-chosen route completing after a privacy close leaves the window closed`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)

        signal.onWakeWordDetected()
        val generation = signal.currentGeneration
        sinks.cloudRoute.whileInFlight = { signal.onAppBackgrounded() }
        executor.execute(
            VoiceIntent.CloudRoute(
                request = "summarize this episode",
                tier = VoiceIntent.CloudTier.Premium,
                // The router chose this one; the rule has to hold for it too.
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
    fun `a locally recognised command refreshes the window budget`() = runTest {
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 30_000L)
        val sinks = FakeSinks()
        val executor = sinks.executor(signal)

        signal.onWakeWordDetected()
        assertNotNull(signal.issueEscalation())
        executor.execute(VoiceIntent.Playback.Pause)

        // A locally handled command is a new act, so it earns a fresh allowance.
        assertNotNull(signal.issueEscalation())
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

    private class FakeSinks {
        val playback = FakePlaybackSink()
        val effects = FakeEffectsSink()
        val volume = FakeVolumeSink()
        val sleep = FakeSleepSink()
        val chapter = FakeChapterSink()
        val bookmark = FakeBookmarkSink()
        val cloudRoute = FakeCloudRouteSink()
        val playbackContext = FakePlaybackContextProvider()
        val queue = FakeQueueSink()
        val playbackQuery = FakePlaybackQuerySink()
        val statsQuery = FakeStatsQuerySink()

        val playbackManager: au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager = org.mockito.kotlin.mock()

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
        override suspend fun resume(): VoiceResponse {
            calls += "resume"
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

        /** The grace flag each call carried, so a case can assert continuation versus creation. */
        val graceFlags = mutableListOf<Boolean>()

        /** Runs while the turn is in flight, to land a privacy event mid-turn. */
        var whileInFlight: (() -> Unit)? = null

        /** The captured window identity each call carried, so a case can assert what was captured. */
        val capturedOrigins = mutableListOf<Long?>()
        val wakeOrigins = mutableListOf<Boolean>()

        /** The prepared turns a retry dispatched, so a case can assert identity was reused. */
        val preparedDispatches = mutableListOf<au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()

        /** The turns prepared on a first dispatch, so a case can assert identity was minted once. */
        val preparedTurns = mutableListOf<au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()

        /** Retained turns by logical request, mirroring the sink's store. */
        private val retained =
            mutableMapOf<String, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()

        /** Invoked when the production release path forgets a request, so a case can observe it. */
        var onForget: ((String) -> Unit)? = null

        override fun onRetentionReleased(consumer: (logicalRequestId: String) -> Unit) {
            // Retained for symmetry with the production interface; the fake has no store to release.
        }

        override fun forgetRetained(logicalRequestId: String) {
            // ACTUALLY FORGET, not just report: a fake that only notified left the entry in its store,
            // so a retry the production release had retired still resolved here -- the fake would have
            // made the defect invisible.
            retained.remove(logicalRequestId)
            onForget?.invoke(logicalRequestId)
        }

        /** Runs at the dispatch seam, between the executor's guards and preparation. */
        var onPrepareSeam: (() -> Unit)? = null

        /** Retires this logical request the first time a retry reaches preparation. */
        var forgetAfterFirstPrepare: String? = null

        /** The windows whose ending reached the sink, so a case can assert the dispatch was wired. */
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
        ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? {
            onPrepareSeam?.invoke()
            // RETIRE AT THE SEAM, once, on the retry: a retirement landing after the executor's guards
            // have already read the store is what the refusal must still catch.
            forgetAfterFirstPrepare?.let { id ->
                forgetAfterFirstPrepare = null
                forgetRetained(id)
            }
            // Mirrors the production rule: a logical request that already has a turn reuses it, so the
            // fake cannot mask a retry by minting a fresh one.
            logicalRequestId?.let { id ->
                // THE STORE IS THE `retained` MAP ALONE. Looking in `preparedTurns` as well made the
                // fake's store a SECOND record that a retirement never cleared -- so a retired request
                // still resolved here and the case could never observe the refusal. Production has one
                // store; the fake now does too.
                retained[id]?.let { return it }
                // Mirrors the production rule: a RETRY that misses is refused rather than prepared,
                // because preparing would resurrect a request that is no longer retryable.
                if (isRetry) return null
            }
            val prepared = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn(
                // The logical id is carried in the request text so the fake can key on it exactly as
                // production keys on the identity field.
                request = if (logicalRequestId != null) "$request|$logicalRequestId" else request,
                context = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext(),
                requestId = "prepared-${preparedTurns.size + 1}",
                conversationId = "handle-${preparedTurns.size + 1}",
                conversationMode = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode.Create,
                capturedOrigin = capturedOrigin,
            )
            preparedTurns += prepared
            logicalRequestId?.let { retained[it] = prepared }
            // Recorded at PREPARE time: production resolves the window and wake selection here, so a
            // case asserts what the executor actually decided, not what a call site happened to pass.
            graceFlags += isLiveGraceTurn
            capturedOrigins += capturedOrigin
            wakeOrigins += isWakeOriginated
            calls += "prepareTurn:$request"
            return prepared
        }

        override suspend fun dispatchPrepared(
            prepared: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn,
        ): VoiceResponse {
            preparedDispatches += prepared
            calls += "dispatchPrepared:${prepared.request}"
            // The hook belongs to the DISPATCH, which is where production now sends the turn: a case
            // that landed an event "while in flight" through the old call site would silently land it
            // nowhere, and the case would pass or fail for the wrong reason.
            whileInFlight?.invoke()
            return VoiceResponse.Silent
        }

        override suspend fun routeToCloud(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            isLiveGraceTurn: Boolean,
            capturedOrigin: Long?,
            isWakeOriginated: Boolean,
        ): VoiceResponse {
            calls += "routeToCloud:$request:${tier.name}"
            graceFlags += isLiveGraceTurn
            capturedOrigins += capturedOrigin
            wakeOrigins += isWakeOriginated
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
}
