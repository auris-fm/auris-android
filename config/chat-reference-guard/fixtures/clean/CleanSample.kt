package au.com.shiftyjelly.pocketcasts.voicecontrol.fixture

// Every comment here states its rule and names no chat lane. None of these may be flagged.

/**
 * The output stream is closed exactly once. Two owners awaiting the same close would
 * otherwise race to release it, and the loser would observe a torn buffer.
 */
class CleanSample {
    // A released capture must refuse a second start: the surface is gone and a start
    // would hand the caller a stream no reader can drain.
    fun refuseAfterRelease() = Unit

    // The SCO device's route is Unknown between release and the next acquire, so the
    // restart waits for the route to settle rather than reading through the gap.
    fun waitForSettledRoute() = Unit

    // The episode binding on a resume is about the recording the turn is on.
    fun episodeBoundResume() = Unit
}
