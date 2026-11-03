package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The production requester for the voice-sharing policy (reviewer blocker: a Hilt binding with
 * no consumer is not composition). The app's startup convention is Hilt-injected singletons
 * initialized from [au.com.shiftyjelly.pocketcasts.PocketCastsApplication.onCreate]
 * (same pattern as DefaultPlaylistsInitializer); this owner is injected there and its
 * [initializeOnAppStart] instantiates the policy provider — which starts its real
 * lifecycle-owned fetch/activation join — at app start.
 *
 * Instantiating the provider does NOT enable collection: the fail-closed provider only begins
 * observing the remote policy. Enabling collection requires the account projection and
 * enrollment composed at the VoiceSharingController gate (later plan tasks).
 */
@Singleton
class VoiceSharingPolicyStartup @Inject constructor(
    private val providerProvider: Lazy<VoiceSharingPolicyProvider>,
    @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope private val scope: CoroutineScope,
) {
    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        // Requesting the singleton here is the composition act: the provider's constructor
        // joins the application scope and observes the real Firebase adapter sequence.
        providerProvider.get()
    }

    /**
     * FIRST ACCOUNT-READABILITY TRIGGER (@spec 69630b93): the launch exchange runs from
     * `onCreate`, so a cold start that follows a login can run it before the account is readable,
     * and nothing re-ran it afterwards — leaving the lease disabled with no capture driver. This
     * forwards the first readability signal of the process into exactly ONE extra policy refresh.
     *
     * Scope: policy acquisition ONLY. The allowance batch, its ledger and any queued records are
     * untouched (no re-issue, no epoch change, no rebind).
     */
    fun onAccountReadable(accountName: String?, session: Long) {
        if (accountName == null) return
        scope.launch { providerProvider.get().refreshOnAccountReadable(accountName, session) }
    }
}
