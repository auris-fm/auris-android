package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Fires the optional playback-start prefetch hint
 * (`POST /api/v1/cloud/context/prefetch`, cloud-assistant.md).
 *
 * Structurally non-blocking: it observes the playback state in its own
 * coroutine and never participates in playback preparation. Gated by
 * [CloudPrefetchSettings.isEnabled] (default off), so merging it adds no new
 * production traffic until the flag is switched on.
 */
@Singleton
class CloudPrefetchObserver @Inject constructor(
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val playbackManager: PlaybackManager,
    private val prefetchClient: CloudPrefetchHinter,
    private val settings: CloudPrefetchFlag,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        applicationScope.launch {
            playbackManager.playbackStateFlow
                .map { state -> state.episodeUuid.takeIf { state.isPlaying && it.isNotEmpty() } }
                .distinctUntilChanged()
                .collect { episodeUuid ->
                    if (episodeUuid == null || !settings.isEnabled()) return@collect
                    // Best effort: the client swallows every failure and does
                    // not retry, so a hint can never affect playback.
                    prefetchClient.prefetch(episodeUuid)
                }
        }
        Timber.i("CloudPrefetchObserver started")
    }
}
