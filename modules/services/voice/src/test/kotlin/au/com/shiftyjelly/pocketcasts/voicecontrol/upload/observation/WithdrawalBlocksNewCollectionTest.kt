package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FAILURE MODE THIS CLOSES (reviewer 9156a8ea), stated as the sequence that actually happens:
 *
 * 1. The user turns sharing off.
 * 2. We purge the queue — but purging only removes entries that exist at that instant and bumps the
 *    generation, so it does NOT by itself prevent the next capture from admitting itself.
 * 3. The withdrawal request is in flight, or it failed.
 * 4. The server projection still says `voiceSharingEnrolled == true` (it has not heard from us).
 * 5. A new segment completes and asks the authority for permission.
 *
 * Without durable local intent, step 5 is ADMITTED, and a user who explicitly asked to stop keeps
 * contributing. These controls pin down that the answer is now NO at every point in that sequence,
 * and that it survives the two ways intent gets lost: closing the screen and restarting the app.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class WithdrawalBlocksNewCollectionTest {

    private fun startup(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures.startup(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    )

    private fun authority(
        startup: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup,
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

    private fun projection(
        enrolled: Boolean = true,
        withdrawal: WithdrawalState = WithdrawalState.ACTIVE,
        epoch: String? = "epoch-1",
    ) = VoiceSharingAccountProjection(
        membershipRevision = "rev-1",
        plan = VoiceSharingPlan.FREE,
        voiceSharingEnrolled = enrolled,
        enrollmentEpoch = epoch,
        acceptedDisclosureVersion = "2026-10-01",
        withdrawalState = withdrawal,
    )

    @Test
    fun `a capture arriving while the withdrawal is still in flight is refused`() {
        val persistence = InMemoryIntentPersistence()
        val intent = LocalWithdrawalIntentStore(persistence)
        // Step 1: the user turned sharing off. The request has not come back yet.
        intent.recordWithdrawalRequested()

        // Steps 4-5: the server still reports enrolled, and a new segment asks for permission.
        assertFalse(
            "a capture during a pending withdrawal must be refused; the server projection is stale " +
                "by definition while our own request is in flight",
            intent.collectionPermittedByLocalIntent(),
        )
    }

    @Test
    fun `a capture arriving after the withdrawal FAILED is still refused`() {
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        intent.recordWithdrawalRequested()
        intent.onWithdrawalFailed()

        assertFalse(
            "a failed withdrawal is a network problem, not consent to keep recording",
            intent.collectionPermittedByLocalIntent(),
        )
    }

    @Test
    fun `closing and reopening the screen does not resurrect collection`() {
        val persistence = InMemoryIntentPersistence()
        LocalWithdrawalIntentStore(persistence).recordWithdrawalRequested()

        // Reopening builds a fresh store over the SAME storage, exactly as a new screen would.
        val reopened = LocalWithdrawalIntentStore(persistence)
        assertFalse(
            "the screen must read the durable stop, not derive the toggle from the unchanged " +
                "server projection",
            reopened.collectionPermittedByLocalIntent(),
        )
    }

    @Test
    fun `a process restart does not resurrect collection`() {
        val persistence = InMemoryIntentPersistence()
        LocalWithdrawalIntentStore(persistence).recordWithdrawalRequested()

        val afterRestart = LocalWithdrawalIntentStore(persistence)
        assertFalse(
            "killing the app must not re-enable a feature the user turned off",
            afterRestart.collectionPermittedByLocalIntent(),
        )
    }

    /**
     * Contract after spec 1af5e705: the intent store answers ONLY the stop veto. Consent lives in
     * the saved account-owned choice, so "resumes only after an explicit new opt-in" is asserted at
     * the authority level: clearing the veto alone must NOT admit, and a saved opt-in must.
     */
    @Test
    fun `collection resumes only after an explicit new opt-in`() {
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        intent.recordWithdrawalRequested()
        intent.clearOnUserEnrollment()

        assertTrue(
            "an explicit new opt-in clears the local veto",
            intent.collectionPermittedByLocalIntent(),
        )

        // The veto being gone is NOT consent: with no saved choice the authority still refuses.
        val startup = startup()
        val owner = startup.ownerForHandoff() as au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish(account = "a@x", session = 7L, bearer = "Bearer token")
        val refused = authority(startup, intent).decide()
        assertFalse(
            "clearing the local stop must not by itself permit collection",
            refused.collectionPermitted,
        )
        assertEquals(
            "the refusal must name the missing saved choice",
            AdmissionRefusalReason.NO_SAVED_CHOICE,
            refused.refusalReason,
        )

        // Recording the user's genuine opt-in (what the account layer does on an Applied answer)
        // is what admits - the veto and the choice TOGETHER.
        val choice = choiceStore().apply { recordEnrolled("a@x", "epoch-1", "1") }
        val permitted = authority(startup, intent, choice).decide()
        assertTrue(
            "a cleared veto plus a saved opt-in permits collection",
            permitted.collectionPermitted,
        )
    }

    @Test
    fun `an account with no saved choice refuses and names the missing choice`() {
        val startup = startup()
        val owner = startup.ownerForHandoff() as au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish(account = "a@x", session = 7L, bearer = "Bearer token")
        val sut = authority(startup, LocalWithdrawalIntentStore(InMemoryIntentPersistence()))
        val decision = sut.decide()
        assertFalse(
            "a session without a durably saved opt-in must fail closed",
            decision.collectionPermitted,
        )
        assertEquals(
            "absence of a saved choice must be NAMED, not read as permission",
            AdmissionRefusalReason.NO_SAVED_CHOICE,
            decision.refusalReason,
        )
    }
}
