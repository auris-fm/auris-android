package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackIntentExecutorTest {
    @Test
    fun `pause pauses sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.Pause)

        assertEquals(VoiceResponse.Earcon("pause"), response)
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

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(30_000))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipForward:30"), sinks.playback.calls)
    }

    @Test
    fun `relative negative seek skips backward`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(-10_000))

        assertEquals(VoiceResponse.Silent, response)
        assertEquals(listOf("skipBackward:10"), sinks.playback.calls)
    }

    @Test
    fun `relative positive sub-second seek does nothing`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Playback.SeekRelative(999))

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

        assertEquals(VoiceResponse.Earcon("next_episode"), response)
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

        assertEquals(VoiceResponse.Earcon("speed"), response)
        assertEquals(listOf("setSpeed:1.25"), sinks.effects.calls)
    }

    @Test
    fun `set volume calls volume sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Volume.SetVolume(50))

        assertEquals(VoiceResponse.Earcon("volume"), response)
        assertEquals(listOf("setVolume:50"), sinks.volume.calls)
    }

    @Test
    fun `sleep set calls sleep sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Sleep.Set(30))

        assertEquals(VoiceResponse.Earcon("sleep"), response)
        assertEquals(listOf("set:30"), sinks.sleep.calls)
    }

    @Test
    fun `bookmark add calls bookmark sink`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.Bookmark.Add("my bookmark"))

        assertEquals(VoiceResponse.Earcon("bookmark"), response)
        assertEquals(listOf("add:my bookmark"), sinks.bookmark.calls)
    }

    @Test
    fun `unhandled domain returns silent`() = runTest {
        val sinks = FakeSinks()
        val executor = sinks.executor()

        val response = executor.execute(VoiceIntent.PlaybackQuery.WhatsPlaying)

        assertEquals(VoiceResponse.Silent, response)
    }

    private class FakeSinks {
        val playback = FakePlaybackSink()
        val effects = FakeEffectsSink()
        val volume = FakeVolumeSink()
        val sleep = FakeSleepSink()
        val chapter = FakeChapterSink()
        val bookmark = FakeBookmarkSink()

        fun executor() = VoicePlaybackIntentExecutor(
            playbackSink = playback,
            effectsSink = effects,
            volumeSink = volume,
            sleepSink = sleep,
            chapterSink = chapter,
            bookmarkSink = bookmark,
        )
    }

    private class FakePlaybackSink : VoicePlaybackSink {
        val calls = mutableListOf<String>()
        override suspend fun pause(): VoiceResponse {
            calls += "pause"
            return VoiceResponse.Earcon("pause")
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
        override suspend fun seekTo(positionMs: Int): VoiceResponse {
            calls += "seekTo:$positionMs"
            return VoiceResponse.Silent
        }
        override fun nextEpisode(): VoiceResponse {
            calls += "nextEpisode"
            return VoiceResponse.Earcon("next_episode")
        }
    }

    private class FakeEffectsSink : VoiceEffectsSink {
        val calls = mutableListOf<String>()
        override fun setSpeed(speed: Double): VoiceResponse {
            calls += "setSpeed:$speed"
            return VoiceResponse.Earcon("speed")
        }
        override fun adjustSpeed(delta: Double): VoiceResponse {
            calls += "adjustSpeed:$delta"
            return VoiceResponse.Earcon("speed")
        }
        override fun setTrimMode(mode: String): VoiceResponse {
            calls += "setTrimMode:$mode"
            return VoiceResponse.Earcon("trim")
        }
        override fun setVolumeBoost(enabled: Boolean): VoiceResponse {
            calls += "setVolumeBoost:$enabled"
            return VoiceResponse.Earcon("volume_boost")
        }
        override fun queryEffects(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }

    private class FakeVolumeSink : VoiceVolumeSink {
        val calls = mutableListOf<String>()
        override fun setVolume(volume: Int): VoiceResponse {
            calls += "setVolume:$volume"
            return VoiceResponse.Earcon("volume")
        }
        override fun adjustVolume(delta: Int): VoiceResponse {
            calls += "adjustVolume:$delta"
            return VoiceResponse.Earcon("volume")
        }
        override fun query(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }

    private class FakeSleepSink : VoiceSleepSink {
        val calls = mutableListOf<String>()
        override fun set(minutes: Int): VoiceResponse {
            calls += "set:$minutes"
            return VoiceResponse.Earcon("sleep")
        }
        override fun endOfEpisode(): VoiceResponse {
            calls += "endOfEpisode"
            return VoiceResponse.Earcon("sleep")
        }
        override fun endOfChapter(): VoiceResponse {
            calls += "endOfChapter"
            return VoiceResponse.Earcon("sleep")
        }
        override fun addTime(minutes: Int): VoiceResponse {
            calls += "addTime:$minutes"
            return VoiceResponse.Earcon("sleep")
        }
        override fun cancel(): VoiceResponse {
            calls += "cancel"
            return VoiceResponse.Earcon("sleep")
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
            return VoiceResponse.Earcon("chapter_link")
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
            return VoiceResponse.Earcon("bookmark")
        }
        override fun rename(ref: String, title: String): VoiceResponse {
            calls += "rename:$ref:$title"
            return VoiceResponse.Earcon("bookmark")
        }
        override fun play(ref: String): VoiceResponse {
            calls += "play:$ref"
            return VoiceResponse.Silent
        }
        override fun delete(ref: String): VoiceResponse {
            calls += "delete:$ref"
            return VoiceResponse.Earcon("bookmark")
        }
        override fun deleteAll(): VoiceResponse {
            calls += "deleteAll"
            return VoiceResponse.Earcon("bookmark")
        }
        override fun queryList(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
        override fun queryCount(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
        override fun queryNearby(): VoiceResponse.Spoken = VoiceResponse.Spoken("query")
    }
}
