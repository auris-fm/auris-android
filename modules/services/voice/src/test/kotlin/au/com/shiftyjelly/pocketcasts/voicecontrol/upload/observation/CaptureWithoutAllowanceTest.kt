package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val LIVE_CONSENT = AuthorityDecision(collectionPermitted = true, fullRevocation = false)
private val ISSUED_AUTHORITY = IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "disclosure-1")

/**
 * Core c74d7e30, spec §"Actual upload usage", plan delta item 2:
 *
 * > Remove allowance fetch/provision/readiness gates, prepaid usage charging and allocation-identity
 * > dependencies from contribution capture. [...] A fresh install with no allowance state must work.
 *
 * This replaces a reviewed invariant rather than adding to it. The allowance KDoc said capture
 * "remains fail-closed: until this path provisions a batch, UnavailableCaptureAllowance still refuses
 * every capture". After the delta that sentence is false by design: consent, account/enrollment and
 * fresh policy admit a capture, and the daily limit is metered server-side at acceptance.
 *
 * THE CONTROL IS DELIBERATELY ANTI-FIXTURE. It passes no allowance authority at all, so the observer
 * falls back to its own default. On the pre-delta tree that default is `UnavailableCaptureAllowance`,
 * which refuses every capture — so this test is RED for exactly the right reason: capture is blocked
 * by the ABSENCE OF AN ALLOWANCE rather than by consent or policy. A control that injected a
 * permissive fixture would pass before and after and prove nothing (reviewer ce3eae70).
 */
class CaptureWithoutAllowanceTest {

    private fun observer(
        decision: AuthorityDecision = LIVE_CONSENT,
    ): Pair<SegmentObserver, ContributionRetentionSink> {
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val observer = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { decision },
            issueAuthority = { ISSUED_AUTHORITY },
            // NO `allowance =` ARGUMENT. This is the fresh-install shape the delta requires.
        )
        return observer to sink
    }

    @Test
    fun `consent refusal still closes admission under the production outcome-wait guard`() {
        // The production guard (CollectionStartup: outcomeWaitGuard = { limitsSelected }, with the
        // module's FINITE PRODUCTION_OUTCOME_WAIT) admits; a WITHDRAWN/revoked consent decision
        // must STILL refuse — the wait guard never overrides consent/policy (spec e6b28827 keeps
        // consent/policy checks).
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 20))
        val finalizer = CaptureOutcomeFinalizer(
            sink = sink,
            waitLimits = VoiceObservationModule.PRODUCTION_OUTCOME_WAIT,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob()),
            nowMs = { 0L },
        )
        val refusedObserver = SegmentObserver(
            gate = CollectionGate { it.shouldRetainNewWork },
            sink = sink,
            authority = { AuthorityDecision(collectionPermitted = false, fullRevocation = false) },
            issueAuthority = { ISSUED_AUTHORITY },
        )
        refusedObserver.outcomeWaitGuard = { finalizer.limitsSelected }
        assertTrue("premise: the production guard admits", finalizer.limitsSelected)
        val admission = refusedObserver.speechStartedAdmitting(
            captureSession = 1L,
            routeClass = "Headset",
            producerSize = 8,
        )
        assertEquals("consent refusal closes admission even with the production guard live", null, admission)
    }

    @Test
    fun `a consented capture is admitted with NO allowance authority composed`() {
        val (observer, _) = observer()

        val admission = observer.speechStartedAdmitting(
            captureSession = 1L,
            routeClass = "Headset",
            producerSize = 8,
        )

        assertNotNull(
            "capture must be admitted without any allowance state; refusing here is the " +
                "pre-delta fail-closed rule that c74d7e30 removes",
            admission,
        )
        assertTrue("the retained clip bound must still be finite", admission!!.selectedMaxClipBytes > 0)
    }

    @Test
    fun `a fresh install with no legacy allowance files still admits`() {
        // "A fresh install with no allowance state must work" — no identity store, no ledger,
        // no no_backup/voice_allowance directory exists in this test at all.
        val (observer, sink) = observer()

        val admission = observer.speechStartedAdmitting(1L, "Headset", 8)
        assertNotNull("a fresh install must capture", admission)

        // And the admission reaches the REAL sink rather than a permissive stub.
        assertEquals("no bytes retained until audio actually arrives", 0, sink.retainedCount())
        assertTrue(
            "the capture is open on the real sink",
            observer.isOpen(admission!!.context.captureId),
        )
    }

    @Test
    fun `a closed consent gate still refuses capture after the allowance is gone`() {
        val (observer, _) = observer(
            AuthorityDecision(collectionPermitted = false, fullRevocation = false),
        )

        assertEquals(
            "removing the allowance must not remove the consent gate",
            null,
            observer.speechStartedAdmitting(1L, "Headset", 8),
        )
    }

    @Test
    fun `a full revocation still refuses capture after the allowance is gone`() {
        val (observer, _) = observer(
            AuthorityDecision(collectionPermitted = true, fullRevocation = true),
        )

        assertEquals(
            "removing the allowance must not remove the revocation gate",
            null,
            observer.speechStartedAdmitting(1L, "Headset", 8),
        )
    }
}
