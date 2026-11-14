package au.com.shiftyjelly.pocketcasts.voicecontrol.fixture

// A lane followed by a colon is a citation. No exemption applies, including to a framework
// resource written in a comment: the tree has no such comment, so exempting the shape would
// cover a collision the guard never sees.
fun laneColon() = Unit
// as @spec: said DENY
// as @reviewer: ruled DENY
