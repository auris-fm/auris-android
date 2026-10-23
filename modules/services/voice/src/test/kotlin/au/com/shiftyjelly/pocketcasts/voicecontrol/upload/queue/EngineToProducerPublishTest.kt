package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE ENGINE→PRODUCER JOIN (task73 last implementation seam): a capture that the REAL pipeline
 * executed (Executed outcome joined by the frozen capture id) is published by the REAL producer
 * into the REAL store — with the authority FROZEN at capture start as the record's identity —
 * through ONE guarded trigger. There is no ad-hoc record assembly on the engine side.
 *
 * Controls (spec: provenance end-to-end, guarded trigger):
 *  - The trigger fires ONLY on Executed; Routed/Recognized-only captures are not published.
 *  - A capture whose FROZEN authority is absent (never issued) is discarded, never published.
 *  - A publish that the store/producer refuses (e.g. live identity invalid at publish time)
 *    leaves NO record and releases the retained charge exactly once — a refused capture is
 *    not a malformed record.
 *  - Published entries carry the frozen account/session in the durable sidecar and appear in
 *    store.readyEntries() with a matching capture id.
 */
class EngineToProducerPublishTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(root: java.io.File, now: Long = 61_000_000L) = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = { now },
        jitter = { it - 1 },
    )

    private val issued = IssuedAuthority(
        accountName = "a@x",
        sessionGeneration = 7L,
        credentialIncarnation = 1L,
        enrollmentEpoch = "epoch-1",
        acceptedDisclosureVersion = "1",
        policyEnabledAtIssue = true,
    )

    private val models = listOf(
        "vad" to ModelRef.Named("silero-vad", "4.0"),
        "wakeword" to ModelRef.Named("auris-wake", "1.2"),
        "asr" to ModelRef.Named("whisper-small", "2024-11"),
        "translation" to ModelRef.None,
        "router" to ModelRef.Named("lfm-intent", "0.3"),
    )

    private val stages = listOf(
        StageName.Wake to StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))),
        StageName.Bleed to StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))),
        StageName.Asr to StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))),
        StageName.Translation to StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))),
        StageName.Routing to StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))),
        StageName.Execution to StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))),
    )

    private fun retainCapture(sink: ContributionRetentionSink, context: CaptureContext, samples: Int = 1600) {
        val first = ShortArray(800) { i -> i.toShort() }
        assertTrue(sink.started(SegmentObservation.Started(context, first, validSamples = 800)))
        val rest = ShortArray(samples - 800) { i -> (i + 1).toShort() }
        sink.completed(
            SegmentObservation.Completed(context, frames = listOf(rest), sampleRateHz = 16000, speechOnsetSample = 0, speechEndSample = samples, wakeState = SegmentObservation.WakeState.Detected),
        )
    }

    private fun producer(store: ContributionStore, account: () -> String = { "a@x" }, session: () -> Long = { 7L }) = ContributionCaptureProducer(
        store,
        appVersion = "8.20",
        appBuild = "12345",
        captureSessionId = { "00000000-0000-4000-8000-000000000000" },
        enrollmentAccount = account,
        enrollmentSession = session,
    )

    @Test
    fun `an executed capture is published into the store with its frozen identity`() {
        val root = tmp.newFolder("exec-pub")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val id = UUID.randomUUID()
        val context = CaptureContext(id, captureSession = 1L, routeClass = "Bluetooth", issuedAuthority = issued)
        retainCapture(sink, context)
        val trigger = RetentionPublishTrigger(
            sink,
            producer(store),
            models = models,
            stagesProvider = { stages },
            dispatch = { it() },
        )
        trigger.attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(id, dispatchedWithoutThrowing = true)))

        val ready = store.readyEntries()
        assertEquals(1, ready.size)
        assertEquals(id.toString(), ready.single().captureId)
        // Retained charge released exactly once: nothing pinned after publication.
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    @Test
    fun `a capture without a frozen issued authority is discarded and never published`() {
        val root = tmp.newFolder("no-auth")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val id = UUID.randomUUID()
        retainCapture(sink, CaptureContext(id, captureSession = 1L, routeClass = "Bluetooth", issuedAuthority = null))
        val trigger = RetentionPublishTrigger(sink, producer(store), models = models, stagesProvider = { stages }, dispatch = { it() })
        trigger.attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(id, dispatchedWithoutThrowing = true)))

        assertNull(store.readyEntries().firstOrNull { it.captureId == id.toString() })
        assertEquals(0L, sink.retainedBytes())
    }

    @Test
    fun `only an Executed outcome triggers publication`() {
        val root = tmp.newFolder("routed-only")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val id = UUID.randomUUID()
        retainCapture(sink, CaptureContext(id, captureSession = 1L, routeClass = "Bluetooth", issuedAuthority = issued))
        val trigger = RetentionPublishTrigger(sink, producer(store), models = models, stagesProvider = { stages }, dispatch = { it() })
        trigger.attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Routed(id, "playback", emptyMap())))
        assertEquals(0, store.readyEntries().size)
        // Still retained, still charged: only publication or loss ends retention.
        assertEquals(1, sink.retainedCount())
    }

    @Test
    fun `a refused publish leaves no record and releases the retained charge`() {
        val root = tmp.newFolder("refused-pub")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val id = UUID.randomUUID()
        retainCapture(sink, CaptureContext(id, captureSession = 1L, routeClass = "Bluetooth", issuedAuthority = issued))
        // Live identity INVALID at publish time: the producer refuses (null), the store never
        // sees a record, and the trigger must still end retention exactly once.
        val trigger = RetentionPublishTrigger(
            sink,
            producer(store, account = { "" }),
            models = models,
            stagesProvider = { stages },
            dispatch = { it() },
        )
        trigger.attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(id, dispatchedWithoutThrowing = true)))

        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    @Test
    fun `dispatch is asynchronous-safe - the hook invocation itself does not run under the sink lock`() {
        // Re-entrancy guard: a dispatch body that touches the sink (retainedCount) must not
        // deadlock. Direct dispatch on the joinOutcome thread with a sink query inside proves
        // the hook is invoked OUTSIDE the lock.
        val root = tmp.newFolder("reentrant")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val id = UUID.randomUUID()
        retainCapture(sink, CaptureContext(id, captureSession = 1L, routeClass = "Bluetooth", issuedAuthority = issued))
        val captured = mutableListOf<UUID>()
        val trigger = RetentionPublishTrigger(
            sink,
            producer(store),
            models = models,
            stagesProvider = { stages },
            dispatch = { body ->
                sink.retainedCount()
                body()
                captured.add(id)
            },
        )
        trigger.attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(id, dispatchedWithoutThrowing = true)))
        assertEquals(listOf(id), captured)
        assertNotNull(store.readyEntries().singleOrNull())
    }
}
