package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
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
 * Drives the REAL [CollectionAuthority] over a real account startup, with the policy flag ON and a
 * live session, so the ONLY thing standing between a capture and retention is the local stop.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class WithdrawalBlocksAuthorityTest {

    private fun startup(): VoiceSharingAccountStartup = VoiceUploadCompositionFixtures.startup(ApplicationProvider.getApplicationContext())

    /**
     * The MODULE's own policy provider, composed exactly as the real module composes it, then given
     * an explicit enabled snapshot through its test seam. Its refresh loop is never started (the
     * scope is cancelled), so nothing can silently overwrite the fixture mid-test.
     */
    private fun enabledPolicy(startup: VoiceSharingAccountStartup): VoiceSharingPolicyProvider = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule
        .provideVoiceSharingPolicyProvider(
            remoteConfig = org.mockito.kotlin.mock(),
            featureProvider = org.mockito.kotlin.mock(),
            ownership = startup.ownerForHandoff(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
        )
        .also {
            // The module wires nowMs to elapsedRealtime(), so the activation stamp must be a REAL
            // recent monotonic instant; an invented 1_000L is always outside the lease and reads
            // back as disabled.
            it.applySnapshotForTest(
                PolicySnapshot(
                    enabled = true,
                    activationGeneration = 3,
                    activatedAtMonotonicMs = android.os.SystemClock.elapsedRealtime(),
                ),
            )
        }

    /**
     * A policy provider stand-in is not enough to make the authority admit work, because the
     * authority also requires a CURRENT-SESSION projection with an epoch and disclosure. Rather than
     * fabricate all of that — which would test my fixture, not the code — this control asserts the
     * narrower, decisive property: with the intent recorded, admission is false regardless of what
     * the other inputs say.
     */
    private fun authority(
        startup: VoiceSharingAccountStartup,
        intent: LocalWithdrawalIntentStore,
        policy: VoiceSharingPolicyProvider = enabledPolicy(startup),
    ): CollectionAuthority = CollectionAuthority(
        policyProvider = policy,
        accountStartup = startup,
        collectionTx = CollectionAuthorityTransaction(
            ownership = startup.ownerForHandoff(),
            policy = object : PolicySnapshotReader {
                override fun snapshot() = policy.snapshot()
            },
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
        // ONE provider instance: constructing a second one for the diagnostic gave a different
        // snapshot holder, so it always read the default disabled state.
        val policy = enabledPolicy(startup)
        val sut = authority(startup, intent, policy)

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
        val projection = startup.state.projection.value!!
        // In production CollectionLossWatcher installs this listener; installing it here mirrors the
        // real composition instead of calling the authority's method directly, so the control
        // exercises the same path the app does.
        startup.enrollmentEvidenceListener = { p, session -> sut.markEnrollmentEstablished(p, session) }
        startup.enrollmentEvidenceListener?.invoke(projection, ownerSession)

        // Fixture diagnostics: name WHICH precondition is unmet instead of only reporting that the
        // overall decision was false. A bare "fixture sanity" failure sent me guessing twice.
        val policyEnabled = policy.snapshot().enabled
        val ownership = startup.authoritySnapshot()
        val liveProjection = startup.state.projection.value
        assertTrue(
            "fixture: policy must read enabled, got=$policyEnabled",
            policyEnabled,
        )
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
