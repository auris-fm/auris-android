package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
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

    /**
     * EFFECTIVE CONSENT (Reviewer on `c2ce670cc7`): the provider the switch renders from must be the
     * SAME read admission uses — [SavedSharingChoiceStore.enrolledChoiceFor], which also refuses on
     * a failed persist, a wrong account or missing provenance. The raw file read could show the
     * switch ON while capture is refused. A named function so the effective-consent control tests
     * exactly this expression.
     */
    internal fun effectiveConsentProvider(
        savedChoice: SavedSharingChoiceStore,
        account: () -> String?,
    ): suspend () -> SavedSharingChoice? = {
        val name = account()
        if (name.isNullOrBlank()) null else savedChoice.enrolledChoiceFor(name)
    }

    /**
     * VISIBILITY provider for the Settings row (spec's bot, offline-start finding): whether the
     * account has ANY durable record, whatever its state. "Sharing is off" is a known state — the
     * row must show offline — while the switch VALUE stays on [effectiveConsentProvider].
     */
    internal fun knownChoiceProvider(
        savedChoice: SavedSharingChoiceStore,
        account: () -> String?,
    ): suspend () -> SavedSharingChoice? = {
        val name = account()
        if (name.isNullOrBlank()) null else savedChoice.knownChoiceFor(name)
    }
    @Provides
    @Singleton
    fun provideVoiceSharingSettingsViewModel(
        accountStartup: VoiceSharingAccountStartup,
        withdrawalIntent: LocalWithdrawalIntentStore,
        savedChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore,
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
            savedChoice = effectiveConsentProvider(savedChoice) { accountStartup.currentAccountName() },
            knownChoice = knownChoiceProvider(savedChoice) { accountStartup.currentAccountName() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
