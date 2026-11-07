package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
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
    fun provideSavedSharingChoiceStore(
        persistence: SharingChoicePersistence,
    ): SavedSharingChoiceStore = SavedSharingChoiceStore(persistence)

    @Provides
    @Singleton
    fun provideSharingChoicePersistence(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): SharingChoicePersistence = FileSharingChoicePersistence(
        context,
        directorySync = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.DirectorySync.PRODUCTION,
    )

    @Provides
    @Singleton
    fun provideCollectionAuthority(
        accountStartup: VoiceSharingAccountStartup,
        collectionTx: CollectionAuthorityTransaction,
        localWithdrawalIntent: LocalWithdrawalIntentStore,
        savedSharingChoice: SavedSharingChoiceStore,
    ): CollectionAuthority = CollectionAuthority(
        accountStartup,
        collectionTx,
        localWithdrawalIntent,
        savedSharingChoice,
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
    ): CollectionAuthorityTransaction = CollectionAuthorityTransaction(
        ownership = accountStartup.ownerForHandoff(),
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

    // Spec 64ce2de6: the withdrawal marker's directory barrier is the android.system.Os adapter in
    // production, bound HERE at the Android construction boundary. A failed clear still reports
    // false and sets the admission latch; there is no unsupported-runtime success fallback.

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
        // FINITE PRODUCTION OUTCOME WAIT (core c74d7e30 connected fix, reviewer 91e13f28): the
        // null selection closed EVERY production capture (CollectionStartup installs
        // outcomeWaitGuard = { outcomeFinalizer.limitsSelected }, and limitsSelected was false).
        // Selected 45 s as a BOUNDED WAIT (spec 0d59b1d5): it is a finite cap on how long a
        // retained capture may stay open without a terminal outcome — not a claim that every
        // healthy capture finishes inside it. The one measured full pipeline (report 0c9710ac,
        // under 25 s) shows the bound is a plausible order of magnitude; a capture that exceeds
        // the bound is finalized outcome_wait_expired and PUBLISHED, losing neither the audio
        // nor the evidence. The 60 s ceiling is the reviewed construction bound used by every
        // finalizer control. At expiry the retained capture is finalized as
        // outcome_wait_expired and published, never left open forever.
        waitLimits = PRODUCTION_OUTCOME_WAIT,
        scope = scope,
        // Same monotonic domain the sink composes by default (production clock).
        nowMs = { System.nanoTime() / 1_000_000L },
    )

    /** The ONE reviewed production selection; asserted verbatim by the outcome-wait control. */
    internal val PRODUCTION_OUTCOME_WAIT: OutcomeWaitLimits =
        OutcomeWaitLimits(selectedWaitMs = 45_000L, ceilingWaitMs = 60_000L)

    @Provides
    @Singleton
    fun provideWithdrawalIntentPersistence(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): WithdrawalIntentPersistence = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.FileWithdrawalIntentPersistence(
        context,
        directorySyncOverride = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.DirectorySync.PRODUCTION,
    )

    /**
     * Starts the PRODUCTION loss subscriptions (CollectionLossWatcher): account change and the
     * enrollment/withdrawal post-commit hook. Called from the app surface once at startup;
     * idempotent inside the watcher.
     */
    @Provides
    @Singleton
    fun provideLossWatcher(
        observer: SegmentObserver,
        authority: CollectionAuthority,
        accountStartup: VoiceSharingAccountStartup,
        syncAccountManager: au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager,
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): CollectionLossWatcher = CollectionLossWatcher(observer, authority, accountStartup, syncAccountManager, scope)
}

/** 64 MiB aggregate ceiling for upload-owned PCM (queue bounds refine this in Task 5). */
private const val DEFAULT_TOTAL_RETENTION_BYTES: Long = 64L * 1024 * 1024
