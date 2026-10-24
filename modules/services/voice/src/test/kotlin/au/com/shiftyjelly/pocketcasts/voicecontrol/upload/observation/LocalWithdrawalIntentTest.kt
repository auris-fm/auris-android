package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DURABLE LOCAL WITHDRAWAL INTENT (reviewer 9156a8ea).
 *
 * The defect this closes: purging the queue removes entries and bumps the generation, but the
 * authority still derived permission from the SERVER projection. While a withdrawal is in flight or
 * has failed, that projection still says `voiceSharingEnrolled == true`, so the very next captured
 * segment acquired the NEW generation and the user who just turned sharing off kept contributing.
 *
 * The rule now: the user's decision to stop is itself durable state, and it overrides the server's
 * stale view until the server agrees. Reopening the screen or restarting the process does not undo
 * it — which is why the store must persist rather than hold it in memory.
 */
class LocalWithdrawalIntentTest {

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

    private fun store() = LocalWithdrawalIntentStore(InMemoryIntentPersistence())

    @Test
    fun `a stored withdrawal intent overrides a server projection that still says enrolled`() {
        val intent = store()
        intent.recordWithdrawalRequested()

        assertFalse(
            "the user's decision to stop must override the server's stale 'enrolled' view; " +
                "otherwise a pending or failed withdrawal keeps collecting",
            intent.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `without a withdrawal intent a healthy enrollment is permitted`() {
        assertTrue(store().collectionPermittedByLocalIntent(projection()))
    }

    @Test
    fun `a stop whose write FAILS still stops collection in this session`() {
        // Reviewer 7a2e10a6. The failure latch used to live in the Settings fragment, where nothing
        // read it, so a failed stop left the gate open for the rest of the session while the disk held
        // an older PERMITTED marker. The latch now lives here - in the object the collection gate
        // actually asks - and this asserts the GATE's answer, not a flag's value.
        val persistence = FailingIntentPersistence()
        val intent = LocalWithdrawalIntentStore(persistence)

        // Precondition: before any stop, an enrolled projection DOES permit collection. Without this
        // the assertion below could pass for the wrong reason.
        assertTrue(
            "precondition: collection must be permitted before the stop, or this control is vacuous",
            intent.collectionPermittedByLocalIntent(projection()),
        )

        assertFalse("the stop write must report failure", intent.recordWithdrawalRequested())
        assertFalse(
            "after a FAILED stop write the collection gate must still refuse admission - the user " +
                "asked to stop, and the disk not knowing it is not permission",
            intent.collectionPermittedByLocalIntent(projection()),
        )

        // A DURABLE clear is the only thing that releases the latch.
        persistence.failWrites = false
        assertTrue("a successful clear must report success", intent.clearOnUserEnrollment())
        assertTrue(
            "an explicit durable opt-in must release the latch",
            intent.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `a failed CLEAR does not release the latch`() {
        val persistence = FailingIntentPersistence()
        val intent = LocalWithdrawalIntentStore(persistence)
        intent.recordWithdrawalRequested()
        assertFalse("the clear write must report failure", intent.clearOnUserEnrollment())
        assertFalse(
            "a failed clear must never release the stop",
            intent.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `the intent survives process recreation`() {
        val persistence = InMemoryIntentPersistence()
        LocalWithdrawalIntentStore(persistence).recordWithdrawalRequested()

        // A NEW store over the same storage stands in for a fresh process.
        val afterRestart = LocalWithdrawalIntentStore(persistence)
        assertFalse(
            "a restart must not resurrect collection the user turned off",
            afterRestart.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `a server-confirmed withdrawal clears the intent and the server remains authoritative`() {
        val intent = store()
        intent.recordWithdrawalRequested()
        // The server has now agreed: projection shows withdrawn.
        intent.onServerAgrees()

        assertFalse(
            "after the server agrees, the withdrawn projection still blocks collection",
            intent.collectionPermittedByLocalIntent(
                projection(enrolled = false, withdrawal = WithdrawalState.WITHDRAWN),
            ),
        )
    }

    @Test
    fun `re-enrolling explicitly clears the withdrawal intent`() {
        val intent = store()
        intent.recordWithdrawalRequested()
        intent.clearOnUserEnrollment()

        assertTrue(
            "an explicit new opt-in is a fresh user action and must permit collection again",
            intent.collectionPermittedByLocalIntent(projection()),
        )
    }

    @Test
    fun `a failed withdrawal keeps the intent so collection stays stopped`() {
        val intent = store()
        intent.recordWithdrawalRequested()
        intent.onWithdrawalFailed()

        assertFalse(
            "a failed server withdrawal must not silently resume collection",
            intent.collectionPermittedByLocalIntent(projection()),
        )
    }
}

/** Test double for the persistence seam; stands in for file/preference storage. */
class InMemoryIntentPersistence : WithdrawalIntentPersistence {
    var stored: Boolean = false

    override fun read(): Boolean = stored

    override fun write(requested: Boolean): Boolean {
        stored = requested
        return true
    }
}

/** Writes fail and say so; reads report whatever was last successfully written. */
class FailingIntentPersistence(var failWrites: Boolean = true) : WithdrawalIntentPersistence {
    private var stored: Boolean = false

    override fun read(): Boolean = stored

    override fun write(requested: Boolean): Boolean {
        if (failWrites) return false
        stored = requested
        return true
    }
}
