package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.RetainedCandidate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordValidator
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
    @get:org.junit.Rule
    val sourceSlots = ProcessWideSourceSlotsRule()

    /** REAL owner-transaction semantics: one lock, snapshot reads inside it (see WiringTest). */
    private class WatchOwner(startAccount: String, startSession: Long) : au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner {
        override val collectionSharedLock = Any()

        @Volatile
        var snap = au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot(
            startAccount,
            startSession,
            startSession,
            au.com.shiftyjelly.pocketcasts.preferences.AccessToken("Bearer-A"),
        )

        override fun authoritySnapshot() = snap
        override fun sessionGeneration(): Long = snap.session
        override fun <T> transaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }

        /** ONE coherent live read for the shared binding: account+session from the snapshot,
         *  enrollment epoch standing in for the projection (fixed in this fixture). */
        fun liveIdentityForTest(): RetentionPublishTrigger.LiveIdentity? = snap.accountName?.let { RetentionPublishTrigger.LiveIdentity(it, snap.session, "epoch-1") }
        override fun installOwnershipBinding(binding: au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding?) = snap
        override fun beginRefresh(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot) = null
        override fun publishRefresh(
            lease: au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease,
            refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken,
            accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken,
        ) = false
        override fun publishCredentials(
            observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot,
            refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken,
            accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken,
        ) = false
        override fun invalidateIfCurrent(
            observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot,
            transaction: () -> Boolean,
        ) = transaction()
        override fun reconcileExternalState() = snap

        fun switchTo(account: String, session: Long) = synchronized(collectionSharedLock) {
            snap = au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot(
                account,
                session,
                session,
                au.com.shiftyjelly.pocketcasts.preferences.AccessToken("Bearer-B"),
            )
        }
    }

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
        appVersion = { "8.20" },
        appBuild = { "12345" },
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
        /** Override the engine's stage provenance (default is the fixed fixture list). */
        stagesForTest: ((RetainedCandidate) -> List<Pair<StageName, StageEntry>>)? = null,
    ) = RetentionPublishTrigger(
        sink,
        producer(store, account = producerAccount, session = producerSession),
        fixedModels = fixedModels,
        stagesProvider = stagesForTest ?: { stages },
        publishUnderOwner = { frozen, commit ->
            val now = live()
            // ATOMIC: validate the frozen tuple and commit in ONE section; the barrier below
            // can change identity BETWEEN trigger entry and this section.
            if (now != null && now.account == frozen.account && now.session == frozen.session && now.enrollmentEpoch == frozen.enrollmentEpoch) commit() else false
        },
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
            publishUnderOwner = { frozen, commit ->
                val now = RetentionPublishTrigger.LiveIdentity("a@x", 7L, "epoch-1")
                if (now.account == frozen.account && now.session == frozen.session && now.enrollmentEpoch == frozen.enrollmentEpoch) commit() else false
            },
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

    // PROVENANCE WIRING (spec audit): production DOES observe bleed rejections —
    // VoiceAsrEngine.transcribeSegment joins CollectionOutcome.NotCollected(captureId, "bleed")
    // when UtteranceFilter's correlator rejects the segment — but the provenance mapping ignored
    // the join and persisted bleed as unknown/observation_unavailable, laundering a KNOWN
    // production fact into an unavailable observation. The mapping must carry the observed
    // rejection into the owning schema: bleed completed with an overlap decision.
    @Test
    fun `a bleed-rejected capture is persisted as bleed completed with an overlap decision`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        val first = ShortArray(800) { i -> i.toShort() }
        assertTrue(sink.started(SegmentObservation.Started(ctx, first, validSamples = 800)))
        sink.completed(
            SegmentObservation.Completed(
                ctx,
                frames = listOf(ShortArray(800) { i -> (i + 1).toShort() }),
                sampleRateHz = 16000,
                speechOnsetSample = 0,
                speechEndSample = 1600,
                wakeState = SegmentObservation.WakeState.Detected,
            ),
        )
        // THE PRODUCTION JOIN, verbatim from VoiceAsrEngine.transcribeSegment's bleed branch.
        assertTrue(sink.joinOutcome(CollectionOutcome.NotCollected(ctx.captureId, "bleed")))

        val candidate = sink.retainedCandidateForTest(ctx.captureId)!!
        val stages = EngineStageProvenance.stagesFor(candidate)
        val bleed = stages.toMap().getValue(StageName.Bleed)
        assertEquals("an observed bleed rejection is a COMPLETED check, not an unavailable one", StageState.Completed, bleed.state)
        assertEquals("overlap", (bleed.fields.getValue("decision") as RecordNode.Str).value)

        // The produced record must carry the same pair into the owning schema.
        val root = tmp.newFolder("bleed-rejected")
        val store = newStore(root)
        val stored = producer(store).publish(
            ctx.captureId.toString(),
            "epoch-1",
            16000,
            listOf(first to 800),
            listOf(
                "vad" to ModelRef.None,
                "wakeword" to ModelRef.None,
                "asr" to ModelRef.None,
                "translation" to ModelRef.None,
                "router" to ModelRef.None,
            ),
            stages,
        )
        assertTrue("the producer must publish a record for a bleed-rejected capture", stored != null)
        val decoded = checkNotNull(store.durableCapture(ctx.captureId.toString()))
        val decodedRecord = when (val v = RecordValidator.validate(decoded.record)) {
            is RecordValidator.Result.Ok -> v.record
            else -> error("the persisted record must decode, got $v")
        }
        val decodedBleed = decodedRecord.observations.getValue(StageName.Bleed)
        assertEquals("the PERSISTED bleed state must be a completed check", StageState.Completed, decodedBleed.state)
        assertEquals("overlap", (decodedBleed.fields.getValue("decision") as RecordNode.Str).value)
    }

    /**
     * A THROWN DETECTOR REACHES THE PRODUCED RECORD AS A KNOWN FAILURE (reviewer 0ee062be, spec 15:46Z).
     *
     * The mapping control alone would only prove an in-memory decision. This drives the path — an
     * INJECTED engine observation with WakeState.DetectorFailed, join, engine provenance, real
     * producer — and then RE-DECODES the persisted record.
     *
     * SCOPE, stated so nobody reads more into it: this control injects the engine OBSERVATION. It does
     * not exercise the detector's own throw site, i.e. it does not prove the real detector actually
     * emits DetectorFailed when speech detection fails. It proves that the state, once observed,
     * survives into the record a recovery would read.
     *
     * The assertion reads the DECODED record, never the in-memory candidate (reviewer b5ed5d2c):
     * recomputing provenance from the candidate would re-read the same input the mapping came from, so
     * a producer that persisted `wake=unknown` would still pass. Verified by mutation — making the
     * producer persist the wrong pair fails this assertion with
     * "the PERSISTED wake state must be a known failure" (receipt: detector-error-mutant.log).
     */
    // THE ACTION-RESULT CHANNEL (spec audit): production reports what the EXECUTOR returned, not
    // an inference. This control drives the real sink/trigger/producer with a reported outcome and
    // asserts the DECODED record. It does not exercise the service's response mapping (that is a
    // separate source-level surface) — it proves the reported value reaches the owning schema.
    @Test
    fun `a reported execution success is persisted as execution completed success`() {
        val root = tmp.newFolder("exec-success")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)

        // The executor's own report, delivered through the sink's ownership rule.
        assertTrue(sink.noteExecutionOutcome(ctx.captureId, ExecutionOutcome.Success))
        // The ENGINE's provenance, not the shared stage fixture: the fixture is a fixed list that
        // bypasses stagesFor entirely, which is what let the execution branch go unexercised.
        trigger(sink, store, stagesForTest = { candidate -> EngineStageProvenance.stagesFor(candidate) }).attach()
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        val decoded = when (val v = RecordValidator.validate(checkNotNull(store.durableCapture(ctx.captureId.toString())).record)) {
            is RecordValidator.Result.Ok -> v.record
            else -> error("the persisted record must decode, got $v")
        }
        val execution = decoded.observations.getValue(StageName.Execution)
        assertEquals("an observed successful execution is COMPLETED, not unknown", StageState.Completed, execution.state)
        assertEquals("success", (execution.fields.getValue("outcome") as RecordNode.Str).value)
    }

    @Test
    fun `a dispatch with no reported execution result stays unknown`() {
        val root = tmp.newFolder("exec-unobserved")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        trigger(sink, store, stagesForTest = { candidate -> EngineStageProvenance.stagesFor(candidate) }).attach()
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        val decoded = when (val v = RecordValidator.validate(checkNotNull(store.durableCapture(ctx.captureId.toString())).record)) {
            is RecordValidator.Result.Ok -> v.record
            else -> error("the persisted record must decode, got $v")
        }
        val execution = decoded.observations.getValue(StageName.Execution)
        assertEquals("dispatch alone must NOT be recorded as execution", StageState.Unknown, execution.state)
        assertEquals("observation_unavailable", (execution.fields.getValue("reason") as RecordNode.Str).value)
    }

    // RETENTION → TRIGGER → PRODUCER → STORE (spec 05f9d6c3, reviewer bb8221f7). ACCURATE SCOPE:
    // this creates NO engine, opens NO invocation, reports NO auxiliary duck fact and calls NO
    // dispatcher — it notes NotObserved on the retention sink directly and joins Executed. The
    // publishUnderOwner here is a TEST lambda checking account/session/epoch, not the production
    // owner binding/transaction. What IS real: the trigger, producer and store, and the assertions
    // read the EXECUTION stage parsed out of the single on-disk metadata file.
    @Test
    fun `an unobserved action published to the store records unknown execution with its reason`() {
        val root = tmp.newFolder("aux-unknown-pub")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)

        // The primary action was unobserved. NOTE: no duck fact is reported here — this control
        // begins at the retention sink, so the assertion is about what an unobserved action
        // publishes, not about an auxiliary/primary pair.
        assertTrue(sink.noteExecutionOutcome(ctx.captureId, ExecutionOutcome.NotObserved))
        trigger(sink, store, stagesForTest = { candidate -> EngineStageProvenance.stagesFor(candidate) }).attach()
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))

        val execution = publishedExecution(root, store, ctx.captureId)
        assertEquals("an auxiliary success must publish UNKNOWN execution", StageState.Unknown, execution.state)
        assertEquals("observation_unavailable", (execution.fields.getValue("reason") as RecordNode.Str).value)
    }

    /** Reads the EXECUTION stage back out of the bytes the store durably persisted. */
    private fun publishedExecution(
        root: java.io.File,
        store: ContributionStore,
        captureId: UUID,
    ): StageProvenanceExecution {
        val stored = checkNotNull(store.durableCapture(captureId.toString())) { "the capture must be published" }
        val decoded = when (val v = RecordValidator.validate(stored.record)) {
            is RecordValidator.Result.Ok -> v.record
            else -> error("the persisted record must decode, got $v")
        }
        // The EXACT single on-disk metadata file is parsed and its EXECUTION stage asserted; a
        // substring match anywhere in joined text would not establish the stage.
        val onDiskExecution = onDiskExecutionStage(root, captureId)
        assertEquals(
            "the published bytes must record the execution as UNKNOWN, got $onDiskExecution",
            "unknown",
            (onDiskExecution.getValue("state") as RecordNode.Str).value,
        )
        assertEquals(
            "the published bytes must carry the unknown REASON, got $onDiskExecution",
            "observation_unavailable",
            (onDiskExecution.getValue("reason") as RecordNode.Str).value,
        )
        val execution = decoded.observations.getValue(StageName.Execution)
        return StageProvenanceExecution(execution.state, execution.fields)
    }

    /** Parses the ONE on-disk metadata file for this capture and returns its Execution stage. */
    private fun onDiskExecutionStage(root: java.io.File, captureId: UUID): Map<String, RecordNode> {
        val files = root.walkTopDown()
            .filter { it.isFile && it.name == "$captureId.metadata.json" }
            .toList()
        assertEquals("exactly one metadata file must be published", 1, files.size)
        val parsed = CanonicalJson.parse(files.single().readText())
        val observations = (parsed as RecordNode.Obj).fields["observations"] as RecordNode.Obj
        return (observations.fields["execution"] as RecordNode.Obj).fields
    }

    private data class StageProvenanceExecution(val state: StageState, val fields: Map<String, RecordNode>)

    @Test
    fun `a failed wake detector is persisted as wake failed with detector_error`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        val first = ShortArray(800) { i -> i.toShort() }
        assertTrue(sink.started(SegmentObservation.Started(ctx, first, validSamples = 800)))
        sink.completed(
            SegmentObservation.Completed(
                ctx,
                frames = listOf(ShortArray(800) { i -> (i + 1).toShort() }),
                sampleRateHz = 16000,
                speechOnsetSample = 0,
                speechEndSample = 1600,
                wakeState = SegmentObservation.WakeState.DetectorFailed,
            ),
        )
        val candidate = sink.retainedCandidateForTest(ctx.captureId)!!
        val stages = EngineStageProvenance.stagesFor(candidate)
        val wake = stages.toMap().getValue(StageName.Wake)
        assertEquals("a thrown detector is a KNOWN failure", StageState.Failed, wake.state)
        assertEquals("detector_error", (wake.fields.getValue("reason") as RecordNode.Str).value)

        // The record actually produced from this engine state must carry the same pair...
        val root = tmp.newFolder("detector-failed")
        val store = newStore(root)
        val stored = producer(store).publish(
            ctx.captureId.toString(),
            "epoch-1",
            16000,
            listOf(first to 800),
            listOf(
                "vad" to ModelRef.None,
                "wakeword" to ModelRef.None,
                "asr" to ModelRef.None,
                "translation" to ModelRef.None,
                "router" to ModelRef.None,
            ),
            stages,
        )
        assertTrue("the producer must publish a record for a failed-detector capture", stored != null)
        // ...and survive a round trip through the RECOVERY authority, which is where the pairing rule
        // is enforced for records coming back in.
        val served = checkNotNull(store.durableCapture(ctx.captureId.toString()))
        val decoded = RecordValidator.validate(served.record)
        assertTrue(
            "the persisted record must be ACCEPTED by the recovery validator, got: $decoded",
            decoded is RecordValidator.Result.Ok,
        )
        // THE ASSERTION MUST READ THE DECODED RECORD, NOT THE IN-MEMORY CANDIDATE (reviewer b5ed5d2c).
        // Recomputing `EngineStageProvenance.stagesFor(candidate)` here would re-read the same candidate
        // the mapping came from, so a producer that persisted `wake=unknown` would still pass. The
        // decoded observation is the only place the persisted pair is actually observed.
        val decodedWake = (decoded as RecordValidator.Result.Ok).record.observations.getValue(StageName.Wake)
        assertEquals("the PERSISTED wake state must be a known failure", StageState.Failed, decodedWake.state)
        assertEquals(
            "the PERSISTED wake reason must be detector_error",
            "detector_error",
            (decodedWake.fields.getValue("reason") as RecordNode.Str).value,
        )
    }

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
        // Translation UNOBSERVED for this capture: honest unknown, never an invented not_run.
        assertEquals(StageState.Unknown, byName.getValue(StageName.Translation).state)
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

    @Test
    fun `identity transition between trigger entry and the atomic section cannot publish the old capture under a new owner`() {
        // THE CHECK-THEN-USE WINDOW (reviewer 8288ac15): the capture is retained under A, the
        // trigger body is entered while A is still live, and the identity flips to B BEFORE the
        // atomic section runs. Because validation and commit share ONE section, the publish must
        // refuse and leave NO record and NO reservation.
        val root = tmp.newFolder("window")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val live = java.util.concurrent.atomic.AtomicReference(RetentionPublishTrigger.LiveIdentity("a@x", 7L, "epoch-1"))
        val bodyEntered = java.util.concurrent.CountDownLatch(1)
        val flipDone = java.util.concurrent.CountDownLatch(1)
        val trig = RetentionPublishTrigger(
            sink,
            producer(store, account = { live.get().account }, session = { live.get().session }),
            fixedModels = fixedModels,
            stagesProvider = { stages },
            publishUnderOwner = { frozen, commit ->
                // BARRIER: run the atomic section on another thread only after the identity
                // flip is complete — the flip happens INSIDE the window.
                bodyEntered.countDown()
                flipDone.await(5, java.util.concurrent.TimeUnit.SECONDS)
                val now = live.get()
                if (now.account == frozen.account && now.session == frozen.session && now.enrollmentEpoch == frozen.enrollmentEpoch) commit() else false
            },
            dispatch = { body ->
                // Synchronous body: the flip (below) happens BEFORE the atomic section runs, so
                // the window is exercised deterministically rather than raced.
                body()
                null
            },
        )
        trig.attach()
        val body = Thread {
            bodyEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            live.set(RetentionPublishTrigger.LiveIdentity("b@y", 9L, "epoch-2"))
            flipDone.countDown()
        }
        body.start()
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))
        body.join(5000)
        val deadline = System.currentTimeMillis() + 5000
        while (sink.retainedCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals("a moved capture must never be published", 0, store.readyEntries().size)
        assertEquals(0L, sink.retainedBytes())
        assertEquals(0, sink.retainedCount())
    }

    @Test
    fun `observed translation provenance is recorded - backend-native, platform and absent`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val candidate = sink.retainedCandidateForTest(ctx.captureId)!!
        // No observation yet: translation is honest UNKNOWN (not an invented not_run reason).
        assertEquals(StageState.Unknown, EngineStageProvenance.stagesFor(candidate).toMap().getValue(StageName.Translation).state)
        // An OBSERVED produced result completes with BOTH schema fields from their OWN
        // observations (reviewer 57719ad2): translated_text is the result text, target_language
        // the language — DISTINCT values so a cross-write cannot pass this control.
        assertTrue(
            sink.noteTranslation(
                ctx.captureId,
                TranslationProvenance(
                    ModelRef.Named("canary-flash-model", "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"),
                    producedResult = true,
                    translatedText = "the shelves are empty",
                    targetLanguage = "en",
                ),
            ),
        )
        val observed = EngineStageProvenance.stagesFor(candidate).toMap().getValue(StageName.Translation)
        assertEquals(StageState.Completed, observed.state)
        assertEquals("the shelves are empty", (observed.fields.getValue("translated_text") as RecordNode.Str).value)
        assertEquals("en", (observed.fields.getValue("target_language") as RecordNode.Str).value)
    }

    @Test
    fun `a produced translation missing either observed half is not a completed record`() {
        // The schema needs translated_text AND target_language (reviewer 57719ad2): a provenance
        // that claims a result but observed only the language must NOT serialize the language as
        // the text -- it degrades to not_run/no_text, never an invented field.
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val candidate = sink.retainedCandidateForTest(ctx.captureId)!!
        assertTrue(
            sink.noteTranslation(
                ctx.captureId,
                TranslationProvenance(ModelRef.None, producedResult = true, targetLanguage = "en"),
            ),
        )
        val observed = EngineStageProvenance.stagesFor(candidate).toMap().getValue(StageName.Translation)
        assertEquals(StageState.NotRun, observed.state)
        assertEquals("no_text", (observed.fields.getValue("reason") as RecordNode.Str).value)
    }

    @Test
    fun `an owner transition attempted between validation and prepare cannot interleave - real owner transaction`() {
        // THE ATOMICITY CONTROL (reviewer 57719ad2, re-driven through the REAL publication
        // path per reviewer 704ef15a): the actual [RetentionPublishTrigger] fires with the
        // PRODUCTION-shaped binding -- validateUnderOwnerTransaction: ONE owner-locked
        // section that compares the frozen tuple against the live snapshot and runs the
        // commit inside the SAME acquisition. A flip thread attempts the transition at the
        // interleaving point (after validation, before prepare); it needs the SAME owner
        // lock, so it cannot complete until the section -- commit included -- is done. The
        // producer's identity reads go through the same locked snapshot, and every read is
        // recorded so the control proves WHOSE owner prepared the record.
        val root = tmp.newFolder("interleave")
        val store = newStore(root)
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retainCapture(sink, ctx)
        val owner = WatchOwner("a@x", 7L)
        val seenAccounts = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val flipGate = java.util.concurrent.CountDownLatch(1)
        val flipDone = java.util.concurrent.CountDownLatch(1)
        val flipThread = Thread {
            flipGate.await(5, java.util.concurrent.TimeUnit.SECONDS)
            owner.switchTo("b@y", 9L)
            flipDone.countDown()
        }
        flipThread.start()
        // Production wiring shape: the producer's identity reads read the owner snapshot
        // under the SAME lock the transaction holds.
        val producer1 = ContributionCaptureProducer(
            store,
            appVersion = { "8.20" },
            appBuild = { "12345" },
            captureSessionId = { "00000000-0000-4000-8000-000000000000" },
            enrollmentAccount = {
                val a = owner.authoritySnapshot().accountName ?: "<null>"
                seenAccounts.add(a)
                a
            },
            enrollmentSession = { owner.authoritySnapshot().session },
        )
        // THE SHARED PRODUCTION BINDING (reviewer fd3faba7): the same ownerPublicationBinding
        // the module wires, with the barrier injected through the control-only interleave hook
        // -- a regression in the locking rule can no longer hide behind a handwritten copy.
        val binding = ownerPublicationBinding(
            object : OwnerPublicationSection {
                override fun underOwnerTransaction(
                    section: (live: RetentionPublishTrigger.LiveIdentity?) -> Boolean?,
                ): Boolean? = owner.transaction { section(owner.liveIdentityForTest()) }
            },
            interleave = {
                // THE INTERLEAVING POINT: release the flip attempt between validation
                // and prepare, then hold until the flip thread is demonstrably BLOCKED
                // on the owner lock -- proof the transition cannot complete while the
                // validate -> prepare -> commit section is running.
                flipGate.countDown()
                val deadline = System.currentTimeMillis() + 5000
                while (flipThread.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(5)
                assertEquals(Thread.State.BLOCKED, flipThread.state)
            },
        )
        val trig = RetentionPublishTrigger(
            sink,
            producer1,
            fixedModels = fixedModels,
            stagesProvider = { stages },
            publishUnderOwner = binding,
            dispatch = directDispatch(),
        )
        trig.attach()
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))
        assertTrue("the flip must complete only after the section", flipDone.await(5, java.util.concurrent.TimeUnit.SECONDS))
        flipThread.join(5000)
        assertEquals("the live owner moved after the section", "b@y", owner.authoritySnapshot().accountName)
        // The record was published under the owner the frozen tuple was validated against.
        val entries = store.readyEntries()
        assertEquals("exactly one record", 1, entries.size)
        assertTrue(
            "every producer identity read saw the frozen owner (saw=$seenAccounts)",
            seenAccounts.all { it == "a@x" },
        )
    }
}
