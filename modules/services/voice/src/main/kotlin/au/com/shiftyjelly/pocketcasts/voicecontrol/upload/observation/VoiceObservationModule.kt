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

    @Provides
    @Singleton
    fun provideSegmentObserver(
        authority: CollectionAuthority,
        sink: ContributionRetentionSink,
        collectionTx: CollectionAuthorityTransaction,
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
    ).also { observer ->
        sink.onRetentionEnded = { context -> observer.noteRetentionEnded(context) }
    }

    /**
     * Starts the PRODUCTION loss subscriptions (CollectionLossWatcher): account change,
     * enrollment/withdrawal post-commit hook, policy disable flow, exact lease-deadline purge.
     * Called from the app surface once at startup; idempotent inside the watcher.
     */
    // Spec 64ce2de6: the withdrawal marker's directory barrier is the android.system.Os adapter in
    // production, bound HERE at the Android construction boundary. A failed clear still reports
    // false and sets the admission latch; there is no unsupported-runtime success fallback.
    @Provides
    @Singleton
    fun provideWithdrawalIntentPersistence(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): WithdrawalIntentPersistence = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.FileWithdrawalIntentPersistence(
        context,
        directorySyncOverride = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.DirectorySync.PRODUCTION,
    )

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
