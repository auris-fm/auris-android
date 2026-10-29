package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.PrepaidTestAllowance
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * THE OBSERVED-DISABLE BOUNDARY (Task 7, docs/plans/android/voice-recording-uploads.md).
 *
 * Spec: an observed disable denies new retention and deletes pending audio, metadata and partial
 * buffers. ORDINARY LISTENING CLOSURE discards only the partial contribution and preserves completed
 * entries. The two causes must not be interchangeable.
 *
 * THE REAL WATCHER IS CONSTRUCTED HERE. An earlier draft of this class reproduced the watcher's
 * collector body inline. That is the substitution plan verification gate 3 forbids ("no
 * uncalled-collector-helper test") and it would have passed while proving nothing about the wiring:
 * if `CollectionLossWatcher.start()` stopped subscribing to the policy, an inline copy would stay
 * green. This class therefore builds the genuine `CollectionLossWatcher` from the shared composition
 * fixtures (real `VoiceSharingAccountStartup` over the Robolectric application, real
 * `CredentialOwnershipOwner`), calls its real `start()`, and drives the flip through the provider's
 * real published-policy flow. Only the Firebase config seam is doubled, matching the existing
 * provider test's established pattern.
 *
 * Assertions are on the RESULTING RETENTION STATE of the production `ContributionRetentionSink`,
 * not on a purge notification a fake could fabricate.
 *
 * Nothing here enables collection outside this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ObservedDisableBoundaryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // A per-test scope holding the real watcher's collectors. Cancelled in tearDown so no collector
    // outlives the test and leaks into the next one (reviewer 05e8da53).
    private lateinit var scope: CoroutineScope
    private lateinit var app: Application
    private lateinit var sharedLock: Any

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        sharedLock = Any()
    }

    @After
    fun tearDown() {
        // Cancel the collectors the real watcher launched, THEN restore the main dispatcher.
        if (::scope.isInitialized) scope.cancel()
        Dispatchers.resetMain()
    }

    private fun realSink(): ContributionRetentionSink = ContributionRetentionSink(CollectionBudget(64L * 1024 * 1024))

    /**
     * Provider built the way the existing provider test builds it: a REAL provider whose only double
     * is the Firebase config seam. The published flow is therefore production code.
     */
    private fun provider(enabled: Boolean): VoiceSharingPolicyProvider {
        val config = mock<FirebaseRemoteConfig>()
        whenever(config.getBoolean("voice_sharing_enabled")).thenReturn(enabled)
        whenever(config.getString("voice_sharing_enabled_source"))
            .thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_REMOTE.toString())
        return VoiceSharingPolicyProvider(
            remoteConfig = config,
            featureProvider = mock(),
            scope = scope,
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            sleepMs = { },
            sharedCollectionLock = sharedLock,
        )
    }

    private fun startup(): VoiceSharingAccountStartup = VoiceUploadCompositionFixtures.startup(app)

    /**
     * The activation stamp must be a REAL monotonic instant, not an invented small number. The
     * watcher arms an exact lease deadline from this stamp; a fabricated past value is already
     * expired, so start() fires `lease-deadline` and purges the candidate before the disable under
     * test — which would make this control pass or fail for entirely the wrong reason.
     */
    private fun snapshot(enabled: Boolean) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
        enabled = enabled,
        activatedAtMonotonicMs = if (enabled) android.os.SystemClock.elapsedRealtime() else 0L,
        activationGeneration = if (enabled) 1L else 0L,
    )

    /**
     * The REAL watcher, with a real startup and a real observer over the production retention sink.
     * `SyncAccountManager` is an interface, so it is the one collaborator doubled for account
     * changes, which are not the subject of these controls.
     */
    private fun realWatcher(
        provider: VoiceSharingPolicyProvider,
        startup: VoiceSharingAccountStartup,
        observer: SegmentObserver,
    ): CollectionLossWatcher {
        val accounts = mock<SyncAccountManager>()
        whenever(accounts.emailFlow()).thenReturn(flowOf(null))
        val collectionTx = CollectionAuthorityTransaction(
            ownership = startup.ownerForHandoff(),
            policy = object : PolicySnapshotReader {
                override fun snapshot() = provider.publishedPolicy.value
            },
            sharedLock = startup.sharedCollectionLock(),
        )
        return CollectionLossWatcher(
            observer = observer,
            authority = CollectionAuthority(
                policyProvider = provider,
                accountStartup = startup,
                collectionTx = collectionTx,
                localWithdrawalIntent = LocalWithdrawalIntentStore(NoWithdrawal()),
            ),
            policyProvider = provider,
            accountStartup = startup,
            syncAccountManager = accounts,
            scope = scope,
        )
    }

    /** No withdrawal recorded: these controls are about an observed policy disable, not a stop. */
    private class NoWithdrawal : WithdrawalIntentPersistence {
        override fun read(): Boolean = false

        override fun write(requested: Boolean): Boolean = true
    }

    private fun observerFor(
        sink: ContributionRetentionSink,
        live: () -> AuthorityDecision,
    ) = SegmentObserver(
        gate = CollectionGate { it.shouldRetainNewWork },
        sink = sink,
        authority = live,
        allowance = PrepaidTestAllowance(),
    )

    /** Retains REAL PCM through the production handoff, so retainedBytes() is genuinely nonzero. */
    private fun retainRealPcm(sink: ContributionRetentionSink, context: CaptureContext): Boolean = sink.completed(
        SegmentObservation.Completed(
            context = context,
            frames = listOf(ShortArray(1600) { i -> (i % 1000).toShort() }),
            sampleRateHz = 16000,
            speechOnsetSample = 0,
            speechEndSample = 1600,
            wakeState = SegmentObservation.WakeState.Detected,
        ),
    )

    /**
     * An observed disable, delivered through the real watcher subscription, deletes REAL retained
     * PCM. The enabled state is established BEFORE subscribing and re-asserted immediately before
     * the disable: the provider's compiled default is disabled, so an initial flow emission could
     * otherwise purge the candidate and make a later assertion pass for the wrong reason.
     */
    @Test
    fun `the real watcher deletes retained PCM when the published policy disables`() {
        val sink = realSink()
        val context = CaptureContext(UUID.randomUUID(), 1L, "Headset")

        // REAL retention: actual PCM through the production handoff, not an empty test candidate.
        assertTrue("real PCM is retained", retainRealPcm(sink, context))
        val retainedBefore = sink.retainedBytes()
        assertTrue("the retained amount is genuinely nonzero before the flip", retainedBefore > 0L)

        val observer = observerFor(sink) { AuthorityDecision(collectionPermitted = true, fullRevocation = false) }
        val policy = provider(enabled = true)
        // Let the provider's own lifecycle init (which launches a failing refresh against a mock
        // feature provider and therefore publishes DISABLED) SETTLE FIRST, then establish enabled.
        // Injecting before init would just be overwritten, and the watcher would then arm its lease
        // deadline off a disabled snapshot and purge on `lease-deadline` before the flip.
        dispatcher.scheduler.advanceUntilIdle()
        policy.applySnapshotForTest(snapshot(enabled = true))
        val startup = startup()
        // A REAL account is published first. `emailFlow()` emits immediately on subscribe, and the
        // watcher routes it through reconcileAndNotifySessionEnd(); with no account in the owner that
        // ends the session and fires account-change, which would purge the candidate on a fixture
        // artifact rather than on the disable under test.
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish("me@example.com", 1L, "Bearer-established")
        val watcher = realWatcher(policy, startup, observer)
        watcher.start()
        // runCurrent, NOT advanceUntilIdle: the watcher arms an exact lease deadline with a virtual
        // delay, and advanceUntilIdle fast-forwards past the 15-minute lease so `lease-deadline`
        // fires and purges before the disable under test is ever published.
        dispatcher.scheduler.runCurrent()

        // The candidate must still be there right up to the flip, or the disable is not what removed it.
        assertNotNull(
            "the retained PCM survives establishing the enabled state and start()",
            sink.retainedCandidateForTest(context.captureId),
        )
        assertEquals("the retained bytes are unchanged by start()", retainedBefore, sink.retainedBytes())

        // THE LIVE EVENT: a disable published through the provider's own flow. The WATCHER'S
        // collector reacts; this test never calls revoked() itself.
        policy.applySnapshotForTest(snapshot(enabled = false))
        dispatcher.scheduler.runCurrent()

        assertNull(
            "the retained PCM is GONE after the observed disable",
            sink.retainedCandidateForTest(context.captureId),
        )
        assertEquals("no retained bytes survive the disable", 0L, sink.retainedBytes())
    }

    /**
     * ORDINARY CLOSURE must do BOTH halves: discard the open PARTIAL and PRESERVE the completed
     * entry. Asserting only survival would pass for a closure that did nothing.
     */
    @Test
    fun `ordinary closure discards the partial and preserves the completed entry`() {
        val sink = realSink()
        val completedContext = CaptureContext(UUID.randomUUID(), 1L, "Headset")
        val partialContext = CaptureContext(UUID.randomUUID(), 1L, "Headset")

        assertTrue("real PCM is retained", retainRealPcm(sink, completedContext))
        assertTrue(
            "the partial opens on the real sink",
            sink.started(SegmentObservation.Started(partialContext, ShortArray(1600) { 7 })),
        )
        assertTrue("the partial holds charged bytes", sink.prefixBytesChargedForTest(partialContext) > 0L)

        sink.cancelled(SegmentObservation.Cancelled(partialContext, "listening-closed"))

        assertEquals(
            "the partial's charge is released by ordinary closure",
            0L,
            sink.prefixBytesChargedForTest(partialContext),
        )
        assertTrue(
            "the discard is recorded as a partial discard",
            sink.lossEvents.any { it.startsWith("partial-discard:") },
        )
        assertNotNull(
            "the completed entry SURVIVES ordinary closure",
            sink.retainedCandidateForTest(completedContext.captureId),
        )
        assertTrue("the completed PCM is still charged", sink.retainedBytes() > 0L)
    }
}
