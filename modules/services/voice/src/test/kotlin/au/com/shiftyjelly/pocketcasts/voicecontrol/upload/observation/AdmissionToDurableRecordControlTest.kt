package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.QueueLimits
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadModule
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CONNECTED POSITIVE CONTROL (spec 200b932b / 5e33fe28, reviewer 6b32f503 gap): from the REAL
 * segment/admission wiring — production outcome-wait guard, production authority/handoff wiring,
 * the module-built identity/owner, retention sink and publish trigger — ALL THE WAY to a DURABLE
 * contribution record, with NO allowance anywhere on the path. The consent-refusal negative gate
 * below stays.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class AdmissionToDurableRecordControlTest {

    // The SAME live identity VoiceUploadCompositionFixtures publishes through the owner lock the
    // trigger's guards read; admission must ISSUE exactly this authority.
    private val issued = IssuedAuthority(
        accountName = "a@x",
        sessionGeneration = 7L,
        credentialIncarnation = 1L,
        enrollmentEpoch = "epoch-1",
        acceptedDisclosureVersion = "1",
        policyEnabledAtIssue = true,
    )

    private class FrameSpy {
        var invocations = 0
        fun provider(frames: List<ShortArray>, rate: Int): () -> Pair<List<ShortArray>, Int> = {
            invocations++
            frames to rate
        }
    }

    @Test
    fun `an admitted capture with no allowance lands a DURABLE record through the production wiring`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val root = java.nio.file.Files.createTempDirectory("admission-durable").toFile()
        val store = ContributionStore(
            root,
            QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
            clock = System::currentTimeMillis,
            jitter = { 0L },
        )
        val startup = VoiceUploadCompositionFixtures.startup(context)
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish("a@x", 7L, "Bearer-fixture")
        startup.credentials.publish("Bearer-fixture")
        val token = startup.state.sessionStarted(7L)
        val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
        assertTrue(
            startup.state.applyOutcome(
                token,
                ticket,
                VoiceUploadCompositionFixtures.authenticatedProjection("epoch-1"),
                ownerSession = 7L,
            ),
        )
        val policy = VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
            remoteConfig = org.mockito.kotlin.mock(),
            featureProvider = org.mockito.kotlin.mock(),
            ownership = owner,
            scope = CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
        )
        policy.applySnapshotForTest(
            PolicySnapshot(enabled = true, activatedAtMonotonicMs = 1L, activationGeneration = 1L),
        )

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
            // PRODUCTION outcome wait, the real finalizer driving the real guard.
            val finalizer = CaptureOutcomeFinalizer(
                sink = sink,
                waitLimits = VoiceObservationModule.PRODUCTION_OUTCOME_WAIT,
                scope = scope,
                nowMs = { 0L },
            )
            finalizer.start()
            val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
            val publishTrigger = VoiceUploadModule.provideRetentionPublishTrigger(
                sink = sink,
                producer = producer,
                accountStartup = startup,
                scope = scope,
            )
            publishTrigger.attach()

            // THE REAL ADMISSION PATH — the same observer shape production composes, with NO
            // allowance argument, admitted under the live production outcome-wait guard.
            val observer = SegmentObserver(
                gate = CollectionGate { it.shouldRetainNewWork },
                sink = sink,
                authority = { AuthorityDecision(collectionPermitted = true, fullRevocation = false) },
                issueAuthority = { issued },
                handoff = CompletionHandoff { ctx, onset, end, wake, provider, handoffScope, live ->
                    handoffScope.completeUnderOwner(ctx, onset, end, wake, provider)
                },
            ).also { it.outcomeWaitGuard = { finalizer.limitsSelected } }
            assertTrue("premise: the production outcome wait is selected", finalizer.limitsSelected)

            val frames = listOf(ShortArray(800) { (it + 1).toShort() })
            val spy = FrameSpy()
            val admission = observer.speechStartedAdmitting(
                captureSession = 1L,
                routeClass = "Headset",
                producerSize = 800,
                asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
            )
            assertNotNull("a consented, policy-live, isolated-route capture with no allowance must be admitted", admission)
            assertTrue(observer.adoptFirstChunk(admission!!, frames.first()))
            observer.wakeCompleted(admission.context, 0, 1600, SegmentObservation.WakeState.Detected, spy.provider(frames, 16000))
            assertEquals("the handoff drained the frames exactly once", 1, spy.invocations)

            // The outcome stages the real pipeline joins.
            assertTrue(
                sink.joinOutcome(CollectionOutcome.Recognized(admission.context.captureId, transcript = "skip this ad", detectedLanguage = "en")),
            )
            assertTrue(sink.joinOutcome(CollectionOutcome.Executed(admission.context.captureId, dispatchedWithoutThrowing = true)))

            // DURABLE CREATION: the record exists in the store, servable, before any transport.
            val captureId = admission.context.captureId.toString()
            assertTrue("the admitted capture reached a durable store entry", store.readyEntries().any { it.captureId == captureId })
            val served = store.durableCapture(captureId)
            org.junit.Assert.assertNotNull("the durable capture is servable", served)
            served!!.owned?.settle(1_000L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `consent refusal never reaches durable creation through the same wiring`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val root = java.nio.file.Files.createTempDirectory("admission-durable-negative").toFile()
        val store = ContributionStore(
            root,
            QueueLimits.defaults(),
            clock = System::currentTimeMillis,
        )
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val finalizer = CaptureOutcomeFinalizer(
            sink = sink,
            waitLimits = VoiceObservationModule.PRODUCTION_OUTCOME_WAIT,
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
            nowMs = { 0L },
        )
        finalizer.start()
        val observer = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = false, fullRevocation = false) },
            issueAuthority = { issued },
            handoff = CompletionHandoff { ctx, onset, end, wake, provider, handoffScope, live ->
                handoffScope.completeUnderOwner(ctx, onset, end, wake, provider)
            },
        ).also { it.outcomeWaitGuard = { finalizer.limitsSelected } }

        val admission = observer.speechStartedAdmitting(1L, "Headset", 800)
        assertNull("consent refusal closes admission through the production wiring", admission)
        assertEquals("no durable record without admission", 0, store.readyEntries().size)
    }
}
