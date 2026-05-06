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
    fun `parses resume`() = runTest {
        assertEquals(
            VoicePlaybackIntent.Resume,
            interpreter.interpret(VoiceRecognitionResult("resume", confidence = 0.95f)),
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
