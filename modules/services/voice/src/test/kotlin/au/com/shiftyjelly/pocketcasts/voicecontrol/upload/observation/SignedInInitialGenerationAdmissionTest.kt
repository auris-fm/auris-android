package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * THE GENUINE SIGNED-IN INITIAL GENERATION ADMITS AND PERSISTS (spec ea8be666).
 *
 * Measured on Pixel 2026-11-06 (Merlin's ordinary test): a signed-in cold start with a saved
 * ENROLLED choice was refused `gate-off reason=no-session` on EVERY sample. Root (spec-confirmed
 * against the real owner source): `SyncAccountManagerImpl` counters initialize at ZERO; the FIRST
 * `installOwnershipBinding` adopts the existing account/token and calls `onSessionStarted(counter)`
 * WITHOUT incrementing — so generation 0 IS a real signed-in cold start. Two leftover
 * positive-readiness gates refused it: `CollectionAuthority.decideLocked` (`session > 0L`) and
 * `ContributionCaptureProducer` (`session <= 0`), the second silently nulling persistence AFTER
 * admission. This control traverses the REAL owner and the module-built production stack from
 * initial-generation-0 admission all the way to a DURABLE store entry; no fake counter, no
 * relogin. The equality fences (issue/handoff/publication binding work to the actual generation
 * value, 0 included) are deliberately untouched and covered by their own controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignedInInitialGenerationAdmissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun realAuth(): SyncAccountManagerImpl = SyncAccountManagerImpl(
        tokenErrorNotification = TokenErrorNotification(mock(EventHorizon::class.java), context),
        accountManager = AccountManager.get(context),
    )

    /**
     * Seed the account on the PLATFORM the way a PREVIOUS process's login left it, WITHOUT going
     * through the fresh owner's `addAccount` (which is a session TRANSITION and would increment
     * the counter). A fresh process over a pre-existing account is exactly the cold start whose
     * genuine initial generation is zero.
     */
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

    private fun authority(startup: VoiceSharingAccountStartup, choice: SavedSharingChoiceStore): CollectionAuthority = CollectionAuthority(
        accountStartup = startup,
        collectionTx = CollectionAuthorityTransaction(
            ownership = startup.ownerForHandoff(),
            sharedLock = startup.sharedCollectionLock(),
        ),
        localWithdrawalIntent = LocalWithdrawalIntentStore(InMemoryIntentPersistence()),
        savedSharingChoice = choice,
    )

    @Test
    fun `a signed-in account at its genuine initial generation zero admits and lands a durable production entry`() {
        seedPlatformAccount()
        val auth = realAuth()
        val choice = SavedSharingChoiceStore(InMemoryChoicePersistence())
        assertTrue(choice.recordEnrolled("user@example.com", "epoch-1", "1"))

        val startup = startup(auth)
        // The genuine signed-in cold start: the binding's FIRST install adopts the platform
        // account/token WITHOUT a session transition.
        startup.binding.install()

        val snapshot = startup.authoritySnapshot()
        assertNotNull("precondition: the signed-in account must be readable", snapshot.accountName)
        assertEquals(
            "a genuine signed-in cold start reads session generation ZERO - the value the old gates refused",
            0L,
            snapshot.session,
        )

        val decision = authority(startup, choice).decide()
        assertTrue(
            "a signed-in account with a saved ENROLLED choice and no local stop must ADMIT at generation 0; got ${decision.refusalReason}",
            decision.collectionPermitted,
        )

        // TRAVERSE THE PRODUCTION STACK (spec ea8be666): admission alone is not enough - the
        // producer's own identity gate must not silently null the persistence at generation 0.
        val store = ContributionStore(
            java.nio.file.Files.createTempDirectory("gen0-durable").toFile(),
            QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
            clock = System::currentTimeMillis,
            jitter = { 0L },
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val sink = VoiceObservationModule.provideRetentionSink(VoiceObservationModule.provideCollectionBudget())
            val finalizer = VoiceObservationModule.provideCaptureOutcomeFinalizer(sink, scope)
            val tx = CollectionAuthorityTransaction(startup.ownerForHandoff(), startup.sharedCollectionLock())
            val observer = VoiceObservationModule.provideSegmentObserver(authority(startup, choice), sink, tx)
            // The production-shaped producer: identity read from the REAL owner snapshot (session 0).
            val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
            VoiceUploadModule.provideRetentionPublishTrigger(
                sink = sink,
                producer = producer,
                accountStartup = startup,
                savedSharingChoice = choice,
                scope = scope,
            ).attach()

            val frames = listOf(ShortArray(800) { (it + 1).toShort() })
            val admission = observer.speechStartedAdmitting(
                captureSession = 1L,
                routeClass = "Headset",
                producerSize = 800,
                asrModel = ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
            )
            assertNotNull("the genuine generation-0 signed-in start must be admitted", admission)
            assertTrue(observer.adoptFirstChunk(admission!!, frames.first()))
            observer.wakeCompleted(admission.context, 0, 1600, SegmentObservation.WakeState.Detected) {
                frames to 16000
            }
            assertTrue(
                sink.joinOutcome(CollectionOutcome.Recognized(admission.context.captureId, transcript = "hello", detectedLanguage = "en")),
            )
            assertTrue(
                sink.joinOutcome(CollectionOutcome.Executed(admission.context.captureId, dispatchedWithoutThrowing = true)),
            )
            val captureId = admission.context.captureId.toString()
            assertTrue(
                "the generation-0 admitted capture must reach a DURABLE store entry",
                store.readyEntries().any { it.captureId == captureId },
            )
            assertNotNull(store.durableCapture(captureId))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a genuinely absent account still refuses with no-session`() {
        val auth = realAuth()
        val choice = SavedSharingChoiceStore(InMemoryChoicePersistence())
        val startup = startup(auth)
        startup.binding.install()

        val decision = authority(startup, choice).decide()
        assertTrue(!decision.collectionPermitted)
        assertEquals(AdmissionRefusalReason.NO_SESSION, decision.refusalReason)
    }
}
