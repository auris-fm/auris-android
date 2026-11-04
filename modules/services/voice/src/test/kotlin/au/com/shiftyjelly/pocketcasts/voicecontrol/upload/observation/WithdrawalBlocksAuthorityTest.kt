package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancel
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
    ): CollectionAuthority = CollectionAuthority(
        accountStartup = startup,
        collectionTx = CollectionAuthorityTransaction(
            ownership = startup.ownerForHandoff(),
            sharedLock = startup.sharedCollectionLock(),
        ),
        localWithdrawalIntent = intent,
    )

    @Test
    fun `a recorded local stop makes the authority refuse even though everything else looks healthy`() {
        val persistence = InMemoryIntentPersistence()
        val intent = LocalWithdrawalIntentStore(persistence)
        intent.recordWithdrawalRequested()

        val sut = authority(startup(), intent)

        val decision = sut.decide()

        assertFalse(
            "with the user's stop recorded, the authority must NOT permit collection",
            decision.collectionPermitted,
        )
        assertTrue(
            "refusal must be a full revocation, so existing entries are purged rather than retained",
            decision.fullRevocation,
        )
    }

    @Test
    fun `admission true becomes false the moment the stop is recorded - a real transition`() {
        val startup = startup()
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        val sut = authority(startup, intent)

        // Establish a GENUINE enrolled session: the fixture's owner is signed in, and we publish the
        // authenticated projection the account layer parses, then mark the enrollment established for
        // this session exactly as the post-commit projection hook does.
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish(account = "a@x", session = 7L, bearer = "Bearer token")
        // The generation must be the OWNER's, not an invented one: beginOperation fences against the
        // owner session, so inventing 7L here made the fence refuse and the fixture NPE further down.
        val ownerSession = startup.authoritySnapshot().session
        val token = startup.state.sessionStarted(ownerSession)
        val json = VoiceUploadCompositionFixtures.authenticatedProjection("epoch-1")
        // ownerSession must be threaded through BOTH the issue and the apply: the fence compares it
        // against the live owner incarnation, so the default (Long.MIN_VALUE) is rejected.
        val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = ownerSession)!!
        startup.state.applyOutcome(token, ticket, json, ownerSession = ownerSession)
        // The tagged projection published by applyOutcome IS the enrollment evidence now.
        val projection = startup.state.projection.value!!

        // Fixture diagnostics: name WHICH precondition is unmet instead of only reporting that the
        // overall decision was false. A bare "fixture sanity" failure sent me guessing twice.
        val ownership = startup.authoritySnapshot()
        val liveProjection = startup.state.projection.value
        assertTrue(
            "fixture: a session must be live, got account=${ownership.accountName} session=${ownership.session}",
            ownership.accountName != null && ownership.session > 0L,
        )
        assertTrue(
            "fixture: projection must be enrolled+active with epoch+disclosure, got=$liveProjection",
            liveProjection?.voiceSharingEnrolled == true &&
                liveProjection.enrollmentEpoch != null &&
                liveProjection.acceptedDisclosureVersion != null &&
                liveProjection.withdrawalState == WithdrawalState.ACTIVE,
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
