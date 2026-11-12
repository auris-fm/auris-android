package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
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
    ): SharingChoicePersistence = SharedPreferencesChoicePersistence(context)

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

    /**
     * The publish path: a sealed capture becomes a queued recording.
     *
     * Attached here rather than in the sink's construction so the sink itself stays free of the
     * record and queue layers, and so a test can drive the sink with a different publisher.
     */
    @Provides
    @Singleton
    fun provideRetentionSink(
        publisher: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.RecordingPublisher,
    ): ContributionRetentionSink = ContributionRetentionSink(
        lossEventObserver = { event, count ->
            // Reason plus running count: bounded bookkeeping in the sink, the trail itself lives
            // in the persisted log, not in singleton memory.
            LogBuffer.i(RETENTION_LOG_TAG, "retention loss: $event (total $count)")
        },
    ).apply {
        onReadyForPublication = { sealed -> publisher.publish(sealed) }
    }

    @Provides
    @Singleton
    fun provideRecordingPublisher(
        queue: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.FolderQueue,
        settings: au.com.shiftyjelly.pocketcasts.preferences.Settings,
        wakeSink: dagger.Lazy<CollectionStartup>,
    ): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.RecordingPublisher = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.RecordingPublisher(
        queue = queue,
        // Real binary provenance: the record names the build that produced it.
        appVersion = { settings.getVersion() },
        appBuild = { settings.getVersionCode().toString() },
        // THE PUBLISH WAKE (S4): a queued recording wakes the uploader to drain immediately.
        onPublished = { wakeSink.get().publishWake?.invoke() },
    )

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

    // The withdrawal marker is one boolean in the platform settings store (SharedPreferences,
    // 2026-11-12): no directory barrier, no latch. A failed commit is logged; the in-memory
    // value stands, so admission never wedges on storage.

    /** BATCH B bounded outcome finalization with THE production 45 s wait (see the constant). */
    @Provides
    @Singleton
    fun provideCaptureOutcomeFinalizer(
        sink: ContributionRetentionSink,
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer(
        sink = sink,
        waitLimitsMs = PRODUCTION_OUTCOME_WAIT_MS,
        scope = scope,
        // Same monotonic domain the sink composes by default (production clock).
        nowMs = { System.nanoTime() / 1_000_000L },
    )

    @Provides
    @Singleton
    fun provideWithdrawalIntentPersistence(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): WithdrawalIntentPersistence = SharedPreferencesWithdrawalIntentPersistence(context)

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
private const val RETENTION_LOG_TAG = "VoiceRetention"
