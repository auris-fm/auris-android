package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import au.com.shiftyjelly.pocketcasts.utils.config.FirebaseConfig
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigFetchThrottledException
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
import timber.log.Timber

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
    /** Base retry delay for transient acquisition failures; tests drive this and [sleepMs]. */
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    /** Wall clock for server throttle deadlines (epoch millis); test seam. */
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    /** SHARED serialization primitive: MUST be the SAME object as the account tier's
     * collectionSharedLock (reviewer 23cae70e b1). NO default — an independent fallback lock
     * would silently deserialize policy publication from account transitions. Composition passes
     * the owner-provided primitive explicitly. */
    private val sharedCollectionLock: Any,
) {
    private val state = MutableStateFlow(DISABLED)

    /** Observable at the collection boundary: a refresh emits here before another segment/upload. */
    val policy: StateFlow<PolicySnapshot> = state

    /** Newest refresh request; captured before any await, used to fence stale publications. */
    private var requestGeneration = 0L

    /** Generation of the activation currently published; fences invalidation against renewals. */
    private var activationGeneration = 0L

    private var invalidationJob: Job? = null

    /**
     * ACQUISITION RETRY DRIVER: a process that starts against a failing fetch previously had NO
     * retry driver — the lease-expiry renewal only arms when a snapshot is ENABLED, so such a
     * process stayed disabled until an app restart (observed in the field 2026-11-04). While the
     * snapshot is disabled by TRANSIENT acquisition failures, each failure schedules the next
     * ordinary refresh after an exponentially growing, capped backoff — bounded rate, no
     * permanent abandonment. A typed server throttle additionally defers the retry to the
     * server's own boundary. Authoritative policy answers cancel the loop; ALL retry ownership
     * mutations are fenced with publication (only the newest answer governs the timer), and each
     * retry re-validates its captured generation before it refreshes. Managed under [fence]
     * like every other timer.
     */
    private var retryJob: Job? = null
    private var retryBackoffMs = retryDelayMs

    /**
     * The OWNER IDENTITY this provider last acquired policy FOR — account name AND session
     * generation; see [refreshOnAccountReadable]. Identity-scoped, not a process-wide latch: repeats
     * for the same identity are deduped, while a real account change, a sign-out and back into the
     * same account, and a same-account session rotation are all NEW authority that must reacquire
     * policy (@spec f5575767, reviewer 4ce3ba23).
     */
    private val lastRefreshedOwner = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /**
     * Serializes generation allocation, conditional publication, and timer install/cancel.
     * Declared BEFORE the init block: the lifecycle join can begin refresh work the moment the
     * instance is constructed (an already-completed adapter enters refresh immediately), so
     * every field refresh uses must already be initialized.
     */
    private val fence = Any()
    private val directExecutor = java.util.concurrent.Executor { command -> command.run() }

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

    /** Raw published state WITHOUT lease evaluation: lets deadline consumers read the stamp. */
    val publishedPolicy: StateFlow<PolicySnapshot> get() = state

    /**
     * TEST SEAM: install an explicitly enabled snapshot without a remote fetch. Controls use this to
     * exercise the OPEN path; production can only reach an enabled snapshot through a validated
     * fetch/activation (the compiled default is disabled).
     */
    @androidx.annotation.VisibleForTesting
    internal fun applySnapshotForTest(snapshot: PolicySnapshot) {
        state.value = snapshot
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
        return executeRefresh(requestGen)
    }

    /**
     * The WHOLE refresh is bounded: a fetch or activation that never completes cannot hang
     * the caller; on deadline it fails closed (fenced).
     */
    private suspend fun executeRefresh(requestGen: Long): PolicySnapshot {
        return withTimeoutOrNull(refreshTimeoutMs) { runRefresh(requestGen) }
            ?: publishDisabled(requestGen, "refresh deadline exceeded", transient = true)
    }

    /**
     * ACCOUNT-READABILITY TRIGGER (@spec 69630b93): the launch exchange in the init block can run
     * before the account is readable on a cold start that follows a login, and nothing re-ran it —
     * so the lease stayed disabled with no capture driver. The first account-readability signal for
     * an identity therefore runs one refresh, and so does every LATER real change of identity
     * (@spec f5575767: a process-wide "already refreshed" flag must never suppress refreshing for
     * a new owner). Repeat signals for the SAME owner are deduped (no spin); a null account name
     * means "not readable yet" and refreshes nothing. NOTHING about the allowance batch, ledger or
     * queued records is touched here — this is policy acquisition only.
     *
     * Returns true only when this call actually drove a refresh.
     */
    suspend fun refreshOnAccountReadable(accountName: String?, session: Long): Boolean {
        if (accountName == null) return false
        // The owner identity is BOTH facts: a new session for the same account is new authority.
        val owner = accountName + "#" + session
        // Atomic claim BEFORE the await: concurrent duplicate signals for the same owner collapse to
        // ONE refresh, and a LATER different owner is never suppressed by the claim.
        val previous = lastRefreshedOwner.getAndSet(owner)
        if (previous == owner) return false
        refresh()
        return true
    }

    private suspend fun runRefresh(requestGen: Long): PolicySnapshot {
        // Capture the SDK's PRE-EXISTING fetch bookkeeping BEFORE this refresh's request — even
        // when this provider has never refreshed before — so a cached success (the SDK already
        // fetched earlier and nothing advanced since) cannot pass as fresh on a first refresh.
        // An unreadable baseline is NOT a pass: fail closed.
        val baseline = attemptFinite { remoteConfig.info }
            ?.let { info -> attemptFinite { info.fetchTimeMillis } }
            ?: return publishDisabled(requestGen, "fetch baseline unreadable", transient = true)

        // A genuine new fetch request, awaited before its bookkeeping is judged. Interval 0 is
        // REQUIRED by the freshness contract below: the default SDK minimum fetch interval
        // throttles repeated fetch() calls (status THROTTLED, fetchTimeMillis not advancing),
        // which would make the SUCCESS-and-advanced gate unsatisfiable — every refresh must be
        // a real network fetch.
        // Typed server-side throttle: taskSuccess discards the failure, so the fetch outcome is
        // read with its typed exception preserved — firebase-config 23.1.0's
        // FirebaseRemoteConfigFetchThrottledException carries the authoritative retry boundary
        // (getThrottleEndTimeMillis()), and the retry must not fire before the server is willing
        // to answer again.
        var throttleDeadlineMs: Long? = null
        val fetched: Boolean? = attemptFinite { taskSuccessWithThrottleDeadline(remoteConfig.fetch(0L)) }
            ?.also { (_, deadline) -> throttleDeadlineMs = deadline }
            ?.first
        if (fetched != true) {
            return publishDisabled(requestGen, "fetch task failed", transient = true, throttleUntilMs = throttleDeadlineMs)
        }

        val info = attemptFinite { remoteConfig.info }
            ?: return publishDisabled(requestGen, "post-fetch info unreadable", transient = true)
        val statusOk = attemptFinite { info.lastFetchStatus == FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS } == true
        val postFetchTimeMs = attemptFinite { info.fetchTimeMillis }
        if (!statusOk || postFetchTimeMs == null || postFetchTimeMs <= baseline) {
            // Failure, or a cached success whose bookkeeping never advanced: not fresh, and the
            // lease must not renew.
            return publishDisabled(
                requestGen,
                "fetch bookkeeping not fresh: status=$statusOk postFetchTimeMs=$postFetchTimeMs baseline=$baseline",
                transient = true,
            )
        }

        val activated = attemptFinite { taskSuccess(remoteConfig.activate()) }
        if (activated != true) {
            return publishDisabled(requestGen, "activation failed", transient = true)
        }

        val value = attemptFinite { remoteConfig.getValue(FirebaseConfig.VOICE_SHARING_ENABLED) }
            ?: return publishDisabled(requestGen, "remote key unreadable", transient = true)
        val strictBoolean = attemptFinite {
            when (value.asString()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        } ?: return publishDisabled(requestGen, "remote key not a strict boolean")
        val enabled = strictBoolean && attemptFinite { value.source } == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE
        if (!enabled) {
            return publishDisabled(
                requestGen,
                "remote value=${value.asString()} (source=${attemptFinite { value.source }}) — key not enabled for this build",
            )
        }

        val snapshot = PolicySnapshot(
            enabled = enabled,
            activatedAtMonotonicMs = nowMs(),
            activationGeneration = requestGen,
        )
        // Generation allocation and conditional publication share one lock, so a newer request
        // can never interleave between this refresh's check and its publication.
        synchronized(sharedCollectionLock) {
            synchronized(fence) {
                if (requestGen != requestGeneration) {
                    return DISABLED // a newer refresh superseded this one while it was awaiting
                }
                activationGeneration = requestGen
                state.value = snapshot
                retryBackoffMs = retryDelayMs
                retryJob?.cancel()
                retryJob = null
                Timber.i("voice-sharing policy ENABLED (gen=%d)", requestGen)
                // Timer ownership is serialized WITH publication under the same fences: a
                // superseded refresh never reaches this line, so it can never cancel a newer
                // timer or install a stale one; a renewal cancels the previous timer here first.
                installInvalidationLocked(snapshot)
            }
        }
        return snapshot
    }

    /**
     * Publishes disabled unless a newer refresh request has started (generation fence). A TRANSIENT
     * failure (the fetch path itself failed — network, deadline, unreadable bookkeeping, server
     * throttling) schedules the next ordinary refresh after the current backoff (never earlier
     * than a Firebase throttle deadline) and widens the backoff, so a dark process recovers at a
     * bounded rate without an app restart and NEVER abandons recovery. An AUTHORITATIVE answer
     * (the remote key decoded false / not enabled) is the policy's own verdict: it cancels the
     * retry loop and resets the backoff, and the lane stays closed until the next normal driver.
     */
    private fun publishDisabled(requestGen: Long, reason: String): PolicySnapshot {
        return publishDisabled(requestGen, reason, transient = false)
    }

    private fun publishDisabled(
        requestGen: Long,
        reason: String,
        transient: Boolean,
        throttleUntilMs: Long? = null,
    ): PolicySnapshot {
        Timber.i("voice-sharing policy disabled: %s", reason)
        synchronized(sharedCollectionLock) {
            synchronized(fence) {
                if (requestGen == requestGeneration) {
                    // Retry ownership is fenced WITH publication: only the NEWEST answer may
                    // cancel/replace the pending retry or move the backoff — an older failed
                    // refresh can never schedule retries over a newer authoritative answer.
                    retryJob?.cancel()
                    retryJob = null
                    if (transient) {
                        scheduleAcquisitionRetryLocked(throttleUntilMs)
                    } else {
                        retryBackoffMs = retryDelayMs
                    }
                    activationGeneration = 0L
                    state.value = DISABLED
                    invalidationJob?.cancel()
                    invalidationJob = null
                }
            }
        }
        return DISABLED
    }

    /**
     * MUST be called while holding [fence] and only for a transient failure OF THE NEWEST
     * REQUEST. Schedules ONE ordinary [refresh] at the LATER of the current backoff and, when
     * the failure was a typed server throttle, the server's authoritative retry boundary
     * ([throttleUntilMs], epoch millis against the wall clock — this may exceed
     * [MAX_RETRY_BACKOFF_MS], which caps only the local backoff growth). The backoff doubles
     * for the next round (capped). The retry runs the full freshness contract unchanged —
     * fetch(0L), advancing bookkeeping, activation, strict decode — so a retry can never
     * publish a stale true; and it validates its CAPTURED generation before starting, so a
     * retry scheduled by a superseded request can never fire over a newer refresh.
     */
    private fun scheduleAcquisitionRetryLocked(throttleUntilMs: Long?) {
        val backoffDelay = retryBackoffMs
        val throttleDelay = throttleUntilMs?.let { (it - wallClockMs()).coerceAtLeast(0L) }
        val delay = maxOf(backoffDelay, throttleDelay ?: 0L)
        retryBackoffMs = (retryBackoffMs * 2).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        val retryGen = requestGeneration
        Timber.i("voice-sharing policy: acquisition retry scheduled in %dms (gen=%d)", delay, retryGen)
        retryJob = scope.launch {
            sleepMs(delay)
            // The sleep is an await: a newer refresh may have superseded this schedule while it
            // ran. Re-validate the captured generation under the fence BEFORE refreshing —
            // publication fences make a stale retry's outcome harmless, but it must not even
            // start over a newer request.
            // Check-and-CLAIM is one atomic step under the fence: the retry becomes the newest
            // request AT CLAIM TIME, so a manual refresh starting in the gap between an old
            // check and this claim can never be superseded by the stale retry (reviewer
            // 7bdc0d3b). Publication fences make a claimed-but-superseded retry harmless.
            val claimed: Long? = synchronized(fence) {
                if (retryGen == requestGeneration) ++requestGeneration else null
            }
            if (claimed == null) {
                Timber.i("voice-sharing policy: acquisition retry superseded before firing (gen=%d)", retryGen)
                return@launch
            }
            Timber.i("voice-sharing policy: acquisition retry attempting refresh (gen=%d)", claimed)
            executeRefresh(claimed)
        }
    }

    /** MUST be called while holding [fence]: timer install/cancel is part of publication. */
    private fun installInvalidationLocked(snapshot: PolicySnapshot) {
        invalidationJob?.cancel()
        if (!snapshot.enabled) {
            invalidationJob = null
            return
        }
        val gen = snapshot.activationGeneration
        val wakeClock = nowMs()
        invalidationJob = scope.launch {
            while (true) {
                val before = nowMs()
                val due = snapshot.activatedAtMonotonicMs + DEFAULT_LEASE_MS - before
                if (due <= 0) {
                    break
                }
                sleepMs(due)
                // No-progress guard: a sleep that returns without the monotonic clock moving
                // cannot prove the lease is alive, and spinning would hang the dispatcher. Fail
                // closed: publish disabled (fenced).
                if (nowMs() <= before) break
            }
            synchronized(sharedCollectionLock) {
                synchronized(fence) {
                    // Fenced on the ACTIVATION generation only: when this schedule's own lease truly
                    // expires, the policy MUST disable — an in-flight renewal is not a fresh policy,
                    // and subscribers must see disabled until the renewal installs a valid lease.
                    // The stale-timer hazard (an old wake AFTER a renewal installed a fresh lease,
                    // before that lease's deadline) is prevented by the renewal cancelling this job
                    // inside the fence at publication; if a late wake still slips through (cancellation
                    // lost the race), the activation-generation check refuses to tear down the renewal.
                    if (activationGeneration == gen) {
                        Timber.i("voice-sharing policy disabled: lease expired (gen=%d) — attempting renewal", gen)
                        state.value = DISABLED
                        // Renewal ON EXPIRY: before the first consented capture there is no other
                        // refresh driver, so without this the consent surface decays LEASE_MS after
                        // every app start and can never be used. Subscribers see disabled until the
                        // fresh install (unchanged semantics); a superseded renewal is fenced below.
                        // NOT for a stalled clock: a sleep that returns without the monotonic clock
                        // moving can never produce a fresh lease (every install would re-expire
                        // immediately), so renewing there would spin. Fail closed instead.
                        if (nowMs() > wakeClock) {
                            scope.launch { refresh() }
                        }
                    }
                }
            }
        }
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

    /**
     * Like [taskSuccess], but a failed task's typed exception is inspected: a
     * FirebaseRemoteConfigFetchThrottledException contributes its server-authoritative
     * throttle-end boundary (epoch millis). Any other failure reads as plain (false, null).
     */
    private suspend fun taskSuccessWithThrottleDeadline(
        task: com.google.android.gms.tasks.Task<Void>,
    ): Pair<Boolean, Long?> = suspendCancellableCoroutine { continuation ->
        task.addOnCompleteListener(directExecutor) { t ->
            val throttled = t.exception as? FirebaseRemoteConfigFetchThrottledException
            continuation.resume(t.isSuccessful to throttled?.throttleEndTimeMillis)
        }
    }

    companion object {
        /** How long a successfully activated snapshot stays valid in monotonic time. */
        const val DEFAULT_LEASE_MS: Long = 15 * 60 * 1000L
        const val RETRY_DELAY_MS = 30_000L
        const val MAX_RETRY_BACKOFF_MS: Long = 15 * 60 * 1000L

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
    /**
     * THE allowed upload networks (`upload_network`, spec §73/§152). The compiled default is
     * [UploadNetwork.ANY]; the value is a validated LOCAL policy input, not a remote key (there is
     * no `upload_network` field in the published snapshot yet — reviewer 177645cc: it must still
     * gate BOTH the scheduler constraint and EACH actual attempt, which it now does).
     */
    val uploadNetwork: UploadNetwork = UploadNetwork.ANY,
)

/**
 * THE allowed upload networks (`upload_network`, spec voice-recording-uploads.md §73/§152).
 *
 * A CLOSED set on purpose: the validated policy may only express constraints this client already
 * implements, so activation cannot strand the queue behind a network rule nobody honours. The
 * compiled default is [ANY]; nothing stricter is invented here (spec 89b4760b — in particular no
 * charging or unmetered requirement unless the validated policy says so).
 */
enum class UploadNetwork {
    /** Any connected network. */
    ANY,

    /** Unmetered networks only (a validated policy may require this). */
    UNMETERED,
    ;

    companion object {
        /**
         * The compiled default when the snapshot expresses no network restriction. NOTE: the
         * published snapshot does not yet carry an `upload_network` field (it is a validated local
         * default, not a remote key — spec §73), so callers use THIS value until activation defines
         * one; the closed set above is what that value may ever be.
         */
        fun compiledDefault(): UploadNetwork = ANY
    }
}
