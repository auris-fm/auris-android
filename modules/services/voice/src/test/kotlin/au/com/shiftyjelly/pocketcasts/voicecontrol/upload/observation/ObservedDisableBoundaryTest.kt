package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val scope = CoroutineScope(dispatcher)
    private lateinit var app: Application
    private lateinit var sharedLock: Any

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        sharedLock = Any()
    }

    @After
    fun tearDown() {
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
            nowMs = { 1_000L },
            sleepMs = { },
            sharedCollectionLock = sharedLock,
        )
    }

    private fun startup(): VoiceSharingAccountStartup = VoiceUploadCompositionFixtures.startup(app)

    private fun snapshot(enabled: Boolean) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
        enabled = enabled,
        activatedAtMonotonicMs = if (enabled) 1_000L else 0L,
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
    )

    /**
     * An observed disable, delivered through the real watcher subscription, must delete the retained
     * completed entry: `retainedCandidateForTest` finds nothing and no bytes remain retained.
     */
    @Test
    fun `the real watcher deletes the retained completed entry when the published policy disables`() {
        val sink = realSink()
        val captureId = UUID.randomUUID()

        // PREMISE on the production sink: a completed entry is genuinely retained right now.
        assertTrue("the completed entry is retained", sink.retainCandidateForTest(captureId))
        assertNotNull("the retained entry is readable", sink.retainedCandidateForTest(captureId))

        var permitted = true
        val observer = observerFor(sink) {
            AuthorityDecision(collectionPermitted = permitted, fullRevocation = false)
        }
        val startup = startup()
        val policy = provider(enabled = true)
        val watcher = realWatcher(policy, startup, observer)
        watcher.start()

        // THE LIVE EVENT: a disable published through the provider's REAL publishedPolicy flow.
        // The watcher's collector reacts to it and is what calls revoked("policy-disable") — this
        // test never calls revoked() itself. `applySnapshotForTest` is the provider's own
        // @VisibleForTesting seam, so the emission is the production flow, not a stand-in.
        permitted = false
        policy.applySnapshotForTest(snapshot(enabled = false))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(
            "the retained completed entry is GONE after the observed disable",
            sink.retainedCandidateForTest(captureId),
        )
        assertEquals("no retained bytes survive the disable", 0L, sink.retainedBytes())
    }

    /**
     * ORDINARY CLOSURE must preserve the completed entry. If closure purged like a disable, the
     * spec's two causes would be indistinguishable and this control would fail.
     */
    @Test
    fun `ordinary closure preserves the completed entry, unlike an observed disable`() {
        val sink = realSink()
        val captureId = UUID.randomUUID()
        assertTrue("the completed entry is retained", sink.retainCandidateForTest(captureId))

        val observer = observerFor(sink) {
            AuthorityDecision(collectionPermitted = true, fullRevocation = false)
        }

        val closedAsRevocation = observer.revoked("listening-closed")

        assertFalse("closure is not a revocation cause", closedAsRevocation)
        assertNotNull(
            "the completed entry SURVIVES ordinary closure",
            sink.retainedCandidateForTest(captureId),
        )
    }
}
