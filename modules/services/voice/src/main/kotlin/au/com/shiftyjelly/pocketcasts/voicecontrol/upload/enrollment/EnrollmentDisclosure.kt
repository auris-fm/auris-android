package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment

/**
 * The enrollment disclosure the UI renders and the version the client accepts.
 *
 * WHY THIS IS A BUILD CONSTANT AND NOT SERVER-READ. The membership projection carries
 * `accepted_disclosure_version` (what an account previously accepted) but no field naming the
 * server's *supported* disclosure. The spec makes the server the validator: it checks the submitted
 * version against its supported set and refuses enrollment on a mismatch, and refuses an
 * unconfigured disclosure outright rather than silently enrolling. So the client cannot discover the
 * right version at runtime; it must ship one that matches, and a mismatch fails closed as an error
 * rather than recording a consent the user did not actually see.
 *
 * THE INVARIANT THAT MATTERS: [VERSION] must be the version of the copy rendered in
 * [body] below. If the disclosure text changes, the version MUST change with it in the same commit.
 * Sending a new version with old text records consent to wording the user never read; changing the
 * text without the version means returning users are treated as having accepted the new wording
 * when they accepted the old one.
 *
 * [body] is the canonical English source. The rendered strings live in the localization module
 * (`voice_sharing_disclosure_supporting` / `_detail`) and must stay in step with it; the test asserts
 * the invariant and the presence of both resources rather than trusting the comment.
 */
object EnrollmentDisclosure {
    const val VERSION: String = "2026-10-01"

    /** The facts this disclosure must communicate, in the spec's terms. */
    const val TITLE: String = "Help improve voice control"
    const val SETTING: String = "Share voice recordings"
    const val SUPPORTING: String =
        "Send your voice recordings, recognized text and command results to help improve Auris."
    const val DETAIL: String =
        "Recordings may include background speech and speech that did not activate Auris. " +
            "Your audio, the text Auris recognized, and the actions it took leave your device " +
            "so the models can be improved."

    /** Facts the spec requires to be disclosed; used by the control to catch silent copy drift. */
    val REQUIRED_FACTS: List<String> = listOf(
        "voice recordings",
        "recognized text",
        "command results",
        "background speech",
        "did not activate",
    )
}
