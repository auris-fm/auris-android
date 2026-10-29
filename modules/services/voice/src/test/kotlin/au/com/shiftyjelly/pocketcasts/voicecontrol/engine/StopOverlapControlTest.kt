package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.PrepaidTestAllowance
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * SERVICE-STOP OVERLAP (the last open item): stopping the engine while an invocation is still open
 * must finalize it exactly once, and a late report or settle for that invocation must not resurrect
 * it or publish a second time.
 *
 * EVIDENCE SCOPE (corrected after reviewer f175ac72): this exercises the REAL engine's
 * stop/report/settle consumption concurrently, but the invocation is opened through the test seam
 * [au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine.openInvocationForTest] and the
 * sink overrides retention publication to count observations. It closes the narrow exactly-once
 * engine boundary; it does NOT drive the actual Service stop/restart lifecycle, and it does not
 * prove retained state is persisted across a restart.
 */
class StopOverlapControlTest {

    @Test
    fun `a stop while an invocation is open settles it once, and a late report cannot resurrect it`() {
        val fixture = Harness.new()
        val token = fixture.engine.openInvocationForTest(fixture.captureId, id = 41L)
        fixture.engine.reportExecutionResult(token, ExecutionOutcome.Success)

        fixture.engine.stop()
        // The stop finalized the invocation: the agreed fact was published exactly once.
        assertEquals(listOf(ExecutionOutcome.Success), fixture.applied.toList())

        // A report that races in AFTER the stop must find nothing to attach to...
        assertFalse("a post-stop report must not attach", fixture.engine.reportExecutionResult(token, ExecutionOutcome.Failure))
        // ...and a post-stop settle must not publish a second time.
        assertFalse("a post-stop settle must not publish again", fixture.engine.settleInvocation(token))
        assertEquals("exactly one publication", 1, fixture.applied.size)
    }

    @Test
    fun `a stop racing concurrent reports publishes exactly once`() {
        val fixture = Harness.new()
        val token = fixture.engine.openInvocationForTest(fixture.captureId, id = 42L)
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val done = CountDownLatch(6)
        // A fact is already recorded, so whichever side consumes the invocation (the stop's
        // abandonment or a racing settle) publishes THE AGREED FACT. Whether a racing consume wins
        // before or after the stop is exactly the interleaving under test.
        fixture.engine.reportExecutionResult(token, ExecutionOutcome.Success)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<String>())
        pool.execute {
            start.await()
            outcomes += "report:" + fixture.engine.reportExecutionResult(token, ExecutionOutcome.Success)
            done.countDown()
        }
        pool.execute {
            start.await()
            fixture.engine.stop()
            outcomes += "stop"
            done.countDown()
        }
        repeat(2) {
            pool.execute {
                start.await()
                outcomes += "settle:" + fixture.engine.settleInvocation(token)
                done.countDown()
            }
        }
        repeat(2) {
            pool.execute {
                start.await()
                outcomes += "report:" + fixture.engine.reportExecutionResult(token, ExecutionOutcome.Success)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        // ADDITIVE facts (Success reported once by three racers) aggregate to the agreed fact, so
        // the publication is exactly one whichever side (stop or a racing settle) consumes it. A
        // settle that arrives before the reports would publish the unknown — that is the OTHER
        // legal outcome, so the property asserted here is "exactly one publication, never a second".
        // DEBUG: report what each racer actually did
        val diag = "applied=" + fixture.applied + " outcomes=" + outcomes
        assertTrue("exactly one publication under a stop race, was " + fixture.applied.size + " | " + diag, fixture.applied.size == 1)
    }

    private class Fixture(
        val engine: VoiceAsrEngine,
        val captureId: UUID,
        val applied: MutableList<ExecutionOutcome>,
        val retention: ContributionRetentionSink,
    ) {
        fun diag(token: VoiceAsrEngine.InvocationToken): String {
            fun field(n: String): Any? = VoiceAsrEngine::class.java.getDeclaredField(n).also { it.isAccessible = true }.get(engine)
            val retentionSinkRef = field("retentionSink")

            @Suppress("UNCHECKED_CAST")
            val open = field("openInvocations") as java.util.concurrent.ConcurrentHashMap<Long, Any>
            val record = open[token.id]
            val obs = record?.let {
                val f = it.javaClass.getMethod("getObservations")
                f.invoke(it)
            }
            return "capture=" + captureId + " token=" + token + " open=" + open.keys + " obs=" + obs + " applied=" + applied + " retentionSinkIsSame=" + (retentionSinkRef === retention)
        }

        fun openPositions(): Int {
            val f = VoiceAsrEngine::class.java.getDeclaredField("openInvocations")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return (f.get(engine) as java.util.concurrent.ConcurrentHashMap<Long, Any>).size
        }
    }

    private object Harness {
        @Volatile
        var retentionRef: ContributionRetentionSink? = null

        fun new(): Fixture {
            val applied = java.util.Collections.synchronizedList(mutableListOf<ExecutionOutcome>())
            val counter = AtomicInteger(0)
            val authority: () -> AuthorityDecision = { AuthorityDecision(false, true) }
            val retention = CountingSink(CollectionBudget(1L shl 16), applied)
            retentionRef = retention
            val captureId = UUID.randomUUID()
            val engine = VoiceAsrEngine(
                voiceAudioProcessor = mock(),
                utteranceFilter = mock(),
                intentRecognizer = mock(),
                wakeWordDetector = mock(),
                gracePeriodSignal = mock(),
                audioFeedbackRenderer = mock(),
                translationStage = mock(),
                collectionAuthority = authority,
                retentionSink = retention,
                segmentObserver = SegmentObserver(
                    CollectionGate { false },
                    retention,
                    authority,
                    issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "e", "d") },
                    handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live ->
                        scope.completeUnderOwner(context, onset, end, wake, provider)
                    },
                    allowance = PrepaidTestAllowance(),
                ),
                context = mock(),
            )
            counter.incrementAndGet()
            return Fixture(engine, captureId, applied, retention)
        }
    }

    private class CountingSink(
        budget: CollectionBudget,
        private val applied: MutableList<ExecutionOutcome>,
    ) : ContributionRetentionSink(budget) {
        // This control counts PUBLICATIONS, not retention bookkeeping: finalization must not
        // depend on a candidate being retained, so the observation is counted directly.
        override fun noteExecutionOutcome(captureId: UUID, outcome: ExecutionOutcome): Boolean {
            applied += outcome
            return true
        }
    }
}
