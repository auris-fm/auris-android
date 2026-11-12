package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

/**
 * THE production outcome wait, as ONE plain constant (spec over-design cut 4/5): the selected
 * wait and its construction ceiling were two independent validated inputs; production only
 * ever wired the one value below, so the type collapses to the constant itself. 45 s is a
 * finite cap on how long a retained capture may stay open without a terminal outcome — at
 * expiry the capture is finalized as outcome_wait_expired and PUBLISHED, never left open
 * forever (spec 0d59b1d5).
 */
internal const val PRODUCTION_OUTCOME_WAIT_MS: Long = 45_000L
