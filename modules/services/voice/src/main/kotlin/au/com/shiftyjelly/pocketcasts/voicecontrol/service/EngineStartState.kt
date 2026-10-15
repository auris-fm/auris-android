package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Whether the ASR engine is running, and whether a start that is still suspended has been
 * superseded.
 *
 * The engine has a single starter — the mode-flow collector — and a `StateFlow` collector is
 * sequential, so one start can never overlap another and no start lock is needed. What a start can
 * do is suspend in the backend readiness wait and outlive the state that justified it, so the
 * generation is bumped by a stop or a gate loss and the suspended start abandons instead of
 * committing capture.
 */
internal class EngineStartState {

    private val startGeneration = AtomicInteger(0)

    @Volatile
    var isStarted: Boolean = false
        private set

    /** The generation to capture before a start attempt; validate it with [isCurrent]. */
    fun generation(): Int = startGeneration.get()

    /** True while [generation] has not been superseded by [invalidatePendingStart] or [onStopped]. */
    fun isCurrent(generation: Int): Boolean = startGeneration.get() == generation

    /**
     * Runs [start] unless the engine is already running, then runs [onStarted] once. Returns true
     * when the engine is running afterwards, false when [start] reported failure.
     *
     * [onStarted] runs *after* the started state is recorded, so a throw from a post-start side
     * effect (the listening earcon, the foreground notification) cannot un-set it. The caller uses
     * the result to decide between handing an acquired stream to the engine and releasing it; a
     * failure here must never release a stream the engine already owns.
     */
    suspend fun startIfNotRunning(
        onStarted: suspend () -> Unit = {},
        start: suspend () -> Boolean,
    ): Boolean {
        if (isStarted) return true
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
        return started
    }

    /**
     * Abandons any start that is still suspended (there may be no running engine yet), so a gate
     * loss cannot be followed by a stale start committing capture. Leaves [isStarted] untouched.
     */
    fun invalidatePendingStart() {
        startGeneration.incrementAndGet()
    }

    /** Marks the engine stopped and abandons any pending start. */
    fun onStopped() {
        startGeneration.incrementAndGet()
        isStarted = false
    }
}
