package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Single-flight guard for starting the ASR engine.
 *
 * Both the acquisition coroutine and the mode-flow collector can try to start the engine:
 * successful acquisition changes the route to an isolated headset, which emits a new listening
 * mode and sends the collector into `startEngine` while the acquisition's own start is still
 * suspended on `ensureReady()`. Without serialization both callers pass the "not started" check
 * and call [au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine.start] twice,
 * overwriting the processing job and double-starting native capture.
 *
 * [startIfNotRunning] makes the second caller wait for the first attempt and then skip when it
 * succeeded. A failed attempt is not marked started, so the next caller retries.
 */
internal class EngineStartCoordinator {

    private val mutex = Mutex()

    @Volatile
    var isStarted: Boolean = false
        private set

    /**
     * Runs [start] with the start lock held, unless the engine is already running, then runs
     * [onStarted] once. Returns true when the engine is running afterwards, false when [start]
     * reported failure. When another caller is already starting the engine, this waits for it and
     * then reports its outcome.
     *
     * [onStarted] runs *after* the started state is recorded, so a throw from a post-start side
     * effect (the listening earcon, the foreground notification) cannot un-set it or make this
     * call report failure. The caller uses the result to decide between handing an acquired stream
     * to the engine and releasing it; a failure here must never release a stream the engine
     * already owns.
     */
    suspend fun startIfNotRunning(
        onStarted: suspend () -> Unit = {},
        start: suspend () -> Boolean,
    ): Boolean = mutex.withLock {
        if (isStarted) return@withLock true
        val started = start()
        if (started) {
            isStarted = true
            try {
                onStarted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "[VoicePipeline] post-start side effect failed")
            }
        }
        started
    }

    /** Marks the engine stopped so a later [startIfNotRunning] may start it again. */
    fun onStopped() {
        isStarted = false
    }
}
