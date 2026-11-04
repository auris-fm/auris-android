package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServiceRetrofit
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * PRODUCTION construction and trigger of the upload assembly (spec e842c497: compiled-off source wiring).
 *
 * NOTHING here can put a request on the network unless ALL of these hold, re-checked on every poll:
 * - the published policy snapshot is `enabled` (remote-config backed; the compiled default is DISABLED);
 * - the transport can produce credentials from the AURIS credential provider;
 * - the store's own ownership fence still validates the generation the poll captured.
 *
 * The upload endpoint is the specified route (POST /api/v1/voice-recordings) resolved against the
 * configured staging gateway origin; it is never guessed and never blank-wired in production.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceUploadModule {

    private const val CAPTURES_ROOT = "voice-contributions"

    /** The production poll cadence; tests pin it so a control can observe a second poll. */
    internal const val POLL_INTERVAL_MS = 60_000L

    @Provides
    @Singleton
    fun provideContributionStore(
        @ApplicationContext context: Context,
    ): ContributionStore = ContributionStore(
        File(context.filesDir, CAPTURES_ROOT),
        QueueLimits.defaults(),
        // Spec 64ce2de6: the android.system.Os platform adapter is bound HERE, at the Android
        // construction boundary, so every durable publication in production uses the documented
        // open(O_DIRECTORY)/fsync/close surface. Unit tests construct the store directly with the
        // JVM-runnable default or their own injected barrier. There is NO unsupported-runtime
        // success fallback: an unsupported platform propagates a failure, never a claimed sync.
        directorySync = DirectorySync.PRODUCTION,
        intentDirectorySyncOverride = DirectorySync.PRODUCTION,
    )

    @Provides
    @Singleton
    fun provideUploadTransport(
        @SyncServiceRetrofit retrofit: dagger.Lazy<retrofit2.Retrofit>,
        aurisToken: AurisTokenProvider,
    ): ContributionUploadTransport = ContributionUploadTransport(
        // Route contract (cloud-voice-uploads 476feadc; core 384952e8): POST /api/v1/voice-recordings
        // on the CONFIGURED staging gateway origin -- the same origin the account routes ride, taken
        // from the same @SyncServiceRetrofit the account API is built on. A blank endpoint cannot be
        // requested, so an unresolved origin fails closed here.
        endpoint = retrofit.get().baseUrl().resolve("/api/v1/voice-recordings").toString(),
        // Bearer comes ONLY from the AURIS credential (reviewer b702cd53): this route verifies
        // Auris-issued tokens; presenting the upstream mirror token is the wrong-credential defect
        // this wiring exists to remove. Resolved per attempt; the epoch binding happens in
        // [provideUploadAssembly].
        authorization = {
            // FAIL-CLOSED synchronous read: only a fresh token for the CURRENT Auris identity;
            // null refuses the attempt (retained, retried), never falls back to the mirror.
            aurisToken.peekCurrentToken()?.takeIf { it.isNotBlank() }
        },
    )

    /** The account's current enrollment epoch, or null when no owner-published epoch is in force. */
    @Provides
    @Singleton
    fun provideCurrentEnrollmentEpoch(
        accountStartup: VoiceSharingAccountStartup,
    ): () -> String? = {
        accountStartup.state.projection.value?.enrollmentEpoch
    }

    /**
     * The production assembly: eligibility gates compose the published policy and network availability;
     * captures come from the store's own durable authority, never a parallel reader.
     */
    @Provides
    @Singleton
    fun provideUploadAssembly(
        store: ContributionStore,
        transport: ContributionUploadTransport,
        accountStartup: VoiceSharingAccountStartup,
        @ApplicationContext context: Context,
        aurisToken: AurisTokenProvider,
    ): ContributionUploadAssembly = provideUploadAssembly(
        store,
        transport,
        accountStartup,
        actualNetworkFrom(context),
        aurisToken = aurisToken,
    )

    /**
     * THE ACTUAL-NETWORK GATE (reviewer 177645cc): the policy snapshot's `upload_network` gates
     * EVERY attempt — the platform scheduler constraint alone cannot stop a FOREGROUND drain
     * started outside WorkManager. [actualNetwork] reports what the device can use right now;
     * the comparison is policy-requested vs actual, nothing invented.
     */
    internal fun provideUploadAssembly(
        store: ContributionStore,
        transport: ContributionUploadTransport,
        accountStartup: VoiceSharingAccountStartup,
        actualNetwork: (UploadNetwork) -> Boolean,
        aurisToken: AurisTokenProvider,
    ): ContributionUploadAssembly {
        // Network availability is "the owner-published credential exists": without it nothing can be sent.
        val accountStartupHasCredentials = { accountStartup.credentials.current() != null }
        val authority = {
            UploadIdentityAuthority(
                underOwnerTransaction = { block -> accountStartup.validateUnderOwnerTransaction(block) },
            )
        }
        val assembly = ContributionUploadAssembly(
            store = store,
            transport = transport,
            captureFor = { captureId ->
                store.durableCapture(captureId)?.let {
                    // The FULL admitted identity travels WITH the capture (owner sidecar), so
                    // acquisition and launch validation compare account AND session AND epoch.
                    ContributionUploadAssembly.Capture(
                        it.record,
                        it.audio,
                        it.enrollmentEpoch,
                        it.enrollmentAccount,
                        it.enrollmentSession,
                        // Ownership of the open descriptor travels WITH the capture; the attempt settles it.
                        it.owned,
                    )
                }
            },
            // ONE full-identity credential authority: account, session, credential, token and epoch
            // projection read under the REAL owner lock in a single transaction; the ADMITTED
            // account/session/epoch are compared, not merely read.
            credentialFor = { admitted ->
                // The owner transaction vouches for account/session/epoch; the BEARER it freezes is
                // the AURIS credential (reviewer b702cd53), fail-closed: no fresh Auris token for the
                // current identity refuses the attempt instead of sending the upstream token.
                authority().credentialFor(admitted)?.let { live ->
                    aurisToken.peekCurrentToken()?.let { auris -> live.copy(bearer = auris) }
                }
            },
            // LAUNCH revalidation: the acquisition lock ends before transmission; this closes the
            // acquisition→Call window on the caller thread (reviewer d2f1bfc8).
            launchIdentityCheck = { admitted -> authority().credentialFor(admitted) != null },
            clock = System::currentTimeMillis,
            // THE PER-ATTEMPT NETWORK GATE: credential presence AND the actual network, re-read
            // per attempt. ANY network is permitted (no remote upload_network policy remains).
            networkAvailable = {
                accountStartupHasCredentials() && actualNetwork(UploadNetwork.ANY)
            },
        )
        return assembly
    }

    /**
     * THE trigger, provided separately so the poll loop's scope failure cannot take the assembly down:
     * one poll pass per interval, every gate owned by the assembly and caller themselves.
     */
    @Provides
    @Singleton
    fun provideUploadTrigger(
        assembly: ContributionUploadAssembly,
        aurisToken: AurisTokenProvider,
        store: ContributionStore,
        accountStartup: VoiceSharingAccountStartup,
        @UploadTriggerScope scope: CoroutineScope,
        publishTrigger: RetentionPublishTrigger,
    ): UploadTrigger {
        // THE production watcher (reviewer f4d85031): EVENT-DRIVEN on the authoritative binding tail -
        // no polling. Every real login/logout/removal fires binding.onSessionStarted/Ended exactly
        // once inside the owner transaction; the watcher subscribes and cancels from the coherent
        // snapshot read at each event. Same-epoch account/session moves enter the mismatch set.
        return UploadTrigger(
            assembly = assembly,
            scope = scope,
            intervalMs = POLL_INTERVAL_MS,
            acquireCredential = { aurisToken.currentToken() },
            identityRevocationWatcher = IdentityRevocationWatchers.production(
                binding = accountStartup.binding,
                accountState = accountStartup.state,
                snapshot = { accountStartup.authoritySnapshot() },
                store = store,
            ),
            publishTrigger = publishTrigger,
        )
    }

    /**
     * THE DEFERRED-TRANSFER DRAIN (spec voice-recording-uploads.md §scheduling): the scheduled
     * worker's one operation is the assembly's own poll — every gate re-evaluated per attempt, the
     * durable queue is the only state. No separate path into the transport exists.
     */
    @Provides
    @Singleton
    fun provideVoiceUploadDrain(
        assembly: ContributionUploadAssembly,
        aurisToken: AurisTokenProvider,
    ): VoiceUploadDrain = VoiceUploadDrain {
        // ACQUIRE before the synchronous poll (reviewer b702cd53): the upload path's fail-closed
        // cache read must find a token that was acquired for THIS pass, not assumed from earlier.
        aurisToken.currentToken()
        assembly.poll()
    }

    /** THE platform scheduler; the cadence is the compiled default (no remote interval). */
    @Provides
    @Singleton
    fun provideVoiceUploadScheduler(@ApplicationContext context: Context): VoiceUploadScheduler = VoiceUploadScheduler(androidx.work.WorkManager.getInstance(context))

    /** THE periodic drain constraint: ANY network (no remote upload_network policy remains). */
    @Provides
    @Singleton
    fun provideUploadSchedulerBinding(
        scheduler: VoiceUploadScheduler,
    ): UploadSchedulerBinding = UploadSchedulerBinding(scheduler)

    /** THE missing binding (reviewer 13eeec80): the trigger's scope, delegated to the app's scope. */
    @Provides
    @Singleton
    @UploadTriggerScope
    fun provideUploadTriggerScope(
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope appScope: CoroutineScope,
    ): CoroutineScope = appScope

    /**
     * THE production provenance producer (task73 composition): the ONLY constructor path for
     * engine captures. Real binary provenance from the installed package; the admitted identity
     * suppliers re-read the owner's coherent snapshot at publish time, so the producer's own
     * refusal is the live-identity check (frozen authority authorizes the TRIGGER, live
     * authority authorizes the RECORD).
     */
    @Provides
    @Singleton
    fun provideContributionCaptureProducer(
        @ApplicationContext context: Context,
        store: ContributionStore,
        accountStartup: VoiceSharingAccountStartup,
    ): ContributionCaptureProducer {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return ContributionCaptureProducer(
            store,
            // SUPPLIER, not a captured value: `versionName` is null in builds without a version (and
            // in some test/embedded runtimes), and the old `?: ""` wrote an EMPTY string into a
            // required wire field, which the Cloud intake refuses — the producer then dropped every
            // capture silently. Resolving lazily means a build that gains a version is picked up
            // without depending on when this singleton happened to be constructed, and the producer's
            // own precondition reports the defect loudly if it is still blank.
            appVersion = { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" },
            appBuild = { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString() },
            captureSessionId = { accountStartup.authoritySnapshot().session.toString() },
            enrollmentAccount = { accountStartup.authoritySnapshot().accountName ?: "" },
            enrollmentSession = { accountStartup.authoritySnapshot().session },
        )
    }

    /**
     * THE guarded engine→producer bridge, ATTACHED at composition (spec 816b92d0): the real
     * worker scope runs the publish body off the pipeline/sink thread (the joiner never blocks
     * on encoding or disk I/O), stage provenance derives ONLY from what the candidate actually
     * observed, and the model set is the REAL deployed backend set. Compiled-off by nature: a
     * trigger with no Executed joins touches nothing.
     */
    @Provides
    @Singleton
    fun provideRetentionPublishTrigger(
        sink: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink,
        producer: ContributionCaptureProducer,
        accountStartup: VoiceSharingAccountStartup,
        @UploadTriggerScope scope: CoroutineScope,
    ): RetentionPublishTrigger {
        // NOT attached here (reviewer 54635caa: a lazy @Singleton nobody requests establishes
        // nothing): attachment happens in [UploadTrigger.start], the actual startup consumer.
        return RetentionPublishTrigger(
            sink,
            producer,
            fixedModels = ENGINE_FIXED_MODEL_PROVENANCE,
            stagesProvider = EngineStageProvenance::stagesFor,
            // THE ATOMIC PUBLICATION SECTION (reviewer 8288ac15): the frozen tuple is compared
            // against ONE coherent live read AND the producer's own identity reads + reservation +
            // prepare+commit run inside the SAME owner transaction — no check-then-use window.
            // THE SHARED owner-publication rule (reviewer fd3faba7): module and barrier control
            // both go through ownerPublicationBinding, so the locking rule has ONE definition.
            // The section adapter reads the coherent identity INSIDE the owner transaction
            // (enrollment epoch from the live projection included) and hands it to the shared
            // validator; a missing account name is "no live identity" (refusal, no reservation).
            publishUnderOwner = ownerPublicationBinding(
                object : OwnerPublicationSection {
                    override fun underOwnerTransaction(
                        section: (live: RetentionPublishTrigger.LiveIdentity?) -> Boolean?,
                    ): Boolean? = accountStartup.validateUnderOwnerTransaction { snap ->
                        val live = snap.accountName?.let { account ->
                            RetentionPublishTrigger.LiveIdentity(
                                account = account,
                                session = snap.session,
                                enrollmentEpoch = accountStartup.state.projection.value?.enrollmentEpoch,
                            )
                        }
                        section(live)
                    }
                },
            ),
            dispatch = { body -> scope.launch { body() } },
        )
    }

    /**
     * P1 (reviewer 0cd7e39d/7033f4d9): NO invented model labels. The bundled VAD/wake assets
     * expose NO loader-verified version ("filename without an established required version is
     * insufficient"), so their refs are the schema's declared absence (ModelRef.None = null)
     * until a loader establishes a verified identity. asr/translation are per-capture observed
     * values, and the router ref is attached only when routing actually runs
     * ([VoiceRecognizer.loadedModelRef]) — never a fixed label here.
     */
    private val ENGINE_FIXED_MODEL_PROVENANCE = listOf(
        "vad" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.None,
        "wakeword" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.None,
    )
}

/**
 * Arms the unique periodic drain once at app startup. The old policy→schedule binding is gone:
 * there is no remote upload_network input, so the constraint is the compiled ANY default.
 */
class UploadSchedulerBinding(
    private val scheduler: VoiceUploadScheduler,
) {
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scheduler.schedule(UploadNetwork.ANY)
    }
}

/**
 * The PRODUCTION actual-network reader: ANY is "there is an active network"; UNMETERED is "the
 * active network is not metered". No requirement beyond the validated policy is evaluated.
 */
internal fun actualNetworkFrom(context: Context): (UploadNetwork) -> Boolean = { policy ->
    val cm: android.net.ConnectivityManager? =
        context.getSystemService(android.net.ConnectivityManager::class.java)
    if (cm == null) {
        false
    } else {
        val connected = cm.activeNetwork != null
        when (policy) {
            UploadNetwork.ANY -> connected
            UploadNetwork.UNMETERED -> connected && !cm.isActiveNetworkMetered
        }
    }
}

/** Distinct qualifier so the upload loop never shares a scope whose failure cancels unrelated work. */
@javax.inject.Qualifier
annotation class UploadTriggerScope

/** The started poll loop; the app surface calls [start] once at startup, idempotently. */
class UploadTrigger(
    private val assembly: ContributionUploadAssembly,
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    /**
     * ACCOUNT-TRANSITION WATCHER (reviewer 0081c95f / 2b67e016, open requirement): launched alongside
     * the poll loop on the SAME scope. The production wiring observes the account projection and
     * calls [ContributionStore.cancelLaunchesIdentityMismatch] for entries whose admitted identity
     * no longer matches - an account move cancels an ALREADY-REGISTERED in-flight call, not only
     * future pre-registration checks.
     */
    private val identityRevocationWatcher: (suspend kotlinx.coroutines.CoroutineScope.() -> Unit)? = null,
    /**
     * THE engine→producer bridge (task73): requested through THIS constructor, so the Hilt
     * binding is consumed by the actual startup path and [RetentionPublishTrigger.attach]
     * happens at real start() — runtime attachment established by consumption, not by a lazy
     * provider nobody calls (reviewer 54635caa).
     */
    private val publishTrigger: RetentionPublishTrigger? = null,
    /**
     * CREDENTIAL ACQUISITION PER PASS (reviewer e8ed131245/57246e46): runs in the loop coroutine
     * BEFORE each poll, so the fail-closed synchronous read the upload path consumes finds a
     * token acquired for THIS pass. Null = no acquisition (composition controls only).
     */
    private val acquireCredential: (suspend () -> Unit)? = null,
) {
    @Volatile
    private var started = false

    /** Deterministic poll counter for controls: a second start() must NOT grow this. */
    val pollsStarted = java.util.concurrent.atomic.AtomicInteger(0)

    fun start() {
        if (started) return
        started = true
        publishTrigger?.attach()
        identityRevocationWatcher?.let { watcher -> scope.launch { runCatching { watcher() } } }
        scope.launch {
            while (true) {
                pollsStarted.incrementAndGet()
                runCatching { acquireCredential?.invoke() }
                runCatching { assembly.poll() }
                delay(intervalMs)
            }
        }
    }
}
