package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import java.io.File

/**
 * The journal-backed seam an app operation is admitted through.
 *
 * Both halves are optional on purpose. A v1 operation needs a durable journal and a stable owner to
 * be answerable; without both, executing would release an effect with no record of it, so the
 * delivery path refuses instead. [Unwired] is that state, and it is the DEFAULT: no owner identity is
 * established in this tree yet, so nothing executes until a caller supplies one.
 *
 * [owner] is the stable authenticated ACCOUNT, never the bearer token and never a rotating token's
 * bytes: an identity that changes would make every redelivery look like a new operation.
 */
class AppOperationSupport(
    private val ownerProvider: () -> String?,
    private val journalRoot: File?,
) {
    fun owner(): String? = ownerProvider()

    /** A store for this turn, or null when persistence is unavailable. */
    fun store(): AppOperationJournal.Store? = journalRoot?.let {
        AppOperationJournalStore(root = it)
    }

    companion object {
        val Unwired = AppOperationSupport(ownerProvider = { null }, journalRoot = null)
    }
}
