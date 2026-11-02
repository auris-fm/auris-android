package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Composition of the collection-retention boundary (plan Task 3): the live authority is read
 * from the ACTUAL authorities — the lease-evaluated policy snapshot and the owner's account
 * session — never reconstructed from cached gate decisions.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceObservationModule {

    /**
     * The durable local stop (reviewer 9156a8ea). File-backed so the user's decision survives a
     * process restart; the authority consults it at every decision, so a pending or failed
     * withdrawal cannot silently resume collection.
     */
    @Provides
    @Singleton
    fun provideLocalWithdrawalIntent(
        // The INTERFACE: the concrete class lost its @Inject constructor when production binding
        // moved to provideWithdrawalIntentPersistence below; requesting the concrete type here made
        // the app-level Hilt graph unresolvable (caught by :app:packageDebug).
        persistence: WithdrawalIntentPersistence,
    ): LocalWithdrawalIntentStore = LocalWithdrawalIntentStore(persistence)

    @Provides
    @Singleton
    fun provideCollectionAuthority(
        policyProvider: VoiceSharingPolicyProvider,
        accountStartup: VoiceSharingAccountStartup,
        collectionTx: CollectionAuthorityTransaction,
        localWithdrawalIntent: LocalWithdrawalIntentStore,
    ): CollectionAuthority = CollectionAuthority(
        policyProvider,
        accountStartup,
        collectionTx,
        localWithdrawalIntent,
    )

    /** Aggregate collection-retention budget: one shared cap across open/ready/queued owners. */
    @Provides
    @Singleton
    fun provideCollectionBudget(): CollectionBudget = CollectionBudget(DEFAULT_TOTAL_RETENTION_BYTES)

    @Provides
    @Singleton
    fun provideEngineCollectionAuthority(
        authority: CollectionAuthority,
    ): () -> AuthorityDecision = authority::decideLossSignalWrapped

    @Provides
    @Singleton
    fun provideCollectionTx(
        accountStartup: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup,
        policyProvider: VoiceSharingPolicyProvider,
    ): CollectionAuthorityTransaction = CollectionAuthorityTransaction(
        ownership = accountStartup.ownerForHandoff(),
        policy = object : PolicySnapshotReader {
            // ONE atomic immutable-snapshot read per acquisition (no 3-call getter).
            override fun snapshot() = policyProvider.publishedPolicy.value
        },
        // THE single shared primitive: same object as the owner's collectionSharedLock, injected
        // through the concrete SyncAccountManagerImpl binding resolved by the startup.
        sharedLock = accountStartup.sharedCollectionLock(),
    )

    @Provides
    @Singleton
    fun provideRetentionSink(budget: CollectionBudget): ContributionRetentionSink = ContributionRetentionSink(budget)

    /**
     * Production allowance authority binding. FAIL-CLOSED: no production limits, window or
     * provisioning authority is selected, so the LIFECYCLE SLOT (reviewer c5d94af4 #4) starts at
     * [au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.UnavailableCaptureAllowance]
     * and refuses EVERY capture before any sink reservation or engine allocation. The lifecycle
     * slot is the composition that retires the bound authority on account/policy/lease/rebind
     * lifecycle inputs (AllowanceRetirementWatcher); binding a real owner-backed authority is
     * still a separate reviewed change - never an implicit default swap. Binding a real
     * owner-backed authority through [au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance
     * .CaptureAllowanceLifecycle.bind] remains the reviewed activation step; production values
     * stay unavailable.
     */
    @Provides
    @Singleton
    fun provideCaptureAllowanceLifecycle(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceLifecycle = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceLifecycle()

    @Provides
    @Singleton
    fun provideCaptureAllowanceAuthority(
        lifecycle: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceLifecycle,
    ): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceAuthority = lifecycle

    @Provides
    @Singleton
    fun provideSegmentObserver(
        authority: CollectionAuthority,
        sink: ContributionRetentionSink,
        collectionTx: CollectionAuthorityTransaction,
        allowance: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceAuthority,
    ): SegmentObserver = SegmentObserver(
        gate = CollectionGate { decision -> decision.shouldRetainNewWork },
        sink = sink,
        // Loss/handoff decisions enter through the collection fence BEFORE any observer monitor:
        // the wrapped decide acquires the fence internally; callers here never hold a monitor.
        authority = authority::decideLossSignalWrapped,
        issueAuthority = authority::issueAuthorityCoherentLockedIndependent,
        handoff = HandoffCoordinator(
            collectionTx = collectionTx,
            validateIssuedLocked = authority::validateIssuedAuthorityLocked,
        ),
        fenceGate = object : FenceGate {
            override fun <R> gate(block: () -> R): R = collectionTx.transaction(block)
        },
        allowance = allowance,
    ).also { observer ->
        sink.onRetentionEnded = { context -> observer.noteRetentionEnded(context) }
    }

    // Spec 64ce2de6: the withdrawal marker's directory barrier is the android.system.Os adapter in
    // production, bound HERE at the Android construction boundary. A failed clear still reports
    // false and sets the admission latch; there is no unsupported-runtime success fallback.

    /**
     * EXPLICIT provider for the retirement watcher (reviewer 9d29473a #3): the watcher's clock
     * and sleep seams are function types WITHOUT Dagger bindings — Kotlin default arguments do
     * not supply them — so this singleton is composed HERE with the production clock
     * (SystemClock.elapsedRealtime / kotlinx delay). Tests inject a virtual clock through the
     * plain constructor.
     */
    @Provides
    @Singleton
    fun provideAllowanceRetirementWatcher(
        lifecycle: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceLifecycle,
        policyProvider: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider,
        accountStartup: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup,
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceRetirementWatcher = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceRetirementWatcher(
        lifecycle = lifecycle,
        policyProvider = policyProvider,
        accountStartup = accountStartup,
        scope = scope,
    )

    /**
     * BATCH B bounded outcome finalization (reviewer c5d94af4 #1): composed with NO selected
     * wait — production limits/window/provisioning are unavailable, so no outcome-wait
     * selection exists. THIS CLOSES CONTRIBUTION ADMISSION fail-closed (spec cf1b9809):
     * CollectionStartup wires the observer's admission guard to [CaptureOutcomeFinalizer.limitsSelected],
     * so a null selection refuses every capture rather than admitting captures with an
     * unbounded wait. Selecting a wait is a separate reviewed change constructing
     * OutcomeWaitLimits explicitly; never an implicit default.
     */
    @Provides
    @Singleton
    fun provideCaptureOutcomeFinalizer(
        sink: ContributionRetentionSink,
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer(
        sink = sink,
        waitLimits = null,
        scope = scope,
        // Same monotonic domain the sink composes by default (production clock).
        nowMs = { System.nanoTime() / 1_000_000L },
    )

    @Provides
    @Singleton
    fun provideWithdrawalIntentPersistence(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): WithdrawalIntentPersistence = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.FileWithdrawalIntentPersistence(
        context,
        directorySyncOverride = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.DirectorySync.PRODUCTION,
    )

    /**
     * Starts the PRODUCTION loss subscriptions (CollectionLossWatcher): account change,
     * enrollment/withdrawal post-commit hook, policy disable flow, exact lease-deadline purge.
     * Called from the app surface once at startup; idempotent inside the watcher.
     */
    @Provides
    @Singleton
    fun provideLossWatcher(
        observer: SegmentObserver,
        authority: CollectionAuthority,
        policyProvider: VoiceSharingPolicyProvider,
        accountStartup: VoiceSharingAccountStartup,
        syncAccountManager: au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager,
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): CollectionLossWatcher = CollectionLossWatcher(observer, authority, policyProvider, accountStartup, syncAccountManager, scope)
}

/** 64 MiB aggregate ceiling for upload-owned PCM (queue bounds refine this in Task 5). */
private const val DEFAULT_TOTAL_RETENTION_BYTES: Long = 64L * 1024 * 1024
