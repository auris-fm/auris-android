package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.sync.TokenErrorNotification
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.QueueLimits
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadModule
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * THE FRESH-PROCESS COMMON-PATH CONTROL (spec 2aea78c3 / reviewer ae959a98).
 *
 * The device incident (2026-11-08): captures were ADMITTED (`voice-capture admission: accepted`)
 * and ASR'd, yet NO queue entry ever appeared and nothing logged why. The break was invisible
 * because every silent return on retention -> seal -> publish -> enqueue was silent, and because
 * it was unproven whether the ASR log IDs (which are minted `admittedId`s, never admission
 * evidence) corresponded to an admitted capture context at all.
 *
 * This control runs the FRESH process composition — no prior state, consent ENROLLED, session
 * generation as the previous process's login left it — through the production module stack, and:
 *  1. asserts CAPTURE-CONTEXT ADMISSION with its refusal reason in the failure message first
 *     (reviewer ae959a98: ASR IDs do not prove admission);
 *  2. joins ONLY `Recognized` — a NON-terminal outcome, so publication must happen at the
 *     OUTCOME-WAIT DEADLINE through the real finalizer, not at the join;
 *  3. asserts a DURABLE store entry after the deadline.
 *
 * If any silent return on the path fires (empty PCM, missing owner, unattached trigger, refused
 * owner section), the reason line now emitted in production names it; this control fails at the
 * point the device failed.
 *
 * CLAIM LIMIT (reviewer 30d46323 #4): this control ASSEMBLES the handoff (finalizer.start(),
 * trigger.attach(), injected Recognized) — it proves the composition works, NOT that the device
 * reaches it. The real engine and the application startup ordering are deliberately NOT
 * exercised here; the production startup/attachment log lines (voice-capture finalizer: started /
 * voice-capture publish: trigger-attached / voice-upload: trigger-started) are what distinguish
 * a missing finalizer from a missing publisher on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FreshProcessDeadlinePublicationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun realAuth(): SyncAccountManagerImpl = SyncAccountManagerImpl(
        tokenErrorNotification = TokenErrorNotification(mock(EventHorizon::class.java), context),
        accountManager = AccountManager.get(context),
    )

    /** A previous process's login: the platform account exists BEFORE this process starts. */
    private fun seedPlatformAccount(email: String = "user@example.com", token: String = "t1") {
        val am = AccountManager.get(context)
        val account = android.accounts.Account(email, AccountConstants.ACCOUNT_TYPE)
        am.addAccountExplicitly(account, "refresh-1", null)
        am.setAuthToken(account, AccountConstants.TOKEN_TYPE, token)
    }

    private fun startup(auth: SyncAccountManagerImpl): VoiceSharingAccountStartup {
        val retrofit = Retrofit.Builder().baseUrl("http://127.0.0.1:1/").client(OkHttpClient()).build()
        return VoiceSharingAccountStartup(
            ownership = auth,
            context = context,
            retrofit = Lazy { retrofit },
            aurisToken = mock(AurisTokenProvider::class.java),
            savedSharingChoice = SavedSharingChoiceStore(InMemoryChoicePersistence()),
        )
    }

    @Test
    fun `a fresh process with consent on queues an ASR'd segment at the outcome deadline`() {
        seedPlatformAccount()
        val auth = realAuth()
        val choice = SavedSharingChoiceStore(InMemoryChoicePersistence())
        assertTrue(choice.recordEnrolled("user@example.com", "epoch-1", "1"))

        val startup = startup(auth)
        startup.binding.install()

        val store = ContributionStore(
            Files.createTempDirectory("fresh-process-deadline").toFile(),
            QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
            clock = System::currentTimeMillis,
            jitter = { 0L },
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            // FRESH composition, exactly the production module providers — no shared test sink.
            val sink = VoiceObservationModule.provideRetentionSink(VoiceObservationModule.provideCollectionBudget())
            val finalizer = CaptureOutcomeFinalizer(
                sink = sink,
                waitLimits = OutcomeWaitLimits(selectedWaitMs = 500L, ceilingWaitMs = 60_000L),
                scope = scope,
                sleepMs = { kotlinx.coroutines.delay(it) },
                nowMs = { System.nanoTime() / 1_000_000L },
            )
            finalizer.start()
            val tx = CollectionAuthorityTransaction(startup.ownerForHandoff(), startup.sharedCollectionLock())
            val observer = VoiceObservationModule.provideSegmentObserver(
                CollectionAuthority(
                    accountStartup = startup,
                    collectionTx = tx,
                    localWithdrawalIntent = LocalWithdrawalIntentStore(InMemoryIntentPersistence()),
                    savedSharingChoice = choice,
                ),
                sink,
                tx,
            )
            val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
            VoiceUploadModule.provideRetentionPublishTrigger(
                sink = sink,
                producer = producer,
                accountStartup = startup,
                savedSharingChoice = choice,
                scope = scope,
            ).attach()

            val frames = listOf(ShortArray(800) { (it + 1).toShort() })
            // (1) ADMISSION IS ASSERTED FIRST, with its refusal reason: a null here is an
            // admission failure, NOT a downstream publish failure (reviewer ae959a98).
            val admission = observer.speechStartedAdmitting(
                captureSession = 1L,
                routeClass = "Headset",
                producerSize = 800,
                asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
            )
            assertNotNull(
                "the fresh-process consented start must ADMIT a capture context (admission is the first gate; a refusal here is upstream of everything else)",
                admission,
            )
            assertTrue(observer.adoptFirstChunk(admission!!, frames.first()))
            observer.wakeCompleted(admission.context, 0, 1600, SegmentObservation.WakeState.Detected) {
                frames to 16000
            }

            // (2) ASR produced text but the turn never dispatched an intent: Recognized is a
            // KNOWN, NON-terminal observation. Publication must come from the OUTCOME-WAIT
            // DEADLINE, the same deferred path the device's segments must take.
            assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(admission.context.captureId, transcript = "hello there", detectedLanguage = "en")))

            // (3) Past the 500 ms deadline the finalizer seals and the trigger publishes.
            runBlocking { delay(1_500L) }
            val captureId = admission.context.captureId.toString()
            assertTrue(
                "the fresh-process ASR'd segment must reach a DURABLE store entry at the outcome deadline (captureId=$captureId); the production reason lines name the silent return if not",
                store.readyEntries().any { it.captureId == captureId },
            )
            store.readyEntries().forEach { entry ->
                store.durableCapture(entry.captureId)?.owned?.settle(1_000L)
            }
        } finally {
            scope.cancel()
        }
    }
}
