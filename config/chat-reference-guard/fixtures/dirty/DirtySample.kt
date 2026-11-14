package au.com.shiftyjelly.pocketcasts.voicecontrol.fixture

// Fixtures of references the guard must catch. Each planted line ends in a deny marker; the
// selftest requires the number of findings to equal the number of markers.
//
// A line ending in the gap marker records a form the shared core rule does not cover. It is
// listed so the gap stays visible rather than forgotten, and it is NOT counted as planted.

/** The route is Unknown between release and acquire (reviewer 63f99bed: the gap). DENY */
class DirtySample {
    fun handoffWindow() = Unit
    // @spec 2243570a, the producer-to-owner handoff window: play() then read. DENY

    fun preBodyExit() = Unit
    // THE PRE-BODY EXIT (reviewer dab802ec): a launch finally would never run. DENY

    fun quotedRuling() = Unit
    // Reviewer's distinction: "a seek carries a position, not a span". KNOWN-GAP

    fun onByDefault() = Unit
    // ON BY DEFAULT since reviewer 486205ae: speak() reports a synthesized-but-unplayable DENY

    fun specRuling() = Unit
    // The disposal path refuses a handoff (spec ruling 7c7575f6). KNOWN-GAP

    // A framework attribute earlier on the line must not hide the reference after it.
    fun attributeBesideReference() = Unit
    // tint is @android:color/white here, as @spec asked. DENY

    // A block opened after a closed one, with an inner line that does NOT start with a star.
    fun reopenedBlock() = Unit
    /* a */ /* b
 per @reviewer this line is inside the reopened block DENY
 */
}
