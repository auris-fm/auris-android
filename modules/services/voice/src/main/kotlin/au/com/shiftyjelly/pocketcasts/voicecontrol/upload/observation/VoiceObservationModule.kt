package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Composition of the collection-retention boundary (plan Task 3): the live authority is read
 * from the ACTUAL authorities — the signed-in account name and its durable saved sharing choice —
 * never reconstructed from cached gate decisions.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceObservationModule {

    @Provides
    @Singleton
    fun provideSavedSharingChoiceStore(
        persistence: SharingChoicePersistence,
    ): SavedSharingChoiceStore = SavedSharingChoiceStore(persistence)

    @Provides
    @Singleton
    fun provideSharingChoicePersistence(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): SharingChoicePersistence = SharedPreferencesChoicePersistence(context)

    @Provides
    @Singleton
    fun provideCollectionAuthority(
        accountStartup: VoiceSharingAccountStartup,
        savedSharingChoice: SavedSharingChoiceStore,
    ): CollectionAuthority = CollectionAuthority(
        accountStartup,
        savedSharingChoice,
    )

    @Provides
    @Singleton
    fun provideEngineCollectionAuthority(
        authority: CollectionAuthority,
    ): () -> AuthorityDecision = authority::decide

    /**
     * The publish path: a sealed capture becomes a queued recording.
     *
     * Attached here rather than in the sink's construction so the sink itself stays free of the
     * record and queue layers, and so a test can drive the sink with a different publisher.
     */
    @Provides
    @Singleton
    fun provideRetentionSink(
        publisher: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.RecordingPublisher,
    ): ContributionRetentionSink = ContributionRetentionSink(
        lossEventObserver = { event, count ->
            // Reason plus running count: bounded bookkeeping in the sink, the trail itself lives
            // in the persisted log, not in singleton memory.
            LogBuffer.i(RETENTION_LOG_TAG, "retention loss: $event (total $count)")
        },
    ).apply {
        onReadyForPublication = { sealed -> publisher.publish(sealed) }
    }

    @Provides
    @Singleton
    fun provideRecordingPublisher(
        queue: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.FolderQueue,
        settings: au.com.shiftyjelly.pocketcasts.preferences.Settings,
        wakeSink: dagger.Lazy<CollectionStartup>,
    ): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.RecordingPublisher = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.RecordingPublisher(
        queue = queue,
        // Real binary provenance: the record names the build that produced it.
        appVersion = { settings.getVersion() },
        appBuild = { settings.getVersionCode().toString() },
        // THE PUBLISH WAKE (S4): a queued recording wakes the uploader to drain immediately.
        onPublished = { wakeSink.get().publishWake?.invoke() },
    )

    @Provides
    @Singleton
    fun provideSegmentObserver(
        authority: CollectionAuthority,
        sink: ContributionRetentionSink,
    ): SegmentObserver = SegmentObserver(
        gate = CollectionGate { decision -> decision.shouldRetainNewWork },
        sink = sink,
        authority = authority::decide,
        issueAuthority = authority::issueAuthority,
        handoff = object : CompletionHandoff {
            override fun handoff(
                context: CaptureContext?,
                speechOnsetSample: Int,
                speechEndSample: Int,
                wakeState: SegmentObservation.WakeState,
                framesProvider: () -> Pair<List<ShortArray>, Int>,
                scope: ObserverHandoffScope,
                liveGateAllowed: Boolean,
                wakeMeasurement: SegmentObservation.WakeMeasurement?,
            ) {
                val issued = context?.issuedAuthority
                val authorized = issued != null && issued.accountName != null &&
                    authority.validateIssuedAuthority(issued)
                // Identity check FIRST: a stale/unknown context is a late NO-OP, never a purge of
                // the current capture's audio.
                if (context != null && !scope.ownsCapture(context.captureId)) return
                if (!authorized || !liveGateAllowed) {
                    // Refusal STILL terminates: exactly one terminal, prefix PCM + charge released
                    // via the cancel path — never a silent return leaving live uncharged audio.
                    scope.completeUnderOwner(
                        context,
                        speechOnsetSample,
                        speechEndSample,
                        wakeState,
                        framesProvider,
                        admit = false,
                        liveGateAllowed = liveGateAllowed,
                        wakeMeasurement = wakeMeasurement,
                    )
                    return
                }
                scope.completeUnderOwner(
                    context,
                    speechOnsetSample,
                    speechEndSample,
                    wakeState,
                    framesProvider,
                    admit = true,
                    liveGateAllowed = true,
                    wakeMeasurement = wakeMeasurement,
                )
            }
        },
    ).also { observer ->
        sink.onRetentionEnded = { context -> observer.noteRetentionEnded(context) }
    }

    // The withdrawal marker is the WITHDRAWN record in the saved sharing choice store
    // (SharedPreferences, 2026-11-12): no directory barrier, no separate latch. A failed commit is
    // logged; the in-memory value stands, so admission never wedges on storage.

    /** BATCH B bounded outcome finalization with THE production 45 s wait (see the constant). */
    @Provides
    @Singleton
    fun provideCaptureOutcomeFinalizer(
        sink: ContributionRetentionSink,
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer(
        sink = sink,
        waitLimitsMs = PRODUCTION_OUTCOME_WAIT_MS,
        scope = scope,
        // Same monotonic domain the sink composes by default (production clock).
        nowMs = { System.nanoTime() / 1_000_000L },
    )

    /**
     * Starts the PRODUCTION loss subscriptions (CollectionLossWatcher): account change and the
     * enrollment/withdrawal post-commit hook. Called from the app surface once at startup;
     * idempotent inside the watcher.
     */
    @Provides
    @Singleton
    fun provideLossWatcher(
        observer: SegmentObserver,
        authority: CollectionAuthority,
        accountStartup: VoiceSharingAccountStartup,
        syncAccountManager: au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager,
        @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): CollectionLossWatcher = CollectionLossWatcher(observer, authority, accountStartup, syncAccountManager, scope)
}

/** 64 MiB aggregate ceiling for upload-owned PCM (queue bounds refine this in Task 5). */
private const val RETENTION_LOG_TAG = "VoiceRetention"
