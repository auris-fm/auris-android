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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * Controls (reviewer 54635caa + spec): full frozen-tuple vs coherent-live comparison (logout,
 * A→B account move, session-only move, epoch rotation each refuse record-less); stage provenance
 * read from the JOIN HISTORY (Recognized→Routed→Executed records the real stages); exactly-once
 * retention termination including cancel-before-start via Job binding; outside-lock execution
 * proven across threads; runtime attachment through the UploadTrigger.start consumer.
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

    private val asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin")

    private val fixedModels = listOf(
        "vad" to ModelRef.Named("silero-vad", "4.0"),
        "wakeword" to ModelRef.Named("auris-wake", "1.2"),
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

    private fun context(id: UUID = UUID.randomUUID(), authority: IssuedAuthority? = issued) = CaptureContext(
        id,
        captureSession = 1L,
        routeClass = "Bluetooth",
        issuedAuthority = authority,
        asrModel = asrModel,
        translationModel = ModelRef.None,
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

    /** Direct synchronous dispatch (tests); production dispatches on the upload worker scope. */
    private fun directDispatch(): (() -> Unit) -> Job? {
        var body: (() -> Unit)? = null
        return { b ->
            body = b
            b()
            null
        }
    }

    private fun trigger(
        sink: ContributionRetentionSink,
        store: ContributionStore,
        live: () -> RetentionPublishTrigger.LiveIdentity? = { RetentionPublishTrigger.LiveIdentity("a@x", 7L, "epoch-1") },
        dispatch: (() -> Unit) -> Job? = directDispatch(),
        producerAccount: () -> String = { "a@x" },
        producerSession: () -> Long = { 7L },
    ) = RetentionPublishTrigger(
        sink,
        producer(store, account = producerAccount, session = producerSession),
        fixedModels = fixedModels,
        stagesProvider = { stages },
        coherentLiveIdentity = live,
        dispatch = dispatch,
    )

    @Test
    fun `an executed capture is published into the store with its frozen identity`() {
        val root = tmp.newFolder("exec-pub")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        val ready = store.readyEntries()
        assertEquals(1, ready.size)
        assertEquals(ctx.captureId.toString(), ready.single().captureId)
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    @Test
    fun `a capture without a frozen issued authority is discarded and never published`() {
        val root = tmp.newFolder("no-auth")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context(authority = null)
        retainCapture(sink, ctx)
        trigger(sink, store).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        assertNull(store.readyEntries().firstOrNull { it.captureId == ctx.captureId.toString() })
        assertEquals(0L, sink.retainedBytes())
    }

    @Test
    fun `only an Executed outcome triggers publication`() {
        val root = tmp.newFolder("routed-only")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Routed(ctx.captureId, "playback", emptyMap())))
        assertEquals(0, store.readyEntries().size)
        assertEquals(1, sink.retainedCount())
    }

    @Test
    fun `a refused publish leaves no record and releases the retained charge`() {
        val root = tmp.newFolder("refused-pub")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store, producerAccount = { "" }).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    // ---- FULL FROZEN-TUPLE vs COHERENT LIVE identity (reviewer 54635caa) ----

    @Test
    fun `an A-to-B account move after capture refuses the record record-less`() {
        val root = tmp.newFolder("ab-move")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store, live = { RetentionPublishTrigger.LiveIdentity("b@y", 7L, "epoch-1") }).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    @Test
    fun `a session-only move after capture refuses the record record-less`() {
        val root = tmp.newFolder("session-move")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store, live = { RetentionPublishTrigger.LiveIdentity("a@x", 9L, "epoch-1") }).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
    }

    @Test
    fun `an epoch rotation after capture refuses the record record-less`() {
        val root = tmp.newFolder("epoch-move")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store, live = { RetentionPublishTrigger.LiveIdentity("a@x", 7L, "epoch-2") }).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
    }

    @Test
    fun `a capture with no observed asr model is not publishable`() {
        val root = tmp.newFolder("no-model")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = CaptureContext(
            UUID.randomUUID(),
            captureSession = 1L,
            routeClass = "Bluetooth",
            issuedAuthority = issued,
            asrModel = ModelRef.None,
        )
        retainCapture(sink, ctx)
        trigger(sink, store).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
    }

    // ---- EXACTLY-ONCE termination under dispatch failure/cancellation ----

    @Test
    fun `a synchronously rejected dispatch releases the retained charge and surfaces the failure`() {
        val root = tmp.newFolder("rejected-dispatch")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store, dispatch = { _ -> throw java.util.concurrent.RejectedExecutionException("scope closed") }).attach()

        var thrown: Throwable? = null
        try {
            sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true))
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("dispatch rejection must surface to the joiner", thrown is java.util.concurrent.RejectedExecutionException)
        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    @Test
    fun `cancel-before-start via a cancelled scope releases the charge exactly once`() {
        // scope.launch on an ALREADY-cancelled scope returns a cancelled Job without running
        // the body: the trigger's Job binding must release immediately (reviewer 54635caa).
        val root = tmp.newFolder("cancel-before-start")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val cancelledScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        cancelledScope.cancel()
        trigger(sink, store, dispatch = { body -> cancelledScope.launch { body() } }).attach()

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        assertEquals(0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    @Test
    fun `cancel during the body releases the charge via the job binding`() {
        val root = tmp.newFolder("cancel-during")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val jobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        trigger(
            sink,
            store,
            dispatch = { body ->
                scope.launch {
                    gate.await()
                    body()
                }.also { jobs.add(it) }
            },
        ).attach()
        try {
            assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))
            jobs.single().cancel()
            gate.complete(Unit)
            val deadline = System.currentTimeMillis() + 5000
            while (sink.retainedCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(0, store.readyEntries().size)
            assertEquals(0L, sink.retainedBytes())
            assertEquals(0, sink.retainedCount())
        } finally {
            scope.cancel()
        }
    }

    // ---- OUTSIDE-LOCK execution, proven across threads ----

    @Test
    fun `the hook runs outside the sink lock - proven across threads with a barrier`() {
        // JVM monitors are REENTRANT: a same-thread retainedCount() inside the dispatch body
        // cannot distinguish "under the lock" from "outside". The body runs on a SECOND thread
        // and waits for latch A; the main thread counts latch A only after joinOutcome RETURNED
        // and queries the sink while the body is blocked. Under the lock this times out.
        val root = tmp.newFolder("cross-thread")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val mainPastJoin = java.util.concurrent.CountDownLatch(1)
        val bodySawMainProgress = java.util.concurrent.CountDownLatch(1)
        val futures = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.Future<*>>()
        val sinkRef = sink
        val storeRef = store
        val ctxRef = ctx
        val trig = RetentionPublishTrigger(
            sinkRef,
            producer(storeRef),
            fixedModels = fixedModels,
            stagesProvider = { stages },
            coherentLiveIdentity = { RetentionPublishTrigger.LiveIdentity("a@x", 7L, "epoch-1") },
            dispatch = { body ->
                futures.add(
                    java.util.concurrent.Executors.newSingleThreadExecutor().submit {
                        assertTrue(mainPastJoin.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        body()
                        bodySawMainProgress.countDown()
                    },
                )
                null
            },
        )
        trig.attach()
        // keep references obviously used
        check(ctxRef.captureId != UUID(0, 0))

        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))
        assertEquals(1, sinkRef.retainedCount())
        mainPastJoin.countDown()
        assertTrue(bodySawMainProgress.await(5, java.util.concurrent.TimeUnit.SECONDS))
        futures.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertEquals(1, storeRef.readyEntries().size)
    }

    // ---- STAGE PROVENANCE from the JOIN HISTORY (reviewer 54635caa) ----

    @Test
    fun `recognized then routed then executed records the real stages - not overwritten by the terminal join`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val candidate = sink.retainedCandidateForTest(ctx.captureId)!!

        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(ctx.captureId, "skip this ad", "en")))
        assertTrue(sink.joinOutcome(CollectionOutcome.Routed(ctx.captureId, "playback", emptyMap())))
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        val byName = EngineStageProvenance.stagesFor(candidate).toMap()
        assertEquals(StageState.Completed, byName.getValue(StageName.Wake).state)
        assertEquals(StageState.Unknown, byName.getValue(StageName.Bleed).state)
        // REAL asr truth from the history Recognized join:
        val asr = byName.getValue(StageName.Asr)
        assertEquals(StageState.Completed, asr.state)
        assertEquals("skip this ad", (asr.fields.getValue("text") as RecordNode.Str).value)
        // Translation without an observed on-device translation result:
        assertEquals(StageState.NotRun, byName.getValue(StageName.Translation).state)
        // REAL routing truth from the history Routed join (NOT overwritten to not_run):
        val routing = byName.getValue(StageName.Routing)
        assertEquals(StageState.Completed, routing.state)
        assertEquals("playback", (routing.fields.getValue("predicted_intent") as RecordNode.Str).value)
        // THE UNVERIFIED RULE: a dispatch observation never becomes completed execution.
        assertEquals(StageState.Unknown, byName.getValue(StageName.Execution).state)
    }

    // ---- RUNTIME ATTACHMENT via the actual startup consumer ----

    @Test
    fun `uploadTrigger start attaches the trigger - a second start is idempotent`() {
        val root = tmp.newFolder("attachment")
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val trig = trigger(sink, newStore(root))
        val upload = UploadTrigger(
            assembly = org.mockito.kotlin.mock(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            intervalMs = 3_600_000L,
            publishTrigger = trig,
        )
        assertNull(sink.onReadyForPublication)
        upload.start()
        assertNotNull("runtime attachment must be established by the startup consumer", sink.onReadyForPublication)
        upload.start()
    }
}
