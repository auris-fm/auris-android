package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        // Requesting the singleton here is the composition act: the provider's constructor
        // joins the application scope and observes the real Firebase adapter sequence.
        providerProvider.get()
    }
}
