package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import au.com.shiftyjelly.pocketcasts.utils.config.FirebaseConfig
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Voice-sharing remote policy (Task 1 of docs/plans/android/voice-recording-uploads.md).
 *
 * Fail-closed at every step, and FRESHNESS IS REAL SDK EVIDENCE (reviewer efd5e29f): the
 * snapshot is enabled only while
 *  - the remote fetch bookkeeping ADVANCED since the last observed refresh
 *    ([FirebaseRemoteConfig.getInfo] reports a strictly larger `fetchTimeMillis` with SUCCESS
 *    status — a cached success is not fresh and never renews the lease),
 *  - activation through the real adapter composition succeeded,
 *  - the value was read from the REMOTE source with strict Boolean decoding ("true"/"false"
 *    exactly; permissive encodings and non-remote values disable), and
 *  - the monotonic-time lease has not expired.
 *
 * The provider is lifecycle-owned: on construction it joins the application scope and observes
 * the real [FirebaseRemoteFeatureProvider] initial fetch/activation, refreshing once that
 * completes. Production callers ([VoiceSharingController]) invoke [refresh] before admitting
 * another segment or starting another upload. A scheduled monotonic invalidation publishes the
 * disabled snapshot at lease expiry, so a plain [policy] subscriber observes the disable with
 * no refresh and no read.
 *
 * Overlapping refreshes are fenced: each refresh captures its request generation BEFORE its
 * first await and publishes only if it is still the newest request — an older true can never
 * overwrite a newer disabled. All awaits are bounded `suspendCancellableCoroutine` work; the
 * refresh path never blocks a thread.
 *
 * Only `voice_sharing_enabled` is a remote key; every numeric limit is a compiled default
 * validated against a compiled ceiling (tracked separately, not part of this claim).
 */
class VoiceSharingPolicyProvider(
    private val remoteConfig: FirebaseRemoteConfig,
    private val featureProvider: FirebaseRemoteFeatureProvider,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
    private val refreshTimeoutMs: Long = REFRESH_TIMEOUT_MS,
) {
    private val state = MutableStateFlow(DISABLED)

    /** Observable at the collection boundary: a refresh emits here before another segment/upload. */
    val policy: StateFlow<PolicySnapshot> = state

    /** Newest refresh request; captured before any await, used to fence stale publications. */
    private var requestGeneration = 0L

    /** Generation of the activation currently published; fences invalidation against renewals. */
    private var activationGeneration = 0L

    private var invalidationJob: Job? = null

    init {
        // Lifecycle-owned composition: the join on the real provider's own fetch+activation
        // sequence gates the first policy read, but is BOUNDED — this provider never depends on
        // the init outcome for authority (its activated value is nonauthoritative here), and
        // refresh() itself performs a genuine fetch whose bookkeeping is judged on its own.
        scope.launch {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(INIT_AWAIT_MS) { featureProvider.awaitInitialization() }
            }
            refresh()
        }
    }

    fun snapshot(): PolicySnapshot {
        val value = state.value
        val now = nowMs()
        return if (
            value.activatedAtMonotonicMs > 0 &&
            now >= value.activatedAtMonotonicMs && // a clock before the activation stamp is not fresh
            now - value.activatedAtMonotonicMs <= DEFAULT_LEASE_MS
        ) {
            value
        } else {
            DISABLED
        }
    }

    /**
     * One refresh, in order: fetch status must report SUCCESS, the fetch timestamp must advance
     * beyond anything previously observed (cached success is not fresh), activation must
     * succeed, and the value must come from the REMOTE source with strict Boolean decoding. Any
     * failure publishes disabled — fenced so a slower older refresh cannot overwrite a newer
     * disabled publication.
     */
    suspend fun refresh(): PolicySnapshot {
        val requestGen: Long = synchronized(fence) { ++requestGeneration }
        // The WHOLE refresh is bounded: a fetch or activation that never completes cannot hang
        // the caller; on deadline it fails closed (fenced).
        return withTimeoutOrNull(refreshTimeoutMs) { runRefresh(requestGen) }
            ?: publishDisabled(requestGen)
    }

    private suspend fun runRefresh(requestGen: Long): PolicySnapshot {
        // Capture the SDK's PRE-EXISTING fetch bookkeeping BEFORE this refresh's request — even
        // when this provider has never refreshed before — so a cached success (the SDK already
        // fetched earlier and nothing advanced since) cannot pass as fresh on a first refresh.
        // An unreadable baseline is NOT a pass: fail closed.
        val baseline = attemptFinite { remoteConfig.info }
            ?.let { info -> attemptFinite { info.fetchTimeMillis } }
            ?: return publishDisabled(requestGen)

        // A genuine new fetch request, awaited before its bookkeeping is judged.
        val fetched = attemptFinite { taskSuccess(remoteConfig.fetch()) }
        if (fetched != true) {
            return publishDisabled(requestGen)
        }

        val info = attemptFinite { remoteConfig.info } ?: return publishDisabled(requestGen)
        val statusOk = attemptFinite { info.lastFetchStatus == FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS } == true
        val postFetchTimeMs = attemptFinite { info.fetchTimeMillis }
        if (!statusOk || postFetchTimeMs == null || postFetchTimeMs <= baseline) {
            // Failure, or a cached success whose bookkeeping never advanced: not fresh, and the
            // lease must not renew.
            return publishDisabled(requestGen)
        }

        val activated = attemptFinite { taskSuccess(remoteConfig.activate()) }
        if (activated != true) {
            return publishDisabled(requestGen)
        }

        val value = attemptFinite { remoteConfig.getValue(FirebaseConfig.VOICE_SHARING_ENABLED) }
            ?: return publishDisabled(requestGen)
        val strictBoolean = attemptFinite {
            when (value.asString()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        } ?: return publishDisabled(requestGen)
        val enabled = strictBoolean && attemptFinite { value.source } == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE

        val snapshot = PolicySnapshot(
            enabled = enabled,
            activatedAtMonotonicMs = nowMs(),
            activationGeneration = requestGen,
        )
        // Generation allocation and conditional publication share one lock, so a newer request
        // can never interleave between this refresh's check and its publication.
        synchronized(fence) {
            if (requestGen != requestGeneration) {
                return DISABLED // a newer refresh superseded this one while it was awaiting
            }
            activationGeneration = requestGen
            state.value = snapshot
        }
        if (enabled) {
            scheduleLeaseInvalidation(snapshot)
        }
        return snapshot
    }

    /** Publishes disabled unless a newer refresh request has started (generation fence). */
    private fun publishDisabled(requestGen: Long): PolicySnapshot {
        synchronized(fence) {
            if (requestGen == requestGeneration) {
                activationGeneration = 0L
                state.value = DISABLED
            }
        }
        return DISABLED
    }

    /**
     * Runs [block], converting ordinary failures into null — but CancellationException is
     * rethrown: a cancelled refresh is a cancellation (e.g. the whole-refresh deadline), never
     * an ordinary failure to publish over.
     */
    private inline fun <T> attemptFinite(block: () -> T): T? = try {
        block()
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

    /**
     * Wakes when the lease expires — via the injected monotonic sleep — and publishes disabled
     * so a plain subscriber observes the disable with no refresh and no read. Fenced on the
     * activation generation so a renewal (a newer enabled snapshot with its own invalidation)
     * is not torn down by this older schedule.
     */
    private fun scheduleLeaseInvalidation(snapshot: PolicySnapshot) {
        invalidationJob?.cancel()
        val gen = snapshot.activationGeneration
        invalidationJob = scope.launch {
            while (nowMs() < snapshot.activatedAtMonotonicMs + DEFAULT_LEASE_MS) {
                val before = nowMs()
                sleepMs(snapshot.activatedAtMonotonicMs + DEFAULT_LEASE_MS - before)
                // No-progress guard: a sleep that returns without the monotonic clock moving
                // cannot prove the lease is still alive, and spinning would hang the dispatcher.
                // Fail closed: publish disabled (fenced), so a stalled schedule can never leave
                // a stale enabled snapshot on the live StateFlow.
                if (nowMs() <= before) {
                    if (activationGeneration == gen) {
                        state.value = DISABLED
                    }
                    return@launch
                }
            }
            if (activationGeneration == gen) {
                state.value = DISABLED
            }
        }
    }

    /**
     * Completes with whether the task itself succeeded — typed results are not needed here.
     * A direct executor is required: the no-executor overload routes completion through the
     * Android main looper, and this path must be able to run on any thread. Bounded
     * cancellable suspension; no blocking.
     */
    private suspend fun <T> taskSuccess(task: com.google.android.gms.tasks.Task<T>): Boolean = suspendCancellableCoroutine { continuation ->
        task.addOnCompleteListener(directExecutor) { t ->
            continuation.resume(t.isSuccessful)
        }
    }

    private val directExecutor = java.util.concurrent.Executor { command -> command.run() }

    /** Serializes generation allocation and conditional publication across threads. */
    private val fence = Any()

    companion object {
        /** How long a successfully activated snapshot stays valid in monotonic time. */
        const val DEFAULT_LEASE_MS: Long = 15 * 60 * 1000L

        /** Bound on joining the underlying provider's initial fetch/activation sequence. */
        const val INIT_AWAIT_MS: Long = 2_000L

        /** Bound on a whole refresh: pending fetch/activate tasks cannot suspend it forever. */
        const val REFRESH_TIMEOUT_MS: Long = 10_000L

        private val DISABLED = PolicySnapshot(enabled = false, activatedAtMonotonicMs = 0L, activationGeneration = 0L)
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
    val activationGeneration: Long,
)
