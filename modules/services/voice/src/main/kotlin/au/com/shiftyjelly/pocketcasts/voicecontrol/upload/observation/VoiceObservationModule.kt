package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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

    @Provides
    @Singleton
    fun provideRetentionSink(): ContributionRetentionSink = ContributionRetentionSink()

    @Provides
    @Singleton
    fun provideSegmentObserver(
        authority: CollectionAuthority,
        sink: ContributionRetentionSink,
    ): SegmentObserver = SegmentObserver(
        gate = CollectionGate { decision -> decision.shouldRetainNewWork },
        sink = sink,
        authority = authority::decide,
    )
}
