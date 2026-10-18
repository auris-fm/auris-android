package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * RED SKELETON (Task 1, plan docs/plans/android/voice-recording-uploads.md): fail-closed by
 * construction. The real implementation must enable only after the real provider's fetch AND
 * activation both succeed, keep the snapshot valid inside a monotonic-time lease, and emit
 * changes on [policy]. Nothing here is enabled yet; the red control pins the contract.
 */
@Singleton
class VoiceSharingPolicyProvider @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig,
    private val featureProvider: FirebaseRemoteFeatureProvider,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    private val state = MutableStateFlow(PolicySnapshot(enabled = false, activatedAtMonotonicMs = 0L))

    val policy: StateFlow<PolicySnapshot> = state

    fun snapshot(): PolicySnapshot = state.value

    /** Drives one refresh synchronously for tests; production uses the provider's own lifecycle. */
    fun refreshBlocking() {
        // RED: intentionally never enables.
    }

    companion object {
        const val DEFAULT_LEASE_MS: Long = 15 * 60 * 1000L
    }
}

/** Immutable compiled policy view; numeric limits are compiled ceilings validated at construction. */
data class PolicySnapshot(
    val enabled: Boolean,
    val activatedAtMonotonicMs: Long,
)
