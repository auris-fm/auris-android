package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceLimits
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceProvisioning
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceWindow
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceOwner
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.Ceilings
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * Persistence-boundary controls for the prepaid METADATA bound (reviewer 55433b71 #3 / fde5349e,
 * spec 0987c46f). The bound is FROZEN into the immutable retained candidate by the REAL
 * allowance-admitted observer capture (no manual note), carried through the REAL
 * trigger→producer→store path, and counts the FULL canonical record/digest envelope:
 *
 * - a bound that fits the record alone but not its required envelope refuses, with NO queue
 *   admission and NO write;
 * - a capture that was never allowance-admitted (no frozen bound) refuses fail-closed;
 * - the positive companion publishes with a bound that fits the envelope.
 */
@RunWith(RobolectricTestRunner::class)
class AllowanceMetadataPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")

    private fun limits(maxMetadataBytes: Int) = AllowanceLimits(
        10,
        1_000_000,
        maxClipBytes = 100_000 - 1024,
        maxMetadataBytes = maxMetadataBytes,
        ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192),
    )

    private fun owner(dir: File, maxMetadataBytes: Int) = CaptureAllowanceOwner(dir, account, limits(maxMetadataBytes), window, AllowanceProvisioning.TestOnly)

    /**
     * Drives the REAL path: provisioned-owner allowance admission (observer) → sink candidate
     * (bound frozen by the admission event) → REAL trigger → producer → store. Returns the
     * published entry's stored canonical size, or null when publication was refused.
     */
    private fun publishThroughRealAdmission(maxMetadataBytes: Int): Int? {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = java.nio.file.Files.createTempDirectory("meta-cap").toFile()
        val store = ContributionStore(
            root,
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
            .provideVoiceSharingPolicyProvider(
                remoteConfig = mock(),
                featureProvider = mock(),
                ownership = owner,
                scope = CoroutineScope(Job().apply { cancel() }),
            )
        policy.applySnapshotForTest(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                enabled = true,
                activatedAtMonotonicMs = 1L,
                activationGeneration = 1L,
            ),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val allowance = this.owner(tmp.newFolder(), maxMetadataBytes)
        assertTrue("the prepaid token must be minted off-path", allowance.prepareToken())
        val observer = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = {
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(
                    collectionPermitted = true,
                    fullRevocation = false,
                )
            },
            issueAuthority = {
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority(
                    accountName = "a@x",
                    sessionGeneration = 7L,
                    credentialIncarnation = 1L,
                    enrollmentEpoch = "epoch-1",
                    acceptedDisclosureVersion = "1",
                )
            },
            allowance = allowance,
        )
        val admission = observer.speechStartedAdmitting(
            captureSession = 1L,
            routeClass = "Bluetooth",
            producerSize = 800,
            asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
        )
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
        // The bound is FROZEN on the immutable candidate by the admission itself.
        assertEquals(
            maxMetadataBytes,
            sink.retainedCandidateForTest(ctx.captureId)?.allowanceMetadataCap,
        )
        val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
        val publishTrigger = VoiceUploadModule.provideRetentionPublishTrigger(
            sink = sink,
            producer = producer,
            accountStartup = startup,
            scope = scope,
        )
        publishTrigger.attach()
        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(ctx.captureId, transcript = "skip this ad", detectedLanguage = "en")))
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(ctx.captureId, dispatchedWithoutThrowing = true)))
        val ready = store.readyEntries().firstOrNull { it.captureId == ctx.captureId.toString() }
        return ready?.metadataBytes
    }

    @Test
    fun `a bound that fits the record but not its digest envelope refuses with no queue admission`() {
        // Positive companion FIRST: a generous bound publishes and reveals the stored canonical size.
        val canonicalSize = publishThroughRealAdmission(maxMetadataBytes = 4_096)
            ?: throw AssertionError("the positive companion must publish through the real path")
        // Refusal: the SELECTED bound fits the stored canonical record but NOT the record+digest
        // envelope the transport must send (the envelope overhead alone is far above 5 bytes).
        val refused = publishThroughRealAdmission(maxMetadataBytes = canonicalSize + 5)
        assertNull("a record-only bound must refuse publication", refused)
    }

    @Test
    fun `each capture freezes its own bound - a late sibling or stale cancel cannot cross caps`() {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val ctxA = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext(
            UUID.randomUUID(),
            captureSession = 1L,
            routeClass = "Headset",
        )
        val ctxB = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext(
            UUID.randomUUID(),
            captureSession = 2L,
            routeClass = "Headset",
        )
        fun complete(ctx: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext) = sink.completed(
            SegmentObservation.Completed(
                ctx,
                frames = listOf(ShortArray(8)),
                sampleRateHz = 16000,
                speechOnsetSample = 0,
                speechEndSample = 16,
                wakeState = SegmentObservation.WakeState.Detected,
            ),
        )
        assertTrue(sink.started(SegmentObservation.Started(ctxA, ShortArray(8), allowanceMetadataCapBytes = 111)))
        assertTrue(complete(ctxA)) // A retained with ITS OWN frozen cap
        assertTrue(sink.started(SegmentObservation.Started(ctxB, ShortArray(8), allowanceMetadataCapBytes = 222)))
        // STALE terminal for A while B owns the open prefix: must not erase or alter B's bound.
        sink.cancelled(SegmentObservation.Cancelled(ctxA, "stale-terminal"))
        assertTrue(complete(ctxB))
        assertEquals(111, sink.retainedCandidateForTest(ctxA.captureId)?.allowanceMetadataCap)
        assertEquals("the stale terminal must not cross caps onto B", 222, sink.retainedCandidateForTest(ctxB.captureId)?.allowanceMetadataCap)
    }

    @Test
    fun `a capture that was never allowance-admitted refuses publication fail-closed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = java.nio.file.Files.createTempDirectory("meta-missing").toFile()
        val store = ContributionStore(
            root,
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
            .provideVoiceSharingPolicyProvider(
                remoteConfig = mock(),
                featureProvider = mock(),
                ownership = owner,
                scope = CoroutineScope(Job().apply { cancel() }),
            )
        policy.applySnapshotForTest(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                enabled = true,
                activatedAtMonotonicMs = 1L,
                activationGeneration = 1L,
            ),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val captureId = UUID.randomUUID()
        val ctx = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext(
            captureId,
            captureSession = 1L,
            routeClass = "Bluetooth",
            issuedAuthority = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority(
                accountName = "a@x",
                sessionGeneration = 7L,
                credentialIncarnation = 1L,
                enrollmentEpoch = "epoch-1",
                acceptedDisclosureVersion = "1",
            ),
            asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
        )
        // NO allowance admission: the direct-sink start freezes NO bound (default null).
        assertTrue(
            sink.started(
                SegmentObservation.Started(ctx, ShortArray(800) { it.toShort() }, validSamples = 800),
            ),
        )
        assertNull("no frozen bound on a never-allowance-admitted capture", sink.retainedCandidateForTest(captureId)?.allowanceMetadataCap)
        sink.completed(
            SegmentObservation.Completed(
                ctx,
                frames = listOf(ShortArray(800) { (it + 1).toShort() }),
                sampleRateHz = 16000,
                speechOnsetSample = 0,
                speechEndSample = 1600,
                wakeState = SegmentObservation.WakeState.Detected,
            ),
        )
        val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
        val publishTrigger = VoiceUploadModule.provideRetentionPublishTrigger(
            sink = sink,
            producer = producer,
            accountStartup = startup,
            scope = scope,
        )
        publishTrigger.attach()
        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(captureId, transcript = "skip this ad", detectedLanguage = "en")))
        assertTrue(sink.joinOutcome(CollectionOutcome.Executed(captureId, dispatchedWithoutThrowing = true)))
        assertEquals(
            "missing metadata authority never means unconstrained: nothing is admitted or written",
            0,
            store.readyEntries().count { it.captureId == captureId.toString() },
        )
    }
}
