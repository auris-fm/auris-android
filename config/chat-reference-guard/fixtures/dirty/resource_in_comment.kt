package au.com.shiftyjelly.pocketcasts.voicecontrol.fixture

// A resource attribute written inside a comment is still a citation to fix, so the guard
// reports it. That looks wrong and is deliberate: the tree has no such attribute in a
// Kotlin comment, so an exemption would cover a collision the guard never sees while
// letting a lane followed by a colon through.
// the tint is @android:color/white DENY
fun resourceInComment() = Unit
