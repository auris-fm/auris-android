package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * DI for the settings shell (plan Task 7: "wire through existing voice-module Hilt modules"). The
 * ViewModel composes the SAME production singletons the collection boundary uses — the live policy
 * provider, the account startup's projection + enrollment controller, the local withdrawal intent
 * store — so a Settings tap and a collection decision read one truth, not two.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceSharingSettingsModule {

    @Provides
    @Singleton
    fun provideVoiceSharingSettingsViewModel(
        policyProvider: VoiceSharingPolicyProvider,
        accountStartup: VoiceSharingAccountStartup,
        withdrawalIntent: LocalWithdrawalIntentStore,
        store: ContributionStore,
    ): VoiceSharingSettingsViewModel {
        val controller: EnrollmentController = accountStartup.enrollmentController(
            // THE QUEUE'S OWN REVOCATION: "stop everything now" uses the same ownership-locked
            // purge as every other revocation, not a bare file sweep.
            clearLocalPending = { runCatching { store.revokeAndPurge("withdrawal requested from settings") } },
        )
        return VoiceSharingSettingsViewModel(
            policy = policyProvider.policy,
            projection = accountStartup.state.projection,
            enrollmentController = controller,
            withdrawalIntent = withdrawalIntent,
            // THE SAME shared session source the controller fences with: the presentation
            // boundary and the correctness boundary must move together (reviewer a2136dd9).
            ownerSession = { accountStartup.authoritySnapshot().session },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
