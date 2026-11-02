package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
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
class WithdrawalBlocksNewCollectionTest {

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
            intent.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `a capture arriving after the withdrawal FAILED is still refused`() {
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        intent.recordWithdrawalRequested()
        intent.onWithdrawalFailed()

        assertFalse(
            "a failed withdrawal is a network problem, not consent to keep recording",
            intent.collectionPermittedByLocalIntent(projection()),
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
            reopened.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `a process restart does not resurrect collection`() {
        val persistence = InMemoryIntentPersistence()
        LocalWithdrawalIntentStore(persistence).recordWithdrawalRequested()

        val afterRestart = LocalWithdrawalIntentStore(persistence)
        assertFalse(
            "killing the app must not re-enable a feature the user turned off",
            afterRestart.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `collection resumes only after an explicit new opt-in, and only with a healthy projection`() {
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        intent.recordWithdrawalRequested()
        intent.clearOnUserEnrollment()

        assertTrue(
            "a fresh opt-in plus a healthy projection must permit collection again",
            intent.collectionPermittedByLocalIntent(projection()),
        )
        // ...but the local stop never GRANTS anything on its own.
        assertFalse(
            "clearing the local stop must not permit collection for a withdrawn account",
            intent.collectionPermittedByLocalIntent(
                projection(enrolled = false, withdrawal = WithdrawalState.WITHDRAWN),
            ),
        )
    }

    @Test
    fun `with no projection at all the local intent still refuses`() {
        val intent = LocalWithdrawalIntentStore(InMemoryIntentPersistence())
        intent.clearOnUserEnrollment()
        assertFalse(
            "absence of a projection must fail closed, not read as permission",
            intent.collectionPermittedByLocalIntent(null),
        )
    }
}
