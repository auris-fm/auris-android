package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CONNECTED POSITIVE CONTROL (spec 200b932b / 5e33fe28, reviewer 6b32f503 + b24c3dcd): from the
 * MODULE-BUILT production stack — `VoiceObservationModule.provideSegmentObserver` (real
 * `CollectionAuthority` over the real account startup/withdrawal store, real
 * `CollectionAuthorityTransaction` fence, real `HandoffCoordinator`), the module's
 * `PRODUCTION_OUTCOME_WAIT` finalizer with `CollectionStartup`'s real admission-guard wiring, the
 * module's retention sink and publish trigger over the real producer/store — ALL THE WAY to a
 * DURABLE, servable contribution record, with NO allowance anywhere on the path. External inputs
 * only: owner identity, a live session with its authenticated enrollment projection. The
 * consent-refusal negative runs through the SAME connected stack.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class AdmissionToDurableRecordControlTest {

    /** Mirrors VoiceObservationModule.provideCollectionTx over the SAME startup. */
    private fun collectionTx(startup: VoiceSharingAccountStartup) = CollectionAuthorityTransaction(
        ownership = startup.ownerForHandoff(),
        sharedLock = startup.sharedCollectionLock(),
    )

    /**
     * Establishes the external inputs: owner identity published, live session, authenticated
     * enrollment projection applied, and the enrollment evidence marked exactly as the production
     * post-commit hook (CollectionLossWatcher's listener) does.
     */
    private fun establishLiveEnrolledSession(startup: VoiceSharingAccountStartup, authority: CollectionAuthority) {
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish(account = "a@x", session = 7L, bearer = "Bearer-fixture")
        startup.credentials.publish("Bearer-fixture")
        val ownerSession = startup.authoritySnapshot().session
        val token = startup.state.sessionStarted(ownerSession)
        val json = VoiceUploadCompositionFixtures.authenticatedProjection("epoch-1")
        val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = ownerSession)!!
        assertTrue(startup.state.applyOutcome(token, ticket, json, ownerSession = ownerSession))
        // The tagged projection published by applyOutcome IS the enrollment evidence now.
    }

    private fun newStore(name: String): ContributionStore = ContributionStore(
        java.nio.file.Files.createTempDirectory(name).toFile(),
        QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
        clock = System::currentTimeMillis,
        jitter = { 0L },
    )

    @Test
    fun `an admitted capture with no allowance lands a DURABLE record through the module-built production stack`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = newStore("admission-durable")
        val startup = VoiceUploadCompositionFixtures.startup(context)
        val tx = collectionTx(startup)
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        val authority = CollectionAuthority(
            accountStartup = startup,
            collectionTx = tx,
            localWithdrawalIntent = intent,
        )
        establishLiveEnrolledSession(startup, authority)

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            // Module-built pieces only (VoiceObservationModule / VoiceUploadModule providers).
            val sink = VoiceObservationModule.provideRetentionSink(
                VoiceObservationModule.provideCollectionBudget(),
            )
            val finalizer = VoiceObservationModule.provideCaptureOutcomeFinalizer(sink, scope)
            val observer = VoiceObservationModule.provideSegmentObserver(authority, sink, tx)
            val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
            VoiceUploadModule.provideRetentionPublishTrigger(
                sink = sink,
                producer = producer,
                accountStartup = startup,
                scope = scope,
            ).attach()

            val frames = listOf(ShortArray(800) { (it + 1).toShort() })
            val admission = observer.speechStartedAdmitting(
                captureSession = 1L,
                routeClass = "Headset",
                producerSize = 800,
                asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
            )
            assertNotNull("a consented, enrolled, isolated-route capture with no allowance must be admitted", admission)
            assertTrue(observer.adoptFirstChunk(admission!!, frames.first()))
            // The REAL HandoffCoordinator: coherent revalidation under the fence, then completion.
            observer.wakeCompleted(admission.context, 0, 1600, SegmentObservation.WakeState.Detected) {
                frames to 16000
            }

            assertTrue(
                sink.joinOutcome(CollectionOutcome.Recognized(admission.context.captureId, transcript = "skip this ad", detectedLanguage = "en")),
            )
            assertTrue(sink.joinOutcome(CollectionOutcome.Executed(admission.context.captureId, dispatchedWithoutThrowing = true)))

            // DURABLE CREATION: servable record + queue row before any transport.
            val captureId = admission.context.captureId.toString()
            assertTrue("the admitted capture reached a durable store entry", store.readyEntries().any { it.captureId == captureId })
            val served = store.durableCapture(captureId)
            assertNotNull("the durable capture is servable", served)
            served!!.owned?.settle(1_000L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `consent refusal through the SAME module-built stack never reaches durable creation`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = newStore("admission-durable-negative")
        val startup = VoiceUploadCompositionFixtures.startup(context)
        val tx = collectionTx(startup)
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        val authority = CollectionAuthority(
            accountStartup = startup,
            collectionTx = tx,
            localWithdrawalIntent = intent,
        )
        establishLiveEnrolledSession(startup, authority)
        // THE NEGATIVE INPUT: the user's local stop, through the real durable store the
        // module-built authority consults at every decision.
        intent.recordWithdrawalRequested()

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val sink = VoiceObservationModule.provideRetentionSink(
                VoiceObservationModule.provideCollectionBudget(),
            )
            val finalizer = VoiceObservationModule.provideCaptureOutcomeFinalizer(sink, scope)
            val observer = VoiceObservationModule.provideSegmentObserver(authority, sink, tx)
            val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
            VoiceUploadModule.provideRetentionPublishTrigger(
                sink = sink,
                producer = producer,
                accountStartup = startup,
                scope = scope,
            ).attach()
            val admission = observer.speechStartedAdmitting(
                captureSession = 1L,
                routeClass = "Headset",
                producerSize = 800,
                asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
            )
            assertEquals("the local stop closes admission through the production stack", null, admission)
            assertEquals("no durable record without admission", 0, store.readyEntries().size)
        } finally {
            scope.cancel()
        }
    }
}
