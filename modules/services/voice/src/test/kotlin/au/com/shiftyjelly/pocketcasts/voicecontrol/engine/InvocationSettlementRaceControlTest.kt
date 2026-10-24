package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE-LOCK VALIDATE-AND-CONSUME (reviewer 9d5ee1ca/c15bf037): settlement must compare the FULL
 * token and consume the record in the SAME lock interval. The previous remove-then-restore shape
 * opened a window where a concurrent report could attach to a record that had already been
 * removed (fact lost) or where restoring a mismatched record resurrected a stopped invocation.
 *
 * These controls drive real threads against a real [VoiceAsrEngine], asserting the two failure
 * modes the two-step window allowed: (a) exactly one settle WINS and no loser may publish twice,
 * (b) a mismatched-token settle must leave the legitimate invocation open and its facts intact.
 */
class InvocationSettlementRaceControlTest {

    @Test
    fun `a mismatched token never consumes or resurrects the legitimate invocation`() {
        val fixture = EngineInvocations.forRace(ExecutionOutcome.Success)
        val legit = fixture.token
        val impostor = VoiceAsrEngine.InvocationToken(id = legit.id, captureId = UUID.randomUUID())

        assertFalse("a differing captureId must NOT consume", fixture.engine.settleInvocation(impostor))
        // The legitimate invocation is untouched: its recorded fact still settles.
        assertTrue("the legitimate invocation must still be settleable", fixture.engine.settleInvocation(legit))
        assertEquals(listOf(ExecutionOutcome.Success), fixture.reports.settled)
        // And a second settle of the same token cannot publish again.
        assertFalse(fixture.engine.settleInvocation(legit))
        assertEquals(1, fixture.reports.settled.size)
    }

    @Test
    fun `concurrent settles publish exactly once under contention`() {
        val fixture = EngineInvocations.forRace(ExecutionOutcome.Success)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                if (fixture.engine.settleInvocation(fixture.token)) winners.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("all racers must complete", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        // EXACTLY ONE publication for one invocation — the property the two-step window broke.
        assertEquals("exactly one settle may win", 1, winners.get())
        assertEquals(1, fixture.reports.settled.size)
    }

    private class FakeReports {
        val settled = java.util.Collections.synchronizedList(mutableListOf<ExecutionOutcome>())
    }

    private class Fixture(
        val engine: VoiceAsrEngine,
        val token: VoiceAsrEngine.InvocationToken,
        val reports: FakeReports,
        val sink: CountingRetentionSink,
    )

    // Counts the engine's APPLIED outcome on the REAL retention sink. The engine only records an
    // outcome for a capture that is actually retained, so this fixture retains one first — the
    // counter then observes the real publication, not a mock's callback list.
    private class CountingRetentionSink(
        budget: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget,
        private val reports: FakeReports,
    ) : au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink(budget) {
        override fun noteExecutionOutcome(captureId: UUID, outcome: ExecutionOutcome): Boolean {
            val applied = super.noteExecutionOutcome(captureId, outcome)
            if (applied) reports.settled += outcome
            return applied
        }
    }

    private object EngineInvocations {
        fun forRace(outcome: ExecutionOutcome): Fixture {
            val reports = FakeReports()
            val engine = raceEngine(reports)
            val captureId = UUID.randomUUID()
            sink.retainCandidateForTest(captureId)
            val token = engine.openInvocationForTest(captureId, id = 7L)
            engine.reportExecutionResult(token, outcome)
            return Fixture(engine, token, reports, sink)
        }

        /**
         * A REAL engine over real (in-memory) collaborator stand-ins: the race lives entirely in
         * the engine's invocation bookkeeping, which is what this control exercises.
         */
        private lateinit var sink: CountingRetentionSink

        private fun raceEngine(reports: FakeReports): VoiceAsrEngine {
            val authority: () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision = {
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, true)
            }
            sink = CountingRetentionSink(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget(1L shl 16),
                reports,
            )
            val observer = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate { false },
                sink,
                authority,
                issueAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority("a@b.c", 1L, 1L, "e", "d") },
                handoff = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff { context, onset, end, wake, provider, scope, live ->
                    scope.completeUnderOwner(context, onset, end, wake, provider)
                },
            )
            return VoiceAsrEngine(
                voiceAudioProcessor = org.mockito.kotlin.mock(),
                utteranceFilter = org.mockito.kotlin.mock(),
                intentRecognizer = org.mockito.kotlin.mock(),
                wakeWordDetector = org.mockito.kotlin.mock(),
                gracePeriodSignal = org.mockito.kotlin.mock(),
                audioFeedbackRenderer = org.mockito.kotlin.mock(),
                translationStage = org.mockito.kotlin.mock(),
                collectionAuthority = authority,
                retentionSink = sink,
                segmentObserver = observer,
                context = org.mockito.kotlin.mock(),
            )
        }
    }
}
