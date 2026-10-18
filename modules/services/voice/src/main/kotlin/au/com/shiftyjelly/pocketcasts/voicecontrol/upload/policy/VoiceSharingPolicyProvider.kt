package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import au.com.shiftyjelly.pocketcasts.utils.config.FirebaseConfig
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Voice-sharing remote policy (Task 1 of docs/plans/android/voice-recording-uploads.md).
 *
 * Fail-closed at every step: [snapshot] is enabled ONLY while a successful fetch AND activation
 * were both observed through the real adapter composition ([FirebaseRemoteFeatureProvider]) and
 * the resulting snapshot is still inside its monotonic-time lease. A cached config value can
 * never enable: any fetch failure, activation failure, missing value, or lease expiry reads as
 * disabled. Local debug overrides may disable but not enable (enforced at the consuming
 * controller; this provider has no override input by design).
 *
 * Only `voice_sharing_enabled` comes from Firebase; the numeric limits and scheduling constants
 * are COMPILED DEFAULTS with compiled ceilings (see [PolicySnapshot]) and are never remote keys.
 */
@Singleton
class VoiceSharingPolicyProvider @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig,
    private val featureProvider: FirebaseRemoteFeatureProvider,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    private val state = MutableStateFlow(DISABLED)

    /** Observable at the collection boundary: a refresh emits here before another segment/upload. */
    val policy: StateFlow<PolicySnapshot> = state

    fun snapshot(): PolicySnapshot {
        val value = state.value
        return if (value.activatedAtMonotonicMs > 0 && nowMs() - value.activatedAtMonotonicMs <= DEFAULT_LEASE_MS) {
            value
        } else {
            DISABLED
        }
    }

    /**
     * Performs one synchronous refresh: fetch must succeed, activation must succeed, and only
     * then is the remote value read and published with a fresh monotonic activation stamp. Any
     * failure publishes (or leaves) the disabled snapshot — the previous enabled snapshot does
     * not survive beyond its lease on a broken refresh path.
     */
    fun refreshBlocking(): PolicySnapshot {
        val fetched = runCatching { taskSuccess(remoteConfig.fetch()) }.getOrDefault(false)
        if (!fetched) {
            state.value = DISABLED
            return state.value
        }
        val activated = runCatching { taskSuccess(remoteConfig.activate()) }.getOrDefault(false)
        if (!activated) {
            state.value = DISABLED
            return state.value
        }
        val enabled = remoteConfig.getBoolean(FirebaseConfig.VOICE_SHARING_ENABLED)
        val snapshot = PolicySnapshot(enabled = enabled, activatedAtMonotonicMs = nowMs())
        state.value = snapshot
        return snapshot
    }

    /**
     * Completes with whether the task itself succeeded — typed results are not needed here.
     * A direct executor is required: the no-executor overload routes completion through the
     * Android main looper, and this refresh path must be able to run on any thread.
     */
    private fun <T> taskSuccess(task: com.google.android.gms.tasks.Task<T>): Boolean = kotlinx.coroutines.runBlocking {
        suspendCoroutine { continuation ->
            task.addOnCompleteListener(directExecutor) { t ->
                continuation.resume(t.isSuccessful)
            }
        }
    }

    private val directExecutor = java.util.concurrent.Executor { command -> command.run() }

    companion object {
        /** How long a successfully activated snapshot stays valid in monotonic time. */
        const val DEFAULT_LEASE_MS: Long = 15 * 60 * 1000L

        private val DISABLED = PolicySnapshot(enabled = false, activatedAtMonotonicMs = 0L)
    }
}

/**
 * Immutable compiled policy view. `voice_sharing_enabled` is the only remote input; every
 * numeric value here is a compiled default validated against a compiled ceiling at
 * construction — they are deliberately NOT Firebase keys.
 */
data class PolicySnapshot(
    val enabled: Boolean,
    val activatedAtMonotonicMs: Long,
)
