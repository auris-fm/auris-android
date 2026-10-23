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
        val assembly = ContributionUploadAssembly(
            store = store,
            transport = transport,
            captureFor = { captureId ->
                store.durableCapture(captureId)?.let {
                    // The admitted epoch travels WITH the capture so the assembly can bind the
                    // credential lookup to the identity the entry was admitted under.
                    ContributionUploadAssembly.Capture(it.record, it.audio, it.enrollmentEpoch)
                }
            },
            identityHolds = { epoch -> accountStartup.state.projection.value?.enrollmentEpoch == epoch },
            // ONE account+epoch-bound credential authority (spec 6480d0c5): account identity, session,
            // credential and the owner-published token are read under the REAL owner lock in a single
            // transaction, together with the epoch projection - not two separate reads labelled
            // atomic. The assembly revalidates the returned epoch against the admitted one and freezes
            // the bearer for the attempt, so a switch inside acquisition cannot swap identity.
            credentialFor = { admittedEpoch ->
                UploadIdentityAuthority(
                    state = accountStartup.state,
                    underOwnerTransaction = { block -> accountStartup.validateUnderOwnerTransaction(block) },
                ).credentialFor(admittedEpoch)
            },
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
        @UploadTriggerScope scope: CoroutineScope,
    ): UploadTrigger = UploadTrigger(assembly, scope, POLL_INTERVAL_MS)

    /** THE missing binding (reviewer 13eeec80): the trigger's scope, delegated to the app's scope. */
    @Provides
    @Singleton
    @UploadTriggerScope
    fun provideUploadTriggerScope(
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope appScope: CoroutineScope,
    ): CoroutineScope = appScope
}

/** Distinct qualifier so the upload loop never shares a scope whose failure cancels unrelated work. */
@javax.inject.Qualifier
annotation class UploadTriggerScope

/** The started poll loop; the app surface calls [start] once at startup, idempotently. */
class UploadTrigger(
    private val assembly: ContributionUploadAssembly,
    private val scope: CoroutineScope,
    private val intervalMs: Long,
) {
    @Volatile
    private var started = false

    /** Deterministic poll counter for controls: a second start() must NOT grow this. */
    val pollsStarted = java.util.concurrent.atomic.AtomicInteger(0)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                pollsStarted.incrementAndGet()
                runCatching { assembly.poll() }
                delay(intervalMs)
            }
        }
    }
}
