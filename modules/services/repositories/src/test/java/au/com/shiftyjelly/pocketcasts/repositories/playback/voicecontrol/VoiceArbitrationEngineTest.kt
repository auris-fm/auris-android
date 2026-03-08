package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceArbitrationEngineTest {

    private val engine = DeterministicVoiceArbitrationEngine()

    @Test
    fun `selects cloud when cloud returns within deadline`() = runTest {
        val result = engine.arbitrate(
            localCall = {
                delay(200)
                recognized(RecognitionSource.LOCAL, IntentType.REWIND)
            },
            cloudCall = {
                delay(100)
                recognized(RecognitionSource.CLOUD, IntentType.SKIP_FORWARD)
            },
            arbitrationDeadlineMs = 1000,
        )

        assertNotNull(result.decision)
        assertEquals(RecognitionSource.CLOUD, result.decision?.selectedSource)
        assertEquals(IntentType.SKIP_FORWARD, result.selectedCommand?.intent?.intentType)
    }

    @Test
    fun `falls back to local when cloud misses deadline`() = runTest {
        val result = engine.arbitrate(
            localCall = {
                delay(60)
                recognized(RecognitionSource.LOCAL, IntentType.REWIND)
            },
            cloudCall = {
                delay(1500)
                recognized(RecognitionSource.CLOUD, IntentType.SKIP_FORWARD)
            },
            arbitrationDeadlineMs = 1000,
        )

        assertNotNull(result.decision)
        assertEquals(RecognitionSource.LOCAL, result.decision?.selectedSource)
        assertEquals(IntentType.REWIND, result.selectedCommand?.intent?.intentType)
        assertTrue(result.decision?.lateSourceIgnored == true)
    }

    @Test
    fun `uses local directly when cloud is unavailable`() = runTest {
        val result = engine.arbitrate(
            localCall = { recognized(RecognitionSource.LOCAL, IntentType.NEXT_EPISODE) },
            cloudCall = null,
            arbitrationDeadlineMs = 1000,
        )

        assertNotNull(result.decision)
        assertEquals(RecognitionSource.LOCAL, result.decision?.selectedSource)
        assertEquals(IntentType.NEXT_EPISODE, result.selectedCommand?.intent?.intentType)
    }

    private fun recognized(source: RecognitionSource, intentType: IntentType): RecognizedCommand {
        return RecognizedCommand(
            intent = CommandIntent(
                sessionId = "session-1",
                intentType = intentType,
                rawPhrase = intentType.name,
            ),
            latencyMs = 42,
            source = source,
        )
    }
}
