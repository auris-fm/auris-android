package au.com.shiftyjelly.pocketcasts.voicecontrol.fixture

// Each line below is a reference the guard must catch. The form of each one is what
// the repository actually contained: a bare lane mention, a hex handoff, a quoted
// ruling, and a possessive attribution.

/** The route is Unknown between release and acquire (reviewer 63f99bed: the gap). */
class DirtySample {
    // @spec 2243570a, the producer-to-owner handoff window: play() then read.
    fun handoffWindow() = Unit

    // THE PRE-BODY EXIT (reviewer dab802ec): a launch finally would never run.
    fun preBodyExit() = Unit

    // Reviewer's distinction: "a seek carries a position, not a span", so the interval
    // cannot become a timed action.
    fun quotedRuling() = Unit

    // ON BY DEFAULT since reviewer 486205ae: speak() reports a synthesized-but-unplayable
    // track instead of a silent success.
    fun onByDefault() = Unit

    // The disposal path refuses a handoff (spec ruling 7c7575f6).
    fun specRuling() = Unit
}
