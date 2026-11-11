package au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3: the per-turn timing table, as it is written to the persisted log.
 *
 * These tests are about what the record is allowed to claim. A timing table is worthless if it
 * reports a step that nothing measured, or reports two different events as one, so most of them
 * are about refusal rather than arithmetic.
 */
class TurnTimingRecordTest {

    private fun complete(): TurnTimingRecord {
        val record = TurnTimingRecord(requestId = "turn-1")
        record.mark(TurnTimingRecord.Stage.AcousticEnd, atNanos = 1_000 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.EndpointDecision, atNanos = 1_180 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.WakeResult, atNanos = 1_210 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.AsrDone, atNanos = 1_360 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.RoutingDone, atNanos = 1_940 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.CloudRequestSent, atNanos = 1_950 * 1_000_000L)
        record.markDisposition(atNanos = 2_100 * 1_000_000L, cancelled = false)
        record.mark(TurnTimingRecord.Stage.LocalActionExecuted, atNanos = 2_120 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.FirstAudible, atNanos = 2_160 * 1_000_000L)
        return record
    }

    @Test
    fun `acoustic end and the endpoint decision are separate stages`() {
        // They are different events: the audio stops, and then the endpointing decides the
        // utterance is over. Reporting one number for both hides exactly the hangover the
        // latency work is trying to measure.
        val record = complete()
        val lines = record.lines()
        val acoustic = lines.indexOfFirst { it.contains("stage=acoustic_end") }
        val endpoint = lines.indexOfFirst { it.contains("stage=endpoint_decision") }
        assertTrue("both stages must be recorded", acoustic >= 0 && endpoint >= 0)
        assertTrue(
            "the endpoint decision must follow the acoustic end",
            endpoint > acoustic,
        )
    }

    @Test
    fun `a stage that never happened is absent, not zero`() {
        // A committed turn never cancels, and a turn that was gated never executes a local
        // action. Writing either as 0 would read as "instantaneous", which is wrong.
        val record = TurnTimingRecord(requestId = "turn-2")
        record.mark(TurnTimingRecord.Stage.AcousticEnd, atNanos = 1_000 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.EndpointDecision, atNanos = 1_180 * 1_000_000L)
        record.markDisposition(atNanos = 1_400 * 1_000_000L, cancelled = false)

        val text = record.render()
        assertTrue("the measured stages are present", text.contains("stage=acoustic_end"))
        // A commit is reported as a commit, never under the pair's ambiguous name.
        assertTrue("the disposition is named for what happened", text.contains("stage=committed"))
        assertTrue("an unmeasured stage must not appear", !text.contains("stage=local_action_executed"))
        assertTrue("nor should a cancel line be invented", !text.contains("cancel"))
        assertNull("no elapsed time is reported for a stage that did not run", record.elapsedMs(TurnTimingRecord.Stage.LocalActionExecuted))
    }

    @Test
    fun `a failed action is not reported as executed`() {
        // The plan lists "local action executed". An action that errored did not execute, and a
        // table that calls it executed turns a failure into a latency win.
        val record = TurnTimingRecord(requestId = "turn-3")
        record.mark(TurnTimingRecord.Stage.AcousticEnd, atNanos = 1_000 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.RoutingDone, atNanos = 1_500 * 1_000_000L)
        record.markOutcome(TurnTimingRecord.Outcome.ActionFailed)

        val text = record.render()
        assertTrue("the failure is recorded as a failure", text.contains("outcome=action_failed"))
        assertTrue(
            "a failed action must not be reported as executed",
            !text.contains("stage=local_action_executed"),
        )
    }

    @Test
    fun `scheduled audio is not reported as audible`() {
        // Rendering was submitted, not heard. Only the first audible stage is audible, and the
        // transport/queue delay is precisely what this table exists to expose.
        val record = TurnTimingRecord(requestId = "turn-4")
        record.mark(TurnTimingRecord.Stage.AcousticEnd, atNanos = 1_000 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.LocalActionExecuted, atNanos = 1_200 * 1_000_000L)
        record.markOutcome(TurnTimingRecord.Outcome.ActionExecuted)

        val text = record.render()
        assertTrue(text.contains("stage=local_action_executed"))
        assertTrue(
            "no audible stage was measured, so none may be reported",
            !text.contains("stage=first_audible"),
        )
    }

    @Test
    fun `elapsed time is measured from the acoustic end, on one clock`() {
        // One clock for every stage, or the differences are meaningless. The acoustic end is the
        // zero because it is the instant the user stopped speaking and every later cost is ours.
        val record = complete()
        // Elapsed is measured FROM the acoustic end, so it is the difference, not the instant.
        assertEquals(360L, record.elapsedMs(TurnTimingRecord.Stage.AsrDone))
        assertEquals(1_160L, record.elapsedMs(TurnTimingRecord.Stage.FirstAudible))
        // ASR cost on its own, so the table can be compared to the KPIs per stage.
        assertEquals(150L, record.stageMs(TurnTimingRecord.Stage.AsrDone))
    }

    @Test
    fun `the record carries the turn's identity so lines can be joined`() {
        val record = complete()
        assertTrue(
            "every line must be attributable to one turn",
            record.lines().all { it.contains("request_id=turn-1") },
        )
    }

    @Test
    fun `a re-marked stage keeps its first instant`() {
        // A stage's arrival is the first time it was observed. Re-marking it later would move the
        // measurement to whenever the caller happened to notice.
        val record = TurnTimingRecord(requestId = "turn-5")
        record.mark(TurnTimingRecord.Stage.AcousticEnd, atNanos = 1_000 * 1_000_000L)
        record.mark(TurnTimingRecord.Stage.AcousticEnd, atNanos = 1_400 * 1_000_000L)
        // The acoustic end is the table's zero point, so it is checked via a later stage.
        record.mark(TurnTimingRecord.Stage.EndpointDecision, atNanos = 1_500 * 1_000_000L)
        assertEquals(500L, record.elapsedMs(TurnTimingRecord.Stage.EndpointDecision))
    }

    @Test
    fun `every mark flows through the ONE monotonic clock conversion`() {
        // REVIEWER (task81): mixed clock sources (a caller on elapsedRealtime here, nanoTime
        // there) make stage deltas meaningless after a boot-time adjustment. The record owns the
        // clock: callers take instants from TurnTimingRecord.clockNanos, and the ONLY
        // nanos-to-ms conversion is inside the record.
        TurnTimingRecord.clockNanos = { 1_000_000_000L }
        try {
            val record = TurnTimingRecord(requestId = "turn-clock")
            TurnTimingRecord.registerFor("turn-clock", record)
            record.mark(TurnTimingRecord.Stage.AcousticEnd, atNanos = TurnTimingRecord.clockNanos())
            TurnTimingRecord.clockNanos = { 2_500_000_000L }
            TurnTimingRecord.markFor(
                "turn-clock",
                TurnTimingRecord.Stage.FirstAudible,
                TurnTimingRecord.clockNanos(),
            )
            assertEquals(1_500L, record.elapsedMs(TurnTimingRecord.Stage.FirstAudible))
            assertEquals(1_500L, record.stageMs(TurnTimingRecord.Stage.FirstAudible))
        } finally {
            TurnTimingRecord.clockNanos = { System.nanoTime() }
        }
    }
}
