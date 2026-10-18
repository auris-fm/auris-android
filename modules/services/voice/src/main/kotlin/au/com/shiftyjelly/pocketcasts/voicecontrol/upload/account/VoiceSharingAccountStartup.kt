package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServiceRetrofit
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * The actual production STARTUP CONSUMER for the voice-sharing account boundary (reviewer
 * a75df7ef): constructs the coordinated session/credential owner against the REAL auth owner
 * and starts the binding on the application scope — the same app-start convention as
 * VoiceSharingPolicyStartup. Operations built here are the ONLY account operations wired in
 * production: they source credentials from the real token owner and their 401 transaction IS
 * the real owner's atomic compare-and-invalidate.
 */
@Singleton
class VoiceSharingAccountStartup @Inject constructor(
    private val syncAccountManager: SyncAccountManager,
    private val ownership: CredentialOwnershipOwner,
    @SyncServiceRetrofit private val retrofit: Lazy<retrofit2.Retrofit>,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    val state = VoiceSharingAccountState()
    val credentials = VoiceSharingCredentialStore()
    val coordinator = VoiceSharingAccountSessionCoordinator(state, credentials)
    val binding = VoiceSharingAccountAuthBinding(syncAccountManager, ownership, coordinator)

    val operations: VoiceSharingAccountOperations by lazy {
        VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(retrofit.get()),
            state = state,
            ownership = binding, // the REAL owner's atomic compare-and-invalidate
            credentialSource = { binding.syncCredential() }, // ALWAYS the real token owner
            // Notification hook only: the REAL invalidation already committed inside the
            // ownership transaction; telemetry joins here.
            onCredentialInvalid = { },
        )
    }

    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        binding.start(applicationScope)
    }
}
