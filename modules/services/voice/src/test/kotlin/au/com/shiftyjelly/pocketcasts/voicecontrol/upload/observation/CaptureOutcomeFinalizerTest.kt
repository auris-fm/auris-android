package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceLimits
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceProvisioning
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceWindow
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceOwner
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.Ceilings
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.EngineStageProvenance
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.QueueLimits
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadModule
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * Batch B controls (reviewer be6bb62e / spec cf1b9809): bounded outcome finalization through
 * the REAL path. BOTH completion paths seal through ONE sink-lock operation and dispatch an
 * immutable [SealedCapture]; the wait is anchored to real completion time; registration is
 * race-free (owned tokens, atomic generation-checked firing, generation-filtered purge
 * notifications); missing wait limits CLOSE admission; and the exact record PERSISTED by the
 * admitted-PCM harness is accepted by the pinned strict Worker intake.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureOutcomeFinalizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Rendezvous virtual clock: each deadline parks its REMAINING span here until drained. */
    private class VirtualClock {
        val sleeps = Channel<Long>(Channel.RENDEZVOUS)

        suspend fun sleep(remainingMs: Long) {
            if (remainingMs > 0) sleeps.send(remainingMs)
        }

        /** Drains a PARKED deadline (its remaining span) or null when nothing is parked. */
        fun drainDeadline(): Long? {
            val ms = runBlocking { withTimeoutOrNull(4_000) { sleeps.receive() } }
            pump()
            return ms
        }

        fun pump() = runBlocking { repeat(30) { kotlinx.coroutines.delay(5) } }

        fun assertNothingParked() {
            runBlocking {
                assertNull("no deadline may be parked", withTimeoutOrNull(400) { sleeps.receive() })
            }
        }
    }

    /** Controllable monotonic clock for delayed-callback/delayed-start controls. */
    private class MutableClock(startMs: Long = 0L) {
        @Volatile
        var now: Long = startMs

        fun nowMs(): Long = now
    }

    private fun limits(maxMetadataBytes: Int = 4_096) = AllowanceLimits(
        10,
        1_000_000,
        maxClipBytes = 100_000 - 1024,
        maxMetadataBytes = maxMetadataBytes,
        ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192),
    )

    /** The REAL admitted retained-audio harness: provisioned allowance admission → frozen cap
     *  → sink candidate with PCM → REAL module trigger → producer → store, plus the finalizer
     *  wired exactly as production CollectionStartup wires it. */
    private class Harness(
        val sink: ContributionRetentionSink,
        val store: ContributionStore,
        val captureId: UUID,
        val clock: VirtualClock,
        val finalizer: CaptureOutcomeFinalizer,
    ) {
        fun readyFor(captureId: UUID = this.captureId) = store.readyEntries().filter { it.captureId == captureId.toString() }
    }

    private fun harness(
        waitLimits: OutcomeWaitLimits? = OutcomeWaitLimits(selectedWaitMs = 500L, ceilingWaitMs = 60_000L),
        clock: VirtualClock = VirtualClock(),
        expectAdmitted: Boolean = true,
        asrModel: ModelRef = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
        mono: (() -> Long)? = null,
    ): Harness {
        // ONE injected clock domain for the sink AND the finalizer (reviewer 62023896 #1):
        // the completion timestamp is frozen in this domain and the remaining-duration
        // computation reads the same clock.
        val clockMs = mono ?: { 0L }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ContributionStore(
            java.nio.file.Files.createTempDirectory("batch-b").toFile(),
            QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
            clock = System::currentTimeMillis,
            jitter = { 0L },
        )
        val startup = VoiceUploadCompositionFixtures.startup(context as android.app.Application)
        val owner = startup.ownerForHandoff()
        (owner as VoiceUploadCompositionFixtures.AppContextOwner).publish("a@x", 7L, "Bearer-fixture")
        startup.credentials.publish("Bearer-fixture")
        val token = startup.state.sessionStarted(7L)
        val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
        assertTrue(startup.state.applyOutcome(token, ticket, VoiceUploadCompositionFixtures.authenticatedProjection("epoch-1"), ownerSession = 7L))
        val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule
            .provideVoiceSharingPolicyProvider(remoteConfig = mock(), featureProvider = mock(), ownership = owner, scope = CoroutineScope(Job().apply { cancel() }))
        policy.applySnapshotForTest(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                enabled = true,
                activatedAtMonotonicMs = 1L,
                activationGeneration = 1L,
            ),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024), nowMs = clockMs)
        val allowance = CaptureAllowanceOwner(tmp.newFolder(), "a@example.com", limits(), AllowanceWindow.Established("window-1"), AllowanceProvisioning.TestOnly)
        assertTrue(allowance.prepareToken())
        val observer = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@x", 7L, 1L, "epoch-1", "1") },
            allowance = allowance,
        )
        // EXACTLY the production wiring (CollectionStartup): admission guard = limitsSelected.
        val finalizer = CaptureOutcomeFinalizer(
            sink = sink,
            waitLimits = waitLimits,
            scope = scope,
            sleepMs = { clock.sleep(it) },
            nowMs = clockMs,
        )
        observer.outcomeWaitGuard = { finalizer.limitsSelected }
        finalizer.start()
        val admission = observer.speechStartedAdmitting(captureSession = 1L, routeClass = "Bluetooth", producerSize = 800, asrModel = asrModel)
        if (!expectAdmitted) {
            assertNull("missing wait limits must CLOSE admission (spec cf1b9809)", admission)
            return Harness(sink, store, UUID.randomUUID(), clock, finalizer)
        }
        assertNotNull("the allowance-admitted capture must start", admission)
        val ctx = admission!!.context
        assertTrue(observer.adoptFirstChunk(admission, ShortArray(800), 800))
        assertTrue(
            sink.completed(
                SegmentObservation.Completed(
                    ctx,
                    frames = listOf(ShortArray(800) { (it + 1).toShort() }),
                    sampleRateHz = 16000,
                    speechOnsetSample = 0,
                    speechEndSample = 1600,
                    wakeState = SegmentObservation.WakeState.Detected,
                ),
            ),
        )
        val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
        VoiceUploadModule.provideRetentionPublishTrigger(sink = sink, producer = producer, accountStartup = startup, scope = scope).attach()
        return Harness(sink, store, ctx.captureId, clock, finalizer)
    }

    @Test
    fun `a hung router after Recognized seals at the deadline and publishes EXACTLY ONE record`() {
        val h = harness()
        assertTrue(h.sink.joinOutcome(CollectionOutcome.Recognized(h.captureId, transcript = "skip this ad", detectedLanguage = "en")))
        // The router hangs: no Routed/Executed join. The deadline elapses.
        assertNotNull("premise: the deadline is parked", h.clock.drainDeadline())

        // THE SEALED RECORD PUBLISHED through the real trigger → producer → store.
        assertEquals("exactly one persisted immutable record", 1, h.readyFor().size)
        assertNull("retention ended by the publish body", h.sink.retainedCandidateForTest(h.captureId))
        assertEquals(0, h.finalizer.activeTimerCount())
        // Late outcomes AND late observations are REFUSED by the sealed immutable record.
        assertFalse(h.sink.joinOutcome(CollectionOutcome.Executed(h.captureId, dispatchedWithoutThrowing = true)))
        assertFalse(h.sink.noteExecutionOutcome(h.captureId, ExecutionOutcome.Success))
        assertFalse(h.sink.noteTranslation(h.captureId, TranslationProvenance(model = ModelRef.None, producedResult = false, translatedText = null, targetLanguage = null)))
        assertEquals("no second record from a late join", 1, h.readyFor().size)
        assertEquals(0L, h.sink.retainedBytes())
    }

    @Test
    fun `a wake-only terminal keeps the bounded wait then seals WITH its known fact and publishes`() {
        val h = harness()
        assertTrue(h.sink.joinOutcome(CollectionOutcome.NotCollected(h.captureId, "wake_only")))
        h.clock.pump()
        assertEquals("a wake-only join does NOT publish and does NOT cancel the deadline", 0, h.readyFor().size)
        assertNotNull("the deadline survived the wake-only terminal", h.clock.drainDeadline())

        assertEquals("the sealed record published, preserving the known wake fact", 1, h.readyFor().size)
        assertFalse("late outcomes refused after sealing", h.sink.joinOutcome(CollectionOutcome.Executed(h.captureId, dispatchedWithoutThrowing = true)))
    }

    @Test
    fun `an Executed terminal seals at the join through the SAME boundary and leaves no timer`() {
        val h = harness()
        assertTrue(h.sink.joinOutcome(CollectionOutcome.Recognized(h.captureId, transcript = "hello", detectedLanguage = "en")))
        assertTrue(h.sink.joinOutcome(CollectionOutcome.Executed(h.captureId, dispatchedWithoutThrowing = true)))
        h.clock.pump()
        assertEquals("the real join published from the immutable snapshot", 1, h.readyFor().size)
        // The seal cancelled the deadline; nothing parked, nothing leaked, no second record.
        h.clock.assertNothingParked()
        assertEquals("no leftover timers after fast completion", 0, h.finalizer.activeTimerCount())
        assertEquals(1, h.readyFor().size)
        assertFalse("sealed: late terminal refused", h.sink.joinOutcome(CollectionOutcome.Executed(h.captureId, dispatchedWithoutThrowing = true)))
    }

    @Test
    fun `revocation cancels every deadline - nothing seals or publishes afterwards`() {
        val h = harness()
        val gen = h.sink.currentPurgeGeneration()
        h.sink.purgeAllForRevocation("account-change")
        h.clock.assertNothingParked()
        assertEquals("no record after revocation", 0, h.readyFor().size)
        assertNull("late sealing refused for a purged capture", h.sink.sealExpiredOutcome(h.captureId, gen))
    }

    @Test
    fun `a stale-generation retain callback cannot leave a timer behind`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val f = CaptureOutcomeFinalizer(sink, OutcomeWaitLimits(500L, 60_000L), scope, sleepMs = { clock.sleep(it) }, nowMs = { 0L })
        f.start()
        val id = UUID.randomUUID()
        sink.purgeAllForRevocation("revoke") // purge generation -> 1
        // THE STRADDLE (reviewer 62023896 #2): a pre-purge callback (stale generation 0)
        // delivered AFTER the purge. Registration validation drops the timer at once; the
        // atomic generation-checked firing could never seal against it either.
        sink.onCandidateRetained?.invoke(id, 0L, 0L)
        clock.assertNothingParked()
        assertEquals("no leftover timer from the stale callback", 0, f.activeTimerCount())
        assertNull("nothing seals against a stale generation", sink.sealExpiredOutcome(id, 0L))
        scope.cancel()
    }

    @Test
    fun `a late purge notification cannot cancel a capture registered AFTER that purge`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val f = CaptureOutcomeFinalizer(sink, OutcomeWaitLimits(500L, 60_000L), scope, sleepMs = { clock.sleep(it) }, nowMs = { 0L })
        f.start()
        val id = UUID.randomUUID()
        sink.purgeAllForRevocation("revoke") // generation -> 1
        assertTrue(sink.retainCandidateForTest(id)) // registered at generation 1
        // THE LATE NOTIFICATION: the OLD purge's onPurged hook arrives after the fresh
        // registration. The generation filter must NOT cancel the generation-1 timer.
        sink.onPurged?.invoke()
        clock.pump()
        assertEquals("the fresh timer survived the late purge notification", 1, f.activeTimerCount())
        assertNotNull("the fresh deadline is still parked", clock.drainDeadline())
        assertTrue("the valid timer sealed the live candidate", sink.retainedCandidateForTest(id)!!.sealed)
        scope.cancel()
    }

    @Test
    fun `missing wait limits CLOSE contribution admission`() {
        harness(waitLimits = null, clock = VirtualClock(), expectAdmitted = false)
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val allowance = CaptureAllowanceOwner(tmp.newFolder(), "a@example.com", limits(), AllowanceWindow.Established("window-1"), AllowanceProvisioning.TestOnly)
        assertTrue(allowance.prepareToken())
        fun observer(guard: (() -> Boolean)?) = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
            issueAuthority = { IssuedAuthority("a@x", 7L, 1L, "epoch-1", "1") },
            allowance = allowance,
        ).apply { outcomeWaitGuard = guard }

        assertNull("no limits → admission REFUSES (fail-closed, spec cf1b9809)", observer { false }.speechStartedAdmitting(1L, "Headset", 800))
        assertNotNull("a selected wait admits", observer { true }.speechStartedAdmitting(2L, "Headset", 800))
    }

    @Test
    fun `scope cancellation disposes - hooks detach and no later timer arms`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val f = CaptureOutcomeFinalizer(sink, OutcomeWaitLimits(500L, 60_000L), scope, sleepMs = { clock.sleep(it) }, nowMs = { 0L })
        f.start()
        scope.cancel()
        Thread.sleep(50) // Job.invokeOnCompletion runs dispose on cancellation
        assertNull("scope teardown detached the sink hooks", sink.onCandidateRetained)
        assertNull(sink.onCaptureReleased)
        assertNull(sink.onPurged)
        val id = UUID.randomUUID()
        assertTrue(sink.retainCandidateForTest(id))
        clock.assertNothingParked()
        assertEquals(0, f.activeTimerCount())
    }

    @Test
    fun `outcome wait limits are fail-closed with INDEPENDENT inputs`() {
        try {
            OutcomeWaitLimits(selectedWaitMs = 70_000L, ceilingWaitMs = 60_000L)
            fail("a selection above its ceiling must be a construction error, never a clamp")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OutcomeWaitLimits(selectedWaitMs = 0L, ceilingWaitMs = 60_000L)
            fail("a non-positive selection must be a construction error")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(500L, OutcomeWaitLimits(selectedWaitMs = 500L, ceilingWaitMs = 500L).selectedWaitMs)
    }

    @Test(timeout = 20_000)
    fun `a delayed callback or delayed coroutine start cannot extend the wait`() {
        // ONE clock domain: the sink freezes completion at now=0 → absolute deadline 500.
        val mono = MutableClock()
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20), nowMs = mono::nowMs)
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val f = CaptureOutcomeFinalizer(sink, OutcomeWaitLimits(500L, 60_000L), scope, sleepMs = { clock.sleep(it) }, nowMs = mono::nowMs)
        f.start()
        val id = UUID.randomUUID()
        // DELAYED DELIVERY (reviewer 62023896 #1): real completion happened at now=0, but the
        // retained callback only reaches the finalizer at now=400. The deadline was frozen at
        // COMPLETION, so the remaining span shrinks — it is NOT re-extended from delivery.
        sink.retainCandidateForTest(id)
        mono.now = 400L
        sink.onCandidateRetained?.invoke(id, sink.currentPurgeGeneration(), 0L)
        assertEquals("the original completion-frozen deadline stands (remaining 100, not 500)", 100L, clock.drainDeadline())
        assertTrue("the wait sealed at the frozen deadline", sink.retainedCandidateForTest(id)!!.sealed)
        assertEquals(0, f.activeTimerCount())
        scope.cancel()
    }

    @Test(timeout = 20_000)
    fun `a delayed COROUTINE START on a blocked dispatcher cannot extend the wait`() {
        val mono = MutableClock()
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20), nowMs = mono::nowMs)
        val clock = VirtualClock()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val gate = java.util.concurrent.CountDownLatch(1)
        // The poller reads THIS stop signal (a latch it never reads would strand it).
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        var scope: CoroutineScope? = null
        var poller: Thread? = null
        try {
            // CONTROLLED DISPATCHER: the executor is blocked, so scope.launch does NOT run yet.
            executor.submit { gate.await() }
            scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
            val f = CaptureOutcomeFinalizer(sink, OutcomeWaitLimits(500L, 60_000L), scope, sleepMs = { clock.sleep(it) }, nowMs = mono::nowMs)
            f.start()
            val id = UUID.randomUUID()
            assertTrue(sink.retainCandidateForTest(id)) // registration queued; coroutine NOT started
            mono.now = 10_000L // the start is delayed far past the completion-frozen deadline
            gate.countDown() // release the dispatcher: the coroutine starts NOW
            val started = java.util.concurrent.CountDownLatch(1)
            poller = Thread {
                while (!stop.get() && f.activeTimerCount() > 0) Thread.sleep(5)
                started.countDown()
            }
            poller.start()
            assertTrue("the past-due registration completed without parking", started.await(4, TimeUnit.SECONDS))
            clock.assertNothingParked()
            assertTrue("the wait sealed immediately at its frozen deadline", sink.retainedCandidateForTest(id)!!.sealed)
        } finally {
            // NO assertion failure may strand the poller thread or the blocked executor.
            stop.set(true)
            poller?.interrupt()
            poller?.join(2_000)
            scope?.cancel()
            gate.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a callback delivered across an EARLY SEAL registers nothing`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20), nowMs = { 0L })
        val clock = VirtualClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val f = CaptureOutcomeFinalizer(sink, OutcomeWaitLimits(500L, 60_000L), scope, sleepMs = { clock.sleep(it) }, nowMs = { 0L })
        val id = UUID.randomUUID()
        // The callback is PAUSED: retain happens BEFORE the finalizer attaches (no delivery).
        assertTrue(sink.retainCandidateForTest(id))
        // While the callback was paused, the capture seals EARLY (Executed join) — before any
        // publication runs (no trigger attached here).
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(id, dispatchedWithoutThrowing = true)))
        f.start()
        // NOW the paused callback is delivered for the ALREADY-SEALED capture (same generation).
        sink.onCandidateRetained?.invoke(id, sink.currentPurgeGeneration(), 0L)
        clock.assertNothingParked()
        assertEquals("no timer survives registration across an early seal", 0, f.activeTimerCount())
        assertNull("the atomic firing check also refuses a sealed capture", sink.sealExpiredOutcome(id, sink.currentPurgeGeneration()))
        scope.cancel()
    }

    @Test(timeout = 20_000)
    fun `terminal and deadline genuinely race - EXACTLY ONE outcome wins`() {
        val h = harness()
        val raced = Thread {
            // Nondeterministic winner: this may seal (join) before or after the deadline fires.
            h.sink.joinOutcome(CollectionOutcome.Executed(h.captureId, dispatchedWithoutThrowing = true))
        }
        raced.start()
        h.clock.drainDeadline()
        raced.join(4_000)
        h.clock.pump()
        // EXACTLY ONCE, whichever sealed first: one record, nothing else, no leftover timers.
        assertEquals("exactly one record survives the race", 1, h.readyFor().size)
        assertEquals(0, h.finalizer.activeTimerCount())
        assertFalse("post-seal terminal refused", h.sink.joinOutcome(CollectionOutcome.Executed(h.captureId, dispatchedWithoutThrowing = true)))
    }

    @Test
    fun `an expired-wait snapshot types unresolved stages outcome_wait_expired and known facts survive`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val id = UUID.randomUUID()
        sink.retainCandidateForTest(id, allowanceMetadataCap = 1024)
        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(id, transcript = "hello", detectedLanguage = "en")))
        val snap = sink.sealExpiredOutcome(id, sink.currentPurgeGeneration())
        assertNotNull(snap)
        assertEquals(SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED, snap!!.cause)
        val stages = EngineStageProvenance.stagesFor(snap).toMap()
        assertEquals(StageState.Completed, stages.getValue(StageName.Wake).state)
        assertEquals(StageState.Completed, stages.getValue(StageName.Asr).state)
        for (name in listOf(StageName.Bleed, StageName.Translation, StageName.Routing, StageName.Execution)) {
            assertEquals("$name must be unknown", StageState.Unknown, stages.getValue(name).state)
            assertEquals(
                "$name must carry the expired-wait reason",
                "outcome_wait_expired",
                (stages.getValue(name).fields.getValue("reason") as RecordNode.Str).value,
            )
        }
    }

    @Test
    fun `an early-completed capture keeps the pre-Batch-B typing - its absence is not an expiry fact`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        var snap: SealedCapture? = null
        sink.onReadyForPublication = { snap = it }
        val id = UUID.randomUUID()
        sink.retainCandidateForTest(id, allowanceMetadataCap = 1024)
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(id, dispatchedWithoutThrowing = true)))
        assertNotNull(snap)
        assertEquals(SealedCapture.CAUSE_TERMINAL, snap!!.cause)
        val stages = EngineStageProvenance.stagesFor(snap).toMap()
        assertEquals(
            "terminal cause: observation_unavailable, NOT outcome_wait_expired",
            "observation_unavailable",
            (stages.getValue(StageName.Execution).fields.getValue("reason") as RecordNode.Str).value,
        )
    }

    @Test(timeout = 20_000)
    fun `an expired wait eventually ends retention through the real publish body`() {
        val h = harness()
        assertNotNull(h.clock.drainDeadline())
        // RETENTION ENDED BY THE PUBLISH BODY (its finally releases the charge exactly once —
        // onRetentionEnded is the budget-refusal hook, not this path).
        h.clock.pump()
        assertNull("the sealed capture must have been released by the publish body", h.sink.retainedCandidateForTest(h.captureId))
        assertEquals(0L, h.sink.retainedBytes())
    }

    //
    // STRICT WORKER PARITY ON THE PERSISTED RECORD (reviewer be6bb62e #4): the record fed to
    // the pinned Worker probe is the EXACT canonical record the harness's REAL module trigger
    // persisted — reconstructed fixtures prove nothing.
    //

    private fun cloudWorkerRoot(): File = System.getenv("AURIS_CLOUD_WORKER_ROOT")?.let(::File)
        ?: error(
            "AURIS_CLOUD_WORKER_ROOT is not set. The moving core checkout is NOT a valid fallback. " +
                "Point the variable at a pinned approved worktree.",
        )

    private val approvedWorkerRevisions = setOf(
        "d8fe823cbe43ebb310d632b482312c7b282ef018",
        "c569b319e19b5971ada44a0f53245630688c1305",
        "a030ff31d95dd703fab6c5c976f36826ef77f499",
        "3dd0c91d095512e3c49108c25b726a3b66a66eef",
        "862c7ed3fdc540eccc9fdacd6819c97a9e679536",
    )

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun assertApprovedRevision(output: String) {
        val line = output.lines().firstOrNull { it.startsWith("# worker-revision") }
            ?: error("probe did not report its Worker revision; refusing to claim parity")
        val reported = line.substringAfter("\t").trim()
        assertTrue("INTEGRITY: authority reports '$reported' — dirty is not an authority", reported.endsWith("CLEAN"))
        assertTrue(
            "INTEGRITY: parity ran against '${reported.removeSuffix("CLEAN").trim()}' which is not an approved authority: $approvedWorkerRevisions",
            reported.removeSuffix("CLEAN").trim() in approvedWorkerRevisions,
        )
    }

    /** Runs the pinned Worker intake probe over one persisted record. */
    private fun probePersistedRecord(id: String, record: RecordNode.Obj): String {
        require(File(cloudWorkerRoot(), "src/contributions/record_schema.ts").isFile)
        require(nodeAvailable())
        val recordsFile = java.nio.file.Files.createTempFile("sealed-parity", ".jsonl").toFile()
        val probe = File(recordsFile.parentFile, "probe_worker_intake.mjs")
        probe.writeBytes(javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake.mjs")!!.readBytes())
        val json = CanonicalJson.canonicalBytes(record).toString(Charsets.UTF_8)
        recordsFile.writeText("""{"id":"$id","record":$json}""")
        val process = ProcessBuilder("node", probe.absolutePath, cloudWorkerRoot().absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())
        assertApprovedRevision(output)
        return output
    }

    @Test(timeout = 60_000)
    fun `the EXACT record persisted by the hung-router harness is accepted by the strict Worker intake`() {
        val h = harness()
        assertTrue(h.sink.joinOutcome(CollectionOutcome.Recognized(h.captureId, transcript = "skip this ad", detectedLanguage = "en")))
        assertNotNull(h.clock.drainDeadline())
        assertEquals(1, h.readyFor().size)
        val durable = h.store.durableCapture(h.captureId.toString())
        assertNotNull("the persisted record must be readable", durable)
        val output = probePersistedRecord(h.captureId.toString(), durable!!.record)
        val verdictLine = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.firstOrNull()
        assertEquals(
            "the persisted sealed record was NOT accepted by the Worker: $verdictLine",
            "ACCEPT",
            verdictLine?.substringAfter('\t')?.trim(),
        )
        // KNOWN FACTS in the persisted record itself: the wake decision and the ASR transcript.
        val canonical = CanonicalJson.canonicalBytes(durable.record).toString(Charsets.UTF_8)
        assertTrue("the persisted record must carry the wake decision", canonical.contains("\"detected\""))
        assertTrue("the persisted record must carry the recognized transcript", canonical.contains("skip this ad"))
        // P1 (reviewer 0cd7e39d/7033f4d9): the module's REAL fixed list is the declared
        // absence — no invented labels. vad/wakeword serialize as null (no loader-verified
        // version exists), and asr claims only invocation evidence (none was noted here).
        assertTrue("the persisted record must declare the vad absence", canonical.contains("\"vad\":null"))
        assertTrue("the persisted record must declare the wakeword absence", canonical.contains("\"wakeword\":null"))
        assertTrue("the record must not resurrect the removed fixed labels", !canonical.contains("silero-vad"))
    }

    @Test(timeout = 60_000)
    fun `a persisted no-ASR expired-wait record is accepted by the strict Worker intake`() {
        val h = harness(asrModel = ModelRef.None)
        assertNotNull(h.clock.drainDeadline())
        assertEquals(1, h.readyFor().size)
        val durable = h.store.durableCapture(h.captureId.toString())
        assertNotNull(durable)
        val output = probePersistedRecord(h.captureId.toString(), durable!!.record)
        val verdictLine = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.firstOrNull()
        assertEquals(
            "the persisted no-ASR sealed record was NOT accepted: $verdictLine",
            "ACCEPT",
            verdictLine?.substringAfter('\t')?.trim(),
        )
        val canonical = CanonicalJson.canonicalBytes(durable.record).toString(Charsets.UTF_8)
        assertTrue("the persisted record must carry the expired-wait reason", canonical.contains("outcome_wait_expired"))
    }
}
