package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import kotlinx.coroutines.flow.StateFlow

interface AudioRouteMonitor {
    companion object {
        /**
         * The route the SCO **setup** decision runs on.
         *
         * Setup asks "what should be set up?", which is what [availability] answers: a paired
         * classic-Bluetooth headset is present, whether or not a route has been observed. Reading the
         * observed [route] here answers `Unknown` whenever no stream is reporting -- and this
         * observer's only publisher is the cloud player, which publishes only while a cloud answer
         * sounds -- so the SCO branch never ran and capture could bind the phone's microphone rather
         * than the headset's.
         *
         * Named as a function so the choice is checkable rather than being a call-site convention:
         * the value it returns is the value the engine is started with.
         */
        fun setupRoute(monitor: AudioRouteMonitor): AudioRoute = monitor.availability.value
    }

    /**
     * Where audio is **observed** to be routed, or [AudioRoute.Unknown] when no live stream reports it.
     *
     * Route-dependent decisions read this. Unknown is a real answer and must not be replaced by
     * availability: "a headset is paired" is not "the audio goes to it", and treating the first as the
     * second imposes headset setup on a session that may be using the speaker.
     */
    val route: StateFlow<AudioRoute>

    /**
     * Which outputs are **available**, for setup and discovery — a different question from [route].
     *
     * Kept separate so a caller that legitimately needs "what could I connect to" does not have to
     * infer it from a routed answer, and so a routed answer is never derived from availability.
     */
    val availability: StateFlow<AudioRoute>
}
