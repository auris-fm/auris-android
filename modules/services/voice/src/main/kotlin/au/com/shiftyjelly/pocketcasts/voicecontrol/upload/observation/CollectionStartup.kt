package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2.UploaderLoop
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Production composition owner for the collection boundary (reviewer 01877984 blocker 1: no
 * caller graph existed). Injected singletons with no consumer are NOT composition — this class
 * is instantiated from the application's onCreate exactly like VoiceSharingAccountStartup
 * and [VoiceSharingAccountStartup], and it starts every loss subscription for real:
 *
 *   observer ← sink.onRetentionEnded wiring            (provider, VoiceObservationModule)
 *   watcher.start() → accounts listener, enrollment hook, policy flow, exact lease deadline
 *
 * NO ALLOWANCE COMPOSITION (core c74d7e30, spec §"Actual upload usage"). This class used to own the
||||||| /tmp/mfb
 * no allowance composition (core c74d7e30, spec §"Actual upload usage"). This class used to own the
 * capture-allowance lifecycle, its retirement watcher and the provisioner entry point. Those are
 * removed: capture no longer reserves daily capacity before it starts, and the daily limit is
 * metered server-side at acceptance of actual audio bytes. Legacy `no_backup/voice_allowance/`
 * files are deliberately left on disk as inert state — they are not read, not deleted, and not
 * grounds to clear app data (plan delta item 2).
 */
@Singleton
class CollectionStartup @Inject constructor(
    private val watcher: CollectionLossWatcher,
    private val accountStartup: VoiceSharingAccountStartup,
    private val authority: CollectionAuthority,
    private val observer: SegmentObserver,
    private val outcomeFinalizer: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer,
    private val uploaderLoop: UploaderLoop,
    @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope private val appScope: CoroutineScope,
) {
    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        // Enrollment evidence: the state fence's post-commit hook feeds the authority AND the
        // loss watcher; both are set before any capture can start because initializeOnAppStart
        // runs at app startup, ahead of any voice session.
        watcher.start() // installs chained evidence + loss hooks (single composition point)
        // Bounded outcome finalization hooks onto the sink: a retained capture finalizes within
        // the fixed window (background-only; it is never an admission gate).
        outcomeFinalizer.start()
        startUploader()
    }

    /**
     * The one uploader loop. Started at app start and never restarted per capture: a recording is
     * picked up by the next pass, so publishing never depends on the uploader being awake at that
     * instant. Backoff lives in the loop, so a stalled recording simply waits its turn.
     */
    private fun startUploader() {
        if (uploaderStarted) return
        uploaderStarted = true
        appScope.launch {
            while (isActive) {
                runCatching { uploaderLoop.runOnce() }
                delay(UPLOAD_POLL_MS)
            }
        }
    }

    private var uploaderStarted = false

    private companion object {
        const val UPLOAD_POLL_MS = 60_000L
    }
}
