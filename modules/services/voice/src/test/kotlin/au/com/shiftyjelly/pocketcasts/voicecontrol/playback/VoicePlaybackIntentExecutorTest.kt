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
    fun `relative null seek does nothing`() = runTest {
        // null deltaSeconds means "no amount stated — sink should apply its own
        // interval in direction". The executor cannot act without direction info,
        // so it returns Silent. This path is for the local matcher which always
        // provides a non-null value; null is only reachable from the cloud path
        // where the sink handles the null case in executeAction.
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(null))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(emptyList<String>(), sinks.playback.calls)
    }

    @Test
    fun `negative absolute seek clamps to zero`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekAbsolute(-1))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("seekTo:0"), sinks.playback.calls)
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
            listOf("routeToCloud:summarize this episode:Premium"),
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
            listOf("routeToCloud:what did the guests say about sleep and memory:Unknown"),
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
        override suspend fun pause(): VoiceResponse {
            calls += "pause"
            onCall?.invoke("pause")
            return VoiceResponse.Earcon(EarconId.SUCCESS)
        }
        override suspend fun resume(): VoiceResponse {
            calls += "resume"
            return VoiceResponse.Silent
        }
        override suspend fun skipForward(seconds: Int): VoiceResponse {
            calls += "skipForward:$seconds"
            return VoiceResponse.Silent
        }
        override suspend fun skipBackward(seconds: Int): VoiceResponse {
            calls += "skipBackward:$seconds"
            return VoiceResponse.Silent
        }
        override suspend fun seekTo(positionSeconds: Int): VoiceResponse {
            calls += "seekTo:$positionSeconds"
            return VoiceResponse.Silent
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

        /** Runs while the turn is in flight, to land a privacy event mid-turn. */
        var whileInFlight: (() -> Unit)? = null

        override suspend fun routeToCloud(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
        ): VoiceResponse {
            calls += "routeToCloud:$request:${tier.name}"
            whileInFlight?.invoke()
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
