package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PRODUCTION construction and trigger of the upload assembly (spec e842c497: compiled-off source wiring).
 *
 * NOTHING here can put a request on the network unless ALL of these hold, re-checked on every poll:
 * - the published policy snapshot is `enabled` (remote-config backed; the compiled default is DISABLED);
 * - the transport can produce credentials from the account startup's owner-published state;
 * - the store's own ownership fence still validates the generation the poll captured.
 *
 * The upload endpoint is deliberately blank until the route contract is confirmed with the cloud lane -
 * the endpoint actually in service is not decidable from this repo, and wiring a guessed URL would be an
 * activation change, which this module explicitly does not do.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceUploadModule {

    private const val CAPTURES_ROOT = "voice-contributions"
    private const val POLL_INTERVAL_MS = 60_000L

    @Provides
    @Singleton
    fun provideContributionStore(
        @ApplicationContext context: Context,
    ): ContributionStore = ContributionStore(
        File(context.filesDir, CAPTURES_ROOT),
        QueueLimits.defaults(),
    )

    @Provides
    @Singleton
    fun provideUploadTransport(
        accountStartup: VoiceSharingAccountStartup,
    ): ContributionUploadTransport = ContributionUploadTransport(
        // Route contract from cloud-voice-uploads 476feadc: POST /api/v1/voice-recordings, server-issued
// staged key; the HOST stays a deployment-time input (no verified live host), so the endpoint is
// blank until deployment provides one. A blank endpoint cannot be
        // requested.
        endpoint = "",
        // Bearer comes ONLY from the owner-published credential state, resolved per attempt. The epoch
        // binding happens in [provideUploadAssembly]: a credential is only CONSULTED for a capture whose
        // admitted epoch is still the account's current epoch.
        authorization = {
            accountStartup.credentials.current()?.bearer?.takeIf { it.isNotBlank() }
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
        policyProvider: VoiceSharingPolicyProvider,
        accountStartup: VoiceSharingAccountStartup,
    ): ContributionUploadAssembly {
        // Network availability is "the owner-published credential exists": without it nothing can be sent.
        val accountStartupHasCredentials = { accountStartup.credentials.current() != null }
        val authority = {
            UploadIdentityAuthority(
                state = accountStartup.state,
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
                    )
                }
            },
            identityHolds = { epoch -> accountStartup.state.projection.value?.enrollmentEpoch == epoch },
            // ONE full-identity credential authority: account, session, credential, token and epoch
            // projection read under the REAL owner lock in a single transaction; the ADMITTED
            // account/session/epoch are compared, not merely read.
            credentialFor = { admitted ->
                authority().credentialFor(admitted)
            },
            // LAUNCH revalidation: the acquisition lock ends before transmission; this closes the
            // acquisition→Call window on the caller thread (reviewer d2f1bfc8).
            launchIdentityCheck = { admitted -> authority().credentialFor(admitted) != null },
            clock = System::currentTimeMillis,
            // THE compiled-off gate: the remote-config snapshot is disabled by default, so the loop
            // below wakes, finds the gate closed, and touches nothing.
            policyAllows = { policyProvider.publishedPolicy.value.enabled },
            networkAvailable = accountStartupHasCredentials,
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
            assembly,
            scope,
            POLL_INTERVAL_MS,
            IdentityRevocationWatchers.production(
                binding = accountStartup.binding,
                accountState = accountStartup.state,
                snapshot = { accountStartup.authoritySnapshot() },
                epoch = { accountStartup.state.projection.value?.enrollmentEpoch },
                store = store,
            ),
            publishTrigger = publishTrigger,
        )
    }

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
            appVersion = pkg.versionName ?: "",
            appBuild = pkg.longVersionCode.toString(),
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
            publishUnderOwner = { frozen, commit ->
                accountStartup.validateUnderOwnerTransaction { snap ->
                    val epoch = accountStartup.state.projection.value?.enrollmentEpoch
                    if (snap.accountName == frozen.account && snap.session == frozen.session && epoch == frozen.enrollmentEpoch) commit() else false
                }
            },
            dispatch = { body -> scope.launch { body() } },
        )
    }

    /**
     * The FIXED app-deployment models (these do not vary per capture). The per-capture ASR and
     * translation refs travel WITH the frozen capture context — never fixed labels here.
     */
    private val ENGINE_FIXED_MODEL_PROVENANCE = listOf(
        "vad" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("silero-vad", "4.0"),
        "wakeword" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("auris-wake", "1.2"),
        "router" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("lfm-intent", "0.3"),
    )
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
                runCatching { assembly.poll() }
                delay(intervalMs)
            }
        }
    }
}
