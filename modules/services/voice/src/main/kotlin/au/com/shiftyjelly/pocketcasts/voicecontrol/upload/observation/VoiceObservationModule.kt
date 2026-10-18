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

    @Provides
    @Singleton
    fun provideCollectionAuthority(
        policyProvider: VoiceSharingPolicyProvider,
        accountStartup: VoiceSharingAccountStartup,
    ): CollectionAuthority = CollectionAuthority(policyProvider, accountStartup)

    /** Aggregate collection-retention budget: one shared cap across open/ready/queued owners. */
    @Provides
    @Singleton
    fun provideCollectionBudget(): CollectionBudget = CollectionBudget(DEFAULT_TOTAL_RETENTION_BYTES)

    @Provides
    @Singleton
    fun provideRetentionSink(budget: CollectionBudget): ContributionRetentionSink = ContributionRetentionSink(budget)

    @Provides
    @Singleton
    fun provideSegmentObserver(
        authority: CollectionAuthority,
        sink: ContributionRetentionSink,
    ): SegmentObserver = SegmentObserver(
        gate = CollectionGate { decision -> decision.shouldRetainNewWork },
        sink = sink,
        authority = authority::decide,
        issueAuthority = authority::issueAuthority,
        handoffValidator = HandoffValidator { issued -> authority.validateIssuedAuthority(issued) },
    ).also { observer ->
        sink.onRetentionEnded = { context -> observer.noteRetentionEnded(context) }
    }

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
