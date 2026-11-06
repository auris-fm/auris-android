package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * THE AUTHORITY ITSELF MUST CONSULT THE LOCAL STOP (reviewer 9156a8ea).
 *
 * [WithdrawalBlocksNewCollectionTest] proves the store's rule. This proves the rule is actually
 * WIRED — the failure the reviewer described was precisely that a boolean on a screen did nothing to
 * the decision that admits work. Testing the store in isolation cannot distinguish "enforced" from
 * "meticulously computed and ignored", which is the same mistake shape as asserting on an in-memory
 * candidate instead of the persisted record.
 *
 * Drives the REAL [CollectionAuthority] over a real account startup with a live session, so the
 * ONLY thing standing between a capture and retention is the local stop.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class WithdrawalBlocksAuthorityTest {

    private fun startup(): VoiceSharingAccountStartup = VoiceUploadCompositionFixtures.startup(ApplicationProvider.getApplicationContext())

    private fun authority(
        startup: VoiceSharingAccountStartup,
        intent: LocalWithdrawalIntentStore,
        choice: SavedSharingChoiceStore = choiceStore(),
    ): CollectionAuthority = CollectionAuthority(
        accountStartup = startup,
        collectionTx = CollectionAuthorityTransaction(
            ownership = startup.ownerForHandoff(),
            sharedLock = startup.sharedCollectionLock(),
        ),
        localWithdrawalIntent = intent,
        savedSharingChoice = choice,
    )

    @Test
    fun `a recorded local stop makes the authority refuse even though everything else looks healthy`() {
        val persistence = InMemoryIntentPersistence()
        val intent = LocalWithdrawalIntentStore(persistence)
        // The stop must be the REASON for the refusal, not one refusal among several: record a
        // healthy saved choice first so the gate would admit without the stop.
        val choice = choiceStore().apply { recordEnrolled("a@x", "epoch-1", "1") }
        intent.recordWithdrawalRequested()

        val startup = startup()
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish(account = "a@x", session = 7L, bearer = "Bearer token")
        val sut = authority(startup, intent, choice)

        val decision = sut.decide()

        assertFalse(
            "with the user's stop recorded, the authority must NOT permit collection",
            decision.collectionPermitted,
        )
        assertTrue(
            "refusal must be a full revocation, so existing entries are purged rather than retained",
            decision.fullRevocation,
        )
        assertEquals(
            "the fixed reason must name the LOCAL STOP, not a missing choice",
            AdmissionRefusalReason.LOCAL_STOP,
            decision.refusalReason,
        )
    }

    @Test
    fun `admission true becomes false the moment the stop is recorded - a real transition`() {
        val startup = startup()
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        val choice = choiceStore()
        val sut = authority(startup, intent, choice)

        // Establish a GENUINE enrolled session: the fixture's owner is signed in, and we record the
        // saved choice exactly as the account layer records a genuine server answer (spec 1af5e705).
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish(account = "a@x", session = 7L, bearer = "Bearer token")
        assertTrue("fixture: the saved choice must persist", choice.recordEnrolled("a@x", "epoch-1", "1"))

        // Fixture diagnostics: name WHICH precondition is unmet instead of only reporting that the
        // overall decision was false. A bare "fixture sanity" failure sent me guessing twice.
        val ownership = startup.authoritySnapshot()
        assertTrue(
            "fixture: a session must be live, got account=${ownership.accountName} session=${ownership.session}",
            ownership.accountName != null && ownership.session > 0L,
        )
        assertTrue(
            "fixture: a saved ENROLLED choice for this account must exist",
            choice.enrolledChoiceFor(ownership.accountName) != null,
        )

        val beforeStop = sut.decide()
        assertTrue(
            "fixture sanity: with a healthy enrolled session the authority permits collection",
            beforeStop.collectionPermitted,
        )

        intent.recordWithdrawalRequested()

        val afterStop = sut.decide()
        assertFalse(
            "recording the stop must flip the SAME authority to refuse, with nothing else changed - " +
                "this is the wiring the reviewer found missing",
            afterStop.collectionPermitted,
        )
    }
}
