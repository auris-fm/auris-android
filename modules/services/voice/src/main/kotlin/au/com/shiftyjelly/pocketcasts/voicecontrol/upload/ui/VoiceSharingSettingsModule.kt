package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.FolderQueue
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.UploaderLoop
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
@Module
@InstallIn(SingletonComponent::class)
object VoiceSharingSettingsModule {
    @Provides
    @Singleton
    fun provideVoiceSharingSettingsViewModel(
        accountStartup: VoiceSharingAccountStartup,
        withdrawalIntent: LocalWithdrawalIntentStore,
        queue: FolderQueue,
        uploaderLoop: UploaderLoop,
    ): VoiceSharingSettingsViewModel {
        val controller: EnrollmentController = accountStartup.enrollmentController(
            clearLocalPending = { runCatching { queue.deleteAll("sharing turned off") } },
        )
        return VoiceSharingSettingsViewModel(
            projection = accountStartup.state.projection,
            enrollmentController = controller,
            withdrawalIntent = withdrawalIntent,
            ownerSession = { accountStartup.ownerSessionGeneration() },
            refreshProjection = { accountStartup.operations.refreshProjection() },
            cancelInFlightUpload = uploaderLoop::cancelInFlight,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
