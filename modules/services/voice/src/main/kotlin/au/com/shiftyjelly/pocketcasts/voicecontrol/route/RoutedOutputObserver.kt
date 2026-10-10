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

    /** Withdraws the claim of the stream that owns [mine] only if it is still the current one. */
    private fun releaseClaim(mine: Int) {
        if (mine != holders.get()) return
        claimHolders.decrementAndGet()
        type = null
    }

    inner class ObservedRoute internal constructor(private val mine: Int) {
        /** Publishes where this stream's output is routed. Null when the stream cannot report. */
        fun publish(device: AudioDeviceInfo?) = publishType(device?.type)

        /**
         * The same publication by type, so a test can exercise the holder's contract without an
         * `AudioDeviceInfo`, which cannot be constructed off a device.
         */
        internal fun publishType(routedType: Int?) {
            if (mine != holders.get()) return
            type = routedType
        }

        /** Stops publishing. Only effective while this stream is still the current one. */
        fun clear() = releaseClaim(mine)
    }
}
