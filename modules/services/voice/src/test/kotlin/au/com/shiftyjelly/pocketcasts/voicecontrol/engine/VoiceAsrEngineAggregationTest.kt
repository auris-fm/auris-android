package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE AGGREGATION SEMANTICS CONTROL (spec 250ddcc3): settlement publishes the SUPPORTED aggregate
 * of an invocation's per-action observations — full agreement publishes that outcome; ANY
 * disagreement publishes the explicit schema unknown (never an invented single outcome over
 * conflicting facts); nothing observed publishes unknown too.
 */
class VoiceAsrEngineAggregationTest {

    @Test
    fun `full agreement publishes the agreed outcome`() {
        assertEquals(
            ExecutionOutcome.Success,
            VoiceAsrEngine.aggregateObservations(listOf(ExecutionOutcome.Success, ExecutionOutcome.Success)),
        )
        assertEquals(
            ExecutionOutcome.Refused,
            VoiceAsrEngine.aggregateObservations(listOf(ExecutionOutcome.Refused)),
        )
    }

    @Test
    fun `disagreeing observations publish the explicit unknown, not a chosen side`() {
        assertNull(
            VoiceAsrEngine.aggregateObservations(listOf(ExecutionOutcome.Success, ExecutionOutcome.Failure)),
        )
        assertNull(
            VoiceAsrEngine.aggregateObservations(listOf(ExecutionOutcome.Refused, ExecutionOutcome.Cancelled, ExecutionOutcome.Success)),
        )
    }

    @Test
    fun `nothing observed publishes the unknown`() {
        assertNull(VoiceAsrEngine.aggregateObservations(emptyList()))
    }
}
