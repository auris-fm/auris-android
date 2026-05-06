package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackIntentExecutorTest {
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

    private class FakeVoicePlaybackSink : VoicePlaybackSink {
        val calls = mutableListOf<String>()
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun skipForward(seconds: Int) { calls += "skipForward:$seconds" }
        override suspend fun skipBackward(seconds: Int) { calls += "skipBackward:$seconds" }
        override suspend fun seekTo(positionMs: Int) { calls += "seekTo:$positionMs" }
        override fun nextChapter() { calls += "nextChapter" }
        override fun previousChapter() { calls += "previousChapter" }
        override fun chapterByIndex(index: Int) { calls += "chapterByIndex:$index" }
    }
}
