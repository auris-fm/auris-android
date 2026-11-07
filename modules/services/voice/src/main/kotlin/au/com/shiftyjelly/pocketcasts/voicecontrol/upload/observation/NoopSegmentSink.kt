package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

/** Placeholder sink that retains nothing (used by disabled default wiring in tests). */
object NoopSegmentSink : SegmentSink {
    override fun started(observation: SegmentObservation.Started): Boolean = false
    override fun adopted(observation: SegmentObservation.Started, token: ReservationToken?): Boolean = false
    override fun incremental(observation: SegmentObservation.Incremental, samples: ShortArray) = Unit
    override fun completed(observation: SegmentObservation.Completed): Boolean = false
    override fun cancelled(observation: SegmentObservation.Cancelled) = Unit
}
