package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.LegacyQueueRemoval
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.UploaderLoop
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.UploaderWakes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
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
    private val outcomeFinalizer: CaptureOutcomeFinalizer,
    private val uploaderLoop: UploaderLoop,
    private val foregroundStateMonitor: ForegroundStateMonitor,
    @ApplicationContext private val appContext: android.content.Context,
    @ApplicationScope private val appScope: CoroutineScope,
    /**
     * Removes the previous upload path's queue root.
     *
     * It runs here rather than when the queue is constructed, because the queue is built while the
     * object graph is assembled - before logging exists - so a deletion there leaves no record of
     * itself. This hook runs at app start, after logging is live.
     */
    private val legacyQueueRemoval: LegacyQueueRemoval,
) {
    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        // Enrollment evidence: the state fence's post-commit hook feeds the authority AND the
        // loss watcher; both are set before any capture can start because initializeOnAppStart
        // runs at app startup, ahead of any voice session.
        legacyQueueRemoval.run()
        watcher.start() // installs chained evidence + loss hooks (single composition point)
        // Bounded outcome finalization hooks onto the sink: a retained capture finalizes within
        // the fixed window (background-only; it is never an admission gate).
        outcomeFinalizer.start()
        startUploader()
    }

    /**
     * The uploader's wake wiring (event wakes, no
     * poller). One worker drains the queue, woken by:
     * - a recording published ([RecordingPublisher]`s publish wake),
     * - an authority move on the account binding (sign-in restored — and sign-out, where the
     *   pass simply finds nothing eligible),
     * - the app coming to the foreground,
     * - the network coming back,
     * plus exactly ONE timer armed at the loop's earliest retry time (exponential backoff,
     * capped at one hour, in memory). There is deliberately no periodic tick.
     */
    private fun startUploader() {
        if (uploaderStarted) return
        uploaderStarted = true
        val wakes = UploaderWakes(
            earliestRetryAtMs = uploaderLoop::earliestRetryAtMs,
            nowMs = uploaderLoop::nowMs,
            pass = { runCatching { uploaderLoop.runOnce() } },
        )
        wakes.startPass(appScope)
        // The publish wake: a queued recording drains immediately (no poller to catch it later).
        publishWake = wakes::wake
        // Sign-in wake: the binding's authority-move slot (single listener, otherwise unused
        // since the presentation fence moved to the owner's session atomic).
        accountStartup.authorityMovedListener = wakes::wake
        // Foreground wake: every foreground arrival drains.
        appScope.launch {
            foregroundStateMonitor.isInForeground.collect { inForeground ->
                if (inForeground) wakes.wake()
            }
        }
        registerNetworkWake(wakes)
    }

    /** THE PUBLISH WAKE TARGET: set by the wake worker, invoked by the publisher's hook. */
    @Volatile
    var publishWake: (() -> Unit)? = null

    private var uploaderStarted = false

    /**
     * THE NETWORK-BACK WAKE: a default network callback fires [UploaderWakes.wake] when the
     * system reports the default network became available (or its capabilities changed to
     * include internet). Best-effort: a device where registration fails falls back to the retry
     * timer and the other wakes, never to a poller.
     */
    private fun registerNetworkWake(wakes: UploaderWakes) {
        runCatching {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    wakes.wake()
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    capabilities: NetworkCapabilities,
                ) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        wakes.wake()
                    }
                }
            }
            manager.registerDefaultNetworkCallback(callback)
        }
    }
}
