package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import kotlinx.coroutines.flow.StateFlow

interface AudioRouteMonitor {
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
