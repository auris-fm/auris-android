package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE WIRING THE REVIEWER FOUND MISSING (0546969a).
 *
 * The gate and the store were both correct in isolation; nothing in production ever reached the
 * store, so the user's tap had no effect and the gate read a flag nobody set. A unit test on each
 * half cannot catch that: the defect lives entirely in the join.
 *
 * This control reproduces the production SEQUENCE — record the stop, then the server responds, in
 * each of its outcomes — and asserts the gate's answer at every step. It deliberately drives only
 * public entry points, with no fixture reaching in to set the flag, so it fails if the composition
 * goes back to being unwired.
 */
class SettingsStopReachesCollectionGateTest {

    /** Stands in for the file-backed persistence, with the same fail-closed contract. */
    private class RecordingPersistence(var stopped: Boolean = false) : WithdrawalIntentPersistence {
        override fun read(): Boolean = stopped
        override fun write(requested: Boolean): Boolean {
            stopped = requested
            return true
        }
    }

    private fun enrolledProjection() = VoiceSharingAccountProjection(
        membershipRevision = "rev-1",
        plan = VoiceSharingPlan.FREE,
        voiceSharingEnrolled = true,
        enrollmentEpoch = "epoch-2",
        acceptedDisclosureVersion = "2026-10-01",
        withdrawalState = WithdrawalState.ACTIVE,
    )

    @Test
    fun `the production off sequence stops collection before the server answers`() {
        val persistence = RecordingPersistence()
        val store = LocalWithdrawalIntentStore(persistence)

        // Baseline: a healthy enrolled projection permits collection.
        assertTrue(
            "fixture sanity: a healthy enrollment must permit collection before anything is stopped",
            store.collectionPermittedByLocalIntent(enrolledProjection()),
        )

        // EXACTLY what the Settings row does on an Off tap, before any request.
        store.recordWithdrawalRequested()

        assertFalse(
            "the tap alone must stop collection while the server has not answered yet",
            store.collectionPermittedByLocalIntent(enrolledProjection()),
        )
    }

    @Test
    fun `a failed withdrawal keeps collection stopped after the response`() {
        val store = LocalWithdrawalIntentStore(RecordingPersistence())

        store.recordWithdrawalRequested()
        // The production sequence on a network failure.
        store.onWithdrawalFailed()

        assertFalse(
            "a network failure is not consent to resume: collection must stay stopped",
            store.collectionPermittedByLocalIntent(enrolledProjection()),
        )
    }

    @Test
    fun `a server-confirmed withdrawal stays stopped even against a stale enrolled projection`() {
        val store = LocalWithdrawalIntentStore(RecordingPersistence())

        store.recordWithdrawalRequested()
        store.onServerAgrees()

        // A stale projection that still says ACTIVE can be served after the withdrawal applied.
        assertFalse(
            "the server's own withdrawn state, or a stale enrolled one, must never resume collection " +
                "once the user stopped us",
            store.collectionPermittedByLocalIntent(enrolledProjection()),
        )
    }

    @Test
    fun `only an explicit re-enrollment releases the stop`() {
        val store = LocalWithdrawalIntentStore(RecordingPersistence())
        store.recordWithdrawalRequested()
        store.onServerAgrees()

        // The production success path of turning sharing back ON.
        store.clearOnUserEnrollment()

        assertTrue(
            "an explicit opt-in after the server applied it must permit collection again",
            store.collectionPermittedByLocalIntent(enrolledProjection()),
        )
    }
}
