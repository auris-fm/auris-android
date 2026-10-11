package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bridge between a live stream's routed output and the route monitor.
 *
 * The monitor cannot ask a player it does not own, and it must not guess: whichever stream is actually
 * sounding is the one whose routing is the answer. A player publishes what its platform stream reports
 * here, and the monitor reads it. When nothing is publishing — no stream, an idle stream, or one that
 * cannot report — the value is null and the monitor reports [AudioRoute.Unknown] rather than falling
 * back to what is merely *available*.
 *
 * A single slot is deliberate: one route is sounding at a time by construction, and a scope token lets a
 * retiring player clear only its own observation, so a replaced stream cannot leave a stale route behind
 * for its successor.
 */
@Singleton
class RoutedOutputObserver @Inject constructor() {
    private val holders = AtomicInteger(0)
    private val claimHolders = AtomicInteger(0)

    @Volatile
    private var type: Int? = null

    /** The routed output type, or null when no live stream is reporting one. */
    fun current(): Int? = type

    /**
     * Whether a stream currently holds the observation. A holder may legitimately have no type to
     * report (an idle stream), so this is a separate question from [current] and lets a test assert the
     * claim lifecycle without a device.
     */
    internal fun holdsAClaim(): Boolean = claimHolders.get() > 0

    /**
     * Claims the observation for a stream and returns [ObservedRoute] to publish through.
     *
     * Each claim gets its own token, so [ObservedRoute.clear] from a superseded stream is ignored rather
     * than erasing the route its replacement has since reported.
     */
    fun observe(): ObservedRoute {
        val mine = holders.incrementAndGet()
        claimHolders.incrementAndGet()
        return ObservedRoute(mine)
    }

    /** Withdraws the claim only if it is still the current one and still live. */
    private fun releaseClaim(claim: ObservedRoute) {
        if (claim.token != holders.get()) return
        // A release is idempotent per claimed state: the bit flips once, so stop() followed by
        // release() cannot decrement twice, while a later session re-takes the same claim.
        if (!claim.active.compareAndSet(true, false)) return
        claimHolders.decrementAndGet()
        type = null
    }

    inner class ObservedRoute internal constructor(private val mine: Int) {
        /** The stream's ownership token, compared against [holders] to reject a superseded stream. */
        internal val token: Int get() = mine

        /**
         * Whether this claim is currently held. One bit per claim, which gives both properties the
         * lifecycle needs: a release is idempotent per claimed state (not per token), and a claim is
         * reusable across sessions — publish, clear, publish again, clear again — because clearing
         * ends the session rather than spending the claim forever.
         */
        internal val active = java.util.concurrent.atomic.AtomicBoolean(true)

        /** Publishes where this stream's output is routed. Null when the stream cannot report. */
        fun publish(device: AudioDeviceInfo?) = publishType(device?.type)

        /**
         * The same publication by type, so a test can exercise the holder's contract without an
         * `AudioDeviceInfo`, which cannot be constructed off a device.
         */
        internal fun publishType(routedType: Int?) {
            // Ownership AND liveness: the token stays equal to `holders` forever, so it alone cannot
            // tell a live claim from a spent one. A cleared claim that could still write `type` would
            // publish a route nothing can withdraw — which is the same defect as never clearing it.
            if (!active.get()) return
            if (token != holders.get()) return
            type = routedType
        }

        /** Stops publishing. Only effective while this stream is still the current one. */
        fun clear() = releaseClaim(this)
    }
}
