package au.com.shiftyjelly.pocketcasts.repositories.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * The token-addressed registry between seek invocations and native completions.
 *
 * Every seek issued WITH a completion token arms its signal here; every `PlayerEvent.SeekComplete`
 * carrying a token is dispatched to EXACTLY that invocation's signal. Ownership is proven by the
 * token travelling through the native request — a seek this invocation did not cause produces an
 * event with a different (or null) token, which is DROPPED, never queued: it cannot consume or
 * settle another operation's wait. A stale entry — an operation that timed out without a callback —
 * is equally harmless until explicitly released, because only its own token can address it.
 */
class SeekCompletionCoordinator {
    private val signals = ConcurrentHashMap<String, PlaybackManager.SeekCompletionSignal>()

    /** Registers [signal] so an event carrying its token completes it. */
    fun arm(signal: PlaybackManager.SeekCompletionSignal) {
        signals[signal.token] = signal
    }

    /** Drops [signal]'s entry: its token can no longer be completed from the native path. */
    fun release(signal: PlaybackManager.SeekCompletionSignal) {
        signals.remove(signal.token)
    }

    /**
     * Delivers a native completion to the invocation that owns [event]'s token. Returns true when a
     * live signal was completed; an unknown, null or already-released token drops the event.
     */
    fun dispatch(event: PlaybackManager.SeekCompletionEvent): Boolean {
        val token = event.completionToken ?: return false
        val signal = signals[token] ?: return false
        signals.remove(token)
        signal.completeWith(event)
        return true
    }

    /** Whether [signal] is still armed (its completion has not been delivered or released). */
    fun isArmed(signal: PlaybackManager.SeekCompletionSignal): Boolean = signals.containsKey(signal.token)
}
