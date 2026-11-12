package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * What the uploader does with one answer.
 *
 * The spec's answer table is exhaustive and has four outcomes. Everything that used to be a named
 * code - conflict, too large, unsupported format, integrity mismatch, quota, the three 503
 * variants - collapses into [Retry] or [Delete], because the client's only decision was ever
 * "throw this away" or "try again".
 */
enum class Answer {
    /** 201 accepted, or 200 already accepted. The recording is done; delete it. */
    Accepted,

    /** 401: sign-in is missing or expired. Refresh, then retry the same recording. */
    RefreshSignIn,

    /** Any other 4xx: a final refusal. Delete the recording; retrying cannot help. */
    Delete,

    /** Anything else, including no answer at all. Retry with backoff. */
    Retry,
}

object AnswerTable {
    /**
     * Classify a response.
     *
     * A 401 is a 4xx but is NOT a refusal of the recording, so it is checked before the 4xx rule.
     * Getting that order wrong deletes the user's audio whenever their sign-in lapses.
     */
    fun classify(statusCode: Int?): Answer = when {
        statusCode == null -> Answer.Retry
        statusCode in 200..299 -> Answer.Accepted
        statusCode == 401 -> Answer.RefreshSignIn
        statusCode in 400..499 -> Answer.Delete
        else -> Answer.Retry
    }
}
