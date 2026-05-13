package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackIntentExecutorTest {
    @Test
    fun `pause pauses sink`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.Pause)

        assertEquals(listOf("pause"), sink.calls)
    }

    @Test
    fun `resume resumes sink`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.Resume)

        assertEquals(listOf("resume"), sink.calls)
    }

    @Test
    fun `relative positive seek skips forward`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.SeekRelative(30_000))

        assertEquals(listOf("skipForward:30"), sink.calls)
    }

    @Test
    fun `relative negative seek skips backward`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.SeekRelative(-10_000))

        assertEquals(listOf("skipBackward:10"), sink.calls)
    }

    @Test
    fun `relative positive sub-second seek does nothing`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.SeekRelative(999))

        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test
    fun `relative negative sub-second seek does nothing`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.SeekRelative(-999))

        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test
    fun `relative zero seek does nothing`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.SeekRelative(0))

        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test
    fun `negative absolute seek clamps to zero`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.SeekAbsolute(-1))

        assertEquals(listOf("seekTo:0"), sink.calls)
    }

    @Test
    fun `next chapter advances sink chapter`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.NextChapter)

        assertEquals(listOf("nextChapter"), sink.calls)
    }

    @Test
    fun `previous chapter rewinds sink chapter`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.PreviousChapter)

        assertEquals(listOf("previousChapter"), sink.calls)
    }

    @Test
    fun `chapter by index selects sink chapter`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.ChapterByIndex(2))

        assertEquals(listOf("chapterByIndex:2"), sink.calls)
    }

    @Test
    fun `chapter by title does nothing`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.ChapterByTitle("intro"))

        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test
    fun `set playback speed calls setSpeed on sink`() = runTest {
        val sink = FakeVoicePlaybackSink()
        VoicePlaybackIntentExecutor(sink).execute(VoicePlaybackIntent.SetSpeed(1.25))

        assertEquals(listOf("setSpeed:1.25"), sink.calls)
    }

    private class FakeVoicePlaybackSink : VoicePlaybackSink {
        val calls = mutableListOf<String>()
        override suspend fun pause() {
            calls += "pause"
        }
        override suspend fun resume() {
            calls += "resume"
        }
        override suspend fun skipForward(seconds: Int) {
            calls += "skipForward:$seconds"
        }
        override suspend fun skipBackward(seconds: Int) {
            calls += "skipBackward:$seconds"
        }
        override suspend fun seekTo(positionMs: Int) {
            calls += "seekTo:$positionMs"
        }
        override fun nextChapter() {
            calls += "nextChapter"
        }
        override fun previousChapter() {
            calls += "previousChapter"
        }
        override fun chapterByIndex(index: Int) {
            calls += "chapterByIndex:$index"
        }
        override fun nextEpisode() {
            calls += "nextEpisode"
        }
        override fun setSpeed(speed: Double) {
            calls += "setSpeed:$speed"
        }
        override fun adjustSpeed(delta: Double) {
            calls += "adjustSpeed:$delta"
        }
        override fun setVolume(volume: Int) {
            calls += "setVolume:$volume"
        }
        override fun adjustVolume(delta: Int) {
            calls += "adjustVolume:$delta"
        }
        override fun sleepAfter(minutes: Int) {
            calls += "sleepAfter:$minutes"
        }
        override fun setTrimMode(mode: String) {
            calls += "setTrimMode:$mode"
        }
        override fun setVolumeBoost(enabled: Boolean) {
            calls += "setVolumeBoost:$enabled"
        }
        override fun addBookmark(title: String) {
            calls += "addBookmark:$title"
        }
    }
}
