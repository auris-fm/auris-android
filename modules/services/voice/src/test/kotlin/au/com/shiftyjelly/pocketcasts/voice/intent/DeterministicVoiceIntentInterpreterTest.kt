package au.com.shiftyjelly.pocketcasts.voice.intent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DeterministicVoiceIntentInterpreterTest {
    private val interpreter = DeterministicVoiceIntentInterpreter()

    @Test
    fun `parses skip forward`() = runTest {
        assertEquals(
            VoicePlaybackIntent.SeekRelative(deltaMs = 30_000),
            interpreter.interpret(VoiceRecognitionResult("skip forward thirty seconds", confidence = 0.95f)),
        )
    }

    @Test
    fun `parses skip back`() = runTest {
        assertEquals(
            VoicePlaybackIntent.SeekRelative(deltaMs = -10_000),
            interpreter.interpret(VoiceRecognitionResult("skip back ten seconds", confidence = 0.95f)),
        )
    }

    @Test
    fun `parses rewind`() = runTest {
        assertEquals(
            VoicePlaybackIntent.SeekRelative(deltaMs = -10_000),
            interpreter.interpret(VoiceRecognitionResult("rewind ten seconds", confidence = 0.95f)),
        )
    }

    @Test
    fun `parses resume`() = runTest {
        assertEquals(
            VoicePlaybackIntent.Resume,
            interpreter.interpret(VoiceRecognitionResult("resume", confidence = 0.95f)),
        )
    }

    @Test
    fun `accepts minimum confidence`() = runTest {
        assertEquals(
            VoicePlaybackIntent.Resume,
            interpreter.interpret(VoiceRecognitionResult("resume", confidence = 0.7f)),
        )
    }

    @Test
    fun `parses next chapter`() = runTest {
        assertEquals(
            VoicePlaybackIntent.NextChapter,
            interpreter.interpret(VoiceRecognitionResult("next chapter", confidence = 0.95f)),
        )
    }

    @Test
    fun `parses previous chapter`() = runTest {
        assertEquals(
            VoicePlaybackIntent.PreviousChapter,
            interpreter.interpret(VoiceRecognitionResult("previous chapter", confidence = 0.95f)),
        )
    }

    @Test
    fun `does not parse pause inside larger sentence`() = runTest {
        assertEquals(
            null,
            interpreter.interpret(VoiceRecognitionResult("pause after this chapter", confidence = 0.95f)),
        )
    }

    @Test
    fun `rejects low confidence`() = runTest {
        assertEquals(
            null,
            interpreter.interpret(VoiceRecognitionResult("skip forward", confidence = 0.2f)),
        )
    }
}
